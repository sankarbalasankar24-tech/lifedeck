package com.lifedeck.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/**
 * Local-first database. Everything the app does is read/written here first;
 * Google Sheets sync is a downstream copy queued from the same transactions.
 *
 * Reactivity: every successful write bumps [changes]; observers re-query.
 */
class Database private constructor(context: Context) :
    SQLiteOpenHelper(context, NAME, null, VERSION) {

    private val _changes = MutableStateFlow(0L)

    /** Emits after every committed write. Emit the current value first on collection. */
    val changes: Flow<Long> = _changes

    private val io = Dispatchers.IO

    /** Run a read on the IO dispatcher and observe it, re-running on every write. */
    fun <T> observe(block: (SQLiteDatabase) -> T): Flow<T> = _changes
        .mapLatest { block(readableDatabase) }
        .distinctUntilChanged()
        .flowOn(io)

    /** Run a read once, on the IO dispatcher. */
    suspend fun <T> read(block: (SQLiteDatabase) -> T): T = withContext(io) {
        block(readableDatabase)
    }

    /** Run a write inside one transaction, then notify observers. */
    suspend fun write(block: (SQLiteDatabase) -> Unit): Unit = withContext(io) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _changes.value = _changes.value + 1
    }

    /** Bump observers without changing data (e.g. after an external re-sync). */
    fun bump() {
        _changes.value = _changes.value + 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations are additive by convention; see docs/ARCHITECTURE.md.
        if (oldVersion < 2) {
            // Reserved for future schema evolution.
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never destroy user data on a downgrade; the app fails loudly instead.
        throw IllegalStateException("Database downgraded from $oldVersion to $newVersion")
    }

    companion object {
        private const val NAME = "lifedeck.db"
        private const val VERSION = 1

        @Volatile
        private var instance: Database? = null

        fun getInstance(context: Context): Database =
            instance ?: synchronized(this) {
                instance ?: Database(context.applicationContext).also { instance = it }
            }

        private val SCHEMA = listOf(
            """
            CREATE TABLE entries (
                id TEXT PRIMARY KEY NOT NULL,
                type TEXT NOT NULL,
                status TEXT NOT NULL,
                title TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                category_id TEXT,
                priority INTEGER NOT NULL DEFAULT 2,
                due_at INTEGER,
                scheduled_at INTEGER,
                all_day INTEGER NOT NULL DEFAULT 1,
                start_at INTEGER,
                end_at INTEGER,
                location TEXT NOT NULL DEFAULT '',
                recurrence TEXT,
                estimate_minutes INTEGER,
                parent_id TEXT,
                project_id TEXT,
                milestone_id TEXT,
                goal_id TEXT,
                trip_id TEXT,
                dependency_ids TEXT NOT NULL DEFAULT '[]',
                checklist TEXT NOT NULL DEFAULT '[]',
                order_index INTEGER NOT NULL DEFAULT 0,
                favorite INTEGER NOT NULL DEFAULT 0,
                photos TEXT NOT NULL DEFAULT '[]',
                context_hint TEXT,
                quantity TEXT,
                budget REAL,
                price_paid REAL,
                store TEXT,
                purchased_at INTEGER,
                bucket TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                completed_at INTEGER,
                rescheduled_count INTEGER NOT NULL DEFAULT 0,
                version INTEGER NOT NULL DEFAULT 1,
                dirty INTEGER NOT NULL DEFAULT 1,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_entries_status ON entries(status)",
            "CREATE INDEX idx_entries_due ON entries(due_at)",
            "CREATE INDEX idx_entries_scheduled ON entries(scheduled_at)",
            "CREATE INDEX idx_entries_start ON entries(start_at)",
            "CREATE INDEX idx_entries_project ON entries(project_id)",
            "CREATE INDEX idx_entries_milestone ON entries(milestone_id)",
            "CREATE INDEX idx_entries_goal ON entries(goal_id)",
            "CREATE INDEX idx_entries_parent ON entries(parent_id)",
            "CREATE INDEX idx_entries_trip ON entries(trip_id)",
            "CREATE INDEX idx_entries_type ON entries(type)",
            "CREATE INDEX idx_entries_dirty ON entries(dirty)",
            """
            CREATE TABLE goals (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                category_id TEXT,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                priority INTEGER NOT NULL DEFAULT 2,
                start_date INTEGER,
                target_date INTEGER,
                cost_estimate REAL,
                cost_spent REAL NOT NULL DEFAULT 0,
                icon TEXT NOT NULL DEFAULT '',
                favorite INTEGER NOT NULL DEFAULT 0,
                order_index INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                completed_at INTEGER,
                version INTEGER NOT NULL DEFAULT 1,
                dirty INTEGER NOT NULL DEFAULT 1,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_goals_dirty ON goals(dirty)",
            """
            CREATE TABLE projects (
                id TEXT PRIMARY KEY NOT NULL,
                goal_id TEXT,
                title TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                priority INTEGER NOT NULL DEFAULT 2,
                start_date INTEGER,
                due_date INTEGER,
                cost_estimate REAL,
                order_index INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                completed_at INTEGER,
                version INTEGER NOT NULL DEFAULT 1,
                dirty INTEGER NOT NULL DEFAULT 1,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_projects_goal ON projects(goal_id)",
            "CREATE INDEX idx_projects_dirty ON projects(dirty)",
            """
            CREATE TABLE milestones (
                id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                title TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                target_date INTEGER,
                cost_estimate REAL,
                order_index INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                completed_at INTEGER,
                version INTEGER NOT NULL DEFAULT 1,
                dirty INTEGER NOT NULL DEFAULT 1,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_milestones_project ON milestones(project_id)",
            "CREATE INDEX idx_milestones_dirty ON milestones(dirty)",
            """
            CREATE TABLE trips (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                store TEXT NOT NULL DEFAULT '',
                planned_at INTEGER,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                budget REAL,
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                completed_at INTEGER,
                version INTEGER NOT NULL DEFAULT 1,
                dirty INTEGER NOT NULL DEFAULT 1,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE categories (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                color INTEGER NOT NULL DEFAULT 6206546,
                icon TEXT NOT NULL DEFAULT 'label',
                order_index INTEGER NOT NULL DEFAULT 0,
                built_in INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                dirty INTEGER NOT NULL DEFAULT 1,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE reminders (
                id TEXT PRIMARY KEY NOT NULL,
                entry_id TEXT NOT NULL,
                fire_at INTEGER NOT NULL,
                lead_minutes INTEGER NOT NULL DEFAULT 0,
                repeat TEXT,
                escalation_interval_minutes INTEGER,
                escalation_max_count INTEGER NOT NULL DEFAULT 0,
                escalation_fired INTEGER NOT NULL DEFAULT 0,
                origin TEXT NOT NULL DEFAULT 'AUTO',
                sound_enabled INTEGER NOT NULL DEFAULT 1,
                vibrate_enabled INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL DEFAULT 'PENDING',
                snoozed_until INTEGER,
                last_fired_at INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                dirty INTEGER NOT NULL DEFAULT 1,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_reminders_entry ON reminders(entry_id)",
            "CREATE INDEX idx_reminders_status ON reminders(status)",
            "CREATE INDEX idx_reminders_fire ON reminders(fire_at)",
            """
            CREATE TABLE history (
                id TEXT PRIMARY KEY NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                action TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL DEFAULT '',
                occurred_at INTEGER NOT NULL,
                details TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                dirty INTEGER NOT NULL DEFAULT 1,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_history_entity ON history(entity_id)",
            "CREATE INDEX idx_history_time ON history(occurred_at)",
            """
            CREATE TABLE memories (
                id TEXT PRIMARY KEY NOT NULL,
                entry_id TEXT,
                goal_id TEXT,
                milestone_id TEXT,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT '',
                occurred_at INTEGER NOT NULL,
                photos TEXT NOT NULL DEFAULT '[]',
                remind_six_month INTEGER NOT NULL DEFAULT 0,
                remind_yearly INTEGER NOT NULL DEFAULT 1,
                next_reminder_at INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                dirty INTEGER NOT NULL DEFAULT 1,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX idx_memories_time ON memories(occurred_at)",
            """
            CREATE TABLE sync_queue (
                id TEXT PRIMARY KEY NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                op TEXT NOT NULL,
                enqueued_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                state TEXT NOT NULL DEFAULT 'PENDING'
            )
            """.trimIndent(),
            "CREATE INDEX idx_sync_state ON sync_queue(state)",
            """
            CREATE TABLE backups (
                id TEXT PRIMARY KEY NOT NULL,
                created_at INTEGER NOT NULL,
                kind TEXT NOT NULL,
                file_name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                row_count INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
            """
            CREATE TABLE meta (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
    }
}
