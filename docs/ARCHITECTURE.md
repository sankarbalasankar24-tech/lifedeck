# LifeDeck architecture

## Layers

```
ui/                 Compose screens, components, navigation, quick-add sheet
   │  observes Flow<List<…>>, calls suspend repository methods
domain/             Pure Kotlin logic: CaptureParser (NL → entry), RecurrenceEngine
   │
data/repo/          One repository per bounded context (tasks, structure, shopping,
   │                memories, history, categories, search, settings, sync, backup)
data/model/         Entities + enums shared by every layer
data/db/            SQLiteOpenHelper schema, cursor mappers, invalidation flow,
                    sync-queue writer, generic query helpers
   │
notify/             Channels, NotificationHelper, ReminderScheduler, receivers,
                    SummaryNotifier
sync/               SheetsClient (HTTP), SyncCoordinator (push/pull/merge),
                    MaintenanceWorker (periodic sync + backups)
```

`AppContainer` (manual DI) wires everything in dependency order; `LifeDeckApp`
creates it at process start and runs `bootstrap()` (seeds categories, arms
alarms, schedules periodic work).

## Data model (SQLite, schema v1)

| Table | Purpose |
|---|---|
| `entries` | Universal capture row: tasks, subtasks, shopping items, appointments, ideas, errands, project/goal steps, habits. Wide table incl. shopping fields, checklist JSON, dependency ids, photos. |
| `goals`, `projects`, `milestones` | Long-term structure; entries reference them by id (goal id is set even on nested steps so roll-ups stay one query). |
| `trips` | Shopping trips/errands; entries carry `trip_id`. |
| `categories` | User categories (built-ins seeded). |
| `reminders` | One row per alarm: fire time, lead, repeat rule, escalation, `origin` AUTO/MANUAL, status. |
| `history` | Append-only journal: created/edited/completed/rescheduled/purchased/milestone events with timestamps. Never trimmed. |
| `memories` | Heart'd events with anniversary flags and next reminder time. |
| `sync_queue` | Pending Google Sheets changes (one row per entity, deduped). |
| `backups` | Snapshot index (files in `filesDir/backups`). |
| `meta` | Small key/value store. |

All syncable tables share: `id` (UUIDv4), `created_at`, `updated_at`,
`version`, `dirty`, `deleted` (tombstone). Timestamps are epoch millis in the
device time zone.

## Invalidation & reactivity

`Database.observe { query }` collects a `StateFlow<Long>` bumped after every
committed transaction and re-runs the query on `Dispatchers.IO`, then
`distinctUntilChanged()`. Screens collect these flows directly — no caching
layer, so UI can never show stale data.

## Write path invariant

Every mutation runs in **one** transaction that does all of:

1. upsert the row (`updated_at`, `version+1`, `dirty=1`)
2. `enqueueSync(...)` (collapses to one pending queue row per entity)
3. `history.record(...)` (audit trail / rescheduling history)
4. re-derive AUTO reminders for that entry
5. `Rollup.afterEntryChanged(...)` — milestone/project/goal auto-completion

## Reminders

`ReminderScheduler` observes DB changes (debounced 250 ms) and re-arms exact
alarms (`setExactAndAllowWhileIdle`, inexact fallback when permission missing):

* desired alarms = pending/snoozed reminders + summary times + upcoming anniversaries
* `effectiveFireTime()` applies quiet/sleep/work/context windows **only to the
  notification**; deadlines are untouched and the shift is stated in the
  notification text
* previously armed request codes that are no longer desired are cancelled
* `ReminderReceiver` posts the notification, then advances the reminder row:
  repeat → next occurrence, escalation → follow-up, else `FIRED`
* `BootReceiver` re-arms on boot / package update / clock / time-zone change

## Sync & conflict resolution

* Push: queue rows → JSON → POST to the user's Apps Script endpoint
  (idempotent upsert by id; DELETE sends a tombstone).
* Pull: `since` cursor → rows → `DocumentMerger` (same code path as backup
  restore): **last-writer-wins on `updatedAt`**, rows newer locally are skipped
  and stay queued.
* Status surface: pending/in-flight/failed counts + last error + retry.

## Layer rules

* ui → repo/domain only (never touches SQLite directly)
* repo → db only (never UI types)
* notify/sync → repos through `AppContainer`
* No `runBlocking` on the main thread; receivers use `goAsync()` + coroutines
