package com.lifedeck.app.notify

import android.content.Context
import com.lifedeck.app.LifeDeckApp
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.util.dayHeading
import com.lifedeck.app.util.dueLabel
import com.lifedeck.app.util.formatDate
import com.lifedeck.app.util.now
import com.lifedeck.app.util.toLocalDate

/**
 * Morning summary, evening review and inbox prompt notifications.
 * Content is computed live from the local database — no network needed.
 */
class SummaryNotifier(private val context: Context) {

    suspend fun fire(key: String) {
        val app = context.applicationContext as? LifeDeckApp ?: return
        val container = app.container
        val settings = container.settings.get()
        if (!settings.notificationsEnabled) return
        val helper = NotificationHelper(context)

        when (key) {
            "morning" -> if (settings.morningSummaryEnabled) helper.showSummary(
                title = greeting() + " ☀️",
                text = morningLine(container),
                bigText = morningBody(container),
                id = NotificationHelper.summaryId("morning"),
            )
            "evening" -> if (settings.eveningSummaryEnabled) helper.showSummary(
                title = "Evening review 🌙",
                text = eveningLine(container),
                bigText = eveningBody(container),
                id = NotificationHelper.summaryId("evening"),
            )
            "inbox_nudge" -> if (settings.inboxNudgeEnabled) {
                val count = container.tasks.pendingInboxCount()
                if (count > 0) {
                    helper.showAlert(
                        title = "$count unscheduled item${if (count == 1) "" else "s"} in your inbox",
                        text = "Take two minutes to give each one a date, priority or category.",
                        id = NotificationHelper.summaryId("inbox_nudge"),
                        screen = "inbox",
                    )
                }
            }
        }
    }

    private fun greeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour < 5 -> "Still up"
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            hour < 21 -> "Good evening"
            else -> "Good night"
        }
    }

    private suspend fun morningLine(c: com.lifedeck.app.data.AppContainer): String {
        val summary = buildMorning(c)
        return "${summary.done} done · ${summary.total - summary.done} to go · ${summary.overdue} overdue"
    }

    private suspend fun morningBody(c: com.lifedeck.app.data.AppContainer): String {
        val summary = buildMorning(c)
        val sb = StringBuilder()
        sb.append("You have ${summary.total} item${if (summary.total == 1) "" else "s"} planned for ")
            .append(dayHeading(now())).append(".\n")
        if (summary.overdue > 0) sb.append("⚠ ${summary.overdue} overdue need${if (summary.overdue == 1) "" else "s"} attention\n")
        summary.topPriorities.take(3).forEach { sb.append("• ").append(shortLine(it)).append('\n') }
        if (summary.appointmentList.isNotEmpty()) {
            sb.append("🗓 ").append(summary.appointmentList.size).append(" appointment(s)\n")
        }
        if (summary.inboxCount > 0) sb.append("📥 ${summary.inboxCount} unscheduled items waiting\n")
        return sb.toString().trim()
    }

    private suspend fun eveningLine(c: com.lifedeck.app.data.AppContainer): String {
        val stats = buildEvening(c)
        return "${stats.completed} completed · ${stats.missed} missed · ${stats.remaining} unfinished"
    }

    private suspend fun eveningBody(c: com.lifedeck.app.data.AppContainer): String {
        val stats = buildEvening(c)
        val sb = StringBuilder()
        sb.append("✅ ${stats.completed} completed today\n")
        if (stats.missed > 0) sb.append("⏰ ${stats.missed} missed (reschedule?)\n")
        if (stats.remaining > 0) sb.append("🔄 ${stats.remaining} still open — they stay on your list\n")
        if (stats.unscheduled > 0) sb.append("📥 ${stats.unscheduled} never scheduled\n")
        stats.open.take(3).forEach { sb.append("• ").append(shortLine(it)).append('\n') }
        if (stats.completed >= stats.total && stats.total > 0) sb.append("\nEverything planned is done. Well played 🎉")
        return sb.toString().trim()
    }

    data class EveningStats(
        val total: Int, val completed: Int, val missed: Int,
        val remaining: Int, val unscheduled: Int, val open: List<Entry>,
    )

    private suspend fun buildMorning(c: com.lifedeck.app.data.AppContainer): com.lifedeck.app.data.model.DaySummary {
        val todayStart = com.lifedeck.app.util.todayStart()
        val todayEnd = todayStart + 86_400_000L - 1
        val open = c.tasks.missed() + c.tasks.overdue()
        val inboxCount = c.tasks.pendingInboxCount()
        val doneToday = c.database.read { sqlite ->
            sqlite.rawQuery(
                "SELECT COUNT(*) FROM entries WHERE deleted=0 AND status='DONE' AND completed_at>=?",
                arrayOf(todayStart.toString()),
            ).use { cur -> if (cur.moveToFirst()) cur.getInt(0) else 0 }
        }
        val dueToday = dueToday(c, todayStart, todayEnd)
        val overdue = c.tasks.overdue()
        val appointments = dueToday.filter { it.isAppointment }
        val shopping = dueToday.filter { it.isShopping }
        val top = dueToday.filter { it.priority == com.lifedeck.app.data.model.Priority.URGENT || it.priority == com.lifedeck.app.data.model.Priority.HIGH }
            .sortedBy { it.priority.ordinal }.take(3)
        return com.lifedeck.app.data.model.DaySummary(
            total = dueToday.size, done = doneToday, overdue = overdue.size,
            appointments = appointments.size, shopping = shopping.size,
            inboxCount = inboxCount, dueToday = dueToday, overdueList = overdue,
            appointmentList = appointments, shoppingList = shopping,
            topPriorities = if (top.isNotEmpty()) top else dueToday.take(3),
        )
    }

    private suspend fun dueToday(c: com.lifedeck.app.data.AppContainer, from: Long, to: Long): List<Entry> {
        val open = c.database.read { sqlite ->
            sqlite.rawQuery(
                """SELECT * FROM entries WHERE deleted=0 AND status IN ('ACTIVE','MISSED')
                   AND (COALESCE(due_at, scheduled_at, start_at) IS NOT NULL)
                   AND COALESCE(due_at, scheduled_at, start_at) BETWEEN ? AND ?
                   ORDER BY priority, COALESCE(due_at, scheduled_at, start_at)""",
                arrayOf(from.toString(), to.toString()),
            ).use { cur ->
                val out = mutableListOf<Entry>()
                while (cur.moveToNext()) out.add(cur.toEntry())
                out
            }
        }
        return open
    }

    private suspend fun buildEvening(c: com.lifedeck.app.data.AppContainer): EveningStats {
        val todayStart = com.lifedeck.app.util.todayStart()
        val todayEnd = todayStart + 86_400_000L
        val stats = c.database.read { sqlite ->
            fun count(sql: String, args: Array<String>) = sqlite.rawQuery(sql, args)
                .use { cur -> if (cur.moveToFirst()) cur.getInt(0) else 0 }
            val completed = count(
                "SELECT COUNT(*) FROM entries WHERE deleted=0 AND status='DONE' AND completed_at>=? AND completed_at<?",
                arrayOf(todayStart.toString(), todayEnd.toString()),
            )
            val missed = count(
                """SELECT COUNT(*) FROM entries WHERE deleted=0 AND status IN ('ACTIVE','MISSED')
                   AND COALESCE(due_at, scheduled_at, start_at) IS NOT NULL
                   AND COALESCE(due_at, scheduled_at, start_at) < ?""",
                arrayOf(todayStart.toString()),
            )
            val remaining = count(
                """SELECT COUNT(*) FROM entries WHERE deleted=0 AND status IN ('ACTIVE','MISSED')
                   AND COALESCE(due_at, scheduled_at, start_at) BETWEEN ? AND ?""",
                arrayOf(todayStart.toString(), todayEnd.toString()),
            )
            val unscheduled = count("SELECT COUNT(*) FROM entries WHERE deleted=0 AND status='INBOX'", emptyArray())
            Triple(completed to missed, remaining, unscheduled)
        }
        val open = dueToday(c, todayStart, todayEnd - 1)
        return EveningStats(
            total = stats.first.first + stats.second,
            completed = stats.first.first,
            missed = stats.first.second,
            remaining = stats.second,
            unscheduled = stats.third,
            open = open,
        )
    }

    private fun shortLine(e: Entry): String {
        val when_ = when {
            e.startAt != null -> "${formatDate(e.startAt)}"
            e.scheduledAt != null -> dueLabel(e.scheduledAt)
            e.dueAt != null -> dueLabel(e.dueAt)
            else -> "no date"
        }
        return "${e.title} · $when_"
    }
}
