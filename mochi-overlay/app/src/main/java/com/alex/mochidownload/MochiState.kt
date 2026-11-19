package com.alex.mochidownload

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared, best-effort snapshot of what Mochi is showing right now.
 *
 * Written by [MochiAccessibilityService] (which can read Mochi's visible UI
 * text and element bounds) and read by [OverlayService] and
 * [VideoDownloader].
 *
 * The anchor is the key to "leeching" onto Mochi's interface: it is the
 * screen bounds of a stable element in the video player's action column
 * (the like / comment / share buttons on the right side). The overlay
 * positions itself relative to that anchor, so it sits as if it were a
 * native part of Mochi's player UI, and it re-anchors when the layout
 * shifts (e.g. scrolling to the next video).
 *
 * Honest limit: an AccessibilityService can only read what Mochi puts on
 * screen (titles, creator names, episode numbers, element bounds). The
 * video's stream URL / playbackId lives inside Mochi's own app state and
 * is NOT shown in the UI, so it is usually not here. That is why the
 * overlay resolves the playing video through Mochi's public API instead
 * (see MochiApi / VideoDownloader): the on-screen title (+ creator +
 * episode number) identifies the episode, and the API returns the signed
 * playback URL. The Share flow remains as a fallback.
 *
 * Share flow: when Alex taps the overlay download button and no link is
 * already known, [VideoDownloader] asks the AccessibilityService to use
 * Mochi's own Share flow (tap Share, read the link from the share sheet).
 * That handshake is coordinated here with a latch: the downloader waits
 * (bounded time) for the service to report one result string.
 */
object MochiState {
    @Volatile var isMochiForeground: Boolean = false
    @Volatile var currentTitle: String? = null
    @Volatile var currentCreator: String? = null
    /** Episode number as shown in the player UI ("Episode 5", "Ep 5",
     *  "E5"), if the player shows one. Null when it is not visible. */
    @Volatile var currentEpisodeNumber: Int? = null
    @Volatile var currentWatchLink: String? = null
    @Volatile var currentPlaybackId: String? = null
    /** A signed Mochi playback URL (.m3u8?token=...) if one is ever
     *  visible on screen. Rarely present; the overlay normally resolves
     *  it through the API instead. */
    @Volatile var currentPlaybackUrl: String? = null
    @Volatile var lastTitleAtMs: Long = 0L
    /** Set when the episode number changed: the next UI scan is a new
     *  video, so the title/creator captured for the previous video are
     *  stale and must be re-captured from scratch. */
    @Volatile var pendingTitleReset: Boolean = false

    // ----- UI anchor (action column in the video player) -----
    // Bounds of the anchor element in screen pixels. Null when no anchor
    // element is currently visible. Updated by the AccessibilityService.
    @Volatile var anchorLeft: Int = -1
    @Volatile var anchorTop: Int = -1
    @Volatile var anchorRight: Int = -1
    @Volatile var anchorBottom: Int = -1
    @Volatile var anchorLabel: String? = null
    @Volatile var anchorAtMs: Long = 0L

    fun hasAnchor(): Boolean = anchorLeft >= 0 && anchorTop >= 0 &&
        anchorRight > anchorLeft && anchorBottom > anchorTop

    /** Horizontal centre of the anchor, in screen pixels. */
    fun anchorCenterX(): Int = (anchorLeft + anchorRight) / 2

    /** Bottom edge of the anchor, in screen pixels. */
    fun anchorBottomY(): Int = anchorBottom

    fun setAnchor(left: Int, top: Int, right: Int, bottom: Int, label: String?) {
        anchorLeft = left
        anchorTop = top
        anchorRight = right
        anchorBottom = bottom
        anchorLabel = label
        anchorAtMs = System.currentTimeMillis()
    }

    fun clearAnchor() {
        anchorLeft = -1
        anchorTop = -1
        anchorRight = -1
        anchorBottom = -1
        anchorLabel = null
        anchorAtMs = 0L
    }

    /** Clear the per-video identity (title, creator, links) but keep the
     *  episode number, which is what signalled the video change.
     *  Called at the start of the next UI scan after a video change, so
     *  the scan re-captures the new video's title/creator from scratch
     *  instead of keeping the previous video's (stale) ones. */
    fun clearVideoIdentity() {
        currentTitle = null
        currentCreator = null
        currentWatchLink = null
        currentPlaybackId = null
        currentPlaybackUrl = null
        lastTitleAtMs = 0L
        pendingTitleReset = false
    }

    // ----- Share-flow handshake (see class doc) -----
    //
    // Result strings (exactly one is delivered per flow):
    //   "native:<text>"   — Mochi's own "Download video" was tapped; <text>
    //                       is Mochi's own toast/result if one was seen
    //                       ("Saved to your Photos", "Downloads are off", …),
    //                       or "" if none was seen before the flow closed.
    //   "link:<url>"      — a mochi.tv/watch (or other Mochi) link was read
    //                       from the share sheet / clipboard.
    //   "error:<code>"    — the flow failed; <code> is one of:
    //                       share_button_not_found, share_sheet_timeout,
    //                       no_share_option, clipboard_empty,
    //                       share_sheet_dismissed
    // Only the flow started by Alex tapping the download button may tap
    // anything in Mochi. Outside a flow the service is strictly read-only.

    @Volatile var shareFlowActive: Boolean = false
    @Volatile var shareFlowDeadlineMs: Long = 0L
    @Volatile private var shareLatch: CountDownLatch? = null
    @Volatile var shareFlowResult: String? = null

    /** Start a new share flow; returns the latch the caller waits on. */
    fun beginShareFlow(timeoutMs: Long): CountDownLatch {
        val latch = CountDownLatch(1)
        shareLatch = latch
        shareFlowResult = null
        shareFlowActive = true
        shareFlowDeadlineMs = System.currentTimeMillis() + timeoutMs
        return latch
    }

    /** Called by the AccessibilityService when the flow reaches a result. */
    fun completeShareFlow(result: String) {
        if (!shareFlowActive) return
        shareFlowResult = result
        shareFlowActive = false
        shareLatch?.countDown()
    }

    /** Wait for the flow result (bounded); null if it timed out. */
    fun awaitShareFlow(timeoutMs: Long): String? {
        val latch = shareLatch ?: return null
        return try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) shareFlowResult else null
        } catch (e: InterruptedException) {
            null
        }
    }

    /** True while a share flow is running and not yet past its deadline. */
    fun shareFlowLive(): Boolean =
        shareFlowActive && System.currentTimeMillis() < shareFlowDeadlineMs

    fun reset() {
        isMochiForeground = false
        currentTitle = null
        currentCreator = null
        currentEpisodeNumber = null
        currentWatchLink = null
        currentPlaybackId = null
        currentPlaybackUrl = null
        lastTitleAtMs = 0L
        pendingTitleReset = false
        clearAnchor()
        shareFlowActive = false
        shareFlowResult = null
        shareLatch = null
    }
}
