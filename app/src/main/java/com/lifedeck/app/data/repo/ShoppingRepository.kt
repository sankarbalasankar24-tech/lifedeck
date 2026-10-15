package com.lifedeck.app.data.repo

import android.database.sqlite.SQLiteDatabase
import com.lifedeck.app.data.db.*
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.data.model.HistoryAction
import com.lifedeck.app.data.model.LifeStatus
import com.lifedeck.app.data.model.ShoppingBucket
import com.lifedeck.app.data.model.ShoppingTrip
import com.lifedeck.app.data.model.SyncOp
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import kotlinx.coroutines.flow.Flow

/**
 * Shopping & errands: items live in the entries table (type = SHOPPING) with
 * quantity/budget/store columns; trips group several items into one errand.
 * Purchased items stay forever in the searchable ledger.
 */
class ShoppingRepository(
    private val db: Database,
    private val history: HistoryRepository,
    private val tasks: TaskRepository,
) {

    fun observeItems(bucket: ShoppingBucket? = null): Flow<List<Entry>> = db.observe { sqlite ->
        val args = mutableListOf<String>()
        var where = "type='SHOPPING' AND status!='DONE'"
        bucket?.let { where += " AND bucket=?"; args.add(it.name) }
        sqlite.queryEntries("$where ORDER BY CASE priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END, title", args.toTypedArray())
    }

    fun observeAllItems(): Flow<List<Entry>> = db.observe { sqlite ->
        sqlite.queryEntries("type='SHOPPING' ORDER BY status='DONE', purchased_at IS NULL, title")
    }

    suspend fun itemsDueBefore(endMillis: Long): List<Entry> = db.read { sqlite ->
        sqlite.queryEntries(
            "type='SHOPPING' AND status!='DONE' AND purchased_at IS NULL AND (COALESCE(due_at, scheduled_at) IS NOT NULL AND COALESCE(due_at, scheduled_at) <= ?)",
            arrayOf(endMillis.toString()),
        )
    }

    /** Everything ever bought — searchable history. */
    suspend fun purchaseLedger(from: Long? = null, to: Long? = null): List<Entry> = db.read { sqlite ->
        val args = mutableListOf<String>()
        var where = "type='SHOPPING' AND purchased_at IS NOT NULL"
        if (from != null) { where += " AND purchased_at >= ?"; args.add(from.toString()) }
        if (to != null) { where += " AND purchased_at < ?"; args.add(to.toString()) }
        sqlite.queryEntries("$where ORDER BY purchased_at DESC", args.toTypedArray())
    }

    suspend fun budgetUsedBetween(from: Long, to: Long): Double = db.read { sqlite ->
        sqlite.rawQuery(
            "SELECT COALESCE(SUM(price_paid),0) FROM entries WHERE type='SHOPPING' AND deleted=0 AND purchased_at>=? AND purchased_at<?",
            arrayOf(from.toString(), to.toString()),
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
    }

    suspend fun createItem(item: Entry): String =
        tasks.create(
            item.copy(
                type = com.lifedeck.app.data.model.EntryType.SHOPPING,
                bucket = item.bucket ?: ShoppingBucket.NOW,
                status = if (item.dueAt == null && item.scheduledAt == null) EntryStatus.INBOX else EntryStatus.ACTIVE,
            ),
            summary = "Added to shopping list",
        )

    suspend fun updateItem(item: Entry) = tasks.update(item, summary = "Shopping item updated")

    /** Mark purchased: keeps the record forever, with price/date/notes. */
    suspend fun purchase(id: String, pricePaid: Double? = null, notes: String? = null) {
        db.write { sqlite ->
            val item = sqlite.rawQuery("SELECT * FROM entries WHERE id=?", arrayOf(id))
                .use { c -> if (c.moveToFirst()) c.toEntry() else null } ?: return@write
            val ts = now()
            val bought = item.copy(
                status = EntryStatus.DONE,
                purchasedAt = ts,
                pricePaid = pricePaid ?: item.pricePaid,
                notes = notes ?: item.notes,
                updatedAt = ts,
                version = item.version + 1,
                dirty = true,
            )
            sqlite.insertWithOnConflict("entries", null, bought.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.ENTRY, id, SyncOp.UPSERT)
            history.record(
                sqlite, EntityKind.ENTRY, id, HistoryAction.PURCHASED, item.title,
                "Purchased" + (pricePaid?.let { " · %.2f".format(it) } ?: ""),
            )
            // Recompute trip progress if any.
            item.tripId?.let { tripId -> refreshTripStatus(sqlite, tripId) }
        }
    }

    suspend fun unpurchase(id: String) {
        db.write { sqlite ->
            val item = sqlite.rawQuery("SELECT * FROM entries WHERE id=?", arrayOf(id))
                .use { c -> if (c.moveToFirst()) c.toEntry() else null } ?: return@write
            val ts = now()
            val back = item.copy(
                status = EntryStatus.ACTIVE,
                purchasedAt = null,
                pricePaid = null,
                updatedAt = ts,
                version = item.version + 1,
                dirty = true,
            )
            sqlite.insertWithOnConflict("entries", null, back.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.ENTRY, id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.ENTRY, id, HistoryAction.UNPURCHASED, item.title, "Returned to list")
            item.tripId?.let { refreshTripStatus(sqlite, it) }
        }
    }

    suspend fun removeItem(id: String) = tasks.delete(id)

    // ------------------------------------------------------------------ trips

    fun observeTrips(includeDone: Boolean = true): Flow<List<ShoppingTrip>> = db.observe { sqlite ->
        val where = if (includeDone) "deleted=0" else "deleted=0 AND status!='DONE'"
        sqlite.rawQuery("SELECT * FROM trips WHERE $where ORDER BY status='DONE', planned_at IS NULL, planned_at", null)
            .use { it.mapRows { toTrip() } }
    }

    fun observeTrip(id: String): Flow<ShoppingTrip?> = db.observe { sqlite ->
        sqlite.rawQuery("SELECT * FROM trips WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toTrip() else null
        }
    }

    suspend fun createTrip(trip: ShoppingTrip): String {
        val id = trip.id.ifBlank { newId() }
        db.write { sqlite ->
            val ts = now()
            val stored = trip.copy(id = id, createdAt = ts, updatedAt = ts, dirty = true, version = 1)
            sqlite.insertWithOnConflict("trips", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.TRIP, id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.TRIP, id, HistoryAction.CREATED, stored.title, "Shopping trip planned")
        }
        return id
    }

    suspend fun updateTrip(trip: ShoppingTrip, summary: String = "Trip updated") {
        db.write { sqlite ->
            val ts = now()
            val stored = trip.copy(updatedAt = ts, version = trip.version + 1, dirty = true)
            sqlite.insertWithOnConflict("trips", null, stored.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            enqueueSync(sqlite, EntityKind.TRIP, stored.id, SyncOp.UPSERT)
            history.record(sqlite, EntityKind.TRIP, stored.id, HistoryAction.EDITED, stored.title, summary)
            refreshTripStatus(sqlite, stored.id)
        }
    }

    suspend fun deleteTrip(id: String) {
        db.write { sqlite ->
            val ts = now()
            // Detach items instead of deleting them.
            sqlite.execSQL(
                "UPDATE entries SET trip_id=NULL, updated_at=?, dirty=1, version=version+1 WHERE trip_id=?",
                arrayOf<Any>(ts, id),
            )
            sqlite.execSQL(
                "UPDATE trips SET deleted=1, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, id),
            )
            enqueueSync(sqlite, EntityKind.TRIP, id, SyncOp.DELETE)
        }
    }

    suspend fun assignItemsToTrip(tripId: String?, itemIds: List<String>) {
        db.write { sqlite ->
            val ts = now()
            itemIds.forEach { id ->
                if (tripId == null) {
                    sqlite.execSQL(
                        "UPDATE entries SET trip_id=NULL, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                        arrayOf<Any>(ts, id),
                    )
                } else {
                    sqlite.execSQL(
                        "UPDATE entries SET trip_id=?, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                        arrayOf<Any>(tripId, ts, id),
                    )
                }
                enqueueSync(sqlite, EntityKind.ENTRY, id, SyncOp.UPSERT)
            }
            tripId?.let { refreshTripStatus(sqlite, it) }
        }
    }

    /** Complete a trip when every item in it is bought (or none remain). */
    suspend fun completeTrip(id: String) {
        db.write { sqlite ->
            val ts = now()
            sqlite.execSQL(
                "UPDATE trips SET status='DONE', completed_at=?, updated_at=?, dirty=1, version=version+1 WHERE id=?",
                arrayOf<Any>(ts, ts, id),
            )
            enqueueSync(sqlite, EntityKind.TRIP, id, SyncOp.UPSERT)
            sqlite.rawQuery("SELECT title FROM trips WHERE id=?", arrayOf(id)).use { c ->
                if (c.moveToFirst()) {
                    history.record(sqlite, EntityKind.TRIP, id, HistoryAction.COMPLETED, c.getString(0), "Trip completed")
                }
            }
        }
    }

    suspend fun trip(id: String): ShoppingTrip? = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM trips WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.toTrip() else null
        }
    }

    suspend fun allTripsForSync(): List<ShoppingTrip> = db.read { sqlite ->
        sqlite.rawQuery("SELECT * FROM trips ORDER BY created_at", null).use { it.mapRows { toTrip() } }
    }

    private fun refreshTripStatus(sqlite: SQLiteDatabase, tripId: String) {
        val stats = sqlite.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN status='DONE' THEN 1 ELSE 0 END) FROM entries WHERE deleted=0 AND trip_id=?",
            arrayOf(tripId),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getInt(1) else 0 to 0 }
        val (total, done) = stats
        val ts = now()
        if (total > 0 && done >= total) {
            sqlite.execSQL(
                "UPDATE trips SET status='DONE', completed_at=COALESCE(completed_at,?), updated_at=?, dirty=1, version=version+1 WHERE id=? AND status!='DONE'",
                arrayOf<Any>(ts, ts, tripId),
            )
        } else if (total > 0) {
            sqlite.execSQL(
                "UPDATE trips SET status='ACTIVE', completed_at=NULL, updated_at=?, dirty=1, version=version+1 WHERE id=? AND status='DONE'",
                arrayOf<Any>(ts, tripId),
            )
        }
    }

    /** Convenience: quick-add an item directly to a bucket. */
    suspend fun quickAdd(
        title: String,
        bucket: ShoppingBucket,
        quantity: String? = null,
        budget: Double? = null,
        store: String? = null,
        dueAt: Long? = null,
        categoryId: String? = null,
    ): String {
        val item = Entry(
            title = title,
            type = com.lifedeck.app.data.model.EntryType.SHOPPING,
            status = if (dueAt == null) EntryStatus.INBOX else EntryStatus.ACTIVE,
            bucket = bucket,
            quantity = quantity,
            budget = budget,
            store = store,
            dueAt = dueAt,
            categoryId = categoryId,
        )
        return createItem(item)
    }
}
