package com.alex.mochidownload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the video Mochi is currently playing, when Alex taps the
 * locked overlay button. This is the playback-time capture: it resolves
 * the video that is on screen RIGHT NOW and saves it as one .mp4 in
 * Downloads/Mochi/.
 *
 * Resolution order (first hit wins). Steps 1, 4 and 5 need no tapping
 * inside Mochi at all and no title — they identify the playing video
 * from what the AccessibilityService can already see (a playback URL,
 * an episode id, or a watch link) and resolve it through Mochi's
 * public read API (MochiApi, no login). Steps 2 and 3 need the
 * on-screen title. The Share flow is only the fallback.
 *
 *  1. A signed playback URL already visible on screen
 *     (MochiState.currentPlaybackUrl) — rare.
 *  2. For You feed match: fetch the public For You feed and match the
 *     on-screen series title (+ creator, + episode number) to a feed
 *     episode, which already carries its signed playbackUrl.
 *  3. Title search + lookup: search the on-screen title, take the
 *     series slug, and resolve (slug, episode number) to an episode
 *     id, then /play for the signed playbackUrl. Needs the episode
 *     number from the UI; skipped when it is not shown.
 *  4. An episode id already on screen -> /play -> playbackUrl. Runs
 *     before steps 2/3: it needs no title and identifies the episode
 *     exactly.
 *  5. A mochi.tv/watch link already on screen
 *     (mochi.tv/watch/<seriesSlug>/<episodeNumber>) -> lookup ->
 *     /play -> playbackUrl. Also runs before steps 2/3, for the same
 *     reason. The full two-segment link is required: a slug-only link
 *     cannot name an episode (treating the slug as an episode id
 *     makes /play 404 — that was the "watch_page_no_stream" failure,
 *     fixed in v7).
 *  6. Share flow (the fallback): tap Mochi's Share button, use Mochi's
 *     own "Download video" if offered, otherwise "Copy link" and
 *     resolve the watch link through the API (same as step 5).
 *
 * The playbackUrl is a SIGNED Mux HLS playlist (.m3u8?token=...).
 * Mux does NOT serve a static .mp4 for these videos
 * (https://stream.mux.com/<id>/highest.mp4 is 404, and even with a
 * signed token it is 403), so the overlay downloads the HLS stream
 * itself (HlsDownloader: video + audio renditions, remuxed with
 * MediaMuxer into one .mp4). There is no bare-Mux-URL download any
 * more; that path silently produced nothing.
 *
 * What this does NOT do (the standing constraints):
 *  - No Play Integrity bypass. The API calls are the same public read
 *    endpoints the Mochi app itself uses to play a video; nothing
 *    about integrity is touched.
 *  - No Mochi modification, no root, no MITM (no CA install, no
 *    pinning bypass). The stream URL comes from Mochi's API, not from
 *    intercepting its traffic — a plain VPN could not see it anyway,
 *    because the URL path is inside HTTPS.
 *  - No screen recording (MediaProjection). The file is the actual
 *    stream, downloaded and remuxed, not a recording of the screen.
 *
 * Failure codes (toast + notification, one per step — never a fake
 * "downloaded" when nothing was saved):
 *  mochi_not_foreground   — Mochi is not the app in front
 *  no_title               — no video title is visible on screen, so
 *                          the playing video cannot be identified
 *  feed_no_match          — the For You feed has no episode with this
 *                          title (the guest feed is only the first page)
 *  feed_ambiguous         — several feed episodes share this title
 *                          and creator/episode cannot tell them apart
 *  episode_number_unknown — the title resolved to a series but the
 *                          player is not showing which episode it is
 *  lookup_failed          — (series, episode) has no episode id
 *  play_failed            — /play returned no signed playback URL
 *  api_failed             — Mochi's API could not be reached at all
 *  hls_*                  — the stream download failed; see
 *                          HlsDownloader for the exact code
 *                          (hls_master_failed, hls_no_rendition,
 *                          hls_rendition_failed, hls_no_segments,
 *                          hls_segment_failed, hls_remux_failed)
 *  save_failed            — the .mp4 was built but could not be put
 *                          into Downloads/Mochi/
 *  save_not_found         — the save reported success but the file is
 *                          missing or 0 bytes in Downloads/Mochi/
 *                          when checked afterwards
 *  a11y_not_enabled       — the Accessibility Service is off, so the
 *                          Share fallback cannot run
 *  share_button_not_found / share_sheet_timeout / no_share_option /
 *  clipboard_empty / share_sheet_dismissed — the Share fallback
 *  failed at that step
 *  native_download_unavailable — Mochi's own download refused
 *                          ("Downloads are off" / "Couldn't save")
 *  mux_id_unresolved      — the share sheet gave a bare Mux id that
 *                          could not be matched to an episode
 *  watch_page_failed / watch_page_no_stream — kept for the old
 *                          watch-page path; the API lookup replaces it
 */
object VideoDownloader {

    private const val NOTIF_CHANNEL = "mochi_download_status"
    /** Fixed id of the ongoing download notification, so progress
     *  updates replace it in place instead of stacking new ones. */
    private const val NOTIF_DOWNLOAD_ID = 100
    private const val MOCHI_PACKAGE = "com.mochitv.mochi"
    private const val SHARE_FLOW_WAIT_MS = 16000L
    private val WATCH_LINK_TWO_PART =
        Regex("mochi\\.tv/watch/([A-Za-z0-9_-]+)/(\\d+)")
    private val WATCH_LINK_ONE_PART =
        Regex("mochi\\.tv/watch/([A-Za-z0-9_-]+)")

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Outcome of the API resolution. */
    private sealed class ResolveResult {
        class Found(
            val playbackUrl: String,
            val method: String,
            val fileName: String
        ) : ResolveResult()

        class Failed(val code: String, val detail: String) : ResolveResult()
    }

    fun onDownloadTapped(context: Context) {
        // Immediate, visible feedback FIRST, before any slow work: the
        // button turns light blue (OverlayService) and a persistent
        // notification appears, so a tap is never silent — even if the
        // download then fails, the failure replaces this notification
        // with the exact step code.
        OverlayService.notifyDownloadStarted(context)
        postDownloadNotification(
            context, "Mochi Download — downloading…",
            "Finding the video playing in Mochi…",
            progress = -1, ongoing = true
        )
        if (!isMochiForegroundNow(context)) {
            fail(context, "mochi_not_foreground",
                "Mochi does not seem to be open in front right now, so " +
                    "there is no video to download. Open the video in Mochi " +
                    "and tap the button again.")
            return
        }
        Thread {
            when (val resolved = resolvePlaybackUrl()) {
                is ResolveResult.Found -> {
                    downloadFromPlaybackUrl(
                        context, resolved.playbackUrl, resolved.fileName
                    )
                }
                is ResolveResult.Failed -> {
                    // The API could not identify the playing video.
                    // Fall back to the Share flow, which asks Mochi
                    // itself for the link. Only report the API failure
                    // if the Share flow is not available at all.
                    if (!isAccessibilityServiceOn(context)) {
                        fail(context, resolved.code, resolved.detail)
                        return@Thread
                    }
                    runShareFlow(context, MochiState.currentTitle)
                }
            }
        }.start()
    }

    /**
     * Is Mochi the app in front right now? Two signals, and the same
     * "never decide on unknown" rule as the overlay's visibility poll:
     *  - MochiState.isMochiForeground (AccessibilityService window events).
     *  - UsageStats foreground events (needs Usage access).
     * Only returns false when UsageStats positively reports a DIFFERENT
     * app in front. When both signals are unknown, the tap is allowed
     * through — the button is only visible while Mochi is on screen, so
     * a tap on it is itself evidence a video is playing, and the
     * resolution step will fail with a proper code (no_title) if there
     * is genuinely no video to identify.
     *
     * The old code failed fast on MochiState alone, which is only ever
     * set by the AccessibilityService — so with that service off or
     * stale, every tap died silently with mochi_not_foreground. */
    private fun isMochiForegroundNow(context: Context): Boolean {
        if (MochiState.isMochiForeground) return true
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return true
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(now - 3600000L, now)
        } catch (e: Exception) {
            return true
        } ?: return true
        val event = android.app.usage.UsageEvents.Event()
        var foreground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    foreground = event.packageName
                android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (foreground == event.packageName) foreground = null
                }
            }
        }
        // Null = unknown: allow the tap. A different package = blocked.
        return foreground == null || foreground == MOCHI_PACKAGE
    }

    // ----- API resolution (no tapping inside Mochi) -----

    private fun resolvePlaybackUrl(): ResolveResult {
        // 1. A signed playback URL already visible on screen.
        MochiState.currentPlaybackUrl?.takeIf { it.contains(".m3u8") }?.let {
            return ResolveResult.Found(
                it, "on-screen playback URL",
                fileNameFor(null, null, MochiState.currentTitle)
            )
        }
        val episodeNumber = MochiState.currentEpisodeNumber

        // 4. An episode id already on screen -> /play -> playbackUrl.
        // 5. A mochi.tv/watch link already on screen -> lookup -> /play.
        // These run before the title-based steps (2, 3) because they
        // need no title: the id / link identifies the episode exactly,
        // and the title heuristic is the fragile part. They only fire
        // when the id / link is actually visible on screen right now
        // (MochiState clears them when the video changes).
        MochiState.currentPlaybackId?.takeIf { it.isNotBlank() }?.let { episodeId ->
            MochiApi.fetchPlaybackUrl(episodeId)?.let { url ->
                return ResolveResult.Found(
                    url, "on-screen episode id",
                    fileNameFor(null, episodeNumber, MochiState.currentTitle)
                )
            }
        }
        MochiState.currentWatchLink?.takeIf { it.isNotBlank() }?.let { watchLink ->
            playbackUrlForWatchLink(watchLink)?.let { url ->
                return ResolveResult.Found(
                    url, "on-screen watch link",
                    fileNameFor(null, episodeNumber, MochiState.currentTitle)
                )
            }
        }

        val title = MochiState.currentTitle?.trim().takeIf { !it.isNullOrEmpty() }
            ?: return ResolveResult.Failed(
                "no_title",
                "No video title is visible on screen, so the playing " +
                    "video cannot be identified. Make sure a video is " +
                    "playing in Mochi (not a menu) and tap again."
            )
        val creator = MochiState.currentCreator

        // 2. For You feed match.
        val feed = MochiApi.fetchForYouFeed()
        if (feed != null) {
            val match = matchFeedEpisode(feed, title, creator, episodeNumber)
            when (match) {
                is FeedMatch.One -> {
                    val url = match.episode.playbackUrl
                    if (!url.isNullOrBlank()) {
                        return ResolveResult.Found(
                            url, "For You feed",
                            fileNameFor(
                                match.episode.seriesTitle,
                                match.episode.number, title
                            )
                        )
                    }
                }
                is FeedMatch.Ambiguous -> {
                    // Fall through to search + lookup; it can tell the
                    // episodes apart by number.
                }
                is FeedMatch.None -> {
                    // Fall through: the guest feed is only the first
                    // page, so the series may simply not be in it.
                }
            }
        }

        // 3. Title search + lookup (needs the episode number).
        val series = MochiApi.searchSeries(title)
        if (series == null) {
            return ResolveResult.Failed(
                "api_failed",
                "Mochi's API could not be reached. Check the connection " +
                    "and try again."
            )
        }
        if (series.isEmpty()) {
            return ResolveResult.Failed(
                "feed_no_match",
                "The video \"$title\" was not found in Mochi's For You " +
                    "feed or search, so it cannot be identified. Tap again " +
                    "to use the Share flow, or use PCAPdroid."
            )
        }
        val hit = pickSeries(series, title, creator)
        if (episodeNumber == null) {
            return ResolveResult.Failed(
                "episode_number_unknown",
                "Found the series \"${hit.title}\" but the player is not " +
                    "showing which episode is playing, so the exact video " +
                    "cannot be picked out. Tap again to use Mochi's Share " +
                    "flow, which gets the link from Mochi itself."
            )
        }
        val episodeId = MochiApi.lookupEpisode(hit.slug, episodeNumber)
            ?: return ResolveResult.Failed(
                "lookup_failed",
                "The series \"${hit.title}\" has no episode $episodeNumber " +
                    "according to Mochi. Tap again to use the Share flow."
            )
        val url = MochiApi.fetchPlaybackUrl(episodeId)
            ?: return ResolveResult.Failed(
                "play_failed",
                "Mochi returned no stream URL for \"${hit.title}\" " +
                    "episode $episodeNumber. Tap again to use the Share flow."
            )
        return ResolveResult.Found(
            url, "search + lookup",
            fileNameFor(hit.title, episodeNumber, title)
        )
    }

    /** Step 4/5 (also used by the Share flow): resolve an episode id or
     *  a watch link to a signed playback URL. Returns null on failure. */
    private fun playbackUrlForEpisodeId(episodeId: String): String? =
        MochiApi.fetchPlaybackUrl(episodeId)

    private fun playbackUrlForWatchLink(watchLink: String): String? {
        // mochi.tv/watch/<seriesSlug>/<episodeNumber>
        WATCH_LINK_TWO_PART.find(watchLink)?.let {
            val slug = it.groupValues[1]
            val number = it.groupValues[2].toIntOrNull() ?: return null
            val episodeId = MochiApi.lookupEpisode(slug, number) ?: return null
            return MochiApi.fetchPlaybackUrl(episodeId)
        }
        // mochi.tv/watch/<episodeId> (bare id form).
        WATCH_LINK_ONE_PART.find(watchLink)?.let {
            val id = it.groupValues[1]
            // First try it as an episode id.
            MochiApi.fetchPlaybackUrl(id)?.let { url -> return url }
            // If /play 404s, the single segment was a series slug, not
            // an episode id (a slug-only watch link cannot name an
            // episode by itself). Resolve it with the episode number
            // currently on screen, if the player is showing one.
            val ep = MochiState.currentEpisodeNumber ?: return null
            val episodeId = MochiApi.lookupEpisode(id, ep) ?: return null
            return MochiApi.fetchPlaybackUrl(episodeId)
        }
        return null
    }

    private sealed class FeedMatch {
        class One(val episode: MochiApi.FeedEpisode) : FeedMatch()
        object Ambiguous : FeedMatch()
        object None : FeedMatch()
    }

    private fun matchFeedEpisode(
        feed: List<MochiApi.FeedEpisode>,
        title: String,
        creator: String?,
        episodeNumber: Int?
    ): FeedMatch {
        val normTitle = normalise(title)
        var candidates = feed.filter {
            normalise(it.seriesTitle) == normTitle
        }
        if (candidates.isEmpty()) {
            // The on-screen title may be an episode title rather than the
            // series title; try a contains match as a last resort.
            candidates = feed.filter {
                normalise(it.seriesTitle).contains(normTitle) ||
                    normTitle.contains(normalise(it.seriesTitle))
            }
        }
        if (candidates.isEmpty()) return FeedMatch.None
        if (candidates.size == 1) return FeedMatch.One(candidates[0])
        // Disambiguate by creator, then by episode number.
        if (!creator.isNullOrBlank()) {
            val byCreator = candidates.filter {
                it.creatorUsername?.equals(creator, ignoreCase = true) == true
            }
            if (byCreator.size == 1) return FeedMatch.One(byCreator[0])
            if (byCreator.isNotEmpty()) candidates = byCreator
        }
        if (episodeNumber != null) {
            val byNumber = candidates.filter { it.number == episodeNumber }
            if (byNumber.size == 1) return FeedMatch.One(byNumber[0])
            if (byNumber.isNotEmpty()) candidates = byNumber
        }
        return if (candidates.size == 1) FeedMatch.One(candidates[0])
        else FeedMatch.Ambiguous
    }

    /** Pick the best series hit for a title (exact title first, then
     *  creator, then the first hit). */
    private fun pickSeries(
        series: List<MochiApi.SeriesHit>,
        title: String,
        creator: String?
    ): MochiApi.SeriesHit {
        val normTitle = normalise(title)
        val exact = series.filter { normalise(it.title) == normTitle }
        if (exact.size == 1) return exact[0]
        val pool = if (exact.isNotEmpty()) exact else series
        if (!creator.isNullOrBlank()) {
            pool.firstOrNull {
                it.creatorUsername?.equals(creator, ignoreCase = true) == true
            }?.let { return it }
        }
        return pool[0]
    }

    private fun normalise(s: String): String =
        s.lowercase().trim().replace(Regex("\\s+"), " ")

    private fun fileNameFor(
        seriesTitle: String?,
        episodeNumber: Int?,
        fallbackTitle: String?
    ): String {
        val base = when {
            !seriesTitle.isNullOrBlank() && episodeNumber != null ->
                "$seriesTitle - E$episodeNumber"
            !seriesTitle.isNullOrBlank() -> seriesTitle
            !fallbackTitle.isNullOrBlank() -> fallbackTitle
            else -> "mochi-video-${System.currentTimeMillis()}"
        }
        val safe = base.replace(Regex("[^A-Za-z0-9 _.,\\-()\\[\\]]"), "_")
            .replace(Regex("\\s+"), " ").trim().take(100).ifBlank { "mochi-video" }
        return "$safe.mp4"
    }

    // ----- download the signed stream -----

    private fun downloadFromPlaybackUrl(
        context: Context,
        playbackUrl: String,
        fileName: String
    ) {
        postDownloadNotification(
            context, "Downloading $fileName",
            "Downloading the video…", progress = 0, ongoing = true
        )
        toast(context, "Downloading $fileName…")
        val tmp = File(context.cacheDir, "mochi_dl_${System.currentTimeMillis()}.mp4")
        try {
            val result = if (playbackUrl.contains(".m3u8")) {
                HlsDownloader.download(
                    playbackUrl = playbackUrl,
                    outFile = tmp,
                    onProgress = { p ->
                        postDownloadNotification(
                            context, "Downloading $fileName",
                            "Downloading the video… $p%",
                            progress = p, ongoing = true
                        )
                    }
                )
            } else {
                downloadDirectFile(playbackUrl, tmp)
            }
            if (!result.ok) {
                fail(context, result.code ?: "hls_remux_failed",
                    "The stream could not be downloaded (${result.code}). " +
                        (result.detail ?: "") +
                        " Use PCAPdroid to capture the MP4 (see README).")
                return
            }
            val uri = saveToDownloads(context, tmp, fileName)
            if (uri == null) {
                fail(context, "save_failed",
                    "The video was downloaded but could not be saved into " +
                        "Downloads/Mochi/. Check storage permission and try again.")
                return
            }
            // Verify the file actually landed and is non-empty before
            // reporting "Saved": a MediaStore entry can exist while the
            // bytes never made it (or were written as 0 bytes), and a
            // "Saved" report for a file that is not there is worse than
            // a failure — Alex cannot find it afterwards.
            val savedBytes = savedFileSize(context, uri)
            if (savedBytes == null || savedBytes <= 0) {
                fail(context, "save_not_found",
                    "The video was downloaded but the saved file could " +
                        "not be found in Downloads/Mochi/ (or it is " +
                        "empty). Check storage and try again.")
                return
            }
            val note = if (result.audioMissing) {
                " (video only — no audio track was found in the stream)"
            } else ""
            finishDownload(context)
            postDownloadNotification(
                context, "Mochi Download — saved",
                "$fileName is in Downloads/Mochi/ (${formatSize(savedBytes)})$note",
                progress = null, ongoing = false
            )
            toast(context, "Saved to Downloads/Mochi/$fileName (${formatSize(savedBytes)})$note")
        } finally {
            try { tmp.delete() } catch (e: Exception) { }
        }
    }

    /** A plain HTTP GET for a direct file URL (only used when the
     *  playback URL is not an HLS playlist). */
    private fun downloadDirectFile(url: String, outFile: File): HlsDownloader.Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            if (conn.responseCode !in 200..299) {
                return HlsDownloader.Result(
                    false, "hls_master_failed",
                    "the file URL returned HTTP ${conn.responseCode}"
                )
            }
            FileOutputStream(outFile).use { out ->
                conn.inputStream.copyTo(out)
            }
            HlsDownloader.Result(true)
        } catch (e: Exception) {
            HlsDownloader.Result(false, "hls_segment_failed", e.message)
        } finally {
            conn?.disconnect()
        }
    }

    /** Copy a finished .mp4 into the shared Downloads/Mochi/ folder.
     *  Returns the new Uri, or null on failure. */
    private fun saveToDownloads(
        context: Context,
        tmpFile: File,
        fileName: String
    ): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Mochi")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            return try {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(tmpFile).use { it.copyTo(out) }
                }
                uri
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (e2: Exception) { }
                null
            }
        }
        // Android 9 and older: no scoped-storage MediaStore Downloads.
        // Fall back to the app's own external Downloads folder (no
        // permission needed, but it is not the shared Downloads folder).
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Mochi"
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        val dest = File(dir, fileName)
        return try {
            FileInputStream(tmpFile).use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
            Uri.fromFile(dest)
        } catch (e: Exception) {
            null
        }
    }

    /** Bytes of the MediaStore entry at [uri], or null if it cannot be
     *  read (entry missing). Used to verify a save really landed before
     *  the app reports "Saved". For a file:// Uri (Android 9 fallback)
     *  the file length is used instead. */
    private fun savedFileSize(context: Context, uri: Uri): Long? {
        if (uri.scheme == "file") {
            return try {
                val f = File(uri.path ?: return null)
                if (f.exists()) f.length() else null
            } catch (e: Exception) {
                null
            }
        }
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.Downloads.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Downloads.SIZE)
                    if (idx >= 0) cursor.getLong(idx) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun formatSize(bytes: Long): String {
        return if (bytes >= 1_000_000) {
            "%.1f MB".format(bytes / 1_000_000.0)
        } else {
            "%d KB".format(bytes / 1000)
        }
    }

    /** What the overlay thinks is playing, for failure messages: when a
     *  download fails because the video could not be identified, Alex
     *  needs to see what the app actually saw on screen — a wrong or
     *  stale title is the usual cause, and this names it instead of
     *  leaving him guessing. */
    private fun seenSummary(): String {
        val title = MochiState.currentTitle
        val creator = MochiState.currentCreator
        val ep = MochiState.currentEpisodeNumber
        return "On screen: " +
            "title=" + (title?.let { "\"$it\"" } ?: "(none)") +
            ", creator=" + (creator?.let { "@$it" } ?: "(none)") +
            ", episode=" + (ep?.toString() ?: "(none)")
    }

    // ----- Share flow (fallback) -----

    /** Step 3: drive the share flow and act on its single result. */
    private fun runShareFlow(context: Context, title: String?) {
        if (!isAccessibilityServiceOn(context)) {
            fail(context, "a11y_not_enabled",
                "The Accessibility Service is off, so the button cannot " +
                    "open Mochi's Share menu to get the video link. " +
                    "Turn it on in Mochi Download's setup screen, then tap again.")
            return
        }
        if (!isMochiForegroundNow(context)) {
            fail(context, "mochi_not_foreground",
                "Mochi does not seem to be open in front right now, so " +
                    "there is no video to download. Open the video in Mochi " +
                    "and tap the button again.")
            return
        }
        postDownloadNotification(
            context, "Mochi Download — downloading…",
            "Opening Mochi's Share menu to get the video link…",
            progress = -1, ongoing = true
        )
        toast(context, "Opening Mochi's Share menu to get the video link…")
        val intent = Intent(context, MochiAccessibilityService::class.java).apply {
            action = MochiAccessibilityService.ACTION_REQUEST_SHARE_TAP
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            fail(context, "a11y_not_enabled",
                "Could not reach the Accessibility Service. Turn it on in " +
                    "Mochi Download's setup screen, then tap again.")
            return
        }
        val result = MochiState.awaitShareFlow(SHARE_FLOW_WAIT_MS) ?: "error:share_sheet_timeout"
        when {
            result.startsWith("native:") -> {
                val detail = result.removePrefix("native:")
                if (detail.contains("Downloads are off", ignoreCase = true) ||
                    detail.contains("ouldn't save", ignoreCase = true) ||
                    detail.contains("ould not save", ignoreCase = true)
                ) {
                    fail(context, "native_download_unavailable",
                        "Mochi's own download said: \"$detail\". This video " +
                            "cannot be saved by Mochi itself. " +
                            "Use PCAPdroid to capture the MP4 (see README).")
                } else {
                    // Mochi's own download started (or finished).
                    val msg = if (detail.isBlank()) {
                        "Mochi's own download started — check your Photos " +
                            "for the video."
                    } else {
                        "Mochi says: \"$detail\""
                    }
                    finishDownload(context)
                    postDownloadNotification(
                        context, "Mochi Download — saved by Mochi", msg,
                        progress = null, ongoing = false
                    )
                    toast(context, msg)
                }
            }
            result.startsWith("link:") -> {
                val link = result.removePrefix("link:")
                if (link.startsWith("mux:")) {
                    // A bare Mux playback id from the share sheet. There
                    // is no working bare-Mux download (404 / 403), so
                    // try to match it to a feed episode, whose signed
                    // playbackUrl is the real source.
                    val id = link.removePrefix("mux:")
                    val url = MochiApi.fetchForYouFeed()
                        ?.firstOrNull { ep ->
                            ep.playbackUrl?.contains(id) == true
                        }?.playbackUrl
                    if (!url.isNullOrBlank()) {
                        downloadFromPlaybackUrl(
                            context, url, fileNameFor(null, null, title)
                        )
                    } else {
                        fail(context, "mux_id_unresolved",
                            "The share sheet gave a Mux video id ($id) " +
                                "that could not be matched to a Mochi " +
                                "episode, so there is no signed stream URL " +
                                "to download. Use PCAPdroid to capture the " +
                                "MP4 (see README).")
                    }
                    return
                }
                // A watch link: resolve it through the API (lookup ->
                // /play) to the signed stream URL, then download it.
                val url = playbackUrlForWatchLink(link)
                if (!url.isNullOrBlank()) {
                    downloadFromPlaybackUrl(
                        context, url, fileNameFor(null, null, title)
                    )
                } else {
                    fail(context, "watch_page_no_stream",
                        "Got the share link ($link) but Mochi returned no " +
                            "stream URL for it. Mochi may have changed its " +
                            "links. Use PCAPdroid to capture the MP4 " +
                            "(see README).")
                }
            }
            result.startsWith("error:") -> {
                when (val code = result.removePrefix("error:")) {
                    "share_button_not_found" -> fail(context, code,
                        "Could not find Mochi's Share button on this screen. " +
                            "Make sure a video is playing in Mochi (not a menu " +
                            "or the episode list) and tap again.")
                    "share_sheet_timeout" -> fail(context, code,
                        "Mochi's share menu did not open in time. Tap the " +
                            "button again — if it keeps happening, open " +
                            "Mochi's Share button by hand once and check " +
                            "that a share sheet appears.")
                    "no_share_option" -> fail(context, code,
                        "Mochi's share menu opened but had no Download or " +
                            "Copy link option in it. Mochi may have changed " +
                            "the menu. Use PCAPdroid to capture the MP4 " +
                            "(see README).")
                    "clipboard_empty" -> fail(context, code,
                        "Tapped \"Copy link\" but the link could not be read " +
                            "back (Android blocks background clipboard reads). " +
                            "Try again, or use PCAPdroid to capture the MP4 " +
                            "(see README).")
                    "share_sheet_dismissed" -> fail(context, code,
                        "The share menu closed before a link could be read. " +
                            "Tap the button again and leave the menu alone " +
                            "while it works.")
                    else -> fail(context, code,
                        "The share flow failed ($code). Use PCAPdroid to " +
                            "capture the MP4 (see README).")
                }
            }
            else -> fail(context, "unknown",
                "The share flow ended without a usable link. Use PCAPdroid " +
                    "to capture the MP4 (see README).")
        }
    }

    private fun isAccessibilityServiceOn(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as AccessibilityManager
            val enabled = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_GENERIC
            )
            enabled.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        } catch (e: Exception) {
            false
        }
    }

    /** Report a failure plainly: toast (short) + notification (exact step).
     *  Also ends the download state: the ongoing notification is cleared
     *  and the button goes back from light blue to its normal look.
     *  EVERY failure notification also says what the app actually saw on
     *  screen (title / creator / episode), so a wrong or stale capture
     *  is visible instead of a bare code. This used to be limited to the
     *  API resolution codes, which meant Share-flow failures like
     *  "watch_page_no_stream" arrived with no diagnostic at all — Alex
     *  saw the code and "nothing afterwards". Now all of them carry it. */
    private fun fail(context: Context, code: String, message: String) {
        finishDownload(context)
        val fullMessage = "$message\n\n${seenSummary()}"
        showStatus(context, "Mochi Download — could not download [$code]", fullMessage)
        toast(context, "Download failed: $code — see notification for details")
    }

    /** End the download state: clear the light-blue button and drop the
     *  ongoing "downloading" notification (a final notification, if
     *  any, is posted separately by the caller). */
    private fun finishDownload(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            nm.cancel(NOTIF_DOWNLOAD_ID)
        } catch (e: Exception) { }
        OverlayService.notifyDownloadFinished(context)
    }

    /**
     * The persistent download alert (Alex asked for this): one
     * notification, id NOTIF_DOWNLOAD_ID, that appears the moment a
     * download starts, shows progress while it runs, and is replaced in
     * place by the saved/failed result. [progress] is 0..100, -1 for an
     * indeterminate spinner, null for no progress bar (a finished
     * notification). Unlike the old code, which only showed a transient
     * toast, this stays in the shade until the download ends.
     *
     * NOTE: on Android 13+ this is silently dropped unless the
     * POST_NOTIFICATIONS runtime permission is granted — MainActivity
     * now requests it on first launch. */
    private fun postDownloadNotification(
        context: Context,
        title: String,
        body: String,
        progress: Int?,
        ongoing: Boolean
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "Mochi Download status",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(ch)
        }
        val builder = Notification.Builder(context, NOTIF_CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        when (progress) {
            null -> { /* no progress bar */ }
            -1 -> builder.setProgress(0, 0, true)
            else -> builder.setProgress(100, progress.coerceIn(0, 100), false)
        }
        try { nm.notify(NOTIF_DOWNLOAD_ID, builder.build()) } catch (e: Exception) { }
    }

    private fun toast(context: Context, msg: String) {
        mainHandler.post {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun showStatus(context: Context, title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "Mochi Download status",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(ch)
        }
        val notif = Notification.Builder(context, NOTIF_CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setAutoCancel(true)
            .build()
        try { nm.notify((System.currentTimeMillis() % 100000).toInt(), notif) } catch (e: Exception) { }
    }
}
