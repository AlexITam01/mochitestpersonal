package com.alex.mochidownload

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
    private lateinit var toggleButton: Button
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
        toggleButton = findViewById(R.id.toggle_button)
        val overlayPermButton: Button = findViewById(R.id.overlay_perm_button)
        val usagePermButton: Button = findViewById(R.id.usage_perm_button)
        val a11yButton: Button = findViewById(R.id.a11y_button)
        val howButton: Button = findViewById(R.id.how_button)

        toggleButton.setOnClickListener { toggleOverlay() }
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
        howButton.setOnClickListener {
            Toast.makeText(this,
                "Long-press the floating button to move it. Tap it to download " +
                    "the video Mochi is playing. See README in the repo for " +
                    "why a download sometimes cannot be resolved.",
                Toast.LENGTH_LONG).show()
        }
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

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val usageOk = isUsageAccessGranted()
        val a11yOk = isAccessibilityEnabled()
        val running = isOverlayRunning()
        val title = MochiState.currentTitle
        val sb = StringBuilder()
        sb.append("Overlay: ").append(if (running) "ON" else "OFF").append("\n")
        sb.append("Draw over apps: ").append(if (overlayOk) "granted" else "NOT granted").append("\n")
        sb.append("Usage access: ").append(if (usageOk) "granted" else "NOT granted").append("\n")
        sb.append("Accessibility service: ").append(if (a11yOk) "on" else "OFF").append("\n")
        sb.append("Mochi in foreground: ")
            .append(if (MochiState.isMochiForeground) "yes" else "no").append("\n")
        if (!title.isNullOrBlank()) sb.append("Now playing: ").append(title).append("\n")
        if (!overlayOk || !usageOk || !a11yOk) {
            sb.append("\nGrant the three items above, then start the overlay.")
        }
        statusText.text = sb.toString()
        toggleButton.text = if (running) "Stop overlay" else "Start overlay"
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
