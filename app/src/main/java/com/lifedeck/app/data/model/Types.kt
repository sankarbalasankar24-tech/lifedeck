package com.lifedeck.app.data.model

import kotlinx.serialization.Serializable

/** What kind of captured entry this is. Drives which screen renders it. */
@Serializable
enum class EntryType {
    TASK, SHOPPING, APPOINTMENT, IDEA, ERRAND, PROJECT_STEP, GOAL_STEP, HABIT, MEMORY_EVENT
}

/** INBOX = unscheduled capture awaiting review. MISSED = was due and never completed. */
@Serializable
enum class EntryStatus { INBOX, ACTIVE, DONE, SKIPPED, MISSED, ARCHIVED }

/** Priority rank: ordinal 0 is most urgent. */
@Serializable
enum class Priority { URGENT, HIGH, NORMAL, LOW }

/** Shopping urgency buckets: immediate necessities, this week, later. */
@Serializable
enum class ShoppingBucket { NOW, WEEK, FUTURE }

/** Lifecycle of a goal / project / milestone. */
@Serializable
enum class LifeStatus { ACTIVE, PAUSED, DONE, ARCHIVED }

/** How often something repeats. */
@Serializable
enum class RecurrenceFreq { NONE, DAILY, WEEKDAYS, WEEKLY, MONTHLY, YEARLY, CUSTOM_DAYS }

/** State of a single scheduled reminder alarm. */
@Serializable
enum class ReminderStatus { PENDING, FIRED, SNOOZED, DONE, CANCELLED }

/** History ledger actions (audit trail + rescheduling history). */
@Serializable
enum class HistoryAction {
    CREATED, EDITED, COMPLETED, UNCOMPLETED, SKIPPED, RESCHEDULED, DELETED, RESTORED,
    PURCHASED, UNPURCHASED, MILESTONE_DONE, PROJECT_DONE, GOAL_DONE, MEMORY_CREATED,
    REMINDER_FIRED, OPENED, IMPORTED, EXPORTED, SYNCED, BACKUP
}

/** What kind of entity a history row or sync row refers to. */
@Serializable
enum class EntityKind { ENTRY, GOAL, PROJECT, MILESTONE, TRIP, CATEGORY, REMINDER, MEMORY, HISTORY, SETTINGS }

/** Sync queue operation. */
@Serializable
enum class SyncOp { UPSERT, DELETE }

/** Whether a reminder is derived from entry timing or hand-placed. */
@Serializable
enum class ReminderOrigin { AUTO, MANUAL }

@Serializable
data class Recurrence(
    val freq: RecurrenceFreq = RecurrenceFreq.NONE,
    /** Every N units (e.g. every 2 weeks). */
    val interval: Int = 1,
    /** Day-of-month for MONTHLY (1..31). */
    val dayOfMonth: Int? = null,
    /** Month-of-year for YEARLY (1..12). */
    val monthOfYear: Int? = null,
    /** java.time.DayOfWeek values 1..7 for CUSTOM_DAYS. */
    val daysOfWeek: List<Int> = emptyList(),
    val endAt: Long? = null,
    val maxOccurrences: Int? = null,
    val occurrencesDone: Int = 0,
) {
    val isActive: Boolean get() = freq != RecurrenceFreq.NONE
}

@Serializable
data class ChecklistItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
)

/** Non-notification preference attached to an entry (displayed as chips). */
@Serializable
data class EntryMeta(
    val contextHint: String? = null,
    val source: String = "manual",
)

/** A time window in minutes-of-day, used for work/lunch/sleep/quiet hours. */
@Serializable
data class TimeWindow(val startMinute: Int, val endMinute: Int) {
    fun contains(minuteOfDay: Int): Boolean =
        if (startMinute <= endMinute) minuteOfDay in startMinute until endMinute
        else minuteOfDay >= startMinute || minuteOfDay < endMinute
}
