package com.lifedeck.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lifedeck.app.LifeDeckApp
import com.lifedeck.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Quick actions on notifications: Complete (with undo), Snooze and Reschedule.
 * Runs the database work with goAsync() so it works even when the app process
 * was started only for this broadcast.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? LifeDeckApp ?: return@launch
                val container = app.container
                val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID)
                val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
                when (intent.action) {
                    ACTION_COMPLETE -> complete(context, entryId, reminderId, container)
                    ACTION_UNDO -> undo(context, entryId, container)
                    ACTION_SNOOZE -> snooze(context, entryId, reminderId, container)
                    ACTION_RESCHEDULE -> reschedule(context, entryId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun complete(
        context: Context,
        entryId: String?,
        reminderId: String?,
        container: com.lifedeck.app.data.AppContainer,
    ) {
        if (entryId == null) return
        container.tasks.complete(entryId, fromReminder = true)
        reminderId?.let { NotificationHelper(context).cancel(NotificationHelper.notificationIdFor(it)) }
        container.scheduler.reconcileAsync()

        // Offer undo for a few seconds so a mis-tap is never fatal.
        val entry = container.tasks.get(entryId) ?: return
        NotificationHelper(context).showAlert(
            title = "Completed ✓",
            text = "${entry.title} — tap to undo",
            id = NotificationHelper.notificationIdFor("undo:$entryId"),
            undoEntryId = entryId,
        )
    }

    private suspend fun undo(context: Context, entryId: String?, container: com.lifedeck.app.data.AppContainer) {
        if (entryId == null) return
        container.tasks.uncomplete(entryId)
        NotificationHelper(context).cancel(NotificationHelper.notificationIdFor("undo:$entryId"))
        container.scheduler.reconcileAsync()
    }

    private suspend fun snooze(
        context: Context,
        entryId: String?,
        reminderId: String?,
        container: com.lifedeck.app.data.AppContainer,
    ) {
        if (reminderId == null) return
        val settings = container.settings.get()
        val until = com.lifedeck.app.util.now() + settings.snoozeMinutes * 60_000L
        container.tasks.snoozeReminder(reminderId, until)
        NotificationHelper(context).cancel(NotificationHelper.notificationIdFor(reminderId))
        container.scheduler.reconcileAsync()
    }

    private fun reschedule(context: Context, entryId: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            entryId?.let { putExtra(MainActivity.IntentExtra.OPEN_TASK_ID, it) }
            putExtra(MainActivity.IntentExtra.OPEN_SCREEN, "task")
        }
        context.startActivity(intent)
    }

    companion object {
        const val ACTION_COMPLETE = "com.lifedeck.app.action.COMPLETE"
        const val ACTION_UNDO = "com.lifedeck.app.action.UNDO_COMPLETE"
        const val ACTION_SNOOZE = "com.lifedeck.app.action.SNOOZE"
        const val ACTION_RESCHEDULE = "com.lifedeck.app.action.RESCHEDULE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_ENTRY_ID = "entry_id"
    }
}
