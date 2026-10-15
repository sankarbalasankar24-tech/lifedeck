package com.lifedeck.app.data.repo

import android.content.Context
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.BackupRecord
import com.lifedeck.app.data.model.Category
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.Goal
import com.lifedeck.app.data.model.HistoryAction
import com.lifedeck.app.data.model.HistoryEvent
import com.lifedeck.app.data.model.Memory
import com.lifedeck.app.data.model.Milestone
import com.lifedeck.app.data.model.Project
import com.lifedeck.app.data.model.ReminderRecord
import com.lifedeck.app.data.model.ShoppingTrip
import com.lifedeck.app.util.encode
import com.lifedeck.app.util.decode
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.io.File

/** Complete snapshot of the database — the export/import + backup format. */
@Serializable
data class ExportDocument(
    val schemaVersion: Int = 1,
    val app: String = "LifeDeck",
    val exportedAt: Long = 0,
    val entries: List<Entry> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val projects: List<Project> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
    val trips: List<ShoppingTrip> = emptyList(),
    val categories: List<Category> = emptyList(),
    val reminders: List<ReminderRecord> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val history: List<HistoryEvent> = emptyList(),
)

data class ImportResult(
    val inserted: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val total: Int = 0,
) {
    val message: String
        get() = "$inserted new · $updated updated · $skipped unchanged of $total"
}

/**
 * Local backups: JSON snapshots on disk (never syncing secrets), plus a full
 * export/import path for moving data between devices.
 *
 * Import merges by stable id with last-writer-wins, so restoring never
 * duplicates records or resurrects newer local edits.
 */
class BackupRepository(
    private val context: Context,
    private val db: Database,
) {
    private val backupDir: File
        get() = File(context.filesDir, "backups").apply { mkdirs() }

    fun observeBackups(): Flow<List<BackupRecord>> = db.observe { sqlite ->
        sqlite.rawQuery("SELECT * FROM backups ORDER BY created_at DESC LIMIT 200", null)
            .use { it.mapRows { toBackup() } }
    }

    // --------------------------------------------------------------- export

    suspend fun exportDocument(): ExportDocument {
        val entries = db.read { it.rawQuery("SELECT * FROM entries", null).use { c -> c.mapRows { toEntry() } } }
        val goals = db.read { it.rawQuery("SELECT * FROM goals", null).use { c -> c.mapRows { toGoal() } } }
        val projects = db.read { it.rawQuery("SELECT * FROM projects", null).use { c -> c.mapRows { toProject() } } }
        val milestones = db.read { it.rawQuery("SELECT * FROM milestones", null).use { c -> c.mapRows { toMilestone() } } }
        val trips = db.read { it.rawQuery("SELECT * FROM trips", null).use { c -> c.mapRows { toTrip() } } }
        val categories = db.read { it.rawQuery("SELECT * FROM categories", null).use { c -> c.mapRows { toCategory() } } }
        val reminders = db.read { it.rawQuery("SELECT * FROM reminders", null).use { c -> c.mapRows { toReminder() } } }
        val memories = db.read { it.rawQuery("SELECT * FROM memories", null).use { c -> c.mapRows { toMemory() } } }
        val history = db.read { it.rawQuery("SELECT * FROM history", null).use { c -> c.mapRows { toHistory() } } }
        return ExportDocument(
            exportedAt = now(),
            entries = entries, goals = goals, projects = projects, milestones = milestones,
            trips = trips, categories = categories, reminders = reminders,
            memories = memories, history = history,
        )
    }

    suspend fun exportJson(): String = encode(exportDocument())

    /** CSV of every entry — handy for spreadsheet review or re-import elsewhere. */
    suspend fun exportCsv(): String {
        val rows = db.read { it.rawQuery("SELECT * FROM entries ORDER BY created_at", null).use { c -> c.mapRows { toEntry() } } }
        val sb = StringBuilder()
        sb.appendLine("id,type,status,title,notes,priority,due_at,scheduled_at,completed_at,project_id,goal_id,category_id,created_at,updated_at")
        rows.forEach { e ->
            sb.append(csv(e.id)).append(',').append(csv(e.type.name)).append(',')
                .append(csv(e.status.name)).append(',').append(csv(e.title)).append(',')
                .append(csv(e.notes.replace('\n', ' '))).append(',')
                .append(e.priority.name).append(',')
                .append(e.dueAt ?: "").append(',').append(e.scheduledAt ?: "").append(',')
                .append(e.completedAt ?: "").append(',')
                .append(csv(e.projectId ?: "")).append(',').append(csv(e.goalId ?: "")).append(',')
                .append(csv(e.categoryId ?: "")).append(',')
                .append(e.createdAt).append(',').append(e.updatedAt).appendLine()
        }
        return sb.toString()
    }

    // --------------------------------------------------------------- import

    suspend fun importJson(text: String, historyLog: HistoryRepository? = null): ImportResult {
        val doc = decode<ExportDocument>(text) ?: throw IllegalArgumentException("Not a LifeDeck backup file")
        return DocumentMerger.merge(db, doc, historyLog, "Backup restored")
    }

    // --------------------------------------------------------------- snapshots

    /** Write a timestamped JSON snapshot into app-private storage. */
    suspend fun createBackup(kind: String, note: String = ""): BackupRecord {
        val doc = exportDocument()
        val json = encode(doc)
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(now()))
        val fileName = "lifedeck-$kind-$stamp.json"
        val file = File(backupDir, fileName)
        file.writeText(json)

        val record = BackupRecord(
            id = newId(),
            createdAt = now(),
            kind = kind,
            fileName = fileName,
            sizeBytes = file.length(),
            rowCount = doc.entries.size + doc.goals.size + doc.projects.size + doc.milestones.size + doc.history.size,
            note = note,
        )
        db.write { sqlite ->
            sqlite.insert("backups", null, android.content.ContentValues().apply {
                put("id", record.id)
                put("created_at", record.createdAt)
                put("kind", record.kind)
                put("file_name", record.fileName)
                put("size_bytes", record.sizeBytes)
                put("row_count", record.rowCount)
                put("note", record.note)
            })
        }
        return record
    }

    suspend fun readBackup(fileName: String): String? {
        val file = File(backupDir, fileName)
        return if (file.exists()) file.readText() else null
    }

    suspend fun restoreBackup(fileName: String, history: HistoryRepository): ImportResult {
        val json = readBackup(fileName) ?: throw IllegalArgumentException("Backup not found: $fileName")
        return importJson(json, history)
    }

    suspend fun deleteBackup(fileName: String): Boolean {
        val removed = File(backupDir, fileName).delete()
        db.write { sqlite -> sqlite.delete("backups", "file_name=?", arrayOf(fileName)) }
        return removed
    }

    /** Delete the oldest snapshots beyond [keep] (local storage hygiene). */
    suspend fun pruneBackups(keep: Int) {
        val backups = db.read { sqlite ->
            sqlite.rawQuery("SELECT * FROM backups ORDER BY created_at DESC", null).use { it.mapRows { toBackup() } }
        }
        if (backups.size <= keep) return
        backups.drop(keep).forEach { deleteBackup(it.fileName) }
    }

    suspend fun allBackups(): List<BackupRecord> = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM backups ORDER BY created_at DESC", null).use { it.mapRows { toBackup() } }
    }

    /** Directory used by the share/export sheet (FileProvider path: internal_files). */
    fun backupDirectory(): File = backupDir

    private fun csv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(',') || escaped.contains('"') || escaped.contains('\n')) {
            "\"$escaped\""
        } else escaped
    }
}
