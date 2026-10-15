package com.lifedeck.app.data.repo

import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.SyncQueueItem
import com.lifedeck.app.util.now
import kotlinx.coroutines.flow.Flow

/**
 * Pending changes waiting to reach Google Sheets. Nothing is ever dropped:
 * every row stays until it is acknowledged, and failures are visible with a
 * manual retry control.
 */
class SyncRepository(private val db: Database) {

    data class SyncStats(
        val pending: Int = 0,
        val inFlight: Int = 0,
        val failed: Int = 0,
        val lastError: String = "",
    ) {
        val isIdle: Boolean get() = pending == 0 && inFlight == 0 && failed == 0
    }

    fun observeQueue(): Flow<List<SyncQueueItem>> = db.observe { sqlite ->
        sqlite.rawQuery("SELECT * FROM sync_queue ORDER BY enqueued_at DESC LIMIT 500", null)
            .use { it.mapRows { toSyncItem() } }
    }

    fun observeStats(): Flow<SyncStats> = db.observe { sqlite ->
        fun count(state: String) = sqlite.rawQuery(
            "SELECT COUNT(*) FROM sync_queue WHERE state=?", arrayOf(state),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val lastError = sqlite.rawQuery(
            "SELECT IFNULL(last_error,'') FROM sync_queue WHERE last_error!='' ORDER BY enqueued_at DESC LIMIT 1",
            null,
        ).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        SyncStats(
            pending = count("PENDING"),
            inFlight = count("IN_FLIGHT"),
            failed = count("FAILED"),
            lastError = lastError,
        )
    }

    suspend fun pending(limit: Int = 200): List<SyncQueueItem> = db.read { sqlite ->
        sqlite.rawQuery(
            "SELECT * FROM sync_queue WHERE state IN ('PENDING','FAILED') ORDER BY enqueued_at LIMIT ?",
            arrayOf(limit.toString()),
        ).use { it.mapRows { toSyncItem() } }
    }

    suspend fun markInFlight(ids: List<String>) {
        if (ids.isEmpty()) return
        db.write { sqlite ->
            val ph = ids.joinToString(",") { "?" }
            sqlite.execSQL(
                "UPDATE sync_queue SET state='IN_FLIGHT' WHERE id IN ($ph)",
                ids.toTypedArray(),
            )
        }
    }

    suspend fun markSucceeded(ids: List<String>) {
        if (ids.isEmpty()) return
        db.write { sqlite ->
            val ph = ids.joinToString(",") { "?" }
            sqlite.delete("sync_queue", "id IN ($ph)", ids.toTypedArray())
        }
    }

    suspend fun markFailed(ids: List<String>, error: String) {
        if (ids.isEmpty()) return
        db.write { sqlite ->
            val ph = ids.joinToString(",") { "?" }
            sqlite.execSQL(
                "UPDATE sync_queue SET state='FAILED', attempts=attempts+1, last_error=?, enqueued_at=? WHERE id IN ($ph)",
                arrayOf(error.take(500), now().toString()) + ids.toTypedArray(),
            )
        }
    }

    /** Manual retry: push failed rows back to pending. */
    suspend fun retryFailed() {
        db.write { sqlite ->
            sqlite.execSQL("UPDATE sync_queue SET state='PENDING', last_error='' WHERE state='FAILED'")
        }
    }

    suspend fun clearAll() {
        db.write { sqlite -> sqlite.delete("sync_queue", null, null) }
    }

    suspend fun countPending(): Int = db.read { sqlite ->
        sqlite.rawQuery("SELECT COUNT(*) FROM sync_queue WHERE state IN ('PENDING','FAILED')", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    suspend fun markDirtyRows(table: String, kindColumn: String = "dirty") = db.write { sqlite ->
        sqlite.execSQL("UPDATE $table SET $kindColumn=1")
    }
}
