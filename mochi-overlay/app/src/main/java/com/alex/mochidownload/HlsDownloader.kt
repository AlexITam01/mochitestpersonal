package com.alex.mochidownload

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Downloads a signed Mux HLS stream (the `playbackUrl` from Mochi's
 * /play endpoint or the For You feed) and turns it into ONE playable
 * .mp4 file with both audio and video.
 *
 * Why this exists: Mux does NOT serve a static .mp4 for these videos.
 * `https://stream.mux.com/<playbackId>/highest.mp4` returns 404, and
 * even `.../highest.mp4?token=<signed JWT>` returns 403 for the signed
 * playback ids Mochi uses. The only working source is the signed HLS
 * playlist (`.m3u8?token=...`) that Mochi's own player plays. So the
 * overlay fetches that playlist and downloads the stream itself.
 *
 * How it works:
 *  1. Fetch the master playlist. It lists a separate audio rendition and
 *     several video renditions (1080p / 720p / 480p / 270p). Pick the
 *     highest-bandwidth video rendition.
 *  2. Fetch the video and audio rendition playlists. Each is fMP4
 *     (fragmented MP4): an init segment (#EXT-X-MAP) plus .m4s media
 *     segments, ~5s each.
 *  3. Download all segments in parallel (4 threads) into temp files.
 *  4. Concatenate init + segments for video, and the same for audio.
 *     Each concatenation is a valid fragmented MP4 file.
 *  5. Remux with android.media.MediaExtractor + MediaMuxer into a
 *     single .mp4 (video track + audio track). No ffmpeg, no root, no
 *     extra dependency — MediaMuxer is built into Android.
 *
 * The video rendition is video-only and the audio rendition is
 * audio-only, so step 5 is what puts sound back with the picture.
 * If a rendition ever carries both tracks (a muxed rendition), the
 * remux step takes the audio track from the video file instead and
 * the separate audio file is skipped.
 *
 * Failure codes (returned in [Result.code], also surfaced to Alex):
 *  hls_master_failed   — the master playlist could not be fetched
 *  hls_no_rendition    — the master playlist has no video rendition
 *  hls_rendition_failed— a video/audio rendition playlist fetch failed
 *  hls_no_segments     — a rendition playlist listed no segments
 *  hls_segment_failed  — one or more media segments failed to download
 *  hls_remux_failed    — MediaExtractor / MediaMuxer could not build
 *                        the final .mp4
 *  hls_cancelled       — the download was cancelled
 */
object HlsDownloader {

    data class Result(
        val ok: Boolean,
        val code: String? = null,
        val detail: String? = null,
        /** True when the output has video but no audio track. */
        val audioMissing: Boolean = false
    )

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; SM-S938U) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val PLAYLIST_TIMEOUT_MS = 15000
    private const val SEGMENT_TIMEOUT_MS = 30000
    private const val PARALLEL_SEGMENTS = 4
    /** A single segment download is abandoned after this many tries. */
    private const val SEGMENT_RETRIES = 2

    /** Download [playbackUrl] (signed .m3u8) and write the remuxed .mp4
     *  to [outFile]. [onProgress] gets 0..100. [isCancelled] is polled
     *  between steps; return true from it to abort cleanly. */
    fun download(
        playbackUrl: String,
        outFile: File,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Result {
        val tmpDir = File(outFile.parentFile, "hls_${System.currentTimeMillis()}")
        if (!tmpDir.mkdirs()) return Result(false, "hls_remux_failed", "cannot create temp dir")
        try {
            // 1. Master playlist.
            val master = fetchText(playbackUrl) ?: return Result(
                false, "hls_master_failed",
                "could not fetch the stream playlist"
            )
            if (isCancelled()) return Result(false, "hls_cancelled")
            val (videoRendition, audioRendition) = parseMaster(master, playbackUrl)
                ?: return Result(
                    false, "hls_no_rendition",
                    "the stream playlist has no video rendition"
                )

            // 2. Rendition playlists.
            val videoList = fetchText(videoRendition) ?: return Result(
                false, "hls_rendition_failed",
                "could not fetch the video rendition playlist"
            )
            val audioList = audioRendition?.let { fetchText(it) }
            if (audioRendition != null && audioList == null) {
                // Audio playlist failed but video is fine: continue with
                // video only rather than failing the whole download.
            }
            if (isCancelled()) return Result(false, "hls_cancelled")

            val videoSegs = parseRendition(videoList, videoRendition)
            if (videoSegs.segments.isEmpty()) return Result(
                false, "hls_no_segments",
                "the video rendition listed no segments"
            )
            val audioSegs = audioList?.let { parseRendition(it, audioRendition!!) }
                ?.takeIf { it.segments.isNotEmpty() }

            // 3. Download segments in parallel, in order.
            val totalSegs = videoSegs.segments.size +
                (audioSegs?.segments?.size ?: 0)
            var doneSegs = 0
            fun bump() {
                doneSegs++
                onProgress((doneSegs * 90 / totalSegs.coerceAtLeast(1)).coerceIn(0, 90))
            }
            val videoFile = File(tmpDir, "video.fmp4")
            val videoOk = downloadSegments(videoSegs, videoFile, ::bump)
            if (!videoOk) return Result(
                false, "hls_segment_failed",
                "a video segment failed to download"
            )
            if (isCancelled()) return Result(false, "hls_cancelled")
            val audioFile = audioSegs?.let {
                val f = File(tmpDir, "audio.fmp4")
                if (downloadSegments(it, f, ::bump)) f else null
            }

            // 4. Remux video (+ audio) into one .mp4.
            onProgress(95)
            val remuxed = remux(videoFile, audioFile, outFile)
            if (!remuxed.ok) return remuxed
            onProgress(100)
            return Result(true, audioMissing = remuxed.audioMissing)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ----- playlist fetching / parsing -----

    private fun fetchText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.connectTimeout = PLAYLIST_TIMEOUT_MS
            conn.readTimeout = PLAYLIST_TIMEOUT_MS
            if (conn.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                .use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Resolve a possibly-relative URI against the playlist it came from. */
    private fun resolve(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            URL(URL(base), ref).toString()
        } catch (e: Exception) {
            ref
        }
    }

    /** Pick the highest-bandwidth video rendition and the audio rendition
     *  from a master playlist. Returns null if there is no video. */
    private fun parseMaster(
        master: String,
        masterUrl: String
    ): Pair<String, String?>? {
        var audioUri: String? = null
        var bestVideoUri: String? = null
        var bestBandwidth = -1L
        val lines = master.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                val uri = extractAttr(line, "URI")
                if (uri != null) audioUri = resolve(masterUrl, uri)
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val bw = extractAttr(line, "BANDWIDTH")?.toLongOrNull() ?: 0L
                // The rendition URI is the next non-empty, non-comment line.
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j].trim()
                    if (next.isNotEmpty() && !next.startsWith("#")) {
                        if (bw >= bestBandwidth) {
                            bestBandwidth = bw
                            bestVideoUri = resolve(masterUrl, next)
                        }
                        break
                    }
                    j++
                }
                i = j
            }
            i++
        }
        val video = bestVideoUri ?: return null
        return Pair(video, audioUri)
    }

    private data class Rendition(
        val initUrl: String?,
        val segments: List<String>
    )

    /** Init segment (#EXT-X-MAP) + media segment URIs from a rendition
     *  playlist. Segment URIs are the non-comment lines (each follows
     *  an #EXTINF line). */
    private fun parseRendition(playlist: String, playlistUrl: String): Rendition {
        var initUrl: String? = null
        val segments = ArrayList<String>()
        for (raw in playlist.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    extractAttr(line, "URI")?.let {
                        initUrl = resolve(playlistUrl, it)
                    }
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    segments.add(resolve(playlistUrl, line))
                }
            }
        }
        return Rendition(initUrl, segments)
    }

    private fun extractAttr(line: String, name: String): String? {
        // Matches NAME="value" or NAME=value (up to the next comma).
        val key = "$name="
        val idx = line.indexOf(key)
        if (idx < 0) return null
        var start = idx + key.length
        if (start < line.length && line[start] == '"') {
            val end = line.indexOf('"', start + 1)
            if (end < 0) return null
            return line.substring(start + 1, end)
        }
        val end = line.indexOf(',', start).let { if (it < 0) line.length else it }
        return line.substring(start, end)
    }

    // ----- segment download -----

    /** Download init + segments into [outFile] (init first, then segments
     *  in order). Returns false if any segment could not be fetched. */
    private fun downloadSegments(
        rendition: Rendition,
        outFile: File,
        onSegmentDone: () -> Unit
    ): Boolean {
        val urls = ArrayList<String>()
        rendition.initUrl?.let { urls.add(it) }
        urls.addAll(rendition.segments)
        val pool = Executors.newFixedThreadPool(PARALLEL_SEGMENTS)
        try {
            val futures = urls.map { url ->
                pool.submit(Callable<ByteArray?> {
                    var last: Exception? = null
                    repeat(SEGMENT_RETRIES + 1) {
                        try {
                            return@Callable fetchBytes(url)
                        } catch (e: Exception) {
                            last = e
                        }
                    }
                    null
                })
            }
            // Write in order (the futures keep playlist order).
            FileOutputStream(outFile).use { out ->
                for ((idx, f) in futures.withIndex()) {
                    val bytes = try {
                        f.get(SEGMENT_TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS)
                    } catch (e: Exception) {
                        null
                    }
                    if (bytes == null) return false
                    out.write(bytes)
                    // The init segment is not a media segment; only count
                    // media segments toward progress.
                    if (idx > 0 || rendition.initUrl == null) onSegmentDone()
                }
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.connectTimeout = PLAYLIST_TIMEOUT_MS
            conn.readTimeout = SEGMENT_TIMEOUT_MS
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.readBytes()
        } finally {
            conn?.disconnect()
        }
    }

    // ----- remux -----

    /**
     * Combine the video fMP4 and (optionally) the audio fMP4 into one
     * .mp4 at [outFile] using MediaExtractor + MediaMuxer.
     * If the video file already carries an audio track, that is used and
     * the separate audio file is ignored. If there is no audio anywhere,
     * the output is video-only and [Result.audioMissing] is true.
     */
    private fun remux(
        videoFile: File,
        audioFile: File?,
        outFile: File
    ): Result {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrack = findTrack(videoExtractor, "video/")
            if (videoTrack < 0) {
                return Result(false, "hls_remux_failed", "no video track in the stream")
            }
            var audioTrack = findTrack(videoExtractor, "audio/")
            if (audioTrack < 0 && audioFile != null) {
                audioExtractor = MediaExtractor()
                audioExtractor.setDataSource(audioFile.absolutePath)
                audioTrack = findTrack(audioExtractor, "audio/")
            }
            val audioMissing = audioTrack < 0

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoOut = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            var audioOut = -1
            if (!audioMissing) {
                val ext = if (findTrack(videoExtractor, "audio/") >= 0) {
                    videoExtractor
                } else {
                    audioExtractor!!
                }
                audioOut = muxer.addTrack(ext.getTrackFormat(audioTrack))
            }
            muxer.start()

            copyTrack(videoExtractor, videoTrack, muxer, videoOut)
            if (!audioMissing) {
                val ext = if (findTrack(videoExtractor, "audio/") >= 0) {
                    videoExtractor
                } else {
                    audioExtractor!!
                }
                copyTrack(ext, audioTrack, muxer, audioOut)
            }
            muxer.stop()
            return Result(true, audioMissing = audioMissing)
        } catch (e: Exception) {
            return Result(
                false, "hls_remux_failed",
                "could not build the .mp4: ${e.message}"
            )
        } finally {
            try { muxer?.release() } catch (e: Exception) { }
            try { videoExtractor?.release() } catch (e: Exception) { }
            try { audioExtractor?.release() } catch (e: Exception) { }
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    /** Copy every sample of one track from [extractor] into [muxer].
     *  The extractor must already have the track selected. The buffer is
     *  sized from the track's declared max input size so a large
     *  keyframe does not overflow it. */
    private fun copyTrack(
        extractor: MediaExtractor,
        trackIndex: Int,
        muxer: MediaMuxer,
        muxerTrack: Int
    ) {
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val maxInput = try {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } catch (e: Exception) {
            0
        }
        val buffer = ByteBuffer.allocate((maxInput.coerceAtLeast(256 * 1024)))
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(muxerTrack, buffer, info)
            extractor.advance()
        }
        extractor.unselectTrack(trackIndex)
    }
}
