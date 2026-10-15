package com.lifedeck.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifedeck.app.LifeDeckApp
import com.lifedeck.app.util.now

/**
 * Periodic background maintenance:
 *  - runs a Google Sheets sync pass when enabled (queue is never discarded),
 *  - creates a scheduled backup when one is due (daily/weekly/monthly),
 *  - re-reconciles alarms as a safety net,
 *  - prunes old snapshots beyond the retention setting.
 */
class MaintenanceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? LifeDeckApp ?: return Result.success()
        val container = app.container
        return try {
            // 1) Sync (only when configured; failures stay queued for retry).
            runCatching { container.syncCoordinator.syncNow() }

            // 2) Scheduled backup.
            val settings = container.settings.get()
            val due = when (settings.backupFrequency.uppercase()) {
                "OFF", "" -> false
                "DAILY" -> now() - settings.lastBackupAt >= 86_400_000L
                "MONTHLY" -> now() - settings.lastBackupAt >= 30L * 86_400_000L
                else -> now() - settings.lastBackupAt >= 7L * 86_400_000L // WEEKLY
            }
            if (due) {
                runCatching {
                    container.backup.createBackup(kind = settings.backupFrequency.uppercase(), note = "Automatic backup")
                    container.backup.pruneBackups(settings.keepBackups)
                    container.settings.update { it.copy(lastBackupAt = now()) }
                }
            }

            // 3) Safety-net alarm reconcile.
            runCatching { container.scheduler.reconcile() }

            Result.success()
        } catch (e: Exception) {
            // Keep retrying; never crash the worker.
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "lifedeck_maintenance"

        fun schedule(context: Context) {
            val app = context.applicationContext as? LifeDeckApp ?: return
            runCatching { app.container.syncCoordinator.schedulePeriodicSync() }
        }
    }
}
