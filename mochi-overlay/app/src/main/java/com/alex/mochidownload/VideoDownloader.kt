package com.alex.mochidownload

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * What happens when the floating download button is tapped.
 *
 * How a tap resolves the video now (in order):
 *  1. A playbackId already seen on screen (rare — Mochi hides it).
 *  2. A mochi.tv/watch link already seen on screen: fetch the public
 *     watch page and extract the Mux playbackId from it.
 *  3. Mochi's own Share flow (the reliable path): the AccessibilityService
 *     taps Mochi's Share button, then in the share sheet taps Mochi's own
 *     "Download video" if it is there (Mochi saves it to Photos itself —
 *     no URL needed at all), otherwise taps "Copy link" and reads the
 *     copied link, then the watch page is fetched as in (2).
 *  4. If nothing resolves, report exactly which step failed — never fake
 *     a download. The working fallback is still PCAPdroid capture.
 *
 * Mochi plays video from Mux as HLS: https://stream.mux.com/<playbackId>.m3u8
 * The playbackId lives inside Mochi's app state and its HTTPS traffic; a
 * separate overlay app cannot read either. The Share flow is the way
 * around that: it uses Mochi's own feature to surface a link (or its own
 * download) instead of trying to see inside the app.
 */
object VideoDownloader {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val NOTIF_CHANNEL = "mochi_download"
    private const val MUX_HOST = "stream.mux.com"
    private const val SHARE_FLOW_WAIT_MS = 15000L

    private val muxUrl = Regex("https?://stream\\.mux\\.com/([A-Za-z0-9_-]+)")
    private val playbackIdJson = Regex("playbackId\"?\\s*[:=]\\s*\"([A-Za-z0-9_-]{10,})\"")
    private val playbackIdSnake = Regex("playback_id\"?\\s*[:=]\\s*\"([A-Za-z0-9_-]{10,})\"")
    private val signedPlaybackId = Regex("signedPlaybackId\"?\\s*[:=]\\s*\"([A-Za-z0-9_-]{10,})\"")
    private val watchId = Regex("mochi\\.tv/watch/([A-Za-z0-9_-]+)")

    fun onDownloadTapped(context: Context) {
        val appContext = context.applicationContext
        executor.execute { resolveAndDownload(appContext) }
    }

    private fun resolveAndDownload(context: Context) {
        val title = MochiState.currentTitle

        // 1. We already have a playbackId (seen on screen).
        val playbackId = MochiState.currentPlaybackId
        if (!playbackId.isNullOrBlank()) {
            downloadForPlaybackId(context, playbackId, title)
            return
        }

        // 2. We have a watch link: fetch the public watch page.
        val watchLink = MochiState.currentWatchLink
        if (!watchLink.isNullOrBlank()) {
            when (val found = fetchPlaybackIdFromWatchPage(watchLink)) {
                is WatchPageResult.Found -> {
                    MochiState.currentPlaybackId = found.playbackId
                    downloadForPlaybackId(context, found.playbackId, title)
                    return
                }
                is WatchPageResult.PageFailed -> {
                    fail(context, "watch_page_failed",
                        "Could not load the video's share page (${watchLink}). " +
                            "Check the connection and try again, or use PCAPdroid " +
                            "to capture the MP4 (see README).")
                    return
                }
                is WatchPageResult.NoStream -> {
                    fail(context, "watch_page_no_stream",
                        "The video's share page loaded but had no stream link in " +
                            "it. Mochi may have changed its page layout. " +
                            "Use PCAPdroid to capture the MP4 (see README).")
                    return
                }
            }
        }

        // 3. Share flow: use Mochi's own Share to surface a link (or its
        //    own Download video). This is the step that makes the download
        //    actually work in the normal case.
        if (!isAccessibilityServiceOn(context)) {
            fail(context, "a11y_not_enabled",
                "The Accessibility Service is off, so the button cannot " +
                    "open Mochi's Share menu to get the video link. " +
                    "Turn it on in Mochi Download's setup screen, then tap again.")
            return
        }
        if (!MochiState.isMochiForeground) {
            fail(context, "mochi_not_foreground",
                "Mochi does not seem to be open in front right now, so " +
                    "there is no video to download. Open the video in Mochi " +
                    "and tap the button again.")
            return
        }
        runShareFlow(context, title)
    }

    /** Step 3: drive the share flow and act on its single result. */
    private fun runShareFlow(context: Context, title: String?) {
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
                    showStatus(context, "Mochi Download — saved by Mochi", msg)
                    toast(context, msg)
                }
            }
            result.startsWith("link:") -> {
                val link = result.removePrefix("link:")
                if (link.startsWith("mux:")) {
                    val id = link.removePrefix("mux:")
                    if (id.isNotBlank()) {
                        downloadForPlaybackId(context, id, title)
                    } else {
                        fail(context, "no_share_option",
                            "The share sheet gave a Mux link without a usable " +
                                "video ID. Use PCAPdroid to capture the MP4 " +
                                "(see README).")
                    }
                    return
                }
                // A watch link: fetch the page for the stream URL.
                when (val found = fetchPlaybackIdFromWatchPage(link)) {
                    is WatchPageResult.Found -> {
                        MochiState.currentPlaybackId = found.playbackId
                        downloadForPlaybackId(context, found.playbackId, title)
                    }
                    is WatchPageResult.PageFailed -> fail(context, "watch_page_failed",
                        "Got the share link ($link) but its page could not " +
                            "be loaded. Check the connection and try again, " +
                            "or use PCAPdroid to capture the MP4 (see README).")
                    is WatchPageResult.NoStream -> fail(context, "watch_page_no_stream",
                        "Got the share link ($link) and its page loaded, but " +
                            "the page had no stream link in it. Mochi may have " +
                            "changed its page layout. Use PCAPdroid to capture " +
                            "the MP4 (see README).")
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

    /** Try the Mux MP4 rendition first, fall back to the HLS playlist. */
    private fun downloadForPlaybackId(context: Context, playbackId: String, title: String?) {
        val safeTitle = (title ?: "mochi-video")
            .replace(Regex("[^A-Za-z0-9 _.-]"), "_")
            .take(80)
            .ifBlank { "mochi-video" }
        val mp4Url = "https://$MUX_HOST/$playbackId/highest.mp4"
        val hlsUrl = "https://$MUX_HOST/$playbackId.m3u8"
        val url: String
        val fileName: String
        if (urlExists(mp4Url)) {
            url = mp4Url
            fileName = "$safeTitle.mp4"
        } else {
            // No static MP4 rendition. The HLS playlist is the stream itself;
            // DownloadManager will save the playlist (playable in VLC, or
            // convertible). Say so in the status, don't pretend it is an MP4.
            url = hlsUrl
            fileName = "$safeTitle.m3u8"
            showStatus(
                context, "Mochi Download — playlist saved",
                "No MP4 rendition for this video, so the HLS playlist was " +
                    "saved as $fileName. Play it in VLC or convert it to MP4."
            )
        }
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Mochi — $safeTitle")
            .setDescription("Downloading from Mux")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Mochi/$fileName")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        try {
            dm.enqueue(req)
            toast(context, "Downloading $fileName")
        } catch (e: Exception) {
            showStatus(context, "Mochi Download failed",
                "DownloadManager could not start the download: ${e.message}")
        }
    }

    private sealed class WatchPageResult {
        class Found(val playbackId: String) : WatchPageResult()
        object PageFailed : WatchPageResult()
        object NoStream : WatchPageResult()
    }

    /** Fetch a public mochi.tv/watch page and look for a Mux URL / playbackId. */
    private fun fetchPlaybackIdFromWatchPage(watchLink: String): WatchPageResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(watchLink).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                )
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return WatchPageResult.PageFailed
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val id = muxUrl.find(body)?.groupValues?.getOrNull(1)
                ?: playbackIdJson.find(body)?.groupValues?.getOrNull(1)
                ?: playbackIdSnake.find(body)?.groupValues?.getOrNull(1)
                ?: signedPlaybackId.find(body)?.groupValues?.getOrNull(1)
            if (!id.isNullOrBlank()) WatchPageResult.Found(id)
            else WatchPageResult.NoStream
        } catch (e: Exception) {
            WatchPageResult.PageFailed
        } finally {
            conn?.disconnect()
        }
    }

    /** HEAD request: does this URL exist (200)? */
    private fun urlExists(url: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
            }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
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

    /** Report a failure plainly: toast (short) + notification (exact step). */
    private fun fail(context: Context, code: String, message: String) {
        showStatus(context, "Mochi Download — could not download [$code]", message)
        toast(context, "Download failed: $code — see notification for details")
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
