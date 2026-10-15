package com.lifedeck.app.data.repo

import android.database.sqlite.SQLiteDatabase
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.HistoryAction
import com.lifedeck.app.data.model.Memory
import com.lifedeck.app.data.model.SyncOp
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import com.lifedeck.app.util.toLocalDate
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Memories & personal diary. Completed tasks with a heart, milestones, goals
 * and free-form events become permanent, searchable memories with optional
 * six-month and yearly anniversary reminders.
 */
class MemoryRepository(
    private val db: Database,
    private val history: HistoryRepository,
) {

    fun observeAll(): Flow<List<Memory>> = db.observe { sqlite ->
        sqlite.rawQuery("SELECT * FROM memories WHERE deleted=0 ORDER BY occurred_at DESC", null)
            .use { it.mapRows { toMemory() } }
    }

    fun observeById(id: String): Flow<Memory?> = db.observe { sqlite ->
        sqlite.rawQuery("SELECT * FROM memories WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toMemory() else null
        }
    }

    suspend fun get(id: String): Memory? = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM memories WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toMemory() else null
        }
    }

    suspend fun memoryForEntry(entryId: String): Memory? = db.read { sqlite ->
        sqlite.rawQuery(
            "SELECT * FROM memories WHERE entry_id=? AND deleted=0 ORDER BY occurred_at DESC LIMIT 1",
            arrayOf(entryId),
        ).use { c -> if (c.moveToFirst()) c.toMemory() else null }
    }

    /** Memories that happened on this month-day in any previous year. */
    fun observeOnThisDay(monthDay: String): Flow<List<Memory>> = db.observe { sqlite ->
        sqlite.rawQuery(
            """SELECT * FROM memories WHERE deleted=0
               AND strftime('%m-%d', occurred_at/1000, 'unixepoch')=?
               ORDER BY occurred_at DESC""",
            arrayOf(monthDay),
        ).use { it.mapRows { toMemory() } }
    }

    /** Anniversaries coming up within [days] (six-month and yearly reminders). */
    suspend fun upcomingWithin(days: Int): List<Memory> = db.read { sqlite ->
        val today = LocalDate.now()
        sqlite.rawQuery(
            """SELECT * FROM memories
               WHERE deleted=0 AND (remind_yearly=1 OR remind_six_month=1)
               ORDER BY occurred_at DESC""",
            null,
        ).use { c ->
            c.mapRows { toMemory() }.filter { mem ->
                val next = anniversariesOf(mem, today).minOrNull()
                next != null && next in today..today.plusDays(days.toLong())
            }
        }
    }

    fun observeUpcomingAnniversaries(days: Int = 14): Flow<List<Memory>> = db.observe { sqlite ->
        val today = LocalDate.now()
        val from = today.toEpochDay() * 86_400_000L
        val to = from + days * 86_400_000L
        // Compare by month-day across years: we check next occurrence this year.
        sqlite.rawQuery(
            """SELECT * FROM memories
               WHERE deleted=0 AND (remind_yearly=1 OR remind_six_month=1)
               ORDER BY occurred_at DESC""",
            null,
        ).use { c ->
            val today2 = LocalDate.now()
            c.mapRows { toMemory() }.filter { mem ->
                anniversariesOf(mem, today2).any { it in today2..today2.plusDays(days.toLong()) }
            }
        }
    }

    suspend fun create(memory: Memory): String {
        val id = memory.id.ifBlank { newId() }
        db.write { sqlite ->
            val ts = now()
            val stored = memory.copy(
                id = id,
                createdAt = ts,
                updatedAt = ts,
                dirty = true,
                version = 1,
                nextReminderAt = computeNextReminder(memory),
            )
            sqlite.insertWithOnConflict("memories", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.MEMORY, id, SyncOp.UPSERT)
            history.record(
                sqlite, EntityKind.MEMORY, id, HistoryAction.MEMORY_CREATED, stored.title,
                "Memory saved · " + com.lifedeck.app.util.formatFullDate(stored.occurredAt),
            )
        }
        return id
    }

    suspend fun update(memory: Memory) {
        db.write { sqlite ->
            val ts = now()
            val stored = memory.copy(
                updatedAt = ts,
                version = memory.version + 1,
                dirty = true,
                nextReminderAt = computeNextReminder(memory),
            )
            sqlite.insertWithOnConflict("memories", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.MEMORY, stored.id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.MEMORY, stored.id, HistoryAction.EDITED, stored.title, "Memory updated")
        }
    }

    suspend fun delete(id: String) {
        db.write { sqlite ->
            val ts = now()
            sqlite.execSQL(
                "UPDATE memories SET deleted=1, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, id),
            )
            enqueueSync(sqlite, EntityKind.MEMORY, id, SyncOp.DELETE)
        }
    }

    /**
     * Turn an entity (task/goal/milestone) into a memory. Used by the
     * heart/favourite feature and by milestone celebrations.
     */
    suspend fun createFromEntry(entry: Entry, description: String = "", photos: List<String> = emptyList()): String {
        val existing = memoryForEntry(entry.id)
        if (existing != null) return existing.id
        return create(
            Memory(
                entryId = entry.id,
                goalId = entry.goalId,
                milestoneId = entry.milestoneId,
                title = entry.title,
                description = description.ifBlank { entry.notes },
                category = entry.type.name,
                occurredAt = entry.completedAt ?: entry.createdAt,
                photos = photos.ifEmpty { entry.photos },
                remindYearly = true,
            )
        )
    }

    suspend fun toggleAnniversary(memoryId: String, yearly: Boolean) {
        val mem = get(memoryId) ?: return
        update(mem.copy(remindYearly = yearly))
    }

    suspend fun toggleSixMonth(memoryId: String, enabled: Boolean) {
        val mem = get(memoryId) ?: return
        update(mem.copy(remindInSixMonths = enabled))
    }

    suspend fun allMemoriesForSync(): List<Memory> = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM memories ORDER BY created_at", null).use { it.mapRows { toMemory() } }
    }

    /** "This day last year" — same month/day, previous years. */
    suspend fun onThisDay(monthDay: String): List<Memory> = db.read { sqlite ->
        sqlite.rawQuery(
            """SELECT * FROM memories WHERE deleted=0
               AND strftime('%m-%d', occurred_at/1000, 'unixepoch')=?
               ORDER BY occurred_at DESC""",
            arrayOf(monthDay),
        ).use { it.mapRows { toMemory() } }
    }

    /** Next anniversary moment for a memory, honouring yearly/six-month flags. */
    private fun computeNextReminder(memory: Memory): Long? {
        val occurred = memory.occurredAt
        val now = now()
        val dates = mutableListOf<Long>()
        if (memory.remindYearly) {
            val base = occurred.toLocalDate()
            var y = LocalDate.now().year
            while (y < LocalDate.now().year + 3) {
                val candidate = base.withYear(y)
                if (candidate.isAfter(LocalDate.now())) {
                    dates.add(candidate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                    break
                }
                y++
            }
        }
        if (memory.remindInSixMonths) {
            val six = occurred.toLocalDate().plusMonths(6)
            if (six.isAfter(LocalDate.now())) {
                dates.add(six.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            }
        }
        return dates.filter { it > now }.minOrNull()
    }

    /** Every anniversary date for a memory that falls in [today]..[today+days]. */
    private fun anniversariesOf(mem: Memory, today: LocalDate): List<LocalDate> {
        val occurred = mem.occurredAt.toLocalDate()
        val out = mutableListOf<LocalDate>()
        if (mem.remindYearly) {
            for (y in today.year..today.year + 1) out += occurred.withYear(y)
        }
        if (mem.remindInSixMonths) {
            val six = occurred.plusMonths(6)
            for (y in today.year..today.year + 1) out += six.withYear(y)
        }
        return out
    }
}
