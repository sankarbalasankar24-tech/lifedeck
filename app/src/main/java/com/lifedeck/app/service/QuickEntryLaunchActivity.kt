package com.lifedeck.app.service

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class QuickEntryLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Close system dialogs / notification panel
        try {
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Exception) {}

        // Launch the floating Quick Entry overlay window
        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = FloatingBubbleService.ACTION_SHOW_QUICK_ENTRY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        finish()
        overridePendingTransition(0, 0)
    }
}
