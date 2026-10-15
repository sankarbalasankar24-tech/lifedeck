package com.lifedeck.app.data.repo

import android.database.sqlite.SQLiteDatabase
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.data.model.Goal
import com.lifedeck.app.data.model.HistoryAction
import com.lifedeck.app.data.model.LifeStatus
import com.lifedeck.app.data.model.Milestone
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.data.model.Project
import com.lifedeck.app.data.model.Progress
import com.lifedeck.app.data.model.SyncOp
import com.lifedeck.app.util.Celebration
import com.lifedeck.app.util.CelebrationBus
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import kotlinx.coroutines.flow.Flow

/**
 * Long-term structure: goals → projects → milestones → actionable entries.
 * Progress always rolls up automatically (see [Rollup]).
 */
class StructureRepository(
    private val db: Database,
    private val history: HistoryRepository,
    val tasks: TaskRepository,
) {

    // ------------------------------------------------------------------ goals

    fun observeGoals(includeDone: Boolean = false): Flow<List<Goal>> = db.observe { sqlite ->
        val where = if (includeDone) "deleted=0" else "deleted=0 AND status!='ARCHIVED'"
        sqlite.rawQuery("SELECT * FROM goals WHERE $where ORDER BY status='DONE', order_index, created_at DESC", null)
            .use { it.mapRows { toGoal() } }
    }

    fun observeGoal(id: String): Flow<Goal?> = db.observe { sqlite ->
        sqlite.rawQuery("SELECT * FROM goals WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toGoal() else null
        }
    }

    suspend fun goal(id: String): Goal? = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM goals WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toGoal() else null
        }
    }

    suspend fun createGoal(goal: Goal): String {
        val id = goal.id.ifBlank { newId() }
        db.write { sqlite ->
            val ts = now()
            val stored = goal.copy(id = id, createdAt = ts, updatedAt = ts, dirty = true, version = 1)
            sqlite.insertWithOnConflict("goals", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.GOAL, id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.GOAL, id, HistoryAction.CREATED, stored.title, "Goal created")
        }
        return id
    }

    suspend fun updateGoal(goal: Goal, summary: String = "Goal updated") {
        db.write { sqlite ->
            val ts = now()
            val wasDone = existingGoalStatus(sqlite, goal.id) == LifeStatus.DONE.name
            val stored = goal.copy(updatedAt = ts, version = goal.version + 1, dirty = true)
            sqlite.insertWithOnConflict("goals", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.GOAL, stored.id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.GOAL, stored.id, HistoryAction.EDITED, stored.title, summary)
            if (!wasDone && stored.status == LifeStatus.DONE) {
                completeGoalSideEffects(sqlite, stored)
            }
        }
    }

    suspend fun deleteGoal(id: String) {
        db.write { sqlite ->
            val ts = now()
            sqlite.execSQL(
                "UPDATE goals SET deleted=1, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, id),
            )
            // Children stay in history; their links are preserved for restore.
            sqlite.execSQL(
                "UPDATE projects SET deleted=1, updated_at=?, dirty=1, version=version+1 WHERE goal_id=?",
                arrayOf<Any>(ts, id),
            )
            enqueueSync(sqlite, EntityKind.GOAL, id, SyncOp.DELETE)
            history.record(sqlite, EntityKind.GOAL, id, HistoryAction.DELETED, "Goal", "Deleted")
        }
    }

    suspend fun progressForGoal(id: String): Progress = db.read { Rollup.goalProgress(it, id) }

    /** Live progress that recomputes whenever any linked row changes. */
    fun observeGoalProgress(goalId: String): Flow<Progress> =
        db.observe { Rollup.goalProgress(it, goalId) }

    fun observeProjectProgress(projectId: String): Flow<Progress> =
        db.observe { Rollup.projectProgress(it, projectId) }

    fun observeMilestoneProgress(milestoneId: String): Flow<Progress> =
        db.observe { Rollup.milestoneProgress(it, milestoneId) }

    // --------------------------------------------------------------- projects

    fun observeProjects(goalId: String? = null, includeDone: Boolean = false): Flow<List<Project>> =
        db.observe { sqlite ->
            val filters = mutableListOf("deleted=0")
            if (!includeDone) filters += "status!='ARCHIVED'"
            goalId?.let { filters += "goal_id=?" }
            val args = goalId?.let { arrayOf(it) } ?: emptyArray()
            sqlite.rawQuery(
                "SELECT * FROM projects WHERE ${filters.joinToString(" AND ")} ORDER BY status='DONE', order_index, created_at DESC",
                args,
            ).use { it.mapRows { toProject() } }
        }

    fun observeProject(id: String): Flow<Project?> = db.observe { sqlite ->
        sqlite.rawQuery("SELECT * FROM projects WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toProject() else null
        }
    }

    suspend fun project(id: String): Project? = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM projects WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toProject() else null
        }
    }

    suspend fun createProject(project: Project): String {
        val id = project.id.ifBlank { newId() }
        db.write { sqlite ->
            val ts = now()
            val stored = project.copy(id = id, createdAt = ts, updatedAt = ts, dirty = true, version = 1)
            sqlite.insertWithOnConflict("projects", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.PROJECT, id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.PROJECT, id, HistoryAction.CREATED, stored.title, "Project created")
        }
        return id
    }

    suspend fun updateProject(project: Project, summary: String = "Project updated") {
        db.write { sqlite ->
            val ts = now()
            val wasDone = existingStatus(sqlite, "projects", project.id) == LifeStatus.DONE.name
            val stored = project.copy(updatedAt = ts, version = project.version + 1, dirty = true)
            sqlite.insertWithOnConflict("projects", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.PROJECT, stored.id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.PROJECT, stored.id, HistoryAction.EDITED, stored.title, summary)
            if (!wasDone && stored.status == LifeStatus.DONE) {
                history.record(sqlite, EntityKind.PROJECT, stored.id, HistoryAction.PROJECT_DONE, stored.title, "Marked complete")
                CelebrationBus.emit(Celebration(Celebration.Kind.PROJECT, "Project complete 🎉", "\"${stored.title}\" is finished", 100))
            }
        }
    }

    suspend fun deleteProject(id: String) {
        db.write { sqlite ->
            val ts = now()
            sqlite.execSQL(
                "UPDATE projects SET deleted=1, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, id),
            )
            sqlite.execSQL(
                "UPDATE milestones SET deleted=1, updated_at=?, dirty=1, version=version+1 WHERE project_id=?",
                arrayOf<Any>(ts, id),
            )
            enqueueSync(sqlite, EntityKind.PROJECT, id, SyncOp.DELETE)
            history.record(sqlite, EntityKind.PROJECT, id, HistoryAction.DELETED, "Project", "Deleted")
        }
    }

    suspend fun progressForProject(id: String): Progress = db.read { Rollup.projectProgress(it, id) }

    // ------------------------------------------------------------- milestones

    fun observeMilestones(projectId: String, includeDone: Boolean = true): Flow<List<Milestone>> =
        db.observe { sqlite ->
            sqlite.rawQuery(
                "SELECT * FROM milestones WHERE deleted=0 AND project_id=? ORDER BY status='DONE', order_index, created_at",
                arrayOf(projectId),
            ).use { it.mapRows { toMilestone() } }
        }

    fun observeMilestone(id: String): Flow<Milestone?> = db.observe { sqlite ->
        sqlite.rawQuery("SELECT * FROM milestones WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toMilestone() else null
        }
    }

    suspend fun milestone(id: String): Milestone? = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM milestones WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toMilestone() else null
        }
    }

    suspend fun createMilestone(milestone: Milestone): String {
        val id = milestone.id.ifBlank { newId() }
        db.write { sqlite ->
            val ts = now()
            val stored = milestone.copy(id = id, createdAt = ts, updatedAt = ts, dirty = true, version = 1)
            sqlite.insertWithOnConflict("milestones", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.MILESTONE, id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.MILESTONE, id, HistoryAction.CREATED, stored.title, "Milestone created")
        }
        return id
    }

    suspend fun updateMilestone(milestone: Milestone, summary: String = "Milestone updated") {
        db.write { sqlite ->
            val ts = now()
            val stored = milestone.copy(updatedAt = ts, version = milestone.version + 1, dirty = true)
            sqlite.insertWithOnConflict("milestones", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.MILESTONE, stored.id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.MILESTONE, stored.id, HistoryAction.EDITED, stored.title, summary)
        }
    }

    suspend fun deleteMilestone(id: String) {
        db.write { sqlite ->
            val ts = now()
            sqlite.execSQL(
                "UPDATE milestones SET deleted=1, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, id),
            )
            enqueueSync(sqlite, EntityKind.MILESTONE, id, SyncOp.DELETE)
            history.record(sqlite, EntityKind.MILESTONE, id, HistoryAction.DELETED, "Milestone", "Deleted")
        }
    }

    suspend fun progressForMilestone(id: String): Progress = db.read { Rollup.milestoneProgress(it, id) }

    // ------------------------------------------------------------ next action

    /**
     * The recommended next step inside a goal: earliest-due open entry that is
     * not blocked by an unfinished dependency, preferring overdue work.
     */
    suspend fun nextActionForGoal(goalId: String): Entry? = db.read { sqlite ->
        val rows = sqlite.rawQuery(
            """SELECT * FROM entries
               WHERE deleted=0 AND status IN ('ACTIVE','MISSED','INBOX') AND goal_id=?
               ORDER BY (due_at IS NULL), due_at, priority, order_index""",
            arrayOf(goalId),
        ).use { it.mapRows { toEntry() } }
        rows.firstOrNull { entry ->
            entry.dependencyIds.none { dep -> isOpenEntry(sqlite, dep) }
        }
    }

    suspend fun overdueStepsInGoal(goalId: String): List<Entry> = db.read { sqlite ->
        val now = now()
        sqlite.rawQuery(
            """SELECT * FROM entries
               WHERE deleted=0 AND status IN ('ACTIVE','MISSED') AND goal_id=?
                 AND due_at IS NOT NULL AND due_at < ?
               ORDER BY due_at""",
            arrayOf(goalId, now.toString()),
        ).use { it.mapRows { toEntry() } }
    }

    /** Goals whose target date is within [days] and are not finished. */
    fun observeGoalsDueWithin(days: Int): Flow<List<Goal>> = db.observe { sqlite ->
        val from = now()
        val to = from + days * 86_400_000L
        sqlite.rawQuery(
            "SELECT * FROM goals WHERE deleted=0 AND status='ACTIVE' AND target_date IS NOT NULL AND target_date BETWEEN ? AND ? ORDER BY target_date",
            arrayOf(from.toString(), to.toString()),
        ).use { it.mapRows { toGoal() } }
    }

    suspend fun allGoalsForSync(): List<Goal> = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM goals ORDER BY created_at", null).use { it.mapRows { toGoal() } }
    }

    suspend fun allProjectsForSync(): List<Project> = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM projects ORDER BY created_at", null).use { it.mapRows { toProject() } }
    }

    suspend fun allMilestonesForSync(): List<Milestone> = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM milestones ORDER BY created_at", null).use { it.mapRows { toMilestone() } }
    }

    // ----------------------------------------------------------------- private

    private fun isOpenEntry(sqlite: SQLiteDatabase, id: String): Boolean =
        sqlite.rawQuery(
            "SELECT status FROM entries WHERE id=? AND deleted=0",
            arrayOf(id),
        ).use { c ->
            c.moveToFirst() && c.getString(0) in listOf(EntryStatus.INBOX.name, EntryStatus.ACTIVE.name, EntryStatus.MISSED.name)
        }

    private fun existingStatus(sqlite: SQLiteDatabase, table: String, id: String): String? =
        sqlite.rawQuery("SELECT status FROM $table WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun existingGoalStatus(sqlite: SQLiteDatabase, id: String): String? =
        existingStatus(sqlite, "goals", id)

    private fun completeGoalSideEffects(sqlite: SQLiteDatabase, goal: Goal) {
        history.record(sqlite, EntityKind.GOAL, goal.id, HistoryAction.GOAL_DONE, goal.title, "Goal achieved")
        CelebrationBus.emit(Celebration(Celebration.Kind.GOAL, "Goal achieved 🌟", "\"${goal.title}\" — you did it!", 100))
    }
}

/** Standard structure scaffold for a new multi-step project. */
fun defaultMilestones(projectId: String, titles: List<String>): List<Milestone> {
    val ts = now()
    return titles.mapIndexed { index, title ->
        Milestone(
            id = newId(),
            projectId = projectId,
            title = title,
            orderIndex = index + 1,
            createdAt = ts,
            updatedAt = ts,
        )
    }
}

/** Create actionable entries under a milestone in one go. */
suspend fun StructureRepository.addSteps(
    projectId: String,
    milestoneId: String?,
    goalId: String?,
    titles: List<String>,
    priority: Priority = Priority.NORMAL,
): List<String> = titles.mapIndexed { index, title ->
    tasks.create(
        Entry(
            title = title,
            status = EntryStatus.ACTIVE,
            priority = priority,
            projectId = projectId,
            milestoneId = milestoneId,
            goalId = goalId,
            orderIndex = index + 1,
            type = com.lifedeck.app.data.model.EntryType.PROJECT_STEP,
        ),
        summary = "Step added",
    )
}
