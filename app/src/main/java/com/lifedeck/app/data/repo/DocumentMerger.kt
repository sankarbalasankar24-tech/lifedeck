package com.lifedeck.app.data.repo

import android.database.sqlite.SQLiteDatabase
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.HistoryAction

/**
 * Merges an incoming [ExportDocument] (from a backup restore or a Google
 * Sheets sync pull) into the local database.
 *
 * Conflict rule: stable id + last-writer-wins on `updatedAt`. A row that is
 * newer locally is skipped (and keeps its pending sync state), so a restore or
 * pull can never duplicate a record or resurrect an older edit over a newer one.
 */
object DocumentMerger {

    suspend fun merge(
        db: Database,
        doc: ExportDocument,
        history: HistoryRepository? = null,
        logLabel: String? = null,
    ): ImportResult {
        var result = ImportResult()
        db.write { sqlite ->
            result = mergeInTransaction(sqlite, doc)
            if (history != null && logLabel != null) {
                history.record(
                    sqlite, EntityKind.SETTINGS, "import", HistoryAction.IMPORTED,
                    logLabel, result.message,
                )
            }
        }
        return result
    }

    fun mergeInTransaction(sqlite: SQLiteDatabase, doc: ExportDocument): ImportResult {
        var inserted = 0
        var updated = 0
        var skipped = 0

        fun localTs(table: String, id: String): Long? =
            sqlite.rawQuery("SELECT updated_at FROM $table WHERE id=?", arrayOf(id)).use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }

        fun <T> merge(
            table: String,
            rows: List<T>,
            id: (T) -> String,
            rowUpdated: (T) -> Long,
            write: (T) -> android.content.ContentValues,
        ) {
            rows.forEach { row ->
                val rowId = id(row)
                val local = localTs(table, rowId)
                when {
                    local == null -> {
                        sqlite.insertWithOnConflict(table, null, write(row), SQLiteDatabase.CONFLICT_REPLACE)
                        inserted++
                    }
                    rowUpdated(row) > local -> {
                        sqlite.insertWithOnConflict(table, null, write(row), SQLiteDatabase.CONFLICT_REPLACE)
                        updated++
                    }
                    else -> skipped++
                }
            }
        }

        merge("entries", doc.entries, { it.id }, { it.updatedAt }) { it.copy(dirty = false).toValues() }
        merge("goals", doc.goals, { it.id }, { it.updatedAt }) { it.copy(dirty = false).toValues() }
        merge("projects", doc.projects, { it.id }, { it.updatedAt }) { it.copy(dirty = false).toValues() }
        merge("milestones", doc.milestones, { it.id }, { it.updatedAt }) { it.copy(dirty = false).toValues() }
        merge("trips", doc.trips, { it.id }, { it.updatedAt }) { it.copy(dirty = false).toValues() }
        merge("categories", doc.categories, { it.id }, { it.updatedAt }) { it.copy(dirty = false).toValues() }
        merge("reminders", doc.reminders, { it.id }, { it.updatedAt }) { it.copy(dirty = false).toValues() }
        merge("memories", doc.memories, { it.id }, { it.updatedAt }) { it.copy(dirty = false).toValues() }
        merge("history", doc.history, { it.id }, { it.updatedAt }) { it.copy(dirty = false).toValues() }

        val total = doc.entries.size + doc.goals.size + doc.projects.size + doc.milestones.size +
            doc.trips.size + doc.categories.size + doc.reminders.size + doc.memories.size + doc.history.size
        return ImportResult(inserted, updated, skipped, total)
    }
}
