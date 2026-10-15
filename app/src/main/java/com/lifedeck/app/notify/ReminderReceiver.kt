package com.lifedeck.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lifedeck.app.LifeDeckApp
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.ReminderStatus
import com.lifedeck.app.domain.RecurrenceEngine
import com.lifedeck.app.util.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fired by AlarmManager. Posts the reminder notification, then advances the
 * reminder row (repeat → next occurrence, escalation → follow-up, otherwise
 * delivered) so scheduled alarms always reflect reality.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderReceiver.ACTION_FIRE) return
        val code = intent.getIntExtra(EXTRA_REQUEST_CODE, -1)
        val payload = intent.getStringExtra(EXTRA_PAYLOAD)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? LifeDeckApp ?: return@launch
                val container = app.container
                when {
                    ReminderScheduler.isSummaryCode(code) ->
                        SummaryNotifier(context).fire(ReminderScheduler.summaryKeyFor(code) ?: return@launch)
                    code >= MEMORY_CODE_BASE && payload != null ->
                        fireMemoryAnniversary(context, payload)
                    payload != null -> fireReminder(context, payload)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fireReminder(context: Context, reminderId: String) {
        val container = (context.applicationContext as LifeDeckApp).container
        val reminder = container.database.read { sqlite ->
            sqlite.rawQuery("SELECT * FROM reminders WHERE id=?", arrayOf(reminderId))
                .use { c -> if (c.moveToFirst()) c.toReminder() else null }
        } ?: return

        if (reminder.status == ReminderStatus.CANCELLED || reminder.status == ReminderStatus.DONE) return

        val entry = container.tasks.get(reminder.entryId) ?: return
        if (entry.deleted || !entry.isOpen) {
            container.tasks.updateReminder(reminder.copy(status = ReminderStatus.CANCELLED))
            return
        }

        val settings = container.settings.get()
        if (!settings.notificationsEnabled) return

        val scheduler = container.scheduler
        val effective = scheduler.effectiveFireTime(reminder, entry.contextHint, settings) ?: reminder.fireAt
        val note = scheduler.shiftNote(reminder.fireAt, effective)

        NotificationHelper(context).showReminder(reminder, entry, shiftedNote = note)

        // Advance the reminder record.
        val ts = now()
        val hasRepeat = reminder.repeat != null && reminder.repeat!!.isActive
        val escalationLeft = (reminder.escalationIntervalMinutes != null) &&
            reminder.escalationFired < reminder.escalationMaxCount
        val updated = when {
            hasRepeat -> {
                val next = RecurrenceEngine.nextOccurrence(ts, reminder.repeat!!) ?: ts + 86_400_000L
                reminder.copy(
                    fireAt = next, status = ReminderStatus.PENDING, lastFiredAt = ts,
                    snoozedUntil = null, updatedAt = ts, version = reminder.version + 1, dirty = true,
                )
            }
            escalationLeft && entry.isOpen -> reminder.copy(
                fireAt = ts + (reminder.escalationIntervalMinutes!! * 60_000L),
                escalationFired = reminder.escalationFired + 1,
                status = ReminderStatus.PENDING,
                snoozedUntil = null,
                lastFiredAt = ts,
                updatedAt = ts,
                version = reminder.version + 1,
                dirty = true,
            )
            else -> reminder.copy(
                status = ReminderStatus.FIRED, lastFiredAt = ts, snoozedUntil = null,
                updatedAt = ts, version = reminder.version + 1, dirty = true,
            )
        }
        container.tasks.updateReminder(updated)
        scheduler.reconcileAsync()
    }

    private suspend fun fireMemoryAnniversary(context: Context, memoryId: String) {
        val container = (context.applicationContext as LifeDeckApp).container
        val memory = container.memories.get(memoryId) ?: return
        NotificationHelper(context).showAlert(
            title = "On this day 💖",
            text = "${memory.title} happened " + com.lifedeck.app.util.formatFullDate(memory.occurredAt).let { "on $it" },
            id = NotificationHelper.notificationIdFor("memory:$memoryId"),
            screen = "memories",
        )
        // Roll the reminder to next year so it does not repeat every boot.
        val nextYear = memory.occurredAt + 365L * 86_400_000L
        container.memories.update(memory.copy(nextReminderAt = if (memory.remindYearly) nextYear else null))
    }

    companion object {
        const val ACTION_FIRE = "com.lifedeck.app.action.REMINDER_FIRE"
        const val EXTRA_REQUEST_CODE = "request_code"
        const val EXTRA_PAYLOAD = "payload"
        const val MEMORY_CODE_BASE = 900_000_000
    }
}
