package com.lifedeck.app.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lifedeck.app.MainActivity
import com.lifedeck.app.R
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.ReminderRecord
import com.lifedeck.app.util.formatDateTime

/**
 * Posts notifications: reminders with Complete / Snooze / Reschedule quick
 * actions that deep-link straight into the relevant task.
 */
class NotificationHelper(private val context: Context) {

    private val canPost: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    fun showReminder(
        reminder: ReminderRecord,
        entry: Entry,
        shiftedNote: String = "",
        notificationId: Int = notificationIdFor(reminder.id),
    ) {
        if (!canPost) return

        val openIntent = taskIntent(entry.id, fromNotification = true)
        val content = buildString {
            append(entry.notes.ifBlank { entry.type.name.lowercase().replace('_', ' ') })
            if (shiftedNote.isNotBlank()) append(" · ").append(shiftedNote)
        }.ifBlank { "Tap to open" }

        val complete = actionIntent(NotificationActionReceiver.ACTION_COMPLETE, reminder.id, entry.id, 1)
        val snooze = actionIntent(NotificationActionReceiver.ACTION_SNOOZE, reminder.id, entry.id, 2)
        val reschedule = actionIntent(NotificationActionReceiver.ACTION_RESCHEDULE, reminder.id, entry.id, 3)

        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_lifedeck)
            .setContentTitle(entry.title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setWhen(System.currentTimeMillis())
            .addAction(0, context.getString(R.string.action_complete), complete)
            .addAction(0, context.getString(R.string.action_snooze), snooze)
            .addAction(0, context.getString(R.string.action_reschedule), reschedule)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun showDirectAlarm(id: String, title: String, type: String) {
        if (!canPost) return
        val prefs = context.getSharedPreferences("lifedeck_prefs", Context.MODE_PRIVATE)
        val soundUriStr = prefs.getString("alarm_sound_uri", null)
        val soundUri = if (!soundUriStr.isNullOrBlank()) {
            android.net.Uri.parse(soundUriStr)
        } else {
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
        }
        val openIntent = taskIntent(id, fromNotification = true)
        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_lifedeck)
            .setContentTitle("⏰ Reminder: $title")
            .setContentText("Scheduled LifeDeck alarm reminder is due now")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setFullScreenIntent(openIntent, true)
            .build()
        NotificationManagerCompat.from(context).notify(("alarm:$id").hashCode() and 0x7FFFFFFF, notification)
    }

    /** Morning / evening review notification. */
    fun showSummary(title: String, text: String, bigText: String, id: Int) {
        if (!canPost) return
        val open = taskIntent(null, fromNotification = false, screen = "today")
        val notification = NotificationCompat.Builder(context, NotificationChannels.SUMMARIES)
            .setSmallIcon(R.drawable.ic_stat_lifedeck)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /** Generic alert used for inbox prompts, permission warnings, sync errors, undo. */
    fun showAlert(title: String, text: String, id: Int, screen: String = "today", undoEntryId: String? = null) {
        if (!canPost) return
        val contentIntent = if (undoEntryId != null) {
            PendingIntent.getBroadcast(
                context, id,
                Intent(context, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_UNDO
                    putExtra(NotificationActionReceiver.EXTRA_ENTRY_ID, undoEntryId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            taskIntent(null, fromNotification = false, screen = screen)
        }
        val notification = NotificationCompat.Builder(context, NotificationChannels.TASK_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_lifedeck)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun cancel(id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun taskIntent(entryId: String?, fromNotification: Boolean, screen: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            entryId?.let { putExtra(MainActivity.IntentExtra.OPEN_TASK_ID, it) }
            screen?.let { putExtra(MainActivity.IntentExtra.OPEN_SCREEN, it) }
            if (fromNotification) putExtra("from_notification", true)
        }
        return PendingIntent.getActivity(
            context, if (entryId != null) entryId.hashCode() and 0x7FFFFFFF else 9001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(action: String, reminderId: String, entryId: String, code: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(NotificationActionReceiver.EXTRA_ENTRY_ID, entryId)
        }
        return PendingIntent.getBroadcast(
            context,
            (reminderId.hashCode() + code * 31) and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Stable notification id per reminder (also used by the scheduler). */
        fun notificationIdFor(reminderId: String): Int =
            ("reminder:" + reminderId).hashCode() and 0x7FFFFFFF

        fun summaryId(kind: String): Int = ("summary:" + kind).hashCode() and 0x7FFFFFFF
    }
}
