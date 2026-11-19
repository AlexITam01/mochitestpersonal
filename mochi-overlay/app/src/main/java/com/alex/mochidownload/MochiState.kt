package com.alex.mochidownload

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
 * screen (titles, labels, element bounds). The video's stream URL /
 * playbackId lives inside Mochi's own app state and is NOT shown in the
 * UI, so it is usually not here. That is why a tap cannot always resolve
 * a download — see [VideoDownloader] and README.md.
 */
object MochiState {
    @Volatile var isMochiForeground: Boolean = false
    @Volatile var currentTitle: String? = null
    @Volatile var currentWatchLink: String? = null
    @Volatile var currentPlaybackId: String? = null
    @Volatile var lastTitleAtMs: Long = 0L

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

    fun reset() {
        isMochiForeground = false
        currentTitle = null
        currentWatchLink = null
        currentPlaybackId = null
        lastTitleAtMs = 0L
        clearAnchor()
    }
}
