package com.alex.mochidownload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast

/**
 * Draws the floating download button on top of Mochi, and runs concurrently
 * with Mochi: a foreground service + overlay window that is alive while
 * Mochi is in the foreground.
 *
 * Behaviour (exactly what Alex asked for):
 * 1. Runs concurrently with Mochi. The service is alive while Mochi is in
 *    the foreground; the button is only drawn over Mochi.
 * 2. Long-press the button to enter positioning mode, drag it where it
 *    should go, and on RELEASE the position is recorded and persisted
 *    (SharedPreferences). No separate "save" step.
 * 3. The overlay closes when Mochi closes (the button window is removed),
 *    and when Mochi opens again it reopens automatically at that same
 *    recorded position — the button is back on the same spot where it was
 *    left. The service keeps a lightweight watch so it can re-show it.
 * 4. Alex navigates Mochi himself. This app never taps, swipes, or scrolls
 *    Mochi for him; it only reads (AccessibilityService) and draws its own
 *    button.
 * 5. Tap the button (when locked) to download the video Mochi is currently
 *    playing, saved to the device's Downloads folder (see VideoDownloader
 *    for the honest limits on resolving the link).
 *
 * Suckerfish anchoring: where Mochi's player action column (like / comment
 * / share) is visible, the button latches onto it — it sits just below the
 * bottom-most action button, as if it were a native fourth action. The
 * AccessibilityService reports that anchor's screen bounds; this service
 * positions the button relative to it and re-anchors when the layout
 * shifts. Long-press + drag sets Alex's preferred offset from the anchor.
 * If no anchor is visible, the button falls back to its recorded absolute
 * position (free-floating).
 *
 * This app does NOT modify Mochi and does NOT inject code into it. It is a
 * separate app that draws an overlay window on top of Mochi. That is why
 * Mochi's signature (and Play Integrity) is untouched.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.alex.mochidownload.ACTION_STOP"
        const val PREFS = "mochi_overlay"
        const val KEY_X = "pos_x"
        const val KEY_Y = "pos_y"
        const val KEY_ENABLED = "overlay_enabled"
        const val KEY_OFF_X = "anchor_off_x"
        const val KEY_OFF_Y = "anchor_off_y"
        const val KEY_HAS_OFF = "has_anchor_off"
        const val MOCHI_PACKAGE = "com.mochitv.mochi"
        private const val NOTIF_CHANNEL = "mochi_overlay"
        private const val NOTIF_ID = 1
        private const val LONG_PRESS_MS = 500L
        private const val MOVE_TOLERANCE_PX = 12f
        private const val POLL_MS = 1000L
        private const val BUTTON_SIZE_DP = 64
        private const val ANCHOR_GAP_DP = 12
        private const val REPOSITION_TOLERANCE_PX = 4
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var button: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var buttonVisible = false
    private var editMode = false
    private var buttonSizePx = 0

    // Last anchor bounds the button was positioned against, so we can tell
    // when the layout shifted and re-anchor.
    private var lastAnchorL = -1
    private var lastAnchorT = -1
    private var lastAnchorR = -1
    private var lastAnchorB = -1

    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { enterEditMode() }
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var moved = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            updateVisibilityAndAnchor()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        buttonSizePx = (BUTTON_SIZE_DP * resources.displayMetrics.density).toInt()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        updateVisibilityAndAnchor()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(longPressRunnable)
        removeButton()
        super.onDestroy()
    }

    fun stopOverlay() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        handler.removeCallbacks(pollRunnable)
        removeButton()
    }

    // ----- foreground detection + anchor tracking -----

    /** Show the button only while Mochi is foreground; re-anchor it when
     *  Mochi's layout shifts. The button is REMOVED (overlay closes) when
     *  Mochi is not foreground, and re-added at the recorded position when
     *  Mochi comes back. */
    private fun updateVisibilityAndAnchor() {
        val foreground = currentForegroundPackage()
        if (foreground == MOCHI_PACKAGE) {
            if (!buttonVisible) {
                showButton()
            } else if (!editMode) {
                trackAnchor()
            }
        } else {
            if (buttonVisible) removeButton()
        }
    }

    /** If the anchor moved since we last positioned the button, move the
     *  button with it (suckerfish). Only when not being dragged by Alex. */
    private fun trackAnchor() {
        val lp = params ?: return
        if (!MochiState.hasAnchor()) return
        val anchorMoved =
            MochiState.anchorLeft != lastAnchorL ||
            MochiState.anchorTop != lastAnchorT ||
            MochiState.anchorRight != lastAnchorR ||
            MochiState.anchorBottom != lastAnchorB
        if (!anchorMoved) return
        val (x, y) = computeButtonPosition()
        if (Math.abs(x - lp.x) > REPOSITION_TOLERANCE_PX ||
            Math.abs(y - lp.y) > REPOSITION_TOLERANCE_PX
        ) {
            lp.x = x
            lp.y = y
            try { windowManager.updateViewLayout(button, lp) } catch (e: Exception) { }
        }
        lastAnchorL = MochiState.anchorLeft
        lastAnchorT = MochiState.anchorTop
        lastAnchorR = MochiState.anchorRight
        lastAnchorB = MochiState.anchorBottom
    }

    /** Best-effort foreground package via UsageStats. Returns null if the
     *  "Usage access" permission has not been granted. */
    private fun currentForegroundPackage(): String? {
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST, now - 5000, now
        ) ?: return null
        var latest: android.app.usage.UsageStats? = null
        for (s in stats) {
            if (latest == null || s.lastTimeUsed > latest.lastTimeUsed) latest = s
        }
        if (latest != null && now - latest.lastTimeUsed < 3000) {
            return latest.packageName
        }
        return null
    }

    // ----- positioning -----

    /**
     * Where the button should sit.
     * If Mochi's action-column anchor is visible, the button latches onto
     * it: centred under the bottom-most action button, plus Alex's saved
     * offset from that anchor (set by long-press + drag). If no anchor is
     * visible, it uses the recorded absolute position (free-floating).
     * Returns (x, y) for the button's top-left corner.
     */
    private fun computeButtonPosition(): Pair<Int, Int> {
        if (MochiState.hasAnchor()) {
            val gapPx = (ANCHOR_GAP_DP * resources.displayMetrics.density).toInt()
            val offX = if (prefs.getBoolean(KEY_HAS_OFF, false)) {
                prefs.getInt(KEY_OFF_X, 0)
            } else 0
            val offY = if (prefs.getBoolean(KEY_HAS_OFF, false)) {
                prefs.getInt(KEY_OFF_Y, gapPx)
            } else gapPx
            val x = MochiState.anchorCenterX() + offX - buttonSizePx / 2
            val y = MochiState.anchorBottomY() + offY
            return Pair(x, y)
        }
        val startX = prefs.getInt(KEY_X, -1)
        val startY = prefs.getInt(KEY_Y, -1)
        if (startX >= 0 && startY >= 0) return Pair(startX, startY)
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels - buttonSizePx - 32, dm.heightPixels / 2)
    }

    /** Record the button's current spot and persist it. Called on release
     *  after a drag in positioning mode (and on tap-to-lock). If an anchor
     *  is visible, the spot is stored as an offset from that anchor so it
     *  keeps tracking the UI; otherwise it is stored as an absolute
     *  position. */
    private fun recordPosition() {
        val lp = params ?: return
        if (MochiState.hasAnchor()) {
            val offX = (lp.x + buttonSizePx / 2) - MochiState.anchorCenterX()
            val offY = lp.y - MochiState.anchorBottomY()
            prefs.edit()
                .putInt(KEY_OFF_X, offX)
                .putInt(KEY_OFF_Y, offY)
                .putBoolean(KEY_HAS_OFF, true)
                .putInt(KEY_X, lp.x)
                .putInt(KEY_Y, lp.y)
                .apply()
        } else {
            prefs.edit()
                .putInt(KEY_X, lp.x)
                .putInt(KEY_Y, lp.y)
                .apply()
        }
        lastAnchorL = MochiState.anchorLeft
        lastAnchorT = MochiState.anchorTop
        lastAnchorR = MochiState.anchorRight
        lastAnchorB = MochiState.anchorBottom
    }

    // ----- floating button -----

    private fun showButton() {
        if (button != null || !Settings.canDrawOverlays(this)) return
        val iv = ImageView(this)
        iv.setImageResource(R.drawable.download_button)
        iv.setOnTouchListener(buttonTouchListener)
        val (x, y) = computeButtonPosition()
        val lp = WindowManager.LayoutParams(
            buttonSizePx, buttonSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y
        try {
            windowManager.addView(iv, lp)
        } catch (e: Exception) {
            return
        }
        button = iv
        params = lp
        buttonVisible = true
        lastAnchorL = MochiState.anchorLeft
        lastAnchorT = MochiState.anchorTop
        lastAnchorR = MochiState.anchorRight
        lastAnchorB = MochiState.anchorBottom
        applyEditHighlight()
    }

    private fun removeButton() {
        val b = button ?: return
        try { windowManager.removeView(b) } catch (e: Exception) { /* already gone */ }
        button = null
        params = null
        buttonVisible = false
        editMode = false
    }

    private fun applyEditHighlight() {
        val b = button ?: return
        if (editMode) {
            b.setBackgroundResource(R.drawable.button_edit_outline)
            b.alpha = 0.85f
        } else {
            b.setBackgroundResource(0)
            b.alpha = 1.0f
        }
    }

    private fun enterEditMode() {
        if (button == null || editMode) return
        editMode = true
        applyEditHighlight()
        Toast.makeText(this, "Move the button, then release to lock the position",
            Toast.LENGTH_SHORT).show()
    }

    private fun exitEditModeAndSave() {
        if (!editMode) return
        editMode = false
        applyEditHighlight()
        recordPosition()
        Toast.makeText(this, "Position saved", Toast.LENGTH_SHORT).show()
    }

    private val buttonTouchListener = View.OnTouchListener { _, event ->
        val lp = params ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downRawX = event.rawX
                downRawY = event.rawY
                downParamX = lp.x
                downParamY = lp.y
                moved = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (Math.abs(dx) > MOVE_TOLERANCE_PX || Math.abs(dy) > MOVE_TOLERANCE_PX) {
                    moved = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (moved && editMode) {
                    lp.x = (downParamX + dx).toInt()
                    lp.y = (downParamY + dy).toInt()
                    try { windowManager.updateViewLayout(button, lp) } catch (e: Exception) { }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (!moved) {
                    // A tap.
                    if (editMode) {
                        // Tap while positioning: lock the spot.
                        exitEditModeAndSave()
                    } else {
                        // Tap (locked) = download the video Mochi is playing.
                        VideoDownloader.onDownloadTapped(this)
                    }
                } else if (editMode) {
                    // Released after a drag in positioning mode: the spot
                    // is recorded and persisted right here, and the button
                    // locks. This is the "on release" behaviour Alex asked
                    // for — no separate save step.
                    exitEditModeAndSave()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                true
            }
            else -> false
        }
    }

    // ----- foreground-service notification -----

    private fun startForegroundNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "Mochi Download overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "Keeps the floating Mochi download button alive."
            nm.createNotificationChannel(ch)
        }
        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Mochi Download")
            .setContentText("Floating download button is on. It appears over Mochi.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }
}
