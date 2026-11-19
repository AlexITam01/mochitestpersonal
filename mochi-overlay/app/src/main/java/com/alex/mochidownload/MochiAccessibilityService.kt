package com.alex.mochidownload

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
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
 * bounds of on-screen elements (including the like / comment / share action
 * column), and — if Mochi ever shows one — a mochi.tv/watch link or a Mux
 * playbackId. What it CANNOT see: the video's stream URL. That lives in
 * Mochi's internal app state and in its encrypted (HTTPS) traffic, neither
 * of which an AccessibilityService can read. See README.md for why the
 * download is best-effort.
 *
 * This service only READS. It never taps, swipes, scrolls, or otherwise
 * navigates Mochi for Alex — he drives Mochi himself.
 */
class MochiAccessibilityService : AccessibilityService() {

    companion object {
        private const val MOCHI_PACKAGE = "com.mochitv.mochi"
        private val WATCH_LINK = Regex("mochi\\.tv/watch/([A-Za-z0-9_-]+)")
        private val MUX_URL = Regex("stream\\.mux\\.com/([A-Za-z0-9_-]+)")
        private val PLAYBACK_ID = Regex("playbackId[^A-Za-z0-9]{0,4}([A-Za-z0-9_-]{10,})")
        // Words that mark the video player's action buttons.
        private val ACTION_WORDS = listOf(
            "share", "like", "comment", "save", "bookmark", "follow", "more"
        )
    }

    private data class AnchorCandidate(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val label: String?, val score: Int
    )

    private val anchorCandidates = ArrayList<AnchorCandidate>()
    private var screenWidth = 0
    private var screenHeight = 0

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != MOCHI_PACKAGE) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                MochiState.isMochiForeground = false
                MochiState.clearAnchor()
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
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val root = rootInActiveWindow ?: return
                anchorCandidates.clear()
                try { scanTree(root) } finally { try { root.recycle() } catch (e: Exception) { } }
                pickAnchor()
            }
        }
    }

    override fun onInterrupt() {}

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
        }
        // Heuristic for the video title: the longest non-empty text on a
        // leaf node. Titles are usually the most prominent text on the
        // player screen. This is best-effort, not exact.
        if (node.childCount == 0 && !text.isNullOrBlank()) {
            val t = text.trim()
            val prev = MochiState.currentTitle
            if (t.length >= 4 && t.length <= 160 && t != prev) {
                if (prev == null || t.length > prev.length) {
                    MochiState.currentTitle = t
                    MochiState.lastTitleAtMs = System.currentTimeMillis()
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
