package com.lifedeck.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lifedeck.app.LifeDeckApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AlarmManager alarms are lost on reboot and can be wrong after clock or
 * time-zone changes. This receiver rebuilds the full schedule from the
 * database every time one of those events happens.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? LifeDeckApp ?: return@launch
                NotificationChannels.ensure(app)
                app.container.scheduler.reconcileAsync()
                // Re-arm periodic maintenance (idempotent).
                com.lifedeck.app.sync.MaintenanceWorker.schedule(app)
                // Restart floating bubble overlay service if permission is active
                try {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                        android.provider.Settings.canDrawOverlays(app)) {
                        val bubbleIntent = Intent(app, com.lifedeck.app.service.FloatingBubbleService::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            app.startForegroundService(bubbleIntent)
                        } else {
                            app.startService(bubbleIntent)
                        }
                    }
                } catch (_: Exception) {}

                // Give the scheduler a moment, then run a full pass.
                kotlinx.coroutines.delay(1_500)
                app.container.scheduler.reconcile()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
