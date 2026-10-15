package com.lifedeck.app.data.repo

import android.database.sqlite.SQLiteDatabase
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.HistoryAction
import com.lifedeck.app.data.model.HistoryEvent
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Append-only personal journal. Nothing here is ever deleted when a task
 * completes: completed tasks, purchases, milestones and memories all land in
 * this ledger permanently, which is what powers the History timeline and the
 * searchable archive.
 */
class HistoryRepository(private val db: Database) {

    /** Record an event inside an existing write transaction. */
    fun record(
        db: SQLiteDatabase,
        entityType: EntityKind,
        entityId: String,
        action: HistoryAction,
        title: String,
        summary: String = "",
        details: String = "",
    ): String {
        val id = newId()
        val ts = now()
        val event = HistoryEvent(
            id = id,
            entityType = entityType,
            entityId = entityId,
            action = action,
            title = title,
            summary = summary,
            occurredAt = ts,
            createdAt = ts,
            updatedAt = ts,
            details = details,
        )
        db.insert("history", null, event.toValues())
        return id
    }

    /** Record an event with its own transaction. */
    suspend fun log(
        entityType: EntityKind,
        entityId: String,
        action: HistoryAction,
        title: String,
        summary: String = "",
        details: String = "",
    ) = db.write { record(it, entityType, entityId, action, title, summary, details) }

    fun observe(limit: Int = 300): Flow<List<HistoryEvent>> = db.observe { sqlite ->
        sqlite.rawQuery(
            "SELECT * FROM history WHERE deleted=0 ORDER BY occurred_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { it.toList { toHistory() } }
    }

    fun observeFor(entityId: String): Flow<List<HistoryEvent>> = db.observe { sqlite ->
        sqlite.rawQuery(
            "SELECT * FROM history WHERE entity_id=? AND deleted=0 ORDER BY occurred_at DESC",
            arrayOf(entityId),
        ).use { it.toList { toHistory() } }
    }

    fun observeBetween(from: Long, to: Long): Flow<List<HistoryEvent>> = db.observe { sqlite ->
        sqlite.rawQuery(
            "SELECT * FROM history WHERE deleted=0 AND occurred_at>=? AND occurred_at<? ORDER BY occurred_at DESC",
            arrayOf(from.toString(), to.toString()),
        ).use { it.toList { toHistory() } }
    }

    suspend fun between(from: Long, to: Long): List<HistoryEvent> = db.read { sqlite ->
        sqlite.rawQuery(
            "SELECT * FROM history WHERE deleted=0 AND occurred_at>=? AND occurred_at<? ORDER BY occurred_at DESC",
            arrayOf(from.toString(), to.toString()),
        ).use { it.toList { toHistory() } }
    }

    suspend fun allForSync(): List<HistoryEvent> = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM history ORDER BY occurred_at", null)
            .use { it.toList { toHistory() } }
    }

    /** Anything that happened on this calendar date in previous years. */
    fun observeOnThisDay(monthDay: String): Flow<List<HistoryEvent>> = db.observe { sqlite ->
        sqlite.rawQuery(
            "SELECT * FROM history WHERE deleted=0 AND strftime('%m-%d', occurred_at/1000, 'unixepoch')=? ORDER BY occurred_at DESC",
            arrayOf(monthDay),
        ).use { it.toList { toHistory() } }
    }
}

private inline fun <T> android.database.Cursor.toList(mapper: android.database.Cursor.() -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out.add(mapper())
    return out
}
