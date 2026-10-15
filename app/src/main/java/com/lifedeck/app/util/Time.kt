package com.lifedeck.app.util

import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** All timestamps in the app are epoch milliseconds in the device time zone. */
fun now(): Long = System.currentTimeMillis()

fun zone(): ZoneId = ZoneId.systemDefault()

fun LocalDate.atStartOfDayMillis(): Long = atStartOfDay(zone()).toInstant().toEpochMilli()

fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone()).toLocalDate()

fun Long.toLocalDateTime(): LocalDateTime =
    Instant.ofEpochMilli(this).atZone(zone()).toLocalDateTime()

fun startOfDay(millis: Long?): Long? = millis?.toLocalDate()?.atStartOfDayMillis()

fun endOfDay(millis: Long?): Long? =
    millis?.toLocalDate()?.plusDays(1)?.atStartOfDayMillis()?.minus(1)

fun atTime(date: LocalDate, hour: Int, minute: Int): Long =
    date.atTime(hour, minute).atZone(zone()).toInstant().toEpochMilli()

fun atTime(millis: Long, hour: Int, minute: Int): Long =
    atTime(millis.toLocalDate(), hour, minute)

fun nowAt(hour: Int, minute: Int): Long = atTime(LocalDate.now(), hour, minute)

/** Today's 00:00. */
fun todayStart(): Long = LocalDate.now().atStartOfDayMillis()

fun isSameDay(a: Long?, b: Long?): Boolean {
    if (a == null || b == null) return false
    return a.toLocalDate() == b.toLocalDate()
}

fun isToday(millis: Long?): Boolean = isSameDay(millis, now())

fun isPast(millis: Long?): Boolean = millis != null && millis < now()

fun isOverdue(dueAt: Long?): Boolean {
    if (dueAt == null) return false
    return dueAt < now() && !isToday(dueAt)
}

fun weekStart(date: LocalDate = LocalDate.now()): LocalDate =
    date.minusDays((date.dayOfWeek.value - 1).toLong())

fun minutesOfDay(hour: Int, minute: Int): Int = hour * 60 + minute

fun hourOf(minuteOfDay: Int): Int = minuteOfDay / 60

fun minuteOf(minuteOfDay: Int): Int = minuteOfDay % 60

// ---- formatting ----

private val timeFmt = DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
private val dateFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
private val fullDateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())

fun formatTime(millis: Long?): String {
    if (millis == null) return "—"
    return timeFmt.format(millis.toLocalDateTime().toLocalTime())
}

fun formatDate(millis: Long?): String {
    if (millis == null) return "—"
    return dateFmt.format(millis.toLocalDate())
}

fun formatFullDate(millis: Long?): String {
    if (millis == null) return "—"
    return fullDateFmt.format(millis.toLocalDate())
}

fun formatDateTime(millis: Long?): String =
    if (millis == null) "—" else "${formatDate(millis)}, ${formatTime(millis)}"

/** Compact chip label: "Overdue 2d", "Today 17:00", "Fri 17:00", "12 Mar". */
fun dueLabel(millis: Long?): String {
    if (millis == null) return "No date"
    val today = LocalDate.now()
    val date = millis.toLocalDate()
    return when {
        date.isBefore(today) -> {
            val days = java.time.temporal.ChronoUnit.DAYS.between(date, today)
            if (days <= 1L) "Overdue" else "Overdue ${days}d"
        }
        date == today -> if (millis.toLocalDateTime().toLocalTime()
                .toSecondOfDay() > 0
        ) "Today ${formatTime(millis)}" else "Today"
        date == today.plusDays(1) -> "Tomorrow"
        date.isBefore(today.plusWeeks(1)) -> "${date.dayOfWeek.name.lowercase().take(3).replaceFirstChar { it.uppercase() }} ${formatTime(millis)}"
        else -> formatDate(millis)
    }
}

/** Friendly duration, e.g. "45m", "2h 30m". */
fun formatDuration(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

/** Date shown on a timeline heading, e.g. "Today", "Yesterday", "12 Mar 2025". */
fun dayHeading(millis: Long): String {
    val date = millis.toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> {
            if (date.year == today.year) formatDate(millis) else formatFullDate(millis)
        }
    }
}

/** Consistent timestamp formatter for exports (ISO-8601 local). */
fun isoOf(millis: Long): String =
    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(millis.toLocalDateTime())

fun parseIso(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return try {
        LocalDateTime.parse(text).atZone(zone()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(text)?.time
        } catch (e2: Exception) {
            null
        }
    }
}

/** Short weekday name, e.g. "Mon". */
fun weekdayShort(dayOfWeek: Int): String =
    DayOfWeek.of(dayOfWeek).name.lowercase().take(3).replaceFirstChar { it.uppercase() }

fun dateLabelFor(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(dateFmt)
    }
}

fun millisOf(date: LocalDate): Long = date.atStartOfDayMillis()

fun Long?.asMillis(): Long = this ?: 0L

fun formatClock(millis: Long): String = timeFmt.format(millis.toLocalDateTime().toLocalTime())
