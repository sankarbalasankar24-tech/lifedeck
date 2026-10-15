package com.lifedeck.app.data

import android.content.Context
import com.lifedeck.app.data.db.Database
import com.lifedeck.app.data.repo.BackupRepository
import com.lifedeck.app.data.repo.CategoryRepository
import com.lifedeck.app.data.repo.HistoryRepository
import com.lifedeck.app.data.repo.MemoryRepository
import com.lifedeck.app.data.repo.SearchRepository
import com.lifedeck.app.data.repo.SettingsRepository
import com.lifedeck.app.data.repo.ShoppingRepository
import com.lifedeck.app.data.repo.StructureRepository
import com.lifedeck.app.data.repo.SyncRepository
import com.lifedeck.app.data.repo.TaskRepository
import com.lifedeck.app.notify.NotificationChannels
import com.lifedeck.app.notify.ReminderScheduler
import com.lifedeck.app.sync.MaintenanceWorker
import com.lifedeck.app.sync.SheetsClient
import com.lifedeck.app.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container — one instance per process, created in
 * [com.lifedeck.app.LifeDeckApp.onCreate]. Layers are constructed in
 * dependency order so repositories can share the same database handle.
 */
class AppContainer(val context: Context) {

    val database: Database by lazy { Database.getInstance(context) }
    val settings: SettingsRepository by lazy { SettingsRepository(context) }
    val history: HistoryRepository by lazy { HistoryRepository(database) }
    val categories: CategoryRepository by lazy { CategoryRepository(database, history) }
    val tasks: TaskRepository by lazy { TaskRepository(database, history, settings) }
    val structure: StructureRepository by lazy { StructureRepository(database, history, tasks) }
    val shopping: ShoppingRepository by lazy { ShoppingRepository(database, history, tasks) }
    val memories: MemoryRepository by lazy { MemoryRepository(database, history) }
    val search: SearchRepository by lazy { SearchRepository(database) }
    val backup: BackupRepository by lazy { BackupRepository(context, database) }
    val sync: SyncRepository by lazy { SyncRepository(database) }
    val sheets: SheetsClient by lazy { SheetsClient(settings) }
    val syncCoordinator: SyncCoordinator by lazy {
        SyncCoordinator(context, sync, sheets, settings, database)
    }
    val scheduler: ReminderScheduler by lazy { ReminderScheduler(context) }

    fun bootstrap() {
        NotificationChannels.ensure(context)
        scheduler.start()
        MaintenanceWorker.schedule(context)
        // Seed defaults and re-arm alarms; failures here must never block launch.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                categories.ensureDefaults()
                settings.ensureDefaults()
                scheduler.reconcile()
                syncCoordinator.schedulePeriodicSync()
            }
        }
    }
}
