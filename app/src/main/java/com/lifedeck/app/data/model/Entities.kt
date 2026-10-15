package com.lifedeck.app.data.model

import kotlinx.serialization.Serializable

/**
 * One universal capture row. Tasks, subtasks, shopping items, appointments,
 * ideas, project/goal steps and habits all live in this table so quick capture,
 * search and history behave identically for every kind of entry.
 */
@Serializable
data class Entry(
    val id: String = "",
    val type: EntryType = EntryType.TASK,
    val status: EntryStatus = EntryStatus.INBOX,
    val title: String,
    val notes: String = "",
    val categoryId: String? = null,
    val priority: Priority = Priority.NORMAL,
    /** Hard deadline (epoch millis). Never moved automatically. */
    val dueAt: Long? = null,
    /** Intended execution time (epoch millis); for all-day tasks this is day start. */
    val scheduledAt: Long? = null,
    val allDay: Boolean = true,
    /** Appointment window. */
    val startAt: Long? = null,
    val endAt: Long? = null,
    val location: String = "",
    val recurrence: Recurrence? = null,
    val estimateMinutes: Int? = null,
    val parentId: String? = null,
    val projectId: String? = null,
    val milestoneId: String? = null,
    val goalId: String? = null,
    val tripId: String? = null,
    val dependencyIds: List<String> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val orderIndex: Int = 0,
    /** Named context window this reminder should respect, e.g. "lunch" or "after duty". */
    val contextHint: String? = null,
    /** Heart/favourite: candidate for becoming a memory. */
    val favorite: Boolean = false,
    val photos: List<String> = emptyList(),
    // ---- shopping specifics ----
    val quantity: String? = null,
    val budget: Double? = null,
    val pricePaid: Double? = null,
    val store: String? = null,
    val purchasedAt: Long? = null,
    val bucket: ShoppingBucket? = null,
    // ---- lifecycle / sync ----
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val completedAt: Long? = null,
    val rescheduledCount: Int = 0,
    val version: Int = 1,
    val dirty: Boolean = true,
    val deleted: Boolean = false,
) {
    /** True when the entry still needs action from the user. */
    val isOpen: Boolean get() = status == EntryStatus.INBOX || status == EntryStatus.ACTIVE || status == EntryStatus.MISSED

    /** Checklist progress, or null when there is no checklist. */
    val checklistProgress: Pair<Int, Int>?
        get() = if (checklist.isEmpty()) null else checklist.count { it.done } to checklist.size

    val isShopping: Boolean get() = type == EntryType.SHOPPING
    val isAppointment: Boolean get() = type == EntryType.APPOINTMENT
    val isUnscheduled: Boolean get() = status == EntryStatus.INBOX || (dueAt == null && scheduledAt == null && startAt == null)
}

@Serializable
data class Goal(
    val id: String = "",
    val title: String,
    val description: String = "",
    val categoryId: String? = null,
    val status: LifeStatus = LifeStatus.ACTIVE,
    val priority: Priority = Priority.NORMAL,
    val startDate: Long? = null,
    val targetDate: Long? = null,
    val costEstimate: Double? = null,
    val costSpent: Double = 0.0,
    val icon: String = "",
    val favorite: Boolean = false,
    val orderIndex: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val completedAt: Long? = null,
    val version: Int = 1,
    val dirty: Boolean = true,
    val deleted: Boolean = false,
)

@Serializable
data class Project(
    val id: String = "",
    val goalId: String? = null,
    val title: String,
    val notes: String = "",
    val status: LifeStatus = LifeStatus.ACTIVE,
    val priority: Priority = Priority.NORMAL,
    val startDate: Long? = null,
    val dueDate: Long? = null,
    val costEstimate: Double? = null,
    val orderIndex: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val completedAt: Long? = null,
    val version: Int = 1,
    val dirty: Boolean = true,
    val deleted: Boolean = false,
)

@Serializable
data class Milestone(
    val id: String = "",
    val projectId: String,
    val title: String,
    val notes: String = "",
    val status: LifeStatus = LifeStatus.ACTIVE,
    val targetDate: Long? = null,
    val costEstimate: Double? = null,
    val orderIndex: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val completedAt: Long? = null,
    val version: Int = 1,
    val dirty: Boolean = true,
    val deleted: Boolean = false,
)

/** Groups shopping items into one trip/errand. */
@Serializable
data class ShoppingTrip(
    val id: String = "",
    val title: String,
    val store: String = "",
    val plannedAt: Long? = null,
    val status: LifeStatus = LifeStatus.ACTIVE,
    val budget: Double? = null,
    val notes: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val completedAt: Long? = null,
    val version: Int = 1,
    val dirty: Boolean = true,
    val deleted: Boolean = false,
)

@Serializable
data class Category(
    val id: String = "",
    val name: String,
    val color: Long = 0xFF3D5AFE,
    val icon: String = "label",
    val orderIndex: Int = 0,
    val builtIn: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val version: Int = 1,
    val dirty: Boolean = true,
    val deleted: Boolean = false,
)

@Serializable
data class ReminderRecord(
    val id: String = "",
    val entryId: String,
    /** When the next alarm should fire (epoch millis). */
    val fireAt: Long,
    /** Extra advance warning minutes before the target moment. */
    val leadMinutes: Int = 0,
    val repeat: Recurrence? = null,
    /** Escalation: re-alert every N minutes while the entry stays open. */
    val escalationIntervalMinutes: Int? = null,
    val escalationMaxCount: Int = 0,
    val escalationFired: Int = 0,
    /** AUTO reminders follow the entry's timing; MANUAL ones the user placed stay put. */
    val origin: ReminderOrigin = ReminderOrigin.AUTO,
    val soundEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val status: ReminderStatus = ReminderStatus.PENDING,
    val snoozedUntil: Long? = null,
    val lastFiredAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val version: Int = 1,
    val dirty: Boolean = true,
    val deleted: Boolean = false,
)

/** Append-only personal journal of everything that ever happened. */
@Serializable
data class HistoryEvent(
    val id: String = "",
    val entityType: EntityKind,
    val entityId: String,
    val action: HistoryAction,
    val title: String,
    val summary: String = "",
    val occurredAt: Long,
    val details: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val version: Int = 1,
    val dirty: Boolean = true,
    val deleted: Boolean = false,
)

@Serializable
data class Memory(
    val id: String = "",
    val entryId: String? = null,
    val goalId: String? = null,
    val milestoneId: String? = null,
    val title: String,
    val description: String = "",
    val category: String = "",
    /** When the memorable thing happened. */
    val occurredAt: Long,
    val photos: List<String> = emptyList(),
    /** Anniversary reminder cadence. */
    val remindInSixMonths: Boolean = false,
    val remindYearly: Boolean = true,
    val nextReminderAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val version: Int = 1,
    val dirty: Boolean = true,
    val deleted: Boolean = false,
)

data class SyncQueueItem(
    val id: String = "",
    val entityType: EntityKind,
    val entityId: String,
    val op: SyncOp,
    val enqueuedAt: Long,
    val attempts: Int = 0,
    val lastError: String = "",
    val state: String = "PENDING", // PENDING | IN_FLIGHT | FAILED
)

data class BackupRecord(
    val id: String = "",
    val createdAt: Long,
    val kind: String, // MANUAL | DAILY | WEEKLY | MONTHLY | SNAPSHOT
    val fileName: String,
    val sizeBytes: Long,
    val rowCount: Int,
    val note: String = "",
)

/** Derived roll-up progress for goals/projects/milestones. */
data class Progress(
    val entityId: String,
    val total: Int,
    val done: Int,
    val overdue: Int = 0,
    val milestonesTotal: Int = 0,
    val milestonesDone: Int = 0,
) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    val percent: Int get() = (fraction * 100).toInt()
    val isComplete: Boolean get() = total > 0 && done >= total
}

/** A row returned by global search across entity types. */
data class SearchHit(
    val kind: EntityKind,
    val id: String = "",
    val title: String,
    val subtitle: String,
    val occurredAt: Long,
    val status: String,
)

/** Aggregates for the morning dashboard. */
data class DaySummary(
    val total: Int = 0,
    val done: Int = 0,
    val overdue: Int = 0,
    val upcoming: Int = 0,
    val appointments: Int = 0,
    val shopping: Int = 0,
    val inboxCount: Int = 0,
    val dueToday: List<Entry> = emptyList(),
    val overdueList: List<Entry> = emptyList(),
    val appointmentList: List<Entry> = emptyList(),
    val shoppingList: List<Entry> = emptyList(),
    val topPriorities: List<Entry> = emptyList(),
)
