package com.alex.mochidownload

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads what Mochi is showing, so the floating button knows which video is
 * playing and — for the suckerfish anchoring — where Mochi's own player UI
 * sits on screen.
 *
 * What it CAN see (the visible UI): the video title / creator text, the
 * episode number when the player shows one, the bounds of on-screen
 * elements (including the like / comment / share action column), and —
 * if Mochi ever shows one — a mochi.tv/watch link or a Mux playbackId.
 * What it CANNOT see: the video's stream URL. That lives in Mochi's
 * internal app state and in its encrypted (HTTPS) traffic, neither of
 * which an AccessibilityService can read. That is why the overlay
 * resolves the playing video through Mochi's public API (MochiApi):
 * the on-screen title (+ creator + episode number) identifies the
 * episode, and the API returns the signed playback URL. See README.md
 * for the full resolution order and the failure codes.
 *
 * The ONE exception to "read-only": the share flow. When Alex taps the
 * overlay download button and no link is already known, [VideoDownloader]
 * asks this service (via [ACTION_REQUEST_SHARE_TAP]) to use Mochi's own
 * Share flow on his behalf: tap Mochi's Share button, then in the share
 * sheet tap Mochi's own "Download video" if it is there, otherwise tap
 * "Copy link" and read the copied link. The service only ever taps inside
 * a share flow that Alex started with the download button, and it never
 * taps, swipes, scrolls, or navigates Mochi at any other time.
 */
class MochiAccessibilityService : AccessibilityService() {

    companion object {
        private const val MOCHI_PACKAGE = "com.mochitv.mochi"
        const val ACTION_REQUEST_SHARE_TAP =
            "com.alex.mochidownload.ACTION_REQUEST_SHARE_TAP"
        private val WATCH_LINK = Regex("mochi\\.tv/watch/([A-Za-z0-9_-]+)")
        private val MUX_URL = Regex("stream\\.mux\\.com/([A-Za-z0-9_-]+)")
        private val PLAYBACK_ID = Regex("playbackId[^A-Za-z0-9]{0,4}([A-Za-z0-9_-]{10,})")
        private val ANY_URL = Regex("https?://[^\\s\"'<>]+")
        /** A signed Mux playback URL, if one is ever visible on screen. */
        private val SIGNED_MUX_URL =
            Regex("https://stream\\.mux\\.com/[A-Za-z0-9_-]+\\.m3u8\\?token=[^\\s\"'<>]+")
        /** "Episode 5", "Ep 5", "Ep. 5" or a standalone "E5" — how the
         *  player labels the episode number, when it shows one at all.
         *  Group 1 is the word form, group 2 the bare "E5" form. */
        private val EPISODE_NUMBER =
            Regex("(?i)\\b(?:episode|ep\\.?)\\s*#?\\s*(\\d{1,4})\\b|\\bE(\\d{1,4})\\b")
        /** "@username" — the creator handle shown under the video. */
        private val CREATOR_HANDLE = Regex("@([A-Za-z0-9_.]{2,32})")
        // Words that mark the video player's action buttons.
        private val ACTION_WORDS = listOf(
            "share", "like", "comment", "save", "bookmark", "follow", "more"
        )
        // Packages that can host the Android share sheet. Samsung's One UI
        // share sheet is the system resolver; other OEMs vary, so during a
        // share flow any non-Mochi window is also scanned for share UI.
        private val SHARE_SHEET_PACKAGES = setOf(
            "com.android.intentresolver",
            "com.google.android.intentresolver",
            "com.samsung.android.share",
            "android"
        )
        private const val SHARE_FLOW_TIMEOUT_MS = 14000L
        private const val NATIVE_TOAST_WAIT_MS = 8000L
        private const val CLIPBOARD_READ_DELAY_MS = 900L
        /** A captured title older than this is stale: Alex has moved on
         *  to another video, so a fresh candidate may replace it even
         *  when it is shorter (see scanTree). */
        private const val TITLE_STALE_MS = 15000L
    }

    private data class AnchorCandidate(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val label: String?, val score: Int
    )

    private val anchorCandidates = ArrayList<AnchorCandidate>()
    private var screenWidth = 0
    private var screenHeight = 0

    private val handler = Handler(Looper.getMainLooper())

    // ----- share-flow state (only touched while a flow is active) -----
    private var sheetOpen = false
    private var shareTapped = false
    private var nativeDownloadTapped = false
    private var nativeFailed = false
    private var awaitingNativeResult = false
    private var nativeWaitStartedAtMs = 0L
    private var copyLinkTapped = false
    private var lastNativeToast: String? = null
    private val shareTimeoutRunnable = Runnable {
        if (MochiState.shareFlowActive) {
            val code = if (!shareTapped) "share_button_not_found"
                else if (!sheetOpen) "share_sheet_timeout"
                else "no_share_option"
            finishShareFlow("error:$code")
        }
    }
    private val nativeWaitRunnable = Runnable {
        if (MochiState.shareFlowActive && awaitingNativeResult) {
            // Mochi's own download was tapped and no toast was seen.
            // Report it as started (result unknown) — Mochi saves to Photos.
            awaitingNativeResult = false
            finishShareFlow("native:${lastNativeToast ?: ""}")
        }
    }
    private val clipboardReadRunnable = Runnable {
        if (!MochiState.shareFlowActive) return@Runnable
        readClipboardLink()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        val dm = DisplayMetrics()
        try {
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getMetrics(dm)
        } catch (e: Exception) {
            dm.widthPixels = 1080
            dm.heightPixels = 1920
        }
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REQUEST_SHARE_TAP) {
            startShareFlow()
        }
        return START_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Toasts (Mochi's download results) only matter during a share flow.
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (MochiState.shareFlowActive) handleToastEvent(event)
            return
        }

        val isMochi = pkg == MOCHI_PACKAGE
        if (!isMochi) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                MochiState.isMochiForeground = false
                MochiState.clearAnchor()
            }
            // During a share flow the share sheet may live in another
            // package (the system resolver). Scan it for share UI.
            if (MochiState.shareFlowActive &&
                (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            ) {
                val root = rootInActiveWindow ?: return
                try { scanShareSheet(root) } finally {
                    try { root.recycle() } catch (e: Exception) { }
                }
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                MochiState.isMochiForeground = true
                // Mochi just came to the foreground: make sure the overlay
                // service is running so the button reopens at its spot.
                try {
                    val intent = android.content.Intent(
                        this, OverlayService::class.java
                    )
                    startForegroundService(intent)
                } catch (e: Exception) {
                    // If the overlay service cannot be started from here,
                    // MainActivity's Start button still starts it.
                }
                if (MochiState.shareFlowActive) {
                    val root = rootInActiveWindow ?: return
                    try { scanShareSheet(root) } finally {
                        try { root.recycle() } catch (e: Exception) { }
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val root = rootInActiveWindow ?: return
                anchorCandidates.clear()
                try {
                    // The episode number changed on a previous scan: this
                    // is a new video, so drop the previous video's
                    // title/creator and re-capture them from this scan
                    // instead of keeping the stale ones.
                    if (MochiState.pendingTitleReset) {
                        MochiState.clearVideoIdentity()
                    }
                    scanTree(root)
                    if (MochiState.shareFlowActive) scanShareSheet(root)
                } finally {
                    try { root.recycle() } catch (e: Exception) { }
                }
                pickAnchor()
            }
        }
    }

    override fun onInterrupt() {}

    // ----- share flow -----

    /**
     * Start the user-initiated share flow: tap Mochi's Share button, then
     * work the share sheet (see [scanShareSheet]). Called only when Alex
     * tapped the overlay download button.
     */
    private fun startShareFlow() {
        if (MochiState.shareFlowActive) return
        MochiState.beginShareFlow(SHARE_FLOW_TIMEOUT_MS + 1000L)
        sheetOpen = false
        shareTapped = false
        nativeDownloadTapped = false
        nativeFailed = false
        awaitingNativeResult = false
        copyLinkTapped = false
        lastNativeToast = null
        handler.removeCallbacks(shareTimeoutRunnable)
        handler.postDelayed(shareTimeoutRunnable, SHARE_FLOW_TIMEOUT_MS)

        val root = try { rootInActiveWindow } catch (e: Exception) { null }
        if (root == null) {
            finishShareFlow("error:share_button_not_found")
            return
        }
        try {
            val shareNode = findShareNode(root)
            if (shareNode == null) {
                finishShareFlow("error:share_button_not_found")
                return
            }
            shareNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            shareTapped = true
            // The sheet opening will arrive as window/content events and
            // be handled by scanShareSheet. If nothing arrives, the
            // timeout above reports it.
        } finally {
            try { root.recycle() } catch (e: Exception) { }
        }
    }

    /** Find Mochi's Share button in the player action column. */
    private fun findShareNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        fun visit(n: AccessibilityNodeInfo) {
            val text = ((n.text?.toString() ?: "") + " " +
                (n.contentDescription?.toString() ?: "")).lowercase()
            if (text.contains("share") && n.isClickable && n.isVisibleToUser) {
                var score = 10
                try {
                    val bounds = Rect()
                    n.getBoundsInScreen(bounds)
                    if (screenWidth > 0 && bounds.left >= (screenWidth * 0.66).toInt()) score += 10
                    if (bounds.bottom > (screenHeight * 0.5).toInt()) score += 5
                } catch (e: Exception) { }
                if (score > bestScore) {
                    bestScore = score
                    // Keep a fresh reference: do not recycle `best` later.
                    best = AccessibilityNodeInfo.obtain(n)
                }
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                try { visit(child) } finally {
                    try { child.recycle() } catch (e: Exception) { }
                }
            }
        }
        visit(node)
        return best
    }

    /**
     * Scan a window that may be Mochi's share sheet (or the system share
     * sheet) for something that resolves the video. Runs only while a
     * share flow is active. Priority:
     *  1. Mochi's own "Download video" — tap it; Mochi saves to Photos.
     *  2. "Copy link" — tap it, then read the clipboard for the link.
     *  3. A visible mochi.tv/watch (or Mux) URL in the sheet text.
     */
    private fun scanShareSheet(root: AccessibilityNodeInfo) {
        if (!MochiState.shareFlowActive) return
        var sawSheetMarker = false
        var downloadNode: AccessibilityNodeInfo? = null
        var copyLinkNode: AccessibilityNodeInfo? = null
        var foundLink: String? = null
        var foundMuxId: String? = null

        fun visit(n: AccessibilityNodeInfo) {
            val text = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val combined = "$text $desc"
            val lower = combined.lowercase()
            if (lower.contains("copy link") || lower.contains("download video") ||
                lower.contains("share")) {
                sawSheetMarker = true
            }
            if (downloadNode == null && lower.contains("download video") &&
                n.isClickable && n.isVisibleToUser
            ) {
                downloadNode = AccessibilityNodeInfo.obtain(n)
            }
            if (copyLinkNode == null && lower.contains("copy link") &&
                n.isClickable && n.isVisibleToUser
            ) {
                copyLinkNode = AccessibilityNodeInfo.obtain(n)
            }
            if (foundLink == null) {
                WATCH_LINK.find(combined)?.let {
                    foundLink = "https://www.mochi.tv/watch/${it.groupValues[1]}"
                }
            }
            if (foundMuxId == null) {
                MUX_URL.find(combined)?.let { foundMuxId = it.groupValues[1] }
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                try { visit(child) } finally {
                    try { child.recycle() } catch (e: Exception) { }
                }
            }
        }
        visit(root)
        if (sawSheetMarker) sheetOpen = true

        // 1. Mochi's own Download video (best outcome: no URL needed).
        if (downloadNode != null && !nativeDownloadTapped && !nativeFailed) {
            nativeDownloadTapped = true
            awaitingNativeResult = true
            nativeWaitStartedAtMs = System.currentTimeMillis()
            downloadNode!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            try { downloadNode!!.recycle() } catch (e: Exception) { }
            handler.removeCallbacks(nativeWaitRunnable)
            handler.postDelayed(nativeWaitRunnable, NATIVE_TOAST_WAIT_MS)
            return
        }
        try { downloadNode?.recycle() } catch (e: Exception) { }

        // Still waiting on Mochi's download result: don't tap anything else.
        if (awaitingNativeResult) return

        // 2. Copy link -> clipboard.
        if (copyLinkNode != null && !copyLinkTapped) {
            copyLinkTapped = true
            copyLinkNode!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            try { copyLinkNode!!.recycle() } catch (e: Exception) { }
            handler.removeCallbacks(clipboardReadRunnable)
            handler.postDelayed(clipboardReadRunnable, CLIPBOARD_READ_DELAY_MS)
            return
        }
        try { copyLinkNode?.recycle() } catch (e: Exception) { }

        // 3. A visible link in the sheet.
        if (foundLink != null) {
            finishShareFlow("link:$foundLink")
            return
        }
        if (foundMuxId != null) {
            MochiState.currentPlaybackId = foundMuxId
            finishShareFlow("link:mux:$foundMuxId")
        }
    }

    /** Read the clipboard for the link "Copy link" just placed there. */
    private fun readClipboardLink() {
        if (!MochiState.shareFlowActive) return
        val link = try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip: ClipData? = cm?.primaryClip
            var found: String? = null
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    val text = clip.getItemAt(i)?.text?.toString() ?: continue
                    val m = ANY_URL.find(text)
                    if (m != null && (m.value.contains("mochi.tv") ||
                            m.value.contains("mux.com"))
                    ) {
                        found = m.value
                        break
                    }
                }
            }
            found
        } catch (e: Exception) {
            null
        }
        if (link != null) {
            if (link.contains("mux.com")) {
                MUX_URL.find(link)?.let { MochiState.currentPlaybackId = it.groupValues[1] }
                finishShareFlow("link:mux:${MochiState.currentPlaybackId ?: ""}")
            } else {
                finishShareFlow("link:$link")
            }
            return
        }
        // Clipboard unreadable/empty (Android 10+ restricts background
        // clipboard reads): fall back to scanning the sheet once more for
        // a visible URL before giving up.
        val root = try { rootInActiveWindow } catch (e: Exception) { null }
        if (root != null) {
            try {
                var visibleLink: String? = null
                fun visit(n: AccessibilityNodeInfo) {
                    val combined = "${n.text ?: ""} ${n.contentDescription ?: ""}"
                    if (visibleLink == null) {
                        WATCH_LINK.find(combined)?.let {
                            visibleLink = "https://www.mochi.tv/watch/${it.groupValues[1]}"
                        }
                    }
                    for (i in 0 until n.childCount) {
                        val child = n.getChild(i) ?: continue
                        try { visit(child) } finally {
                            try { child.recycle() } catch (e: Exception) { }
                        }
                    }
                }
                visit(root)
                if (visibleLink != null) {
                    finishShareFlow("link:$visibleLink")
                    return
                }
            } finally {
                try { root.recycle() } catch (e: Exception) { }
            }
        }
        finishShareFlow("error:clipboard_empty")
    }

    /** Toasts from Mochi while its own download runs. */
    private fun handleToastEvent(event: AccessibilityEvent) {
        val texts = event.text ?: return
        for (t in texts) {
            val s = t.toString()
            val lower = s.lowercase()
            if (lower.contains("saved to your photo") ||
                lower.contains("downloads are off") ||
                lower.contains("couldn't save") ||
                lower.contains("could not save") ||
                lower.contains("still preparing") ||
                lower.contains("preparing your video") ||
                lower.contains("render")
            ) {
                lastNativeToast = s
                if (awaitingNativeResult) {
                    awaitingNativeResult = false
                    handler.removeCallbacks(nativeWaitRunnable)
                    if (lower.contains("saved to your photo")) {
                        finishShareFlow("native:$s")
                    } else if (lower.contains("downloads are off") ||
                        lower.contains("couldn't save") ||
                        lower.contains("could not save")
                    ) {
                        // Mochi refused its own download — keep the flow
                        // alive so "Copy link" can still be tried.
                        nativeFailed = true
                    }
                    // "Still preparing" / "rendering": keep waiting.
                }
                return
            }
        }
    }

    /** Complete the flow and close the share sheet if it is still open. */
    private fun finishShareFlow(result: String) {
        handler.removeCallbacks(shareTimeoutRunnable)
        handler.removeCallbacks(nativeWaitRunnable)
        handler.removeCallbacks(clipboardReadRunnable)
        awaitingNativeResult = false
        val wasSheetOpen = sheetOpen
        sheetOpen = false
        MochiState.completeShareFlow(result)
        if (wasSheetOpen) {
            // One BACK dismisses a bottom sheet / resolver sheet and
            // returns Alex to the player. Only sent when the sheet was
            // actually observed open.
            handler.postDelayed({
                try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (e: Exception) { }
            }, 400)
        }
    }

    // ----- normal (read-only) scanning -----

    /**
     * Pick the best anchor from the candidates found in the last scan.
     * The anchor is the bottom-most action button in the player's right-side
     * column (usually Share). The overlay sits just below it, so it reads as
     * a native fourth action button.
     */
    private fun pickAnchor() {
        if (anchorCandidates.isEmpty()) {
            // No action column visible on this screen: keep the last anchor
            // (the button stays where it was) rather than clearing it.
            return
        }
        // Prefer labelled action buttons; among those, the lowest on screen.
        // Fall back to any clickable node in the right-side region.
        val labelled = anchorCandidates.filter { it.score > 0 }
        val pool = if (labelled.isNotEmpty()) labelled else anchorCandidates
        var best: AnchorCandidate? = null
        for (c in pool) {
            if (best == null || c.bottom > best.bottom ||
                (c.bottom == best.bottom && c.score > best.score)
            ) best = c
        }
        val b = best ?: return
        // Only update if it actually moved (avoid jitter / needless moves).
        val moved = !MochiState.hasAnchor() ||
            Math.abs(b.left - MochiState.anchorLeft) > 4 ||
            Math.abs(b.top - MochiState.anchorTop) > 4 ||
            Math.abs(b.right - MochiState.anchorRight) > 4 ||
            Math.abs(b.bottom - MochiState.anchorBottom) > 4
        if (moved) {
            MochiState.setAnchor(b.left, b.top, b.right, b.bottom, b.label)
        }
    }

    /** Walk the visible node tree: title/link/playbackId + anchor candidates. */
    private fun scanTree(node: AccessibilityNodeInfo) {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val viewId = try { node.viewIdResourceName } catch (e: Exception) { null }
        for (candidate in listOf(text, desc, viewId)) {
            if (candidate.isNullOrBlank()) continue
            WATCH_LINK.find(candidate)?.let {
                MochiState.currentWatchLink = "https://www.mochi.tv/watch/${it.groupValues[1]}"
            }
            MUX_URL.find(candidate)?.let {
                MochiState.currentPlaybackId = it.groupValues[1]
            }
            PLAYBACK_ID.find(candidate)?.let {
                MochiState.currentPlaybackId = it.groupValues[1]
            }
            SIGNED_MUX_URL.find(candidate)?.let {
                MochiState.currentPlaybackUrl = it.value
            }
            // Creator handle + episode number: the two extra signals the
            // API resolution needs to identify the playing episode.
            // Only set them from a leaf node's own text, so a stray
            // "@someone" in a comment does not overwrite the creator.
            // Both the text and the content description are checked:
            // Mochi sometimes puts the episode label in the description.
            if (node.childCount == 0) {
                for (candidate in listOf(text, desc)) {
                    if (candidate.isNullOrBlank()) continue
                    CREATOR_HANDLE.find(candidate)?.let {
                        MochiState.currentCreator = it.groupValues[1]
                    }
                    EPISODE_NUMBER.find(candidate)?.let {
                        val num = it.groupValues[1].ifEmpty { it.groupValues[2] }
                        num.toIntOrNull()?.let { n ->
                            val prev = MochiState.currentEpisodeNumber
                            if (prev != null && prev != n) {
                                // New video (episode number changed): the
                                // title/creator captured for the old video
                                // are stale. Flag a re-capture on the
                                // next scan; the episode number itself is
                                // kept, since it is what signalled this.
                                MochiState.pendingTitleReset = true
                            }
                            MochiState.currentEpisodeNumber = n
                        }
                    }
                }
            }
        }
        // Heuristic for the video title: the longest non-empty text on a
        // leaf node. Titles are usually the most prominent text on the
        // player screen. This is best-effort, not exact.
        //
        // Staleness rule (fix for "worked for a few episodes then
        // stopped"): the old rule only ever replaced the title with a
        // LONGER text, so once a long description/comment was captured,
        // the real (shorter) series title could never take over — and
        // across videos the previous video's title stuck around whenever
        // the new video's title was shorter. Now a new candidate also
        // replaces the current title when the current title is stale
        // (> TITLE_STALE_MS old, i.e. Alex has moved on to another
        // video), so a fresh video's title wins. Within one video's
        // first seconds the longest text still wins.
        if (node.childCount == 0 && !text.isNullOrBlank()) {
            val t = text.trim()
            val prev = MochiState.currentTitle
            if (t.length >= 4 && t.length <= 160 && t != prev) {
                val now = System.currentTimeMillis()
                val stale = now - MochiState.lastTitleAtMs > TITLE_STALE_MS
                if (prev == null || stale || t.length > prev.length) {
                    MochiState.currentTitle = t
                    MochiState.lastTitleAtMs = now
                }
            }
        }
        // Anchor candidate: a visible element in the player's right-side
        // action column (like / comment / share). Score it higher when it
        // carries an action word.
        if (screenWidth > 0 && screenHeight > 0) {
            try {
                if (node.isVisibleToUser) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val w = bounds.width()
                    val h = bounds.height()
                    val density = resources.displayMetrics.density
                    val minPx = (24 * density).toInt()
                    val maxPx = (140 * density).toInt()
                    val rightSide = bounds.left >= (screenWidth * 0.66).toInt()
                    val midVertical = bounds.top >= (screenHeight * 0.20).toInt() &&
                        bounds.bottom <= (screenHeight * 0.96).toInt()
                    val saneSize = w in minPx..maxPx && h in minPx..maxPx
                    val isClickable = node.isClickable
                    val labelText = ((desc ?: "") + " " + (text ?: "")).lowercase()
                    var score = 0
                    for (word in ACTION_WORDS) {
                        if (labelText.contains(word)) { score += 10; break }
                    }
                    if (rightSide && midVertical && saneSize &&
                        (isClickable || score > 0)
                    ) {
                        val label = (desc ?: text)?.trim()?.take(40)
                        anchorCandidates.add(
                            AnchorCandidate(
                                bounds.left, bounds.top, bounds.right, bounds.bottom,
                                label, score
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // A node that cannot report bounds is not an anchor.
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { scanTree(child) } finally { try { child.recycle() } catch (e: Exception) { } }
        }
    }
}
