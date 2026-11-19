package com.alex.mochidownload

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * What happens when the floating download button is tapped.
 *
 * Honest summary (see README.md for the full version):
 * Mochi plays video from Mux as HLS: https://stream.mux.com/<playbackId>.m3u8
 * The playbackId lives inside Mochi's app state and its HTTPS traffic. A
 * separate overlay app cannot read either of those. So a tap can only
 * download when a playbackId is actually obtainable:
 *  1. The AccessibilityService saw a playbackId / Mux URL on screen, or
 *  2. Mochi showed a mochi.tv/watch link and the public watch page contains
 *     the stream URL.
 * Otherwise this reports plainly that the URL could not be resolved and
 * tells Alex the working alternative (PCAPdroid capture). It never pretends
 * a download happened when it did not.
 */
object VideoDownloader {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val NOTIF_CHANNEL = "mochi_download"
    private const val MUX_HOST = "stream.mux.com"

    private val muxUrl = Regex("https?://stream\\.mux\\.com/([A-Za-z0-9_-]+)")
    private val playbackIdJson = Regex("playbackId\"?\\s*[:=]\\s*\"([A-Za-z0-9_-]{10,})\"")

    fun onDownloadTapped(context: Context) {
        val appContext = context.applicationContext
        executor.execute { resolveAndDownload(appContext) }
    }

    private fun resolveAndDownload(context: Context) {
        val title = MochiState.currentTitle
        val playbackId = MochiState.currentPlaybackId
        val watchLink = MochiState.currentWatchLink

        // 1. We already have a playbackId (seen on screen).
        if (!playbackId.isNullOrBlank()) {
            downloadForPlaybackId(context, playbackId, title)
            return
        }

        // 2. We have a watch link: fetch the public watch page and look for
        //    a Mux stream URL / playbackId in it.
        if (!watchLink.isNullOrBlank()) {
            val found = fetchPlaybackIdFromWatchPage(watchLink)
            if (!found.isNullOrBlank()) {
                MochiState.currentPlaybackId = found
                downloadForPlaybackId(context, found, title)
                return
            }
        }

        // 3. Cannot resolve. Say so plainly — do not fake a download.
        val msg = if (title.isNullOrBlank()) {
            "Could not find the video link. Mochi hides the stream URL " +
                "inside the app, so the overlay cannot see it. " +
                "Use PCAPdroid to capture the MP4 (see README)."
        } else {
            "Could not resolve a download link for \"$title\". " +
                "Mochi hides the stream URL inside the app. " +
                "Use PCAPdroid to capture the MP4 (see README)."
        }
        showStatus(context, "Mochi Download — no link found", msg)
        toast(context, "No download link found for this video")
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

    /** Fetch a public mochi.tv/watch page and look for a Mux URL / playbackId. */
    private fun fetchPlaybackIdFromWatchPage(watchLink: String): String? {
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
            if (conn.responseCode !in 200..299) return null
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            muxUrl.find(body)?.groupValues?.getOrNull(1)
                ?: playbackIdJson.find(body)?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            null
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
