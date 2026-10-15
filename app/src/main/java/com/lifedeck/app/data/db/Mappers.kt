package com.lifedeck.app.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lifedeck.app.data.model.BackupRecord
import com.lifedeck.app.data.model.Category
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.data.model.EntryType
import com.lifedeck.app.data.model.Goal
import com.lifedeck.app.data.model.HistoryAction
import com.lifedeck.app.data.model.HistoryEvent
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.LifeStatus
import com.lifedeck.app.data.model.Memory
import com.lifedeck.app.data.model.Milestone
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.data.model.Project
import com.lifedeck.app.data.model.Recurrence
import com.lifedeck.app.data.model.ReminderOrigin
import com.lifedeck.app.data.model.ReminderRecord
import com.lifedeck.app.data.model.ReminderStatus
import com.lifedeck.app.data.model.ShoppingBucket
import com.lifedeck.app.data.model.ShoppingTrip
import com.lifedeck.app.data.model.SyncOp
import com.lifedeck.app.data.model.SyncQueueItem
import com.lifedeck.app.data.model.ChecklistItem
import com.lifedeck.app.util.decode
import com.lifedeck.app.util.decodeList
import com.lifedeck.app.util.encode

// ---- cursor helpers ----

fun Cursor.s(name: String): String? {
    val idx = getColumnIndex(name)
    return if (idx < 0 || isNull(idx)) null else getString(idx)
}

fun Cursor.sOr(name: String, def: String = ""): String = s(name) ?: def

fun Cursor.i(name: String, def: Int = 0): Int {
    val idx = getColumnIndex(name)
    return if (idx < 0 || isNull(idx)) def else getInt(idx)
}

fun Cursor.lOrNull(name: String): Long? {
    val idx = getColumnIndex(name)
    return if (idx < 0 || isNull(idx)) null else getLong(idx)
}

fun Cursor.lOr(name: String, def: Long = 0L): Long = lOrNull(name) ?: def

fun Cursor.dOrNull(name: String): Double? {
    val idx = getColumnIndex(name)
    return if (idx < 0 || isNull(idx)) null else getDouble(idx)
}

fun Cursor.b(name: String): Boolean = i(name) != 0

private fun <T> enumOrNull(values: Array<T>, raw: String?): T? where T : Enum<T> =
    values.firstOrNull { it.name == raw }

// ---- entry ----

fun Cursor.toEntry(): Entry = Entry(
    id = sOr("id"),
    type = enumOrNull(EntryType.values(), s("type")) ?: EntryType.TASK,
    status = enumOrNull(EntryStatus.values(), s("status")) ?: EntryStatus.INBOX,
    title = sOr("title"),
    notes = sOr("notes"),
    categoryId = s("category_id"),
    priority = enumOrNull(Priority.values(), s("priority")) ?: Priority.NORMAL,
    dueAt = lOrNull("due_at"),
    scheduledAt = lOrNull("scheduled_at"),
    allDay = b("all_day"),
    startAt = lOrNull("start_at"),
    endAt = lOrNull("end_at"),
    location = sOr("location"),
    recurrence = decode<Recurrence>(s("recurrence")),
    estimateMinutes = lOrNull("estimate_minutes")?.toInt(),
    parentId = s("parent_id"),
    projectId = s("project_id"),
    milestoneId = s("milestone_id"),
    goalId = s("goal_id"),
    tripId = s("trip_id"),
    dependencyIds = decodeList<String>(s("dependency_ids")),
    checklist = decodeList<ChecklistItem>(s("checklist")),
    orderIndex = i("order_index"),
    contextHint = s("context_hint"),
    favorite = b("favorite"),
    photos = decodeList<String>(s("photos")),
    quantity = s("quantity"),
    budget = dOrNull("budget"),
    pricePaid = dOrNull("price_paid"),
    store = s("store"),
    purchasedAt = lOrNull("purchased_at"),
    bucket = enumOrNull(ShoppingBucket.values(), s("bucket")),
    createdAt = lOr("created_at"),
    updatedAt = lOr("updated_at"),
    completedAt = lOrNull("completed_at"),
    rescheduledCount = i("rescheduled_count"),
    version = i("version", 1),
    dirty = b("dirty"),
    deleted = b("deleted"),
)

fun Entry.toValues(): ContentValues = ContentValues().apply {
    put("id", id)
    put("type", type.name)
    put("status", status.name)
    put("title", title)
    put("notes", notes)
    put("category_id", categoryId)
    put("priority", priority.name)
    putNullable("due_at", dueAt)
    putNullable("scheduled_at", scheduledAt)
    put("all_day", if (allDay) 1 else 0)
    putNullable("start_at", startAt)
    putNullable("end_at", endAt)
    put("location", location)
    put("recurrence", recurrence?.let { encode(it) })
    putNullable("estimate_minutes", estimateMinutes?.toLong())
    put("parent_id", parentId)
    put("project_id", projectId)
    put("milestone_id", milestoneId)
    put("goal_id", goalId)
    put("trip_id", tripId)
    put("dependency_ids", encode(dependencyIds))
    put("checklist", encode(checklist))
    put("order_index", orderIndex)
    putNullable("context_hint", contextHint)
    put("favorite", if (favorite) 1 else 0)
    put("photos", encode(photos))
    put("quantity", quantity)
    putNullable("budget", budget)
    putNullable("price_paid", pricePaid)
    put("store", store)
    putNullable("purchased_at", purchasedAt)
    put("bucket", bucket?.name)
    put("created_at", createdAt)
    put("updated_at", updatedAt)
    putNullable("completed_at", completedAt)
    put("rescheduled_count", rescheduledCount)
    put("version", version)
    put("dirty", if (dirty) 1 else 0)
    put("deleted", if (deleted) 1 else 0)
}

// ---- structure ----

fun Cursor.toGoal(): Goal = Goal(
    id = sOr("id"),
    title = sOr("title"),
    description = sOr("description"),
    categoryId = s("category_id"),
    status = enumOrNull(LifeStatus.values(), s("status")) ?: LifeStatus.ACTIVE,
    priority = enumOrNull(Priority.values(), s("priority")) ?: Priority.NORMAL,
    startDate = lOrNull("start_date"),
    targetDate = lOrNull("target_date"),
    costEstimate = dOrNull("cost_estimate"),
    costSpent = dOrNull("cost_spent") ?: 0.0,
    icon = sOr("icon"),
    favorite = b("favorite"),
    orderIndex = i("order_index"),
    createdAt = lOr("created_at"),
    updatedAt = lOr("updated_at"),
    completedAt = lOrNull("completed_at"),
    version = i("version", 1),
    dirty = b("dirty"),
    deleted = b("deleted"),
)

fun Goal.toValues(): ContentValues = ContentValues().apply {
    put("id", id); put("title", title); put("description", description)
    put("category_id", categoryId); put("status", status.name); put("priority", priority.name)
    putNullable("start_date", startDate); putNullable("target_date", targetDate)
    putNullable("cost_estimate", costEstimate); put("cost_spent", costSpent)
    put("icon", icon); put("favorite", if (favorite) 1 else 0); put("order_index", orderIndex)
    put("created_at", createdAt); put("updated_at", updatedAt)
    putNullable("completed_at", completedAt)
    put("version", version); put("dirty", if (dirty) 1 else 0); put("deleted", if (deleted) 1 else 0)
}

fun Cursor.toProject(): Project = Project(
    id = sOr("id"),
    goalId = s("goal_id"),
    title = sOr("title"),
    notes = sOr("notes"),
    status = enumOrNull(LifeStatus.values(), s("status")) ?: LifeStatus.ACTIVE,
    priority = enumOrNull(Priority.values(), s("priority")) ?: Priority.NORMAL,
    startDate = lOrNull("start_date"),
    dueDate = lOrNull("due_date"),
    costEstimate = dOrNull("cost_estimate"),
    orderIndex = i("order_index"),
    createdAt = lOr("created_at"),
    updatedAt = lOr("updated_at"),
    completedAt = lOrNull("completed_at"),
    version = i("version", 1),
    dirty = b("dirty"),
    deleted = b("deleted"),
)

fun Project.toValues(): ContentValues = ContentValues().apply {
    put("id", id); putNullable("goal_id", goalId); put("title", title); put("notes", notes)
    put("status", status.name); put("priority", priority.name)
    putNullable("start_date", startDate); putNullable("due_date", dueDate)
    putNullable("cost_estimate", costEstimate); put("order_index", orderIndex)
    put("created_at", createdAt); put("updated_at", updatedAt)
    putNullable("completed_at", completedAt)
    put("version", version); put("dirty", if (dirty) 1 else 0); put("deleted", if (deleted) 1 else 0)
}

fun Cursor.toMilestone(): Milestone = Milestone(
    id = sOr("id"),
    projectId = sOr("project_id"),
    title = sOr("title"),
    notes = sOr("notes"),
    status = enumOrNull(LifeStatus.values(), s("status")) ?: LifeStatus.ACTIVE,
    targetDate = lOrNull("target_date"),
    costEstimate = dOrNull("cost_estimate"),
    orderIndex = i("order_index"),
    createdAt = lOr("created_at"),
    updatedAt = lOr("updated_at"),
    completedAt = lOrNull("completed_at"),
    version = i("version", 1),
    dirty = b("dirty"),
    deleted = b("deleted"),
)

fun Milestone.toValues(): ContentValues = ContentValues().apply {
    put("id", id); put("project_id", projectId); put("title", title); put("notes", notes)
    put("status", status.name); putNullable("target_date", targetDate)
    putNullable("cost_estimate", costEstimate); put("order_index", orderIndex)
    put("created_at", createdAt); put("updated_at", updatedAt)
    putNullable("completed_at", completedAt)
    put("version", version); put("dirty", if (dirty) 1 else 0); put("deleted", if (deleted) 1 else 0)
}

fun Cursor.toTrip(): ShoppingTrip = ShoppingTrip(
    id = sOr("id"),
    title = sOr("title"),
    store = sOr("store"),
    plannedAt = lOrNull("planned_at"),
    status = enumOrNull(LifeStatus.values(), s("status")) ?: LifeStatus.ACTIVE,
    budget = dOrNull("budget"),
    notes = sOr("notes"),
    createdAt = lOr("created_at"),
    updatedAt = lOr("updated_at"),
    completedAt = lOrNull("completed_at"),
    version = i("version", 1),
    dirty = b("dirty"),
    deleted = b("deleted"),
)

fun ShoppingTrip.toValues(): ContentValues = ContentValues().apply {
    put("id", id); put("title", title); put("store", store)
    putNullable("planned_at", plannedAt); put("status", status.name)
    putNullable("budget", budget); put("notes", notes)
    put("created_at", createdAt); put("updated_at", updatedAt)
    putNullable("completed_at", completedAt)
    put("version", version); put("dirty", if (dirty) 1 else 0); put("deleted", if (deleted) 1 else 0)
}

fun Cursor.toCategory(): Category = Category(
    id = sOr("id"),
    name = sOr("name"),
    color = lOr("color", 0xFF3D5AFE),
    icon = sOr("icon"),
    orderIndex = i("order_index"),
    builtIn = b("built_in"),
    createdAt = lOr("created_at"),
    updatedAt = lOr("updated_at"),
    version = i("version", 1),
    dirty = b("dirty"),
    deleted = b("deleted"),
)

fun Category.toValues(): ContentValues = ContentValues().apply {
    put("id", id); put("name", name); put("color", color); put("icon", icon)
    put("order_index", orderIndex); put("built_in", if (builtIn) 1 else 0)
    put("created_at", createdAt); put("updated_at", updatedAt)
    put("version", version); put("dirty", if (dirty) 1 else 0); put("deleted", if (deleted) 1 else 0)
}

// ---- reminders ----

fun Cursor.toReminder(): ReminderRecord = ReminderRecord(
    id = sOr("id"),
    entryId = sOr("entry_id"),
    fireAt = lOr("fire_at"),
    leadMinutes = i("lead_minutes"),
    repeat = decode<Recurrence>(s("repeat")),
    escalationIntervalMinutes = lOrNull("escalation_interval_minutes")?.toInt(),
    escalationMaxCount = i("escalation_max_count"),
    escalationFired = i("escalation_fired"),
    origin = enumOrNull(ReminderOrigin.values(), s("origin")) ?: ReminderOrigin.AUTO,
    soundEnabled = b("sound_enabled"),
    vibrateEnabled = b("vibrate_enabled"),
    status = enumOrNull(ReminderStatus.values(), s("status")) ?: ReminderStatus.PENDING,
    snoozedUntil = lOrNull("snoozed_until"),
    lastFiredAt = lOrNull("last_fired_at"),
    createdAt = lOr("created_at"),
    updatedAt = lOr("updated_at"),
    version = i("version", 1),
    dirty = b("dirty"),
    deleted = b("deleted"),
)

fun ReminderRecord.toValues(): ContentValues = ContentValues().apply {
    put("id", id); put("entry_id", entryId); put("fire_at", fireAt)
    put("lead_minutes", leadMinutes)
    put("repeat", repeat?.let { encode(it) })
    putNullable("escalation_interval_minutes", escalationIntervalMinutes?.toLong())
    put("escalation_max_count", escalationMaxCount)
    put("escalation_fired", escalationFired)
    put("origin", origin.name)
    put("sound_enabled", if (soundEnabled) 1 else 0)
    put("vibrate_enabled", if (vibrateEnabled) 1 else 0)
    put("status", status.name)
    putNullable("snoozed_until", snoozedUntil)
    putNullable("last_fired_at", lastFiredAt)
    put("created_at", createdAt); put("updated_at", updatedAt)
    put("version", version); put("dirty", if (dirty) 1 else 0); put("deleted", if (deleted) 1 else 0)
}

// ---- history ----

fun Cursor.toHistory(): HistoryEvent = HistoryEvent(
    id = sOr("id"),
    entityType = enumOrNull(EntityKind.values(), s("entity_type")) ?: EntityKind.ENTRY,
    entityId = sOr("entity_id"),
    action = enumOrNull(HistoryAction.values(), s("action")) ?: HistoryAction.EDITED,
    title = sOr("title"),
    summary = sOr("summary"),
    occurredAt = lOr("occurred_at"),
    details = sOr("details"),
    createdAt = lOr("created_at"),
    updatedAt = lOr("updated_at"),
    version = i("version", 1),
    dirty = b("dirty"),
    deleted = b("deleted"),
)

fun HistoryEvent.toValues(): ContentValues = ContentValues().apply {
    put("id", id); put("entity_type", entityType.name); put("entity_id", entityId)
    put("action", action.name); put("title", title); put("summary", summary)
    put("occurred_at", occurredAt); put("details", details)
    put("created_at", createdAt); put("updated_at", updatedAt)
    put("version", version); put("dirty", if (dirty) 1 else 0); put("deleted", if (deleted) 1 else 0)
}

// ---- memories ----

fun Cursor.toMemory(): Memory = Memory(
    id = sOr("id"),
    entryId = s("entry_id"),
    goalId = s("goal_id"),
    milestoneId = s("milestone_id"),
    title = sOr("title"),
    description = sOr("description"),
    category = sOr("category"),
    occurredAt = lOr("occurred_at"),
    photos = decodeList<String>(s("photos")),
    remindInSixMonths = b("remind_six_month"),
    remindYearly = b("remind_yearly"),
    nextReminderAt = lOrNull("next_reminder_at"),
    createdAt = lOr("created_at"),
    updatedAt = lOr("updated_at"),
    version = i("version", 1),
    dirty = b("dirty"),
    deleted = b("deleted"),
)

fun Memory.toValues(): ContentValues = ContentValues().apply {
    put("id", id); put("entry_id", entryId); put("goal_id", goalId)
    put("milestone_id", milestoneId); put("title", title); put("description", description)
    put("category", category); put("occurred_at", occurredAt); put("photos", encode(photos))
    put("remind_six_month", if (remindInSixMonths) 1 else 0)
    put("remind_yearly", if (remindYearly) 1 else 0)
    putNullable("next_reminder_at", nextReminderAt)
    put("created_at", createdAt); put("updated_at", updatedAt)
    put("version", version); put("dirty", if (dirty) 1 else 0); put("deleted", if (deleted) 1 else 0)
}

// ---- sync queue / backups ----

fun Cursor.toSyncItem(): SyncQueueItem = SyncQueueItem(
    id = sOr("id"),
    entityType = enumOrNull(EntityKind.values(), s("entity_type")) ?: EntityKind.ENTRY,
    entityId = sOr("entity_id"),
    op = enumOrNull(SyncOp.values(), s("op")) ?: SyncOp.UPSERT,
    enqueuedAt = lOr("enqueued_at"),
    attempts = i("attempts"),
    lastError = sOr("last_error"),
    state = sOr("state", "PENDING"),
)

fun Cursor.toBackup(): BackupRecord = BackupRecord(
    id = sOr("id"),
    createdAt = lOr("created_at"),
    kind = sOr("kind"),
    fileName = sOr("file_name"),
    sizeBytes = lOr("size_bytes"),
    rowCount = i("row_count"),
    note = sOr("note"),
)

// ---- shared query helpers ----

/** Map every row of an open cursor. */
inline fun <T> Cursor.mapRows(transform: Cursor.() -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out.add(transform())
    return out
}

/** Load entries for a scope, always excluding tombstones. */
fun SQLiteDatabase.queryEntries(where: String, args: Array<String> = emptyArray()): List<Entry> =
    rawQuery("SELECT * FROM entries WHERE deleted=0 AND ($where)", args)
        .use { it.mapRows { toEntry() } }

// ---- generic helpers ----

fun ContentValues.putNullable(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}

fun ContentValues.putNullable(key: String, value: Double?) {
    if (value == null) putNull(key) else put(key, value)
}

fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}
