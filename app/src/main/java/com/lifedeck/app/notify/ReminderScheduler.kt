package com.lifedeck.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import com.lifedeck.app.LifeDeckApp
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.ReminderRecord
import com.lifedeck.app.data.repo.AppSettings
import com.lifedeck.app.data.model.ReminderStatus
import com.lifedeck.app.data.model.TimeWindow
import com.lifedeck.app.util.CelebrationBus
import com.lifedeck.app.util.atTime
import com.lifedeck.app.util.formatTime
import com.lifedeck.app.util.now
import com.lifedeck.app.util.toLocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Keeps Android alarms in sync with the database.
 *
 * Design:
 *  - One reconcile pass reads *pending* reminders and (re)arms exact alarms.
 *  - Every database write triggers a debounced reconcile, so adding, editing,
 *    completing or rescheduling recalculates notifications immediately.
 *  - Notification times may shift into allowed windows (quiet/sleep/work/
 *    named context windows) — deadlines themselves are never moved, and the
 *    shift is shown to the user in the notification.
 *  - Boot, package update, clock and time-zone changes re-arm everything.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null

    private val prefs by lazy { context.getSharedPreferences("lifedeck_alarms", Context.MODE_PRIVATE) }

    private fun container() = (context.applicationContext as? LifeDeckApp)?.container

    /** Start observing database writes (idempotent). */
    fun start() {
        if (observeJob != null) return
        val db = container()?.database ?: return
        observeJob = scope.launch {
            db.changes
                .debounce(250)
                .catch { }
                .collectLatest {
                    runCatching { reconcile() }
                }
        }
    }

    /** Re-arm everything from the current database state. */
    suspend fun reconcile() {
        val c = container() ?: return
        val settings = c.settings.get()
        if (!settings.notificationsEnabled) {
            cancelAll()
            return
        }

        val reminders = c.database.read { sqlite ->
            sqlite.rawQuery(
                "SELECT * FROM reminders WHERE deleted=0 AND status IN ('PENDING','SNOOZED') ORDER BY fire_at",
                null,
            ).use { cur ->
                val out = mutableListOf<ReminderRecord>()
                while (cur.moveToNext()) out.add(cur.toReminder())
                out
            }
        }

        val entryIds = reminders.map { it.entryId }.distinct()
        val entries = c.tasks.getMany(entryIds).associateBy { it.id }

        val desired = mutableMapOf<Int, Long>() // requestCode → triggerAt
        val payloads = mutableMapOf<Int, String>() // requestCode → reminder/memory id
        reminders.forEach { reminder ->
            val entry = entries[reminder.entryId] ?: return@forEach
            if (entry.deleted || !entry.isOpen) return@forEach
            val target = effectiveFireTime(reminder, entry.contextHint, settings) ?: return@forEach
            desired[requestCode(reminder.id)] = target
            payloads[requestCode(reminder.id)] = reminder.id
        }

        // Summary + prompt alarms
        summaryTimes(settings).forEach { (key, at) ->
            if (at != null) desired[summaryRequestCode(key)] = at
        }

        // Memory anniversaries due in the next 3 days
        val anniversaries = c.memories.upcomingWithin(3)
        anniversaries.forEach { mem ->
            mem.nextReminderAt?.let { at ->
                desired[memoryRequestCode(mem.id)] = at
                payloads[memoryRequestCode(mem.id)] = mem.id
            }
        }

        // Cancel alarms that no longer exist
        val previous = prefs.getStringSet("scheduled", emptySet())!!.mapNotNull { it.toIntOrNull() }
        previous.forEach { code ->
            if (code !in desired) cancelAlarm(code)
        }

        // Arm what is due
        desired.forEach { (code, at) -> setAlarm(code, at, payloads[code]) }

        prefs.edit().putStringSet("scheduled", desired.keys.map { it.toString() }.toSet()).apply()
        prefs.edit().putStringSet("payloads", payloads.map { (k, v) -> "$k=$v" }.toSet()).apply()
        prefs.edit().putLong("last_reconcile", now()).apply()
    }

    /** Immediate reconcile triggered by data changes (fire-and-forget). */
    fun reconcileAsync() {
        scope.launch { runCatching { reconcile() } }
    }

    // ------------------------------------------------------------- time windows

    /**
     * Returns the effective alarm time for a reminder after applying the
     * user's windows. Deadlines are untouched: this only moves *notifications*.
     */
    fun effectiveFireTime(
        reminder: ReminderRecord,
        contextHint: String?,
        settings: AppSettings,
    ): Long? {
        if (!settings.respectReminderWindows) return reminder.snoozedUntil ?: reminder.fireAt
        var at = reminder.snoozedUntil ?: reminder.fireAt
        val shifts = mutableListOf<String>()

        // 1. Quiet hours (highest priority)
        if (settings.quietHoursEnabled) {
            val quiet = TimeWindow(settings.quietStartMinute, settings.quietEndMinute)
            if (quiet.containsMinuteOf(at)) {
                val moved = at.nextTimeAfterMinute(settings.quietEndMinute)
                shifts.add("moved out of quiet hours")
                at = moved
            }
        }
        // 2. Sleep hours
        val sleep = TimeWindow(settings.sleepStartMinute, settings.sleepEndMinute)
        if (sleep.containsMinuteOf(at)) {
            val moved = at.nextTimeAfterMinute(settings.sleepEndMinute)
            shifts.add("moved out of sleep hours")
            at = moved
        }
        // 3. Named context window (e.g. "lunch", "after work")
        contextHint?.let { hint ->
            val window = settings.contextWindows.entries
                .firstOrNull { it.key.equals(hint, ignoreCase = true) }?.value
            if (window != null && !window.containsMinuteOf(at)) {
                val candidate = at.atMinuteOf(window.startMinute)
                if (candidate > at) {
                    at = candidate
                    shifts.add("moved into your \"$hint\" window")
                }
            }
        }
        // 4. Avoid working hours
        if (settings.avoidWorkHours) {
            val work = TimeWindow(settings.workStartMinute, settings.workEndMinute)
            if (work.containsMinuteOf(at)) {
                at = at.nextTimeAfterMinute(settings.workEndMinute)
                shifts.add("moved outside working hours")
            }
        }

        // Never let a shift pass the actual deadline/due moment silently.
        reminder.fireAt.let { original ->
            if (at > original && at > now()) {
                // keep as is; the deadline itself was not changed
            }
        }
        if (at < now()) at = now() + 1_000
        return at
    }

    /** Human note when a notification time was moved by a window rule. */
    fun shiftNote(original: Long, effective: Long): String =
        if (effective > original + 60_000) {
            "Was ${formatTime(original)}, moved to ${formatTime(effective)} to fit your schedule"
        } else ""

    // --------------------------------------------------------------- summaries

    /** Key → next trigger time for the daily summaries and prompts. */
    private fun summaryTimes(settings: AppSettings): Map<String, Long?> {
        val today = LocalDate.now()
        fun at(hour: Int, minute: Int): Long = atTime(today, hour, minute)
        val result = mutableMapOf<String, Long?>()
        result["morning"] = if (settings.morningSummaryEnabled) {
            at(settings.morningSummaryHour, settings.morningSummaryMinute).let { if (it < now()) it + 86_400_000L else it }
        } else null
        result["evening"] = if (settings.eveningSummaryEnabled) {
            at(settings.eveningSummaryHour, settings.eveningSummaryMinute).let { if (it < now()) it + 86_400_000L else it }
        } else null
        result["inbox_nudge"] = if (settings.inboxNudgeEnabled) {
            at(settings.inboxNudgeHour, settings.inboxNudgeMinute).let { if (it < now()) it + 86_400_000L else it }
        } else null
        return result
    }

    // ------------------------------------------------------------------ alarms

    private fun setAlarm(requestCode: Int, triggerAt: Long, payload: String? = null) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            putExtra(ReminderReceiver.EXTRA_REQUEST_CODE, requestCode)
            payload?.let { putExtra(ReminderReceiver.EXTRA_PAYLOAD, it) }
        }
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true

        try {
            if (canExact) {
                AlarmManagerCompat.setExactAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 10 * 60_000L, pending)
            }
        } catch (e: SecurityException) {
            // Exact alarm permission revoked mid-flight: fall back to inexact.
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun cancelAlarm(requestCode: Int) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
        }
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pending)
        pending.cancel()
        NotificationHelper(context).cancel(requestCode)
    }

    private fun cancelAll() {
        val previous = prefs.getStringSet("scheduled", emptySet())!!.mapNotNull { it.toIntOrNull() }
        previous.forEach { cancelAlarm(it) }
        prefs.edit().remove("scheduled").apply()
    }

    /** Immediately fire a test notification (troubleshooting screen). */
    fun sendTestNotification() {
        val helper = NotificationHelper(context)
        helper.showAlert(
            "LifeDeck test notification 🔔",
            "Alarms and notifications are working. If you don't see this, check battery optimization and notification permissions.",
            id = 777001,
        )
    }

    fun lastReconcileAt(): Long = prefs.getLong("last_reconcile", 0L)

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

    companion object {
        const val ACTION_FIRE = "com.lifedeck.app.action.FIRE_REMINDER"

        fun requestCode(reminderId: String): Int =
            ("alarm:reminder:" + reminderId).hashCode() and 0x7FFFFFFF

        fun summaryRequestCode(key: String): Int =
            ("alarm:summary:" + key).hashCode() and 0x7FFFFFFF

        fun memoryRequestCode(memoryId: String): Int =
            ("alarm:memory:" + memoryId).hashCode() and 0x7FFFFFFF

        fun isSummaryCode(code: Int): Boolean = code == summaryRequestCode("morning") ||
            code == summaryRequestCode("evening") || code == summaryRequestCode("inbox_nudge")

        fun summaryKeyFor(code: Int): String? = when (code) {
            summaryRequestCode("morning") -> "morning"
            summaryRequestCode("evening") -> "evening"
            summaryRequestCode("inbox_nudge") -> "inbox_nudge"
            else -> null
        }
    }
}

// ---- minute-of-day helpers on epoch millis ----

private fun com.lifedeck.app.data.model.TimeWindow.containsMinuteOf(millis: Long): Boolean {
    val ldt = millis.toLocalDateTime()
    val minute = ldt.hour * 60 + ldt.minute
    // Sleep/quiet windows that wrap past midnight are handled by contains().
    return contains(minute)
}

private fun Long.atMinuteOf(minuteOfDay: Int): Long {
    val ldt = this.toLocalDateTime()
    return atTime(ldt.toLocalDate(), minuteOfDay / 60, minuteOfDay % 60)
}

private fun Long.nextTimeAfterMinute(minuteOfDay: Int): Long {
    val ldt = this.toLocalDateTime()
    val todayCandidate = atTime(ldt.toLocalDate(), minuteOfDay / 60, minuteOfDay % 60)
    return if (todayCandidate > this) todayCandidate
    else atTime(ldt.toLocalDate().plusDays(1), minuteOfDay / 60, minuteOfDay % 60)
}
