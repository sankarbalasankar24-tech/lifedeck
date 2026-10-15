package com.lifedeck.app.data.repo

import android.database.sqlite.SQLiteDatabase
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.Category
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.HistoryAction
import com.lifedeck.app.data.model.SyncOp
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import kotlinx.coroutines.flow.Flow

/** User-definable categories that classify captures, tasks and memories. */
class CategoryRepository(
    private val db: Database,
    private val history: HistoryRepository,
) {

    fun observeAll(): Flow<List<Category>> = db.observe { sqlite ->
        sqlite.rawQuery("SELECT * FROM categories WHERE deleted=0 ORDER BY order_index, name", null)
            .use { it.mapRows { toCategory() } }
    }

    suspend fun all(): List<Category> = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM categories WHERE deleted=0 ORDER BY order_index, name", null)
            .use { it.mapRows { toCategory() } }
    }

    suspend fun get(id: String): Category? = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM categories WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toCategory() else null
        }
    }

    suspend fun findByName(name: String): Category? = db.read { sqlite ->
        sqlite.rawQuery(
            "SELECT * FROM categories WHERE deleted=0 AND lower(name)=lower(?) LIMIT 1",
            arrayOf(name),
        ).use { c -> if (c.moveToFirst()) c.toCategory() else null }
    }

    /** Seed friendly defaults on first launch (idempotent). */
    suspend fun ensureDefaults() {
        val existing = all()
        if (existing.any { it.builtIn }) return
        val seeds = listOf(
            "Work" to 0xFF3D5AFE,
            "Personal" to 0xFF00BFA5,
            "Health" to 0xFFFF6D9D,
            "Home" to 0xFFFFB300,
            "Finance" to 0xFF00897B,
            "Family" to 0xFF7E57C2,
            "Learning" to 0xFF26A69A,
            "Errands" to 0xFFEF5350,
        )
        db.write { sqlite ->
            val ts = now()
            seeds.forEachIndexed { index, (name, color) ->
                val category = Category(
                    id = newId(),
                    name = name,
                    color = color,
                    orderIndex = index,
                    builtIn = true,
                    createdAt = ts,
                    updatedAt = ts,
                )
                sqlite.insertWithOnConflict(
                    "categories", null, category.toValues(), SQLiteDatabase.CONFLICT_IGNORE,
                )
                enqueueSync(sqlite, EntityKind.CATEGORY, category.id, SyncOp.UPSERT)
            }
        }
    }

    suspend fun create(name: String, color: Long, icon: String = "label"): String {
        val id = newId()
        db.write { sqlite ->
            val ts = now()
            val nextIndex = sqlite.rawQuery(
                "SELECT COALESCE(MAX(order_index),0)+1 FROM categories WHERE deleted=0", null,
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 1 }
            val category = Category(
                id = id, name = name, color = color, icon = icon,
                orderIndex = nextIndex, createdAt = ts, updatedAt = ts,
            )
            sqlite.insertWithOnConflict("categories", null, category.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.CATEGORY, id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.CATEGORY, id, HistoryAction.CREATED, name, "Category created")
        }
        return id
    }

    suspend fun update(category: Category) {
        db.write { sqlite ->
            val ts = now()
            val stored = category.copy(updatedAt = ts, version = category.version + 1, dirty = true)
            sqlite.insertWithOnConflict("categories", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.CATEGORY, stored.id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.CATEGORY, stored.id, HistoryAction.EDITED, stored.titleSafe, "Category updated")
        }
    }

    /**
     * Delete a category. Items keep their history: they simply fall back to
     * "no category" rather than disappearing.
     */
    suspend fun delete(id: String) {
        db.write { sqlite ->
            val ts = now()
            sqlite.execSQL(
                "UPDATE entries SET category_id=NULL, updated_at=?, dirty=1, version=version+1 WHERE category_id=?",
                arrayOf<Any>(ts, id),
            )
            sqlite.execSQL(
                "UPDATE categories SET deleted=1, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, id),
            )
            enqueueSync(sqlite, EntityKind.CATEGORY, id, SyncOp.DELETE)
        }
    }

    suspend fun rename(id: String, name: String, color: Long) {
        val existing = get(id) ?: return
        update(existing.copy(name = name, color = color))
    }

    suspend fun allForSync(): List<Category> = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM categories ORDER BY created_at", null)
            .use { it.mapRows { toCategory() } }
    }
}

private val Category.titleSafe: String get() = name
