package com.lifedeck.app.data.repo

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.data.model.HistoryAction
import com.lifedeck.app.data.model.ReminderOrigin
import com.lifedeck.app.data.model.ReminderRecord
import com.lifedeck.app.data.model.ReminderStatus
import com.lifedeck.app.data.model.SyncOp
import com.lifedeck.app.domain.RecurrenceEngine
import com.lifedeck.app.util.Celebration
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import kotlinx.coroutines.flow.Flow

/**
 * The heart of the local-first store: every capture, edit, completion and
 * reschedule lands here first, writes the history ledger and the sync queue in
 * the same transaction, and re-derives that entry's reminders immediately.
 */
class TaskRepository(
    private val db: Database,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
) {

    // ---------------------------------------------------------------- queries

    fun observeInbox(): Flow<List<Entry>> = db.observe { it.queryEntries("status='INBOX'", emptyArray()) }

    /** Everything still in play (ACTIVE + MISSED), ordered by urgency. */
    fun observeOpen(): Flow<List<Entry>> = db.observe {
        it.queryEntries("status IN ('ACTIVE','MISSED')", emptyArray())
    }

    fun observeActive(): Flow<List<Entry>> = observeOpen()

    fun observeById(id: String): Flow<Entry?> = db.observe { sqlite ->
        sqlite.rawQuery("SELECT * FROM entries WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toEntry() else null
        }
    }

    fun observeChildren(parentId: String): Flow<List<Entry>> = db.observe {
        it.queryEntries("parent_id=? AND deleted=0", arrayOf(parentId))
    }

    fun observeByProject(projectId: String): Flow<List<Entry>> = db.observe {
        it.queryEntries("project_id=? AND deleted=0", arrayOf(projectId))
    }

    fun observeByMilestone(milestoneId: String): Flow<List<Entry>> = db.observe {
        it.queryEntries("milestone_id=? AND deleted=0", arrayOf(milestoneId))
    }

    fun observeByGoal(goalId: String): Flow<List<Entry>> = db.observe {
        it.queryEntries("goal_id=? AND deleted=0", arrayOf(goalId))
    }

    fun observeByTrip(tripId: String): Flow<List<Entry>> = db.observe {
        it.queryEntries("trip_id=? AND deleted=0", arrayOf(tripId))
    }

    fun observeByIds(ids: List<String>): Flow<List<Entry>> = db.observe { sqlite ->
        if (ids.isEmpty()) return@observe emptyList()
        val ph = ids.joinToString(",") { "?" }
        sqlite.rawQuery("SELECT * FROM entries WHERE id IN ($ph)", ids.toTypedArray())
            .use { c -> c.mapRows { toEntry() } }
    }

    fun observeCompletedBetween(from: Long, to: Long): Flow<List<Entry>> = db.observe { sqlite ->
        sqlite.rawQuery(
            "SELECT * FROM entries WHERE deleted=0 AND status='DONE' AND completed_at>=? AND completed_at<? ORDER BY completed_at DESC",
            arrayOf(from.toString(), to.toString()),
        ).use { it.mapRows { toEntry() } }
    }

    fun observeReminders(entryId: String): Flow<List<ReminderRecord>> = db.observe { sqlite ->
        sqlite.rawQuery(
            "SELECT * FROM reminders WHERE entry_id=? AND deleted=0 ORDER BY fire_at",
            arrayOf(entryId),
        ).use { it.mapRows { toReminder() } }
    }

    fun observeAllReminders(): Flow<List<ReminderRecord>> = db.observe { sqlite ->
        sqlite.rawQuery("SELECT * FROM reminders WHERE deleted=0 ORDER BY fire_at", null)
            .use { it.mapRows { toReminder() } }
    }

    suspend fun get(id: String): Entry? = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM entries WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toEntry() else null
        }
    }

    suspend fun getMany(ids: List<String>): List<Entry> = db.read { sqlite ->
        if (ids.isEmpty()) return@read emptyList()
        val ph = ids.joinToString(",") { "?" }
        sqlite.rawQuery("SELECT * FROM entries WHERE id IN ($ph)", ids.toTypedArray())
            .use { it.mapRows { toEntry() } }
    }

    suspend fun childrenOf(parentId: String): List<Entry> = db.read { sqlite ->
        sqlite.queryEntries("parent_id=? AND deleted=0", arrayOf(parentId))
    }

    /** All rows including tombstones — used by sync, backup and export. */
    suspend fun allRows(): List<Entry> = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM entries ORDER BY created_at", null).use { it.mapRows { toEntry() } }
    }

    suspend fun pendingInboxCount(): Int = db.read { sqlite ->
        sqlite.rawQuery("SELECT COUNT(*) FROM entries WHERE deleted=0 AND status='INBOX'", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** Open entries whose time already passed (the evening review's "missed"). */
    suspend fun missed(): List<Entry> = db.read { sqlite ->
        val todayStart = com.lifedeck.app.util.todayStart()
        sqlite.rawQuery(
            """SELECT * FROM entries WHERE deleted=0 AND status IN ('ACTIVE','MISSED')
               AND (COALESCE(scheduled_at, due_at, start_at) IS NOT NULL AND COALESCE(scheduled_at, due_at, start_at) < ?)
               ORDER BY COALESCE(due_at, scheduled_at, start_at)""",
            arrayOf(todayStart.toString()),
        ).use { it.mapRows { toEntry() } }
    }

    suspend fun overdue(): List<Entry> = db.read { sqlite ->
        val now = now()
        sqlite.rawQuery(
            """SELECT * FROM entries WHERE deleted=0 AND status IN ('ACTIVE','MISSED')
               AND due_at IS NOT NULL AND due_at < ? ORDER BY due_at""",
            arrayOf(now.toString()),
        ).use { it.mapRows { toEntry() } }
    }

    // ----------------------------------------------------------------- create

    /**
     * Insert a captured entry: history + sync queue + derived reminders are
     * written in one transaction so nothing is ever half-saved.
     * @return the new entry id.
     */
    suspend fun create(
        raw: Entry,
        action: HistoryAction = HistoryAction.CREATED,
        summary: String = "Captured",
    ): String {
        val s = settings.get()
        var id = raw.id.ifBlank { newId() }
        db.write { sqlite ->
            val ts = now()
            val entry = raw.copy(
                id = id,
                createdAt = if (raw.createdAt == 0L) ts else raw.createdAt,
                updatedAt = ts,
                version = 1,
                dirty = true,
                orderIndex = if (raw.orderIndex != 0) raw.orderIndex else nextOrderIndex(sqlite, raw),
            )
            sqlite.insertWithOnConflict("entries", null, entry.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.ENTRY, entry.id, SyncOp.UPSERT)
            history.record(
                sqlite, EntityKind.ENTRY, entry.id, action, entry.title, summary,
                """{"type":"${entry.type}","status":"${entry.status}","dueAt":${entry.dueAt ?: 0}}""",
            )
            resyncAutoReminders(sqlite, entry, s)
        }
        return id
    }

    // ------------------------------------------------------------------ edits

    /** Generic update: bumps version/updatedAt, logs, syncs and re-derives reminders. */
    suspend fun update(
        entry: Entry,
        action: HistoryAction = HistoryAction.EDITED,
        summary: String = "",
        celebrate: Boolean = true,
    ) {
        val s = settings.get()
        db.write { sqlite ->
            val ts = now()
            val stored = entry.copy(updatedAt = ts, version = entry.version + 1, dirty = true)
            sqlite.insertWithOnConflict("entries", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.ENTRY, stored.id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.ENTRY, stored.id, action, stored.title, summary)
            resyncAutoReminders(sqlite, stored, s)
            if (celebrate) {
                Rollup.afterEntryChanged(sqlite, stored, s.celebrateMilestones, s.celebrateSound)
            }
        }
    }

    /** Apply a set of inbox-processing decisions (date, priority, category, project). */
    suspend fun processInbox(
        id: String,
        dueAt: Long? = null,
        scheduledAt: Long? = null,
        priority: com.lifedeck.app.data.model.Priority? = null,
        categoryId: String? = null,
        projectId: String? = null,
        goalId: String? = null,
        activate: Boolean = true,
    ) {
        val current = get(id) ?: return
        update(
            current.copy(
                dueAt = dueAt,
                scheduledAt = scheduledAt,
                priority = priority ?: current.priority,
                categoryId = categoryId ?: current.categoryId,
                projectId = projectId ?: current.projectId,
                goalId = goalId ?: current.goalId,
                status = if (activate) EntryStatus.ACTIVE else current.status,
            ),
            HistoryAction.EDITED,
            "Reviewed from inbox",
        )
    }

    // -------------------------------------------------------------- completion

    /** Complete an entry. Returns celebrations triggered by the roll-up. */
    suspend fun complete(id: String, fromReminder: Boolean = false): List<Celebration> {
        val s = settings.get()
        val celebrations = mutableListOf<Celebration>()
        var nextOccurrence: Entry? = null
        db.write { sqlite ->
            val entry = getOn(sqlite, id) ?: return@write
            if (entry.status == EntryStatus.DONE) return@write
            val ts = now()
            val done = entry.copy(
                status = EntryStatus.DONE,
                completedAt = ts,
                updatedAt = ts,
                version = entry.version + 1,
                dirty = true,
            )
            upsert(sqlite, done)
            enqueueSync(sqlite, EntityKind.ENTRY, id, SyncOp.UPSERT)
            history.record(
                sqlite, EntityKind.ENTRY, id, HistoryAction.COMPLETED, entry.title,
                if (fromReminder) "Completed from notification" else "Completed",
                """{"completedAt":$ts}""",
            )
            cancelPendingReminders(sqlite, id)

            // Recurring entries spawn their next occurrence.
            entry.recurrence?.let { rule ->
                val nextAt = entry.dueAt ?: entry.scheduledAt ?: entry.startAt
                if (rule.isActive && nextAt != null) {
                    val nextMillis = RecurrenceEngine.nextOccurrence(nextAt, rule)
                    if (nextMillis != null) {
                        val next = entry.copy(
                            id = newId(),
                            status = if (entry.isUnscheduled) EntryStatus.INBOX else EntryStatus.ACTIVE,
                            dueAt = entry.dueAt?.let { nextMillis },
                            scheduledAt = entry.scheduledAt?.let { nextMillis },
                            startAt = entry.startAt?.let { nextMillis },
                            endAt = entry.endAt?.let { old ->
                                val dur = old - (entry.startAt ?: old)
                                nextMillis + dur
                            },
                            completedAt = null,
                            createdAt = ts,
                            updatedAt = ts,
                            version = 1,
                            dirty = true,
                            orderIndex = entry.orderIndex,
                        )
                        upsert(sqlite, next)
                        enqueueSync(sqlite, EntityKind.ENTRY, next.id, SyncOp.UPSERT)
                        history.record(
                            sqlite, EntityKind.ENTRY, next.id, HistoryAction.CREATED,
                            entry.title, "Next recurring occurrence",
                        )
                        resyncAutoReminders(sqlite, next, s)
                        nextOccurrence = next
                    }
                    upsert(sqlite, done.copy(recurrence = RecurrenceEngine.advanced(rule)))
                }
            }

            celebrations += Rollup.afterEntryChanged(sqlite, done, s.celebrateMilestones, s.celebrateSound)
            if (s.celebrateMilestones && entry.favorite) {
                val ev = Celebration(
                    Celebration.Kind.TASK_DONE, "Nicely done ✨",
                    "\"${entry.title}\" is complete", 100,
                )
                celebrations.add(ev)
                com.lifedeck.app.util.CelebrationBus.emit(ev)
            }
        }
        return celebrations
    }

    suspend fun uncomplete(id: String) {
        val s = settings.get()
        db.write { sqlite ->
            val entry = getOn(sqlite, id) ?: return@write
            val revived = entry.copy(
                status = if (entry.isUnscheduled) EntryStatus.INBOX else EntryStatus.ACTIVE,
                completedAt = null,
                updatedAt = now(),
                version = entry.version + 1,
                dirty = true,
            )
            upsert(sqlite, revived)
            enqueueSync(sqlite, EntityKind.ENTRY, id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.ENTRY, id, HistoryAction.UNCOMPLETED, entry.title, "Reopened")
            resyncAutoReminders(sqlite, revived, s)
            Rollup.afterEntryChanged(sqlite, revived, celebrate = false, sound = false)
        }
    }

    /** Skip today's instance (recurring entries advance to the next slot). */
    suspend fun skip(id: String) {
        val s = settings.get()
        db.write { sqlite ->
            val entry = getOn(sqlite, id) ?: return@write
            val ts = now()
            val skipped = entry.copy(status = EntryStatus.SKIPPED, updatedAt = ts, version = entry.version + 1, dirty = true)
            upsert(sqlite, skipped)
            enqueueSync(sqlite, EntityKind.ENTRY, id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.ENTRY, id, HistoryAction.SKIPPED, entry.title, "Skipped")
            cancelPendingReminders(sqlite, id)
            entry.recurrence?.let { rule ->
                val at = entry.dueAt ?: entry.scheduledAt ?: entry.startAt
                if (rule.isActive && at != null) {
                    RecurrenceEngine.nextOccurrence(at, rule)?.let { nextMillis ->
                        val next = entry.copy(
                            id = newId(), status = EntryStatus.ACTIVE,
                            dueAt = entry.dueAt?.let { nextMillis },
                            scheduledAt = entry.scheduledAt?.let { nextMillis },
                            startAt = entry.startAt?.let { nextMillis },
                            completedAt = null, createdAt = ts, updatedAt = ts,
                            version = 1, dirty = true,
                        )
                        upsert(sqlite, next)
                        enqueueSync(sqlite, EntityKind.ENTRY, next.id, SyncOp.UPSERT)
                        history.record(sqlite, EntityKind.ENTRY, next.id, HistoryAction.CREATED, entry.title, "Next occurrence after skip")
                        resyncAutoReminders(sqlite, next, s)
                    }
                    upsert(sqlite, skipped.copy(recurrence = RecurrenceEngine.advanced(rule)))
                }
            }
        }
    }

    /**
     * Move a task to new date/time. The deadline is never changed silently:
     * the previous value is recorded in the history ledger and shown in the
     * rescheduling history.
     */
    suspend fun reschedule(
        id: String,
        dueAt: Long? = null,
        scheduledAt: Long? = null,
        startAt: Long? = null,
        endAt: Long? = null,
        keepExisting: Boolean = true,
        reason: String = "",
    ) {
        val s = settings.get()
        db.write { sqlite ->
            val entry = getOn(sqlite, id) ?: return@write
            val ts = now()
            val moved = entry.copy(
                dueAt = if (keepExisting) dueAt ?: entry.dueAt else dueAt,
                scheduledAt = if (keepExisting) scheduledAt ?: entry.scheduledAt else scheduledAt,
                startAt = startAt ?: entry.startAt,
                endAt = endAt ?: entry.endAt,
                status = if (entry.status == EntryStatus.MISSED) EntryStatus.ACTIVE else entry.status,
                rescheduledCount = entry.rescheduledCount + 1,
                updatedAt = ts,
                version = entry.version + 1,
                dirty = true,
            )
            upsert(sqlite, moved)
            enqueueSync(sqlite, EntityKind.ENTRY, id, SyncOp.UPSERT)
            val from = com.lifedeck.app.util.formatDateTime(entry.dueAt ?: entry.scheduledAt ?: entry.startAt)
            val to = com.lifedeck.app.util.formatDateTime(moved.dueAt ?: moved.scheduledAt ?: moved.startAt)
            history.record(
                sqlite, EntityKind.ENTRY, id, HistoryAction.RESCHEDULED, entry.title,
                "Moved from $from to $to" + if (reason.isNotBlank()) " · $reason" else "",
                """{"from":${entry.dueAt ?: 0},"to":${moved.dueAt ?: 0},"scheduledFrom":${entry.scheduledAt ?: 0},"scheduledTo":${moved.scheduledAt ?: 0}}""",
            )
            resyncAutoReminders(sqlite, moved, s)
            Rollup.afterEntryChanged(sqlite, moved, celebrate = false, sound = false)
        }
    }

    suspend fun setStatus(id: String, status: EntryStatus, note: String = "") {
        val s = settings.get()
        db.write { sqlite ->
            val entry = getOn(sqlite, id) ?: return@write
            val ts = now()
            val updated = entry.copy(
                status = status,
                completedAt = if (status == EntryStatus.DONE) entry.completedAt ?: ts else null,
                updatedAt = ts,
                version = entry.version + 1,
                dirty = true,
            )
            upsert(sqlite, updated)
            enqueueSync(sqlite, EntityKind.ENTRY, id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.ENTRY, id, HistoryAction.EDITED, entry.title, note.ifBlank { "Status → ${status.name.lowercase()}" })
            if (status == EntryStatus.DONE) cancelPendingReminders(sqlite, id) else resyncAutoReminders(sqlite, updated, s)
            Rollup.afterEntryChanged(sqlite, updated, s.celebrateMilestones, s.celebrateSound)
        }
    }

    /** Soft delete: the row is tombstoned, never destroyed. Undo restores it. */
    suspend fun delete(id: String) {
        db.write { sqlite ->
            val entry = getOn(sqlite, id) ?: return@write
            val ts = now()
            val tomb = entry.copy(deleted = true, updatedAt = ts, version = entry.version + 1, dirty = true)
            upsert(sqlite, tomb)
            enqueueSync(sqlite, EntityKind.ENTRY, id, SyncOp.DELETE)
            history.record(sqlite, EntityKind.ENTRY, id, HistoryAction.DELETED, entry.title, "Deleted")
            cancelPendingReminders(sqlite, id, all = true)
        }
    }

    suspend fun restore(id: String) {
        val s = settings.get()
        db.write { sqlite ->
            val entry = getOn(sqlite, id) ?: return@write
            val ts = now()
            val back = entry.copy(deleted = false, updatedAt = ts, version = entry.version + 1, dirty = true)
            upsert(sqlite, back)
            enqueueSync(sqlite, EntityKind.ENTRY, id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.ENTRY, id, HistoryAction.RESTORED, entry.title, "Restored")
            resyncAutoReminders(sqlite, back, s)
        }
    }

    /** Heart a task: it becomes a candidate memory and shows on anniversaries. */
    suspend fun toggleFavorite(id: String): Boolean {
        var nowFavorite = false
        db.write { sqlite ->
            val entry = getOn(sqlite, id) ?: return@write
            val fav = !entry.favorite
            nowFavorite = fav
            val ts = now()
            val updated = entry.copy(favorite = fav, updatedAt = ts, version = entry.version + 1, dirty = true)
            upsert(sqlite, updated)
            enqueueSync(sqlite, EntityKind.ENTRY, id, SyncOp.UPSERT)
            history.record(
                sqlite, EntityKind.ENTRY, id,
                if (fav) HistoryAction.MEMORY_CREATED else HistoryAction.EDITED,
                entry.title, if (fav) "Marked as a memory" else "Removed from memories",
            )
        }
        return nowFavorite
    }

    suspend fun toggleChecklistItem(entryId: String, itemId: String) {
        val entry = get(entryId) ?: return
        val items = entry.checklist.map { if (it.id == itemId) it.copy(done = !it.done) else it }
        update(entry.copy(checklist = items), summary = "Checklist updated")
    }

    suspend fun setChecklist(entryId: String, items: List<com.lifedeck.app.data.model.ChecklistItem>) {
        val entry = get(entryId) ?: return
        update(entry.copy(checklist = items), summary = "Checklist updated")
    }

    /** Create a subtask that inherits its parent's context. */
    suspend fun addSubtask(parentId: String, title: String): String? {
        val parent = get(parentId) ?: return null
        val child = Entry(
            type = parent.type,
            title = title,
            status = EntryStatus.ACTIVE,
            categoryId = parent.categoryId,
            priority = parent.priority,
            parentId = parent.id,
            projectId = parent.projectId,
            milestoneId = parent.milestoneId,
            goalId = parent.goalId,
            dueAt = parent.dueAt,
            estimateMinutes = null,
        )
        return create(child, summary = "Subtask added to ${parent.title}")
    }

    /** Manual ordering inside a scope (drag & drop / move up-down). */
    suspend fun reorder(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        db.write { sqlite ->
            val ts = now()
            orderedIds.forEachIndexed { index, id ->
                val values = ContentValues().apply {
                    put("order_index", index)
                    put("updated_at", ts)
                    put("dirty", 1)
                }
                sqlite.update("entries", values, "id=?", arrayOf(id))
                sqlite.execSQL("UPDATE entries SET version=version+1 WHERE id=?", arrayOf(id))
                enqueueSync(sqlite, EntityKind.ENTRY, id, SyncOp.UPSERT)
            }
        }
    }

    // --------------------------------------------------------------- reminders

    /** User places a reminder at an exact moment (kept even if the task moves). */
    suspend fun addManualReminder(
        entryId: String,
        fireAt: Long,
        repeat: com.lifedeck.app.data.model.Recurrence? = null,
        escalationIntervalMinutes: Int? = null,
        escalationMaxCount: Int = 0,
        soundEnabled: Boolean = true,
        vibrateEnabled: Boolean = true,
    ): String {
        val s = settings.get()
        val id = newId()
        db.write { sqlite ->
            val ts = now()
            val reminder = ReminderRecord(
                id = id,
                entryId = entryId,
                fireAt = fireAt,
                leadMinutes = 0,
                repeat = repeat,
                escalationIntervalMinutes = escalationIntervalMinutes,
                escalationMaxCount = escalationMaxCount,
                soundEnabled = soundEnabled || s.soundEnabled,
                vibrateEnabled = vibrateEnabled || s.vibrateEnabled,
                status = ReminderStatus.PENDING,
                origin = ReminderOrigin.MANUAL,
                createdAt = ts,
                updatedAt = ts,
            )
            sqlite.insertWithOnConflict("reminders", null, reminder.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.REMINDER, id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.ENTRY, entryId, HistoryAction.EDITED, "Reminder added", com.lifedeck.app.util.formatDateTime(fireAt))
        }
        return id
    }

    suspend fun removeReminder(reminderId: String, entryId: String) {
        db.write { sqlite ->
            sqlite.delete("reminders", "id=?", arrayOf(reminderId))
            enqueueSync(sqlite, EntityKind.REMINDER, reminderId, SyncOp.DELETE)
            history.record(sqlite, EntityKind.ENTRY, entryId, HistoryAction.EDITED, "Reminder removed", reminderId)
        }
    }

    suspend fun snoozeReminder(reminderId: String, until: Long) {
        db.write { sqlite ->
            val ts = now()
            sqlite.execSQL(
                "UPDATE reminders SET status='SNOOZED', snoozed_until=?, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(until, ts, reminderId),
            )
            enqueueSync(sqlite, EntityKind.REMINDER, reminderId, SyncOp.UPSERT)
        }
    }

    suspend fun updateReminder(reminder: ReminderRecord) {
        db.write { sqlite ->
            val stored = reminder.copy(updatedAt = now(), version = reminder.version + 1, dirty = true)
            sqlite.insertWithOnConflict("reminders", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.REMINDER, stored.id, SyncOp.UPSERT)
        }
    }

    suspend fun reminderRowsForSync(): List<ReminderRecord> = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM reminders ORDER BY created_at", null).use { it.mapRows { toReminder() } }
    }

    // ------------------------------------------------------------- roll-up API

    suspend fun progressForGoal(goalId: String) = db.read { Rollup.goalProgress(it, goalId) }
    suspend fun progressForProject(projectId: String) = db.read { Rollup.projectProgress(it, projectId) }
    suspend fun progressForMilestone(milestoneId: String) = db.read { Rollup.milestoneProgress(it, milestoneId) }

    // ----------------------------------------------------------------- private

    private fun getOn(sqlite: SQLiteDatabase, id: String): Entry? =
        sqlite.rawQuery("SELECT * FROM entries WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toEntry() else null
        }

    private fun upsert(sqlite: SQLiteDatabase, entry: Entry) {
        sqlite.insertWithOnConflict("entries", null, entry.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun nextOrderIndex(sqlite: SQLiteDatabase, entry: Entry): Int {
        val scope = when {
            entry.milestoneId != null -> "milestone_id=?" to entry.milestoneId
            entry.projectId != null -> "project_id=?" to entry.projectId
            entry.parentId != null -> "parent_id=?" to entry.parentId
            else -> null
        }
        val sql = if (scope == null) {
            "SELECT COALESCE(MAX(order_index),0)+1 FROM entries WHERE deleted=0"
        } else {
            "SELECT COALESCE(MAX(order_index),0)+1 FROM entries WHERE deleted=0 AND ${scope.first}"
        }
        val args = scope?.second?.let { arrayOf(it) }
        return sqlite.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 1 }
    }

    private fun cancelPendingReminders(sqlite: SQLiteDatabase, entryId: String, all: Boolean = false) {
        val where = if (all) "entry_id=? AND status IN ('PENDING','SNOOZED','FIRED')" else "entry_id=? AND status IN ('PENDING','SNOOZED')"
        sqlite.rawQuery("SELECT id FROM reminders WHERE $where", arrayOf(entryId)).use { c ->
            val ids = mutableListOf<String>()
            while (c.moveToNext()) ids.add(c.getString(0))
            ids.forEach { rid ->
                sqlite.execSQL(
                    "UPDATE reminders SET status='CANCELLED', updated_at=?, dirty=1, version=version+1 WHERE id=?",
                    arrayOf<Any>(now(), rid),
                )
                enqueueSync(sqlite, EntityKind.REMINDER, rid, SyncOp.UPSERT)
            }
        }
    }

    /**
     * Re-derive AUTO reminders from the entry's current timing. Called on every
     * create/edit/complete/reschedule so alarms are never stale.
     *
     * MANUAL reminders are left untouched except when the task is finished.
     */
    private fun resyncAutoReminders(sqlite: SQLiteDatabase, entry: Entry, s: AppSettings) {
        sqlite.delete("reminders", "entry_id=? AND origin='AUTO' AND status='PENDING'", arrayOf(entry.id))
        if (!entry.isOpen) return
        if (entry.isUnscheduled) return // inbox items are reviewed before they remind

        val lead = s.defaultLeadMinutes.coerceAtLeast(0)
        val targets = mutableListOf<Long>()
        entry.scheduledAt?.let { targets += it }
        entry.startAt?.let { targets += it }
        if (targets.isEmpty()) entry.dueAt?.let { targets += it }
        if (targets.isEmpty()) return

        val ts = now()
        targets.distinct().forEach { target ->
            val withLead = target - lead * 60_000L
            val fireAt = when {
                withLead > ts -> withLead
                target > ts -> target
                else -> return@forEach // already in the past; nothing useful to schedule
            }
            val escalate = entry.priority == com.lifedeck.app.data.model.Priority.URGENT ||
                entry.priority == com.lifedeck.app.data.model.Priority.HIGH
            val reminder = ReminderRecord(
                id = newId(),
                entryId = entry.id,
                fireAt = fireAt,
                leadMinutes = if (fireAt == withLead) lead else 0,
                repeat = entry.recurrence?.takeIf { it.isActive },
                escalationIntervalMinutes = if (escalate) s.escalationIntervalMinutes else null,
                escalationMaxCount = if (escalate) s.escalationMaxCount else 0,
                soundEnabled = s.soundEnabled,
                vibrateEnabled = s.vibrateEnabled,
                status = ReminderStatus.PENDING,
                origin = ReminderOrigin.AUTO,
                createdAt = ts,
                updatedAt = ts,
            )
            sqlite.insertWithOnConflict("reminders", null, reminder.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
            enqueueSync(sqlite, EntityKind.REMINDER, reminder.id, SyncOp.UPSERT)
        }
    }
}
