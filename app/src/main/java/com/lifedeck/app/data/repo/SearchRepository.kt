package com.lifedeck.app.data.repo

import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.data.model.SearchHit
import com.lifedeck.app.util.formatDate

/**
 * Global search across active tasks, completed tasks, shopping history,
 * projects, goals, appointments, notes and memories — with filters for
 * date range, keyword, category, status, priority, project and type.
 */
class SearchRepository(private val db: Database) {

    data class Filters(
        val query: String = "",
        val from: Long? = null,
        val to: Long? = null,
        val categoryId: String? = null,
        val status: String? = null,      // INBOX|ACTIVE|DONE|SKIPPED|MISSED|OPEN
        val priority: Priority? = null,
        val projectId: String? = null,
        val goalId: String? = null,
        val type: String? = null,        // TASK|SHOPPING|APPOINTMENT|...
        val kinds: Set<EntityKind> = setOf(
            EntityKind.ENTRY, EntityKind.GOAL, EntityKind.PROJECT,
            EntityKind.MILESTONE, EntityKind.MEMORY, EntityKind.HISTORY,
        ),
        val includeDeleted: Boolean = false,
    )

    /** Run the search and return hits ordered by most recent activity. */
    suspend fun search(filters: Filters): List<SearchHit> = db.read { sqlite ->
        val hits = mutableListOf<SearchHit>()
        val q = filters.query.trim()
        val like = if (q.isBlank()) null else "%$q%"

        // ---- entries (tasks, shopping, appointments, history of completions) ----
        if (EntityKind.ENTRY in filters.kinds) {
            val args = mutableListOf<String>()
            val where = StringBuilder("deleted=0")
            like?.let { pattern ->
                where.append(" AND (title LIKE ? OR notes LIKE ? OR location LIKE ? OR IFNULL(store,'') LIKE ?)")
                repeat(4) { args.add(pattern) }
            }
            filters.from?.let { where.append(" AND COALESCE(completed_at, due_at, scheduled_at, start_at, created_at) >= ?"); args.add(it.toString()) }
            filters.to?.let { where.append(" AND COALESCE(completed_at, due_at, scheduled_at, start_at, created_at) < ?"); args.add(it.toString()) }
            filters.categoryId?.let { where.append(" AND category_id=?"); args.add(it) }
            filters.projectId?.let { where.append(" AND project_id=?"); args.add(it) }
            filters.goalId?.let { where.append(" AND goal_id=?"); args.add(it) }
            filters.type?.let { where.append(" AND type=?"); args.add(it) }
            filters.priority?.let { where.append(" AND priority=?"); args.add(it.name) }
            when (filters.status) {
                "OPEN" -> where.append(" AND status IN ('INBOX','ACTIVE','MISSED')")
                null -> Unit
                else -> where.append(" AND status=?").also { args.add(filters.status) }
            }
            if (!filters.includeDeleted) Unit
            sqlite.rawQuery("SELECT * FROM entries WHERE $where ORDER BY updated_at DESC LIMIT 300", args.toTypedArray())
                .use { c ->
                    c.mapRows { toEntry() }.forEach { e ->
                        hits.add(
                            SearchHit(
                                kind = EntityKind.ENTRY,
                                id = e.id,
                                title = e.title,
                                subtitle = subtitleFor(e),
                                occurredAt = e.completedAt ?: e.dueAt ?: e.scheduledAt ?: e.startAt ?: e.createdAt,
                                status = e.status.name,
                            )
                        )
                    }
                }
        }

        // ---- goals / projects / milestones ----
        if (EntityKind.GOAL in filters.kinds) {
            sqlite.rawQuery("SELECT * FROM goals WHERE deleted=0 ORDER BY updated_at DESC LIMIT 200", null)
                .use { c ->
                    c.mapRows { toGoal() }.forEach { g ->
                        if (matches(like, g.title, g.description)) {
                            hits.add(SearchHit(EntityKind.GOAL, g.id, g.title, "Goal · ${g.status.name.lowercase()}", g.targetDate ?: g.updatedAt, g.status.name))
                        }
                    }
                }
        }
        if (EntityKind.PROJECT in filters.kinds) {
            sqlite.rawQuery("SELECT * FROM projects WHERE deleted=0 ORDER BY updated_at DESC LIMIT 200", null)
                .use { c ->
                    c.mapRows { toProject() }.forEach { p ->
                        if (matches(like, p.title, p.notes)) {
                            hits.add(SearchHit(EntityKind.PROJECT, p.id, p.title, "Project · ${p.status.name.lowercase()}", p.dueDate ?: p.updatedAt, p.status.name))
                        }
                    }
                }
        }
        if (EntityKind.MILESTONE in filters.kinds) {
            sqlite.rawQuery("SELECT * FROM milestones WHERE deleted=0 ORDER BY updated_at DESC LIMIT 200", null)
                .use { c ->
                    c.mapRows { toMilestone() }.forEach { m ->
                        if (matches(like, m.title, m.notes)) {
                            hits.add(SearchHit(EntityKind.MILESTONE, m.id, m.title, "Milestone", m.targetDate ?: m.updatedAt, m.status.name))
                        }
                    }
                }
        }

        // ---- memories ----
        if (EntityKind.MEMORY in filters.kinds) {
            sqlite.rawQuery("SELECT * FROM memories WHERE deleted=0 ORDER BY occurred_at DESC LIMIT 300", null)
                .use { c ->
                    c.mapRows { toMemory() }.forEach { m ->
                        if (matches(like, m.title, m.description, m.category)) {
                            val inRange = (filters.from == null || m.occurredAt >= filters.from) &&
                                (filters.to == null || m.occurredAt < filters.to)
                            if (inRange) {
                                hits.add(SearchHit(EntityKind.MEMORY, m.id, m.title, "Memory · ${formatDate(m.occurredAt)}", m.occurredAt, "MEMORY"))
                            }
                        }
                    }
                }
        }

        // ---- history ledger (completed work, reschedules, purchases) ----
        if (EntityKind.HISTORY in filters.kinds && like != null) {
            sqlite.rawQuery(
                "SELECT * FROM history WHERE deleted=0 AND (title LIKE ? OR summary LIKE ?) ORDER BY occurred_at DESC LIMIT 300",
                arrayOf(like, like),
            ).use { c ->
                c.mapRows { toHistory() }.forEach { h ->
                    hits.add(SearchHit(EntityKind.HISTORY, h.id, h.title, "${h.action.name.lowercase()} · ${h.summary}", h.occurredAt, h.action.name))
                }
            }
        }

        hits.sortedByDescending { it.occurredAt }
    }

    private fun matches(like: String?, vararg fields: String?): Boolean {
        if (like == null) return true
        val needle = like.trim('%').lowercase()
        return fields.any { !it.isNullOrBlank() && it.lowercase().contains(needle) }
    }

    private fun subtitleFor(e: Entry): String {
        val bits = mutableListOf(e.type.name.lowercase().replace('_', ' '))
        e.store?.takeIf { it.isNotBlank() }?.let { bits.add(it) }
        e.location.takeIf { it.isNotBlank() }?.let { bits.add(it) }
        if (e.status == com.lifedeck.app.data.model.EntryStatus.DONE) {
            e.completedAt?.let { bits.add("done ${formatDate(it)}") }
        } else if (e.dueAt != null) {
            bits.add("due ${formatDate(e.dueAt)}")
        }
        return bits.joinToString(" · ")
    }
}
