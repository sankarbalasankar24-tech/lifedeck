package com.lifedeck.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.SyncOp
import com.lifedeck.app.data.model.SyncQueueItem
import com.lifedeck.app.data.repo.DocumentMerger
import com.lifedeck.app.data.repo.ExportDocument
import com.lifedeck.app.data.repo.SettingsRepository
import com.lifedeck.app.data.repo.SyncRepository
import com.lifedeck.app.util.decode
import com.lifedeck.app.util.encode
import com.lifedeck.app.util.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Pushes the local change queue to Google Sheets and pulls remote changes back.
 *
 * Guarantees:
 *  - Offline changes queue locally and are never dropped.
 *  - Pushes are idempotent (upsert by stable id) so retries cannot duplicate rows.
 *  - Pulls merge with last-writer-wins, so a conflict never destroys newer data.
 *  - Status, pending counts and last error are always visible to the UI.
 */
class SyncCoordinator(
    private val context: Context,
    private val syncRepo: SyncRepository,
    private val sheets: SheetsClient,
    private val settingsRepo: SettingsRepository,
    private val db: Database,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs by lazy { context.getSharedPreferences("lifedeck_sync", Context.MODE_PRIVATE) }
    private val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString()
            .also { prefs.edit().putString("device_id", it).apply() }

    data class SyncResult(
        val skipped: Boolean = false,
        val pushed: Int = 0,
        val pulled: Int = 0,
        val error: String? = null,
    ) {
        val ok: Boolean get() = !skipped && error == null
    }

    private val _state = MutableStateFlow<SyncResult?>(null)
    val state: StateFlow<SyncResult?> = _state

    /** Periodic background sync + maintenance (15-minute minimum cadence). */
    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            MaintenanceWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun syncAsync() {
        scope.launch { runCatching { syncNow() } }
    }

    /** One full sync pass: push queue → pull changes → persist timestamps. */
    suspend fun syncNow(): SyncResult {
        val s = settingsRepo.get()
        if (!s.syncEnabled || s.syncUrl.isBlank()) {
            return SyncResult(skipped = true).also { _state.value = it }
        }

        var pushed = 0
        var error: String? = null

        // 1) Push every pending change.
        val pending = syncRepo.pending()
        if (pending.isNotEmpty()) {
            val rows = buildPushRows(pending)
            if (rows.isNotEmpty()) {
                val res = sheets.push(rows, deviceId)
                if (res.ok) {
                    pushed = pending.size
                    syncRepo.markSucceeded(pending.map { it.id })
                } else {
                    error = res.error.ifBlank { "Push failed" }
                    syncRepo.markFailed(pending.map { it.id }, error)
                }
            } else {
                syncRepo.markSucceeded(pending.map { it.id })
            }
        }

        // 2) Pull changes made elsewhere (another device, or the sheet itself).
        var pulled = 0
        if (error == null) {
            val since = prefs.getLong("since", 0L)
            val res = sheets.pull(since)
            if (res.ok) {
                if (res.rows.isNotEmpty()) {
                    val doc = rowsToDocument(res.rows)
                    val result = DocumentMerger.merge(db, doc, null, null)
                    pulled = result.inserted + result.updated
                    db.bump()
                }
                prefs.edit().putLong("since", maxOf(res.serverTime, now() - 5_000)).apply()
            } else {
                error = res.error.ifBlank { "Pull failed" }
            }
        }

        // 3) Persist status for the settings screen.
        val finalResult = SyncResult(pushed = pushed, pulled = pulled, error = error)
        _state.value = finalResult
        settingsRepo.update {
            it.copy(
                lastSyncAt = now(),
                lastSyncError = error ?: "",
                lastSyncResult = when {
                    error != null -> error
                    finalResult.skipped -> "Sync disabled"
                    else -> "OK · $pushed pushed · $pulled pulled"
                },
            )
        }
        return finalResult
    }

    /** Pull rows → ExportDocument, parsed per table. */
    private fun rowsToDocument(rows: List<PulledRow>): ExportDocument {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        fun <T> collect(table: String, parse: (JsonElement) -> T?): List<T> =
            rows.filter { it.table == table }.mapNotNull { runCatching { parse(it.row) }.getOrNull() }

        return ExportDocument(
            exportedAt = now(),
            entries = collect(SyncTables.ENTRIES) { decode<com.lifedeck.app.data.model.Entry>(it.toString()) },
            goals = collect(SyncTables.GOALS) { decode<com.lifedeck.app.data.model.Goal>(it.toString()) },
            projects = collect(SyncTables.PROJECTS) { decode<com.lifedeck.app.data.model.Project>(it.toString()) },
            milestones = collect(SyncTables.MILESTONES) { decode<com.lifedeck.app.data.model.Milestone>(it.toString()) },
            trips = collect(SyncTables.TRIPS) { decode<com.lifedeck.app.data.model.ShoppingTrip>(it.toString()) },
            categories = collect(SyncTables.CATEGORIES) { decode<com.lifedeck.app.data.model.Category>(it.toString()) },
            reminders = collect(SyncTables.REMINDERS) { decode<com.lifedeck.app.data.model.ReminderRecord>(it.toString()) },
            memories = collect(SyncTables.MEMORIES) { decode<com.lifedeck.app.data.model.Memory>(it.toString()) },
            history = collect(SyncTables.HISTORY) { decode<com.lifedeck.app.data.model.HistoryEvent>(it.toString()) },
        )
    }

    /** Load current rows for pending queue items and serialize them. */
    private suspend fun buildPushRows(items: List<SyncQueueItem>): List<PushRow> {
        val out = mutableListOf<PushRow>()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

        // One row per entity: keep only the newest queue entry per (table,id).
        val deduped = items
            .sortedBy { it.enqueuedAt }
            .associateBy { "${it.entityType}:${it.entityId}" }
            .values.toList()

        deduped.forEach { item ->
            val table = tableFor(item.entityType) ?: return@forEach
            if (item.op == SyncOp.DELETE) {
                out.add(PushRow(table = table, id = item.entityId, op = "DELETE", row = null))
                return@forEach
            }
            when (item.entityType) {
                EntityKind.ENTRY -> db.read { sqlite ->
                    sqlite.rawQuery("SELECT * FROM entries WHERE id=?", arrayOf(item.entityId)).use { c ->
                        if (c.moveToFirst()) {
                            val e = c.toEntry()
                            out.add(PushRow(table, e.id, "UPSERT", json.parseToJsonElement(encode(e))))
                        }
                    }
                }
                EntityKind.GOAL -> db.read { sqlite ->
                    sqlite.rawQuery("SELECT * FROM goals WHERE id=?", arrayOf(item.entityId)).use { c ->
                        if (c.moveToFirst()) {
                            val g = c.toGoal()
                            out.add(PushRow(table, g.id, "UPSERT", json.parseToJsonElement(encode(g))))
                        }
                    }
                }
                EntityKind.PROJECT -> db.read { sqlite ->
                    sqlite.rawQuery("SELECT * FROM projects WHERE id=?", arrayOf(item.entityId)).use { c ->
                        if (c.moveToFirst()) {
                            val p = c.toProject()
                            out.add(PushRow(table, p.id, "UPSERT", json.parseToJsonElement(encode(p))))
                        }
                    }
                }
                EntityKind.MILESTONE -> db.read { sqlite ->
                    sqlite.rawQuery("SELECT * FROM milestones WHERE id=?", arrayOf(item.entityId)).use { c ->
                        if (c.moveToFirst()) {
                            val m = c.toMilestone()
                            out.add(PushRow(table, m.id, "UPSERT", json.parseToJsonElement(encode(m))))
                        }
                    }
                }
                EntityKind.TRIP -> db.read { sqlite ->
                    sqlite.rawQuery("SELECT * FROM trips WHERE id=?", arrayOf(item.entityId)).use { c ->
                        if (c.moveToFirst()) {
                            val t = c.toTrip()
                            out.add(PushRow(table, t.id, "UPSERT", json.parseToJsonElement(encode(t))))
                        }
                    }
                }
                EntityKind.CATEGORY -> db.read { sqlite ->
                    sqlite.rawQuery("SELECT * FROM categories WHERE id=?", arrayOf(item.entityId)).use { c ->
                        if (c.moveToFirst()) {
                            val cat = c.toCategory()
                            out.add(PushRow(table, cat.id, "UPSERT", json.parseToJsonElement(encode(cat))))
                        }
                    }
                }
                EntityKind.REMINDER -> db.read { sqlite ->
                    sqlite.rawQuery("SELECT * FROM reminders WHERE id=?", arrayOf(item.entityId)).use { c ->
                        if (c.moveToFirst()) {
                            val r = c.toReminder()
                            out.add(PushRow(table, r.id, "UPSERT", json.parseToJsonElement(encode(r))))
                        }
                    }
                }
                EntityKind.MEMORY -> db.read { sqlite ->
                    sqlite.rawQuery("SELECT * FROM memories WHERE id=?", arrayOf(item.entityId)).use { c ->
                        if (c.moveToFirst()) {
                            val m = c.toMemory()
                            out.add(PushRow(table, m.id, "UPSERT", json.parseToJsonElement(encode(m))))
                        }
                    }
                }
                EntityKind.HISTORY -> db.read { sqlite ->
                    sqlite.rawQuery("SELECT * FROM history WHERE id=?", arrayOf(item.entityId)).use { c ->
                        if (c.moveToFirst()) {
                            val h = c.toHistory()
                            out.add(PushRow(table, h.id, "UPSERT", json.parseToJsonElement(encode(h))))
                        }
                    }
                }
                EntityKind.SETTINGS -> Unit // settings never leave the device
            }
        }
        return out
    }

    private fun tableFor(kind: EntityKind): String? = when (kind) {
        EntityKind.ENTRY -> SyncTables.ENTRIES
        EntityKind.GOAL -> SyncTables.GOALS
        EntityKind.PROJECT -> SyncTables.PROJECTS
        EntityKind.MILESTONE -> SyncTables.MILESTONES
        EntityKind.TRIP -> SyncTables.TRIPS
        EntityKind.CATEGORY -> SyncTables.CATEGORIES
        EntityKind.REMINDER -> SyncTables.REMINDERS
        EntityKind.MEMORY -> SyncTables.MEMORIES
        EntityKind.HISTORY -> SyncTables.HISTORY
        EntityKind.SETTINGS -> null
    }

    companion object {
        fun instance(context: Context): SyncCoordinator? =
            (context.applicationContext as? com.lifedeck.app.LifeDeckApp)?.container?.syncCoordinator
    }
}
