package com.lifedeck.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.content.Context

/** All notification channels, created once at startup. */
object NotificationChannels {
    const val REMINDERS = "lifedeck_reminders"
    const val SUMMARIES = "lifedeck_summaries"
    const val TASK_ALERTS = "lifedeck_task_alerts"
    const val CELEBRATIONS = "lifedeck_celebrations"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        fun channel(id: String, name: String, importance: Int, sound: Boolean) {
            if (manager.getNotificationChannel(id) != null) return
            val ch = NotificationChannel(id, name, importance).apply {
                description = name
                enableVibration(true)
                if (sound) {
                    setSound(
                        defaultSound,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                } else {
                    setSound(null, null)
                    enableVibration(false)
                }
            }
            manager.createNotificationChannel(ch)
        }

        channel(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH, true)
        channel(SUMMARIES, "Daily summaries", NotificationManager.IMPORTANCE_DEFAULT, true)
        channel(TASK_ALERTS, "Task alerts", NotificationManager.IMPORTANCE_DEFAULT, false)
        channel(CELEBRATIONS, "Celebrations", NotificationManager.IMPORTANCE_LOW, false)
    }
}
