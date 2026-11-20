package com.alex.mochidownload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the IMAGES for the series Mochi is showing right now, when
 * Alex long-presses (holds ~1s) the locked overlay button. Tap = video,
 * hold = images. This is the v8 addition: the poster + the character
 * art (the per-episode stills).
 *
 * What is downloaded, and where it comes from (all Mochi's own public
 * read API, no login — the same endpoints the app itself uses):
 *  - The series poster: the series' cover art, from the series detail
 *    (`thumbnailUrl`, mochi.imgix.net/ugc/series-posters/...). The
 *    API serves it at w=600 by default; this requests w=1200 so the
 *    saved poster is sharper than the on-screen one.
 *  - One still per episode: each episode's `thumbnailUrl`
 *    (image.mux.com/.../thumbnail.jpg, signed with a token). These
 *    are the character art — the stills of the characters in each
 *    episode. The token is used exactly as returned; it expires, so
 *    the stills are downloaded in the same run, not saved for later.
 *
 * Saved to Downloads/Mochi/<Series Title>/ :
 *  "<Series Title> - poster.png" and "<Series Title> - E<n>.jpg"
 *  (one per episode that has a thumbnail). A MediaStore entry is
 *  verified non-empty before it counts as saved, the same as videos.
 *
 * Resolution (first hit wins): the For You feed (the on-screen title
 * matches a feed episode, which already carries the seriesId and the
 * poster URL), otherwise title search -> series id -> series detail.
 * The series detail is what lists every episode's thumbnail; the
 * feed only has the current episode.
 *
 * Failure codes (toast + notification, one per step — never a fake
 * "saved" when nothing landed):
 *  mochi_not_foreground — Mochi is not the app in front
 *  no_title             — no series title is visible on screen
 *  feed_no_match        — the title was not found in the feed or search
 *  api_failed           — Mochi's API could not be reached at all
 *  series_detail_failed — the series detail returned no poster/thumbnails
 *  img_failed           — an individual image download failed (reported
 *                          in the summary; the rest still download)
 *  save_failed          — an image could not be saved into Downloads
 */
object ImageDownloader {

    private const val NOTIF_CHANNEL = "mochi_download_status"
    private const val NOTIF_DOWNLOAD_ID = 100
    private const val POSTER_WIDTH = 1200

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Outcome of the series resolution. */
    private sealed class ResolveResult {
        class Found(val detail: MochiApi.SeriesDetail) : ResolveResult()
        class Failed(val code: String, val detail: String) : ResolveResult()
    }

    /** Long-press on the locked overlay button: download the poster +
     *  episode stills for the series on screen right now. */
    fun onImagesLongPressed(context: Context) {
        OverlayService.notifyDownloadStarted(context)
        postDownloadNotification(
            context, "Mochi Download — images…",
            "Finding the series showing in Mochi…",
            progress = -1, ongoing = true
        )
        Thread {
            when (val resolved = resolveSeries()) {
                is ResolveResult.Found -> downloadImages(context, resolved.detail)
                is ResolveResult.Failed -> fail(context, resolved.code, resolved.detail)
            }
        }.start()
    }

    // ----- series resolution -----

    private fun resolveSeries(): ResolveResult {
        val title = MochiState.currentTitle?.trim().takeIf { !it.isNullOrEmpty() }
            ?: return ResolveResult.Failed(
                "no_title",
                "No series title is visible on screen, so the poster " +
                    "cannot be identified. Open a series in Mochi (a " +
                    "video playing, or the series page) and hold the " +
                    "button again."
            )
        val creator = MochiState.currentCreator

        // 1. For You feed match: the feed episode carries the seriesId
        //    (and the poster URL). Fastest path, no extra calls.
        val feed = MochiApi.fetchForYouFeed()
        if (feed != null) {
            val norm = normalise(title)
            val match = feed.firstOrNull {
                normalise(it.seriesTitle) == norm
            } ?: feed.firstOrNull {
                normalise(it.seriesTitle).contains(norm) ||
                    norm.contains(normalise(it.seriesTitle))
            }
            if (match != null) {
                val detail = MochiApi.fetchSeriesDetail(match.seriesId)
                if (detail != null) return ResolveResult.Found(detail)
                // Feed matched but the detail call failed: fall through
                // to search, which re-resolves the same series.
            }
        }

        // 2. Title search -> series id -> series detail.
        val series = MochiApi.searchSeries(title)
            ?: return ResolveResult.Failed(
                "api_failed",
                "Mochi's API could not be reached. Check the connection " +
                    "and try again."
            )
        if (series.isEmpty()) {
            return ResolveResult.Failed(
                "feed_no_match",
                "The series \"$title\" was not found in Mochi's For You " +
                    "feed or search, so its poster cannot be found."
            )
        }
        val hit = pickSeries(series, title, creator)
        val detail = MochiApi.fetchSeriesDetail(hit.id)
            ?: return ResolveResult.Failed(
                "series_detail_failed",
                "Found the series \"${hit.title}\" but Mochi returned no " +
                    "poster or episode stills for it."
            )
        return ResolveResult.Found(detail)
    }

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

    // ----- download the images -----

    private fun downloadImages(context: Context, detail: MochiApi.SeriesDetail) {
        val safeTitle = safeName(detail.title)
        val folder = "Mochi/$safeTitle"
        var saved = 0
        var failed = 0
        val failures = ArrayList<String>()

        // Collect the work: poster first, then one still per episode.
        val jobs = ArrayList<Pair<String, String>>() // (url, fileName)
        detail.posterUrl?.let { poster ->
            val hiRes = poster.replace(
                Regex("([?&])w=\\d+"), "$1w=$POSTER_WIDTH"
            )
            val ext = poster.substringBefore('?')
                .substringAfterLast('.', "png").takeIf { it.length <= 5 } ?: "png"
            jobs.add(hiRes to "$safeTitle - poster.$ext")
        }
        for (ep in detail.episodes.sortedBy { it.number }) {
            val url = ep.thumbnailUrl ?: continue
            if (ep.number <= 0) continue
            jobs.add(url to "$safeTitle - E${ep.number}.jpg")
        }
        if (jobs.isEmpty()) {
            fail(context, "series_detail_failed",
                "The series \"${detail.title}\" has no poster or episode " +
                    "stills to download.")
            return
        }

        val total = jobs.size
        for ((index, job) in jobs.withIndex()) {
            val (url, fileName) = job
            postDownloadNotification(
                context, "Mochi Download — images…",
                "Saving $fileName (${index + 1}/$total)…",
                progress = (index * 100 / total), ongoing = true
            )
            val tmp = File(context.cacheDir, "mochi_img_${System.currentTimeMillis()}")
            try {
                if (!downloadFile(url, tmp)) {
                    failed++
                    failures.add(fileName)
                    continue
                }
                val uri = saveImage(context, tmp, folder, fileName)
                val bytes = if (uri != null) savedFileSize(context, uri) else null
                if (uri == null || bytes == null || bytes <= 0) {
                    failed++
                    failures.add(fileName)
                    if (uri != null) {
                        try { context.contentResolver.delete(uri, null, null) } catch (e: Exception) { }
                    }
                } else {
                    saved++
                }
            } catch (e: Exception) {
                failed++
                failures.add(fileName)
            } finally {
                try { tmp.delete() } catch (e: Exception) { }
            }
        }

        finishDownload(context)
        if (saved == 0) {
            fail(context, "img_failed",
                "None of the ${total} images for \"${detail.title}\" " +
                    "could be downloaded. ${failures.take(3).joinToString()}")
            return
        }
        val summary = if (failed == 0) {
            "$saved images saved to Downloads/$folder/ (poster + " +
                "episode stills)."
        } else {
            "$saved of $total images saved to Downloads/$folder/. " +
                "$failed failed: ${failures.take(3).joinToString()}."
        }
        postDownloadNotification(
            context, "Mochi Download — images saved", summary,
            progress = null, ongoing = false
        )
        toast(context, "Saved $saved images to Downloads/$folder/")
    }

    /** A plain HTTP GET for one image file. Follows redirects. */
    private fun downloadFile(url: String, outFile: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Accept", "image/*")
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { input.copyTo(it) }
            }
            outFile.length() > 0
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** Copy a finished image into Downloads/Mochi/<Series Title>/.
     *  Returns the new Uri, or null on failure. */
    private fun saveImage(
        context: Context,
        tmpFile: File,
        folder: String,
        fileName: String
    ): Uri? {
        val mime = when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$folder")
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
        val dir = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
            folder
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

    private fun safeName(title: String): String {
        return title.replace(Regex("[^A-Za-z0-9 _.,\\-()\\[\\]]"), "_")
            .replace(Regex("\\s+"), " ").trim().take(100).ifBlank { "mochi-series" }
    }

    // ----- notifications / feedback (same channel as videos) -----

    private fun fail(context: Context, code: String, message: String) {
        finishDownload(context)
        val fullMessage = "$message\n\n${seenSummary()}"
        showStatus(context, "Mochi Download — could not download images [$code]", fullMessage)
        toast(context, "Image download failed: $code — see notification for details")
    }

    private fun finishDownload(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            nm.cancel(NOTIF_DOWNLOAD_ID)
        } catch (e: Exception) { }
        OverlayService.notifyDownloadFinished(context)
    }

    private fun seenSummary(): String {
        val title = MochiState.currentTitle
        val creator = MochiState.currentCreator
        val ep = MochiState.currentEpisodeNumber
        return "On screen: " +
            "title=" + (title?.let { "\"$it\"" } ?: "(none)") +
            ", creator=" + (creator?.let { "@$it" } ?: "(none)") +
            ", episode=" + (ep?.toString() ?: "(none)")
    }

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
