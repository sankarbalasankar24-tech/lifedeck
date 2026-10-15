package com.lifedeck.app.domain

import com.lifedeck.app.data.model.Recurrence
import com.lifedeck.app.data.model.RecurrenceFreq
import com.lifedeck.app.util.toLocalDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Computes the next occurrence of a recurring entry after a completion.
 * Returns null when the series is finished (end date reached or occurrence
 * limit hit).
 */
object RecurrenceEngine {

    fun nextOccurrence(currentAt: Long, rule: Recurrence): Long? {
        if (!rule.isActive) return null
        val done = rule.occurrencesDone + 1
        rule.maxOccurrences?.let { if (done >= it) return null }

        val currentDate = currentAt.toLocalDate()
        val nextDate = nextDate(currentDate, rule) ?: return null
        val nextMillis = nextDate.atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        rule.endAt?.let { if (nextMillis > it) return null }
        return nextMillis
    }

    /** Copy of the rule with the occurrence counter advanced. */
    fun advanced(rule: Recurrence): Recurrence =
        rule.copy(occurrencesDone = rule.occurrencesDone + 1)

    private fun nextDate(current: LocalDate, rule: Recurrence): LocalDate? {
        val step = rule.interval.coerceAtLeast(1)
        return when (rule.freq) {
            RecurrenceFreq.NONE -> null
            RecurrenceFreq.DAILY -> current.plusDays(step.toLong())
            RecurrenceFreq.WEEKDAYS -> {
                var date = current.plusDays(1)
                while (date.dayOfWeek.value > 5) date = date.plusDays(1)
                date
            }
            RecurrenceFreq.WEEKLY -> current.plusWeeks(step.toLong())
            RecurrenceFreq.MONTHLY -> {
                val day = rule.dayOfMonth ?: current.dayOfMonth
                val candidate = current.plusMonths(step.toLong()).withDayOfMonth(
                    day.coerceAtMost(current.plusMonths(step.toLong()).lengthOfMonth())
                )
                candidate
            }
            RecurrenceFreq.YEARLY -> {
                val month = rule.monthOfYear ?: current.monthValue
                val day = rule.dayOfMonth ?: current.dayOfMonth
                var date = current.plusYears(step.toLong()).withMonth(month).withDayOfMonth(
                    day.coerceAtMost(current.plusYears(step.toLong()).withMonth(month).lengthOfMonth())
                )
                date
            }
            RecurrenceFreq.CUSTOM_DAYS -> {
                val days = rule.daysOfWeek.ifEmpty { (1..7).toList() }
                var probe = current.plusDays(1)
                var guard = 0
                while (probe.dayOfWeek.value !in days && guard < 400) {
                    probe = probe.plusDays(1)
                    guard++
                }
                if (guard >= 400) null else probe
            }
        }
    }

    /** Human label for a rule, e.g. "Every 2 weeks". */
    fun label(rule: Recurrence?): String {
        if (rule == null || !rule.isActive) return "Does not repeat"
        val n = rule.interval
        return when (rule.freq) {
            RecurrenceFreq.NONE -> "Does not repeat"
            RecurrenceFreq.DAILY -> if (n == 1) "Every day" else "Every $n days"
            RecurrenceFreq.WEEKDAYS -> "Every weekday"
            RecurrenceFreq.WEEKLY -> if (n == 1) "Every week" else "Every $n weeks"
            RecurrenceFreq.MONTHLY -> if (n == 1) "Every month" else "Every $n months"
            RecurrenceFreq.YEARLY -> if (n == 1) "Every year" else "Every $n years"
            RecurrenceFreq.CUSTOM_DAYS ->
                "Every " + rule.daysOfWeek.joinToString(", ") { shortDay(it) }
        }
    }

    fun shortDay(dayOfWeek: Int): String =
        java.time.DayOfWeek.of(dayOfWeek).name.lowercase()
            .take(3).replaceFirstChar { it.uppercase() }

    /** Days between two dates, used for milestone countdowns. */
    fun daysBetween(from: Long, to: Long): Long =
        ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate())
}
