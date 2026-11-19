package com.alex.mochidownload

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * Setup screen for the Mochi Download overlay.
 *
 * The overlay needs three things, all granted by Alex in Settings (this app
 * cannot grant them itself):
 *  1. "Draw over other apps" — so the floating button can sit on Mochi.
 *  2. "Usage access" — so the button only appears while Mochi is open.
 *  3. The Accessibility Service — so the button knows which video is playing.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var positionText: TextView
    private lateinit var toggleButton: Button
    private lateinit var lockButton: Button
    private lateinit var moveButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        positionText = findViewById(R.id.position_text)
        toggleButton = findViewById(R.id.toggle_button)
        lockButton = findViewById(R.id.lock_button)
        moveButton = findViewById(R.id.move_button)
        val overlayPermButton: Button = findViewById(R.id.overlay_perm_button)
        val usagePermButton: Button = findViewById(R.id.usage_perm_button)
        val a11yButton: Button = findViewById(R.id.a11y_button)
        val notifButton: Button = findViewById(R.id.notif_perm_button)
        val howButton: Button = findViewById(R.id.how_button)

        toggleButton.setOnClickListener { toggleOverlay() }
        lockButton.setOnClickListener { lockPosition() }
        moveButton.setOnClickListener { movePosition() }
        overlayPermButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        usagePermButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        a11yButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        notifButton.setOnClickListener { requestNotificationPermission() }
        // Download alerts (started / saved / failed with the step code)
        // are real notifications. On Android 13+ they are silently
        // dropped unless this runtime permission is granted — the old
        // build declared it in the manifest but never asked for it, so
        // a tap could fail with no alert at all. Ask on first launch.
        requestNotificationPermission()
        howButton.setOnClickListener {
            Toast.makeText(this,
                "1. Long-press the floating button in Mochi and drag it " +
                    "where you want it. Release to place it (it stays dimmed).\n" +
                    "2. Come back here and press \"Lock position\" — the button " +
                    "becomes fixed and tappable: tap it in Mochi to download.\n" +
                    "3. To move it later, press \"Move button\", drag it to a " +
                    "new spot, and lock it again here.\n" +
                    "A tap opens Mochi's Share menu to get the video link (or " +
                    "uses Mochi's own Download). See README for the limits.",
                Toast.LENGTH_LONG).show()
        }
    }

    /** Confirm the placed position: lock the floating button as the
     *  pressable download button. */
    private fun lockPosition() {
        val prefs = getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(OverlayService.KEY_HAS_POSITION, false) &&
            prefs.getInt(OverlayService.KEY_X, -1) < 0
        ) {
            Toast.makeText(this,
                "No position placed yet — long-press the floating button " +
                    "in Mochi, drag it where you want it, release, then " +
                    "press this button.",
                Toast.LENGTH_LONG).show()
            return
        }
        OverlayService.confirmPosition(this)
        refreshStatus()
    }

    /** Unlock the button and re-enter positioning mode. */
    private fun movePosition() {
        OverlayService.requestReposition(this)
        Toast.makeText(this,
            "Positioning mode on — drag the button in Mochi to a new " +
                "spot, release, then press \"Lock position\" here.",
            Toast.LENGTH_LONG).show()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(statusRunnable)
        handler.post(statusRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(statusRunnable)
        super.onPause()
    }

    private fun isOverlayRunning(): Boolean {
        // Best-effort: the service records its enabled state in prefs.
        return getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(OverlayService.KEY_ENABLED, false)
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                "Allow \"Draw over other apps\" first (button above).",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        val intent = Intent(this, OverlayService::class.java)
        if (isOverlayRunning()) {
            intent.action = OverlayService.ACTION_STOP
            startService(intent)
            Toast.makeText(this, "Overlay stopped", Toast.LENGTH_SHORT).show()
        } else {
            startForegroundService(intent)
            Toast.makeText(this,
                "Overlay on. Open Mochi and the button will appear.",
                Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    /** Android 13+: download alerts are real notifications, and the
     *  system silently drops them without this runtime grant. If the
     *  user denied it before, Android will not show the prompt again —
     *  in that case open the app's notification settings instead. */
    private fun isNotificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (isNotificationsGranted()) {
            Toast.makeText(this, "Notifications already allowed.",
                Toast.LENGTH_SHORT).show()
            return
        }
        if (shouldShowRequestPermissionRationale(
                android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        } else {
            // First ask, or "don't ask again" was ticked: try the prompt
            // once; if the system refuses to show it, the user can allow
            // it from the app settings screen instead.
            try {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            } catch (e: Exception) {
                startActivity(Intent(
                    Settings.ACTION_APP_NOTIFICATION_SETTINGS
                ).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val usageOk = isUsageAccessGranted()
        val a11yOk = isAccessibilityEnabled()
        val notifOk = isNotificationsGranted()
        val running = isOverlayRunning()
        val title = MochiState.currentTitle
        val sb = StringBuilder()
        sb.append("Overlay: ").append(if (running) "ON" else "OFF").append("\n")
        sb.append("Draw over apps: ").append(if (overlayOk) "granted" else "NOT granted").append("\n")
        sb.append("Usage access: ").append(if (usageOk) "granted" else "NOT granted").append("\n")
        sb.append("Accessibility service: ").append(if (a11yOk) "on" else "OFF").append("\n")
        sb.append("Notifications: ").append(
            if (notifOk) "granted" else "NOT granted — download alerts will not show"
        ).append("\n")
        sb.append("Mochi in foreground: ")
            .append(if (MochiState.isMochiForeground) "yes" else "no").append("\n")
        if (!title.isNullOrBlank()) sb.append("Now playing: ").append(title).append("\n")
        if (!overlayOk || !usageOk || !a11yOk || !notifOk) {
            sb.append("\nGrant the items above, then start the overlay.")
        }
        statusText.text = sb.toString()
        toggleButton.text = if (running) "Stop overlay" else "Start overlay"

        // Position state (the two-step confirm flow).
        val prefs = getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
        val hasPosition = prefs.getBoolean(OverlayService.KEY_HAS_POSITION, false) ||
            prefs.getInt(OverlayService.KEY_X, -1) >= 0
        val confirmed = prefs.getBoolean(OverlayService.KEY_CONFIRMED, false)
        val posX = prefs.getInt(OverlayService.KEY_X, -1)
        val posY = prefs.getInt(OverlayService.KEY_Y, -1)
        when {
            !hasPosition -> {
                positionText.text = getString(R.string.position_none)
                lockButton.isEnabled = false
                moveButton.isEnabled = false
            }
            !confirmed -> {
                positionText.text = getString(R.string.position_pending) +
                    " (placed at $posX, $posY)"
                lockButton.isEnabled = true
                moveButton.isEnabled = false
            }
            else -> {
                positionText.text = getString(R.string.position_locked) +
                    " (locked at $posX, $posY)"
                lockButton.isEnabled = false
                moveButton.isEnabled = true
            }
        }
    }

    private fun isUsageAccessGranted(): Boolean {
        // There is no direct API; use AppOpsManager to check the mode.
        return try {
            val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(
                "android:get_usage_stats", android.os.Process.myUid(), packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }
}
