package com.lifedeck.app.domain

import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.data.model.EntryType
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.data.model.Recurrence
import com.lifedeck.app.data.model.RecurrenceFreq
import com.lifedeck.app.data.model.ShoppingBucket
import com.lifedeck.app.util.atStartOfDayMillis
import com.lifedeck.app.util.atTime
import com.lifedeck.app.util.todayStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Turns one line of quick capture ("buy milk tomorrow at 6pm every week #home !high")
 * into a structured entry: type, date/time, priority, recurrence, category.
 *
 * Pure Kotlin with no Android dependencies so it is unit-testable.
 */
object CaptureParser {

    data class Parsed(
        val title: String,
        val type: EntryType,
        val dueAt: Long? = null,
        val scheduledAt: Long? = null,
        val priority: Priority = Priority.NORMAL,
        val recurrence: Recurrence? = null,
        val categoryHint: String? = null,
        val contextHint: String? = null,
        val bucket: ShoppingBucket? = null,
        val estimateMinutes: Int? = null,
        val keywords: List<String> = emptyList(),
    )

    private val shoppingWords = setOf(
        "buy", "buying", "purchase", "get", "grab", "order", "shop", "shopping",
        "pick up", "pick up", "need", "restock", "pay for", "collect",
    )
    private val errandWords = setOf(
        "drop off", "return", "collect", "deliver", "errand", "post", "mail",
        "renew", "book", "cancel", "call", "email", "message", "meet",
    )
    private val ideaWords = setOf(
        "idea", "maybe", "someday", "consider", "what if", "brainstorm", "remember that",
    )
    private val appointmentWords = setOf(
        "appointment", "meeting", "interview", "call with", "dentist", "doctor",
        "hospital", "visit", "coffee with", "lunch with", "dinner with", "flight",
        "class", "session", "webinar", "deadline",
    )
    private val goalWords = setOf(
        "goal", "aim", "resolve", "target", "dream", "plan to", "want to",
    )
    private val projectWords = setOf(
        "project", "renovate", "build", "install", "launch", "set up", "setup",
        "research", "plan the", "organize", "organise",
    )

    private val priorityWords = mapOf(
        Priority.URGENT to setOf("urgent", "asap", "critical", "emergency", "right now"),
        Priority.HIGH to setOf("important", "high priority", "must", "priority"),
        Priority.LOW to setOf("whenever", "low priority", "someday", "nice to have"),
    )

    /** Weekday names in order Mon..Sun. */
    private val weekdays = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    )

    fun parse(rawInput: String, now: Long = System.currentTimeMillis(), assumeType: EntryType? = null): Parsed {
        var text = rawInput.trim().replace(Regex("\\s+"), " ")
        val lower = text.lowercase(Locale.getDefault())

        // ---- extract hashtags (#home) and @contexts (@lunch) ----
        val tags = Regex("#(\\p{L}[\\p{L}\\d_-]*)").findAll(text).map { it.groupValues[1] }.toList()
        val contexts = Regex("@(\\p{L}[\\p{L}\\d_-]*)").findAll(text).map { it.groupValues[1] }.toList()
        text = text.replace(Regex("[#@]\\p{L}[\\p{L}\\d_-]*"), "").trim().replace(Regex("\\s+"), " ")

        // ---- priority ----
        var priority = Priority.NORMAL
        priorityWords.forEach { (p, words) ->
            if (words.any { lower.contains(it) }) priority = p
        }
        when {
            lower.startsWith("!!") || lower.contains(" !") || lower.endsWith("!") && !lower.contains("?") ->
                if (priority == Priority.NORMAL) priority = Priority.HIGH
        }
        if (lower.startsWith("!!!")) priority = Priority.URGENT

        // ---- recurrence ("every day", "every monday", "weekly", "monthly") ----
        val recurrence = parseRecurrence(lower)

        // ---- timing ----
        val (dueAt, scheduledAt, remainingLower) = parseWhen(text, now, recurrence)
        var titleText = remainingLower

        // ---- estimate ("for 30 min", "30m", "1h") ----
        val estimate = parseEstimate(titleText)
        estimate?.let { titleText = titleText.replace(it.second, "").trim() }

        // ---- type classification ----
        val type = assumeType ?: classify(lower, dueAt, scheduledAt, tags)

        // ---- bucket for shopping ----
        val bucket = when {
            type != EntryType.SHOPPING -> null
            lower.contains("this week") || lower.contains("soon") -> ShoppingBucket.WEEK
            lower.contains("later") || lower.contains("someday") || lower.contains("next month") -> ShoppingBucket.FUTURE
            dueAt == null && scheduledAt == null -> ShoppingBucket.WEEK
            else -> ShoppingBucket.NOW
        }

        val cleanTitle = titleText
            .replace(Regex("\\b(at|on|by|due|every|weekly|daily|monthly|yearly|tomorrow|today|tonight|yesterday)\\b\\s*", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { text.trim() }
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }

        return Parsed(
            title = cleanTitle,
            type = type,
            dueAt = dueAt,
            scheduledAt = scheduledAt,
            priority = priority,
            recurrence = recurrence,
            categoryHint = tags.firstOrNull(),
            contextHint = contexts.firstOrNull(),
            bucket = bucket,
            estimateMinutes = estimate?.first,
            keywords = tags + contexts,
        )
    }

    // ------------------------------------------------------------------ classify

    private fun classify(
        lower: String,
        dueAt: Long?,
        scheduledAt: Long?,
        tags: List<String>,
    ): EntryType {
        if (tags.any { it.equals("idea", true) }) return EntryType.IDEA
        if (tags.any { it.equals("buy", true) || it.equals("shopping", true) }) return EntryType.SHOPPING
        if (tags.any { it.equals("appt", true) || it.equals("appointment", true) }) return EntryType.APPOINTMENT
        if (tags.any { it.equals("goal", true) }) return EntryType.GOAL_STEP
        if (tags.any { it.equals("project", true) }) return EntryType.PROJECT_STEP

        val words = lower.split(Regex("[^\\p{L}\\p{N}]+"))
        val hasShopping = shoppingWords.any { lower.contains(it) } &&
            !lower.contains("appointment") && !lower.contains("meeting")
        val hasAppointment = appointmentWords.any { lower.contains(it) }
        val hasErrand = errandWords.any { lower.contains(it) }
        val hasIdea = ideaWords.any { lower.contains(it) }
        val hasProject = projectWords.any { lower.contains(it) }
        val hasGoal = goalWords.any { lower.contains(it) }

        return when {
            hasAppointment -> EntryType.APPOINTMENT
            hasShopping && (hasTime(dueAt) || words.any { shoppingWords.contains(it) }) -> EntryType.SHOPPING
            hasShopping -> EntryType.SHOPPING
            hasGoal -> EntryType.GOAL_STEP
            hasProject -> EntryType.PROJECT_STEP
            hasIdea && dueAt == null && scheduledAt == null -> EntryType.IDEA
            hasErrand -> EntryType.ERRAND
            else -> EntryType.TASK
        }
    }

    private fun hasTime(millis: Long?): Boolean = millis != null && millis % 86_400_000L != 0L

    // ------------------------------------------------------------------ parseWhen

    private fun parseWhen(text: String, now: Long, recurrence: Recurrence?): Triple<Long?, Long?, String> {
        var remaining = text
        val lower = remaining.lowercase(Locale.getDefault())
        val today = LocalDate.now()
        var date: LocalDate? = null
        var hour: Int? = null
        var minute: Int = 0

        // explicit time first: "at 6:30 pm", "18:00", "6pm"
        Regex("\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
            .find(remaining)?.let { m ->
                hour = m.groupValues[1].toInt()
                minute = m.groupValues[2].toIntOrNull() ?: 0
                when (m.groupValues[3].lowercase(Locale.getDefault())) {
                    "pm" -> if (hour!! < 12) hour = hour!! + 12
                    "am" -> if (hour!! == 12) hour = 0
                }
                if (hour!! < 24 && minute < 60) {
                    remaining = remaining.replaceRange(m.range, " ")
                } else hour = null
            }
        if (hour == null) {
            Regex("(\\d{1,2}):(\\d{2})\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(remaining)?.let { m ->
                val h = m.groupValues[1].toInt()
                val mi = m.groupValues[2].toInt()
                if (h in 0..23 && mi in 0..59) {
                    hour = when (m.groupValues[3].lowercase(Locale.getDefault())) {
                        "pm" -> if (h < 12) h + 12 else h
                        "am" -> if (h == 12) 0 else h
                        else -> h
                    }
                    minute = mi
                    remaining = remaining.replaceRange(m.range, " ")
                }
            }
        }

        // day expressions
        when {
            lower.contains("day after tomorrow") -> { date = today.plusDays(2); remaining = removePhrase(remaining, "day after tomorrow") }
            lower.contains("tomorrow") -> { date = today.plusDays(1); remaining = removePhrase(remaining, "tomorrow") }
            lower.contains("tonight") -> { date = today; if (hour == null) hour = 19; remaining = removePhrase(remaining, "tonight") }
            lower.contains("today") -> { date = today; remaining = removePhrase(remaining, "today") }
            lower.contains("yesterday") -> { date = today.minusDays(1); remaining = removePhrase(remaining, "yesterday") }
            lower.contains("next week") -> { date = today.plusWeeks(1); remaining = removePhrase(remaining, "next week") }
            lower.contains("next month") -> { date = today.plusMonths(1); remaining = removePhrase(remaining, "next month") }
            lower.contains("next year") -> { date = today.plusYears(1); remaining = removePhrase(remaining, "next year") }
            lower.contains("in ") && Regex("in\\s+\\d+\\s+(day|days|week|weeks|month|months)").containsMatchIn(lower) ->
                Regex("in\\s+(\\d+)\\s+(day|days|week|weeks|month|months)").find(lower)?.let { m ->
                    val n = m.groupValues[1].toInt()
                    date = when (m.groupValues[2].take(3)) {
                        "day" -> today.plusDays(n.toLong())
                        "wee" -> today.plusWeeks(n.toLong())
                        else -> today.plusMonths(n.toLong())
                    }
                    remaining = Regex("\\s*in\\s+\\d+\\s+" + m.groupValues[2], RegexOption.IGNORE_CASE).replace(remaining, " ")
                }
        }

        // weekday: "on friday", "monday"
        if (date == null) {
            weekdays.forEachIndexed { index, name ->
                if (date == null && Regex("\\b(on\\s+)?${name}\\b").containsMatchIn(remaining)) {
                    val target = DayOfWeek.of(index + 1)
                    var probe = today
                    var guard = 0
                    while (probe.dayOfWeek != target && guard < 8) { probe = probe.plusDays(1); guard++ }
                    // "next friday" style already handled; plain weekday = upcoming one
                    if (probe == today) probe = probe.plusWeeks(1)
                    date = probe
                    remaining = Regex("\\b(on\\s+)?${name}\\b", RegexOption.IGNORE_CASE).replace(remaining, " ")
                }
            }
        }

        // "3rd of june" / "june 3" / "2026-06-03"
        Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").find(remaining)?.let { m ->
            runCatching {
                date = LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
            remaining = remaining.replaceRange(m.range, " ")
        }

        // no date but time-only → today; no time → all-day
        var scheduled: Long? = null
        var due: Long? = null
        if (date != null) {
            val base = date!!.atStartOfDayMillis()
            scheduled = if (hour != null) atTime(date!!, hour!!, minute) else base
            due = scheduled
        } else if (hour != null) {
            scheduled = atTime(today, hour!!, minute)
            due = scheduled
        }

        return Triple(due, scheduled, remaining.trim())
    }

    private fun removePhrase(text: String, phrase: String): String =
        Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE).replace(text, " ").trim()

    // ------------------------------------------------------------------ helpers

    private fun parseRecurrence(lower: String): Recurrence? {
        fun rule(freq: RecurrenceFreq, interval: Int = 1, days: List<Int> = emptyList()) =
            Recurrence(freq = freq, interval = interval, daysOfWeek = days)

        Regex("every\\s+(\\d+)\\s+(day|days|week|weeks|month|months|year|years)").find(lower)?.let { m ->
            val n = m.groupValues[1].toInt()
            return when (m.groupValues[2].take(3)) {
                "day" -> rule(RecurrenceFreq.DAILY, n)
                "wee" -> rule(RecurrenceFreq.WEEKLY, n)
                "mon" -> rule(RecurrenceFreq.MONTHLY, n)
                else -> rule(RecurrenceFreq.YEARLY, n)
            }
        }
        if (Regex("\\bevery\\s+weekday").containsMatchIn(lower)) return rule(RecurrenceFreq.WEEKDAYS)
        weekdays.forEachIndexed { index, name ->
            if (Regex("\\bevery\\s+$name").containsMatchIn(lower)) return rule(RecurrenceFreq.WEEKLY, 1, listOf(index + 1))
        }
        when {
            lower.contains("every day") || lower.contains("daily") -> return rule(RecurrenceFreq.DAILY)
            lower.contains("every week") || lower.contains("weekly") -> return rule(RecurrenceFreq.WEEKLY)
            lower.contains("every month") || lower.contains("monthly") -> return rule(RecurrenceFreq.MONTHLY)
            lower.contains("every year") || lower.contains("yearly") || lower.contains("annually") -> return rule(RecurrenceFreq.YEARLY)
        }
        return null
    }

    private fun parseEstimate(text: String): Pair<Int, String>? {
        Regex("(\\d+)\\s*(m|min|mins|minutes)\\b", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            return m.groupValues[1].toInt() to m.value
        }
        Regex("(\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hour|hours)\\b", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val hours = m.groupValues[1].toDouble()
            return (hours * 60).toInt() to m.value
        }
        return null
    }

    /** Convert a parsed capture into an entry ready for the repository. */
    fun toEntry(parsed: Parsed, categoryId: String? = null): Entry {
        val status = if (parsed.dueAt == null && parsed.scheduledAt == null && parsed.type != EntryType.SHOPPING) {
            EntryStatus.INBOX
        } else if (parsed.type == EntryType.SHOPPING && parsed.dueAt == null) {
            EntryStatus.INBOX
        } else {
            EntryStatus.ACTIVE
        }
        return Entry(
            title = parsed.title,
            type = parsed.type,
            status = status,
            dueAt = parsed.dueAt,
            scheduledAt = parsed.scheduledAt,
            priority = parsed.priority,
            recurrence = parsed.recurrence,
            categoryId = categoryId,
            bucket = parsed.bucket,
            estimateMinutes = parsed.estimateMinutes,
            contextHint = parsed.contextHint,
            allDay = parsed.scheduledAt?.let { it % 86_400_000L == 0L } ?: true,
        )
    }
}
