package com.lifedeck.app.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.SyncOp
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now

/**
 * Records a pending change for the Google Sheets synchroniser.
 * Called inside the same transaction as the local write so a change can never
 * exist locally without a matching sync row (and vice versa).
 *
 * One pending row per entity: repeated edits collapse into the newest state.
 */
fun enqueueSync(
    db: SQLiteDatabase,
    kind: EntityKind,
    entityId: String,
    op: SyncOp = SyncOp.UPSERT,
) {
    if (op == SyncOp.DELETE) {
        db.delete(
            "sync_queue",
            "entity_type=? AND entity_id=? AND state='PENDING'",
            arrayOf(kind.name, entityId),
        )
    }
    db.delete(
        "sync_queue",
        "entity_type=? AND entity_id=? AND state IN ('PENDING','FAILED')",
        arrayOf(kind.name, entityId),
    )
    val values = ContentValues().apply {
        put("id", newId())
        put("entity_type", kind.name)
        put("entity_id", entityId)
        put("op", op.name)
        put("enqueued_at", now())
        put("attempts", 0)
        put("last_error", "")
        put("state", "PENDING")
    }
    db.insert("sync_queue", null, values)
}

/** Mark a row as dirty inside its own table (kept for direct upserts from sync pull). */
fun ContentValues.markDirty(now: Long = now()): ContentValues {
    put("updated_at", now)
    put("dirty", 1)
    put("version", (getAsInteger("version") ?: 1) + 1)
    return this
}
