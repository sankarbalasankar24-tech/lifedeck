package com.lifedeck.app.data.repo

import android.database.sqlite.SQLiteDatabase
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.HistoryAction
import com.lifedeck.app.data.model.LifeStatus
import com.lifedeck.app.data.model.Progress
import com.lifedeck.app.util.Celebration
import com.lifedeck.app.util.CelebrationBus
import com.lifedeck.app.util.now

/**
 * Derived progress: completing one item rolls up into its milestone, project
 * and goal automatically. Nothing is stored redundantly — percentages are
 * computed from live rows, so undo and edits stay consistent.
 *
 * All functions run inside an open write transaction.
 */
object Rollup {

    /**
     * Entry completion flag: an entry counts as done when it is DONE, or when
     * every one of its subtasks is done (a parent finishes with its children).
     */
    private const val DONE_FLAG =
        "CASE WHEN e.status='DONE' THEN 1 " +
            "WHEN (SELECT COUNT(*) FROM entries c WHERE c.parent_id=e.id AND c.deleted=0 AND c.status!='DONE')>0 THEN 0 " +
            "WHEN (SELECT COUNT(*) FROM entries c WHERE c.parent_id=e.id AND c.deleted=0)>0 THEN 1 " +
            "ELSE 0 END"

    /** Progress of every entry directly linked to a column value. */
    fun entryProgress(db: SQLiteDatabase, column: String, id: String, now: Long = now()): Progress {
        val sql = """
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN $DONE_FLAG THEN 1 ELSE 0 END) AS done,
                   SUM(CASE WHEN e.due_at IS NOT NULL AND e.due_at < ? AND $DONE_FLAG=0 THEN 1 ELSE 0 END) AS overdue
            FROM entries e
            WHERE e.deleted=0 AND e.status!='ARCHIVED' AND e.$column=?
        """.trimIndent()
        db.rawQuery(sql, arrayOf(now.toString(), id)).use { c ->
            if (c.moveToFirst()) {
                return Progress(
                    entityId = id,
                    total = c.getInt(0),
                    done = c.getInt(1),
                    overdue = c.getInt(2),
                )
            }
        }
        return Progress(id, 0, 0)
    }

    fun milestoneProgress(db: SQLiteDatabase, milestoneId: String, now: Long = now()): Progress =
        entryProgress(db, "milestone_id", milestoneId, now)

    /** A project's progress counts its direct entries plus its milestones. */
    fun projectProgress(db: SQLiteDatabase, projectId: String, now: Long = now()): Progress {
        val entries = entryProgress(db, "project_id", projectId, now)
        var total = entries.total
        var done = entries.done
        var overdue = entries.overdue
        var mTotal = 0
        var mDone = 0
        db.rawQuery(
            """SELECT COUNT(*), SUM(CASE WHEN status='DONE' THEN 1 ELSE 0 END)
               FROM milestones WHERE deleted=0 AND project_id=?""",
            arrayOf(projectId),
        ).use { c ->
            if (c.moveToFirst()) {
                mTotal = c.getInt(0)
                mDone = c.getInt(1)
            }
        }
        total += mTotal
        done += mDone
        overdue += countOverdueMilestones(db, projectId, now)
        return Progress(
            entityId = projectId, total = total, done = done, overdue = overdue,
            milestonesTotal = mTotal, milestonesDone = mDone,
        )
    }

    /** A goal's progress counts its entries, its milestones and its projects. */
    fun goalProgress(db: SQLiteDatabase, goalId: String, now: Long = now()): Progress {
        val entries = entryProgress(db, "goal_id", goalId, now)
        var total = entries.total
        var done = entries.done
        var overdue = entries.overdue

        var mTotal = 0
        var mDone = 0
        db.rawQuery(
            """SELECT COUNT(*), SUM(CASE WHEN m.status='DONE' THEN 1 ELSE 0 END)
               FROM milestones m
               WHERE m.deleted=0 AND m.project_id IN (SELECT id FROM projects WHERE deleted=0 AND goal_id=?)""",
            arrayOf(goalId),
        ).use { c ->
            if (c.moveToFirst()) { mTotal = c.getInt(0); mDone = c.getInt(1) }
        }
        var pTotal = 0
        var pDone = 0
        db.rawQuery(
            """SELECT COUNT(*), SUM(CASE WHEN status='DONE' THEN 1 ELSE 0 END)
               FROM projects WHERE deleted=0 AND goal_id=?""",
            arrayOf(goalId),
        ).use { c ->
            if (c.moveToFirst()) { pTotal = c.getInt(0); pDone = c.getInt(1) }
        }
        total += mTotal + pTotal
        done += mDone + pDone
        overdue += countOverdueMilestonesInGoal(db, goalId, now)
        return Progress(
            entityId = goalId, total = total, done = done, overdue = overdue,
            milestonesTotal = mTotal, milestonesDone = mDone,
        )
    }

    private fun countOverdueMilestones(db: SQLiteDatabase, projectId: String, now: Long): Int =
        db.rawQuery(
            """SELECT COUNT(*) FROM milestones
               WHERE deleted=0 AND status!='DONE' AND project_id=? AND target_date IS NOT NULL AND target_date<?""",
            arrayOf(projectId, now.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun countOverdueMilestonesInGoal(db: SQLiteDatabase, goalId: String, now: Long): Int =
        db.rawQuery(
            """SELECT COUNT(*) FROM milestones
               WHERE deleted=0 AND status!='DONE' AND target_date IS NOT NULL AND target_date<?
                 AND project_id IN (SELECT id FROM projects WHERE deleted=0 AND goal_id=?)""",
            arrayOf(now.toString(), goalId),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * After an entry changed, decide whether its milestone/project/goal just
     * completed (or reopened), log it and celebrate the transition.
     * Returns celebrations to display.
     */
    fun afterEntryChanged(
        db: SQLiteDatabase,
        entry: Entry,
        celebrate: Boolean,
        sound: Boolean,
    ): List<Celebration> {
        val events = mutableListOf<Celebration>()
        entry.milestoneId?.let { id ->
            syncMilestone(db, id, celebrate, sound, events)
        }
        (listOfNotNull(entry.projectId) + projectIdsOf(db, entry)).distinct().forEach { pid ->
            syncProject(db, pid, celebrate, sound, events)
        }
        (listOfNotNull(entry.goalId) + goalIdsOf(db, entry)).distinct().forEach { gid ->
            syncGoal(db, gid, celebrate, sound, events)
        }
        return events
    }

    private fun projectIdsOf(db: SQLiteDatabase, entry: Entry): List<String> {
        val ids = mutableListOf<String>()
        entry.milestoneId?.let { mid ->
            db.rawQuery("SELECT project_id FROM milestones WHERE id=?", arrayOf(mid)).use { c ->
                if (c.moveToFirst()) ids.add(c.getString(0))
            }
        }
        return ids
    }

    private fun goalIdsOf(db: SQLiteDatabase, entry: Entry): List<String> {
        val ids = mutableListOf<String>()
        entry.projectId?.let { pid ->
            db.rawQuery("SELECT goal_id FROM projects WHERE id=?", arrayOf(pid)).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) ids.add(c.getString(0))
            }
        }
        entry.milestoneId?.let { mid ->
            db.rawQuery(
                "SELECT p.goal_id FROM milestones m JOIN projects p ON p.id=m.project_id WHERE m.id=?",
                arrayOf(mid),
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) ids.add(c.getString(0)) }
        }
        return ids
    }

    private fun syncMilestone(
        db: SQLiteDatabase,
        milestoneId: String,
        celebrate: Boolean,
        sound: Boolean,
        events: MutableList<Celebration>,
    ) {
        val progress = milestoneProgress(db, milestoneId)
        if (progress.total == 0) return
        val current = db.rawQuery("SELECT status, title, project_id FROM milestones WHERE id=?", arrayOf(milestoneId))
            .use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getString(2)) else null } ?: return
        val shouldBeDone = progress.isComplete
        val isDone = current.first == LifeStatus.DONE.name
        if (shouldBeDone && !isDone) {
            val ts = now()
            db.execSQL(
                "UPDATE milestones SET status='DONE', completed_at=?, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, ts, milestoneId),
            )
            HistoryRepositoryLog.record(db, EntityKind.MILESTONE, milestoneId, HistoryAction.MILESTONE_DONE, current.second, "Milestone complete")
            if (celebrate) {
                val ev = Celebration(Celebration.Kind.MILESTONE, "Milestone complete 🎯", "\"${current.second}\" is done", progress.percent)
                events.add(ev)
                CelebrationBus.emit(ev)
            }
        } else if (!shouldBeDone && isDone) {
            val ts = now()
            db.execSQL(
                "UPDATE milestones SET status='ACTIVE', completed_at=NULL, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, milestoneId),
            )
        }
    }

    private fun syncProject(
        db: SQLiteDatabase,
        projectId: String,
        celebrate: Boolean,
        sound: Boolean,
        events: MutableList<Celebration>,
    ) {
        val progress = projectProgress(db, projectId)
        if (progress.total == 0) return
        val current = db.rawQuery("SELECT status, title FROM projects WHERE id=?", arrayOf(projectId))
            .use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null } ?: return
        val shouldBeDone = progress.isComplete
        val isDone = current.first == LifeStatus.DONE.name
        if (shouldBeDone && !isDone) {
            val ts = now()
            db.execSQL(
                "UPDATE projects SET status='DONE', completed_at=?, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, ts, projectId),
            )
            HistoryRepositoryLog.record(db, EntityKind.PROJECT, projectId, HistoryAction.PROJECT_DONE, current.second, "Project complete")
            if (celebrate) {
                val ev = Celebration(Celebration.Kind.PROJECT, "Project complete 🎉", "\"${current.second}\" is finished", progress.percent)
                events.add(ev)
                CelebrationBus.emit(ev)
            }
        } else if (!shouldBeDone && isDone) {
            val ts = now()
            db.execSQL(
                "UPDATE projects SET status='ACTIVE', completed_at=NULL, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, projectId),
            )
        }
    }

    private fun syncGoal(
        db: SQLiteDatabase,
        goalId: String,
        celebrate: Boolean,
        sound: Boolean,
        events: MutableList<Celebration>,
    ) {
        val progress = goalProgress(db, goalId)
        if (progress.total == 0) return
        val current = db.rawQuery("SELECT status, title FROM goals WHERE id=?", arrayOf(goalId))
            .use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null } ?: return
        val shouldBeDone = progress.isComplete
        val isDone = current.first == LifeStatus.DONE.name
        if (shouldBeDone && !isDone) {
            val ts = now()
            db.execSQL(
                "UPDATE goals SET status='DONE', completed_at=?, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, ts, goalId),
            )
            HistoryRepositoryLog.record(db, EntityKind.GOAL, goalId, HistoryAction.GOAL_DONE, current.second, "Goal achieved")
            if (celebrate) {
                val ev = Celebration(Celebration.Kind.GOAL, "Goal achieved 🌟", "\"${current.second}\" — you did it!", 100)
                events.add(ev)
                CelebrationBus.emit(ev)
            }
        } else if (!shouldBeDone && isDone) {
            val ts = now()
            db.execSQL(
                "UPDATE goals SET status='ACTIVE', completed_at=NULL, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, goalId),
            )
        }
    }
}

/**
 * Internal helper so Rollup can log to the history table without holding a
 * repository instance (it runs inside the caller's transaction).
 */
internal object HistoryRepositoryLog {
    fun record(
        db: SQLiteDatabase,
        kind: EntityKind,
        entityId: String,
        action: HistoryAction,
        title: String,
        summary: String = "",
    ) {
        val ts = now()
        val event = com.lifedeck.app.data.model.HistoryEvent(
            id = com.lifedeck.app.util.newId(),
            entityType = kind,
            entityId = entityId,
            action = action,
            title = title,
            summary = summary,
            occurredAt = ts,
            createdAt = ts,
            updatedAt = ts,
        )
        db.insert("history", null, event.toValues())
    }
}
