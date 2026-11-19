package com.alex.mochidownload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores the floating button after a reboot, but only if Alex had the
 * overlay enabled when the phone shut down.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(
            OverlayService.PREFS, Context.MODE_PRIVATE
        )
        if (!prefs.getBoolean(OverlayService.KEY_ENABLED, false)) return
        if (!android.provider.Settings.canDrawOverlays(context)) return
        try {
            context.startForegroundService(
                Intent(context, OverlayService::class.java)
            )
        } catch (e: Exception) {
            // System still booting / background-start restriction: ignore.
        }
    }
}
