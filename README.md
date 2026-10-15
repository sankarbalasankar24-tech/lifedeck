# LifeDeck — Personal Life Management & Smart Reminders

A native Android app that acts as a **daily planner, intelligent reminder assistant, goal tracker and searchable personal memory journal**. Local-first: everything works offline and opens instantly; Google Sheets is an optional mirror you own.

**Stack:** Kotlin · Jetpack Compose (Material 3) · SQLite (local source of truth) · AlarmManager + WorkManager (reliable scheduling) · kotlinx.serialization · Google Apps Script bridge for Google Sheets sync.

---

## Table of contents

1. [Features](#features)
2. [Architecture](#architecture)
3. [Repo layout](#repo-layout)
4. [Build & run](#build--run)
5. [Google Sheets sync setup](#google-sheets-sync-setup)
6. [Backups, export & import](#backups-export--import)
7. [Notifications: how reliability is achieved](#notifications-how-reliability-is-achieved)
8. [Testing checklist (acceptance criteria)](#testing-checklist-acceptance-criteria)
9. [Editing & rebuilding via GitHub](#editing--rebuilding-via-github)
10. [Security notes](#security-notes)

---

## Features

| Area | What you get |
|---|---|
| **Quick capture** | Persistent “+ Quick Add” button, voice input (speech-to-text), natural-language parsing (`buy milk tomorrow at 6pm #home !high`), auto-classification into task / shopping / appointment / idea / errand / project step, manual overrides, notes, category, priority, deadline, reminder lead time, recurring schedules. |
| **Inbox / whiteboard** | Unscheduled items wait here; Today and Inbox nudge you to process them by end of day; one-tap “Today” / “Tomorrow”; drag-and-drop ordering. |
| **Today dashboard** | Greeting, encouragement, completion ring, stat tiles (today / overdue / inbox / meetings), overdue with one-tap complete, appointments, time-sensitive shopping, top priorities, today’s plan, goal progress bars, pending inbox prompt, “On this day” memories. |
| **Evening review** | Completed / missed / partially completed / unscheduled breakdown with one-tap **Complete · Tomorrow · Date · Skip**; unfinished work never disappears — it is rescheduled or stays visible. |
| **Reminders** | Exact alarms (with inexact fallback), multiple reminders per task, advance lead time, escalation/repeat rules, sound/vibration, quiet hours, sleep hours, work hours, **named context windows** (e.g. “lunch”, “after work”) — notification times shift into allowed windows, deadlines never move silently (the shift is stated in the notification). Quick actions: **Complete (with undo) · Snooze · Reschedule**. Boot/clock/time-zone re-arm, test notification, permission/battery/exact-alarm troubleshooting screen. |
| **Goals → projects → milestones → steps** | Full hierarchy (e.g. *Install geyser → research specs → compare brands → shortlist vendors → request quotations → compare quotations → schedule installation → verify completion*), progress rolls up automatically, next recommended action, overdue steps, target dates, estimated cost, tasteful confetti celebrations (configurable, non-intrusive). |
| **Shopping & errands** | Now / this week / later buckets, quantity, budget, store, purchase date + price, trips grouping several items into one errand, budget used (30 days), permanent purchase ledger. |
| **Memories** | Heart any task/event/milestone → memory with date, description, category, notes, photos; “On this day” section, six-month and yearly anniversary reminders. |
| **History & search** | Global search across active/completed tasks, shopping history, projects, goals, appointments, notes, memories, and the ledger; filters by keyword, date range, kind, status; day/week/month calendar + activity timeline; created/modified/scheduled/completed/rescheduled timestamps kept; completed work is never deleted. |
| **Storage & sync** | SQLite is the source of truth; offline changes queue and sync when connectivity returns; idempotent upsert by stable UUID; last-writer-wins conflict resolution; sync status, pending/failed counts, retry control; automatic daily/weekly/monthly snapshots; JSON/CSV export and backup import. |
| **Design** | Calming light/dark themes, 6-tab navigation (Today, Inbox, Goals, Shop, Calendar, History) with Settings separate, compact cards, progress bars, priority indicators, animated completion, undo snackbars, adjustable text size, empty/loading/error states, one–two tap common actions. |

---

## Architecture

```
UI (Compose, screens/components)                 ui/
  │  observes Flows, dispatches suspend actions
Domain logic (capture parsing, recurrence)       domain/
  │
Repositories (tasks, structure, shopping,        data/repo/
  memories, history, categories, search,         data/model/   (entities + enums)
  settings, sync queue, backups)                 data/db/      (SQLite schema, mappers,
  │                                                               invalidation, sync queue)
Local SQLite  ← source of truth ──────────────►  (same transaction writes
  │                                                history + sync rows)
  ├── ReminderScheduler (debounced reconcile → AlarmManager)
  ├── SyncCoordinator   (push queue → Apps Script → Sheets; pull → LWW merge)
  └── MaintenanceWorker (periodic sync, auto backup, alarm safety net)
```

Key invariants:

* **One transaction = local write + history event + sync-queue row.** Nothing can exist locally without being logged and queued.
* **Reminders are derived.** Every create/edit/complete/reschedule re-derives AUTO reminders from the entry’s timing; a debounced observer re-arms alarms, so notifications recalculate immediately.
* **Progress is computed, never stored.** Completing a subtask instantly changes its milestone/project/goal percentages (including parents that finish when their subtasks do).
* **Conflict resolution** everywhere is last-writer-wins on `updatedAt` with stable UUID ids — restoring a backup or pulling from Sheets can’t duplicate or downgrade newer data.

## Repo layout

```
app/src/main/java/com/lifedeck/app/
├── LifeDeckApp.kt            Application + manual DI container
├── MainActivity.kt           Single-activity host, deep links
├── data/
│   ├── AppContainer.kt       Wiring for all layers
│   ├── db/                   Schema, cursor mappers, invalidation flow, sync-queue helper
│   ├── model/                Entities, enums, value types
│   └── repo/                 One repository per bounded context (+ DocumentMerger)
├── domain/                   CaptureParser (NL → entry), RecurrenceEngine
├── notify/                   Channels, NotificationHelper, ReminderScheduler,
│                             ReminderReceiver, NotificationActionReceiver,
│                             BootReceiver, SummaryNotifier
├── sync/                     SheetsClient, SyncCoordinator, MaintenanceWorker
└── ui/                       Root shell, routes, components, quick-add, screens
sync/apps-script/LifeDeckSync.gs   Google Sheets bridge you deploy yourself
docs/ARCHITECTURE.md, docs/TESTING.md
```

---

## Build & run

**Prerequisites**

* JDK 17+ (Android Studio’s embedded JBR works)
* Android SDK with **platform 36** and build-tools (Android Studio installs these)
* No other tooling needed — the Gradle wrapper downloads Gradle 8.14.3

```bash
# Linux/macOS/Git Bash
export JAVA_HOME="/path/to/jdk"          # e.g. Android Studio's jbr
./gradlew :app:assembleDebug              # APK → app/build/outputs/apk/debug/
./gradlew :app:installDebug               # install on a connected device
./gradlew :app:test                       # unit tests (parser, recurrence, merge)
```

In Android Studio: **Open** this folder → Run ▶.

| Setting | Value |
|---|---|
| Application id | `com.lifedeck.app` (debug adds `.debug`) |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 |
| Version | 1.0.1 (`versionCode` 2) |

Release build: `./gradlew :app:assembleRelease` — signed automatically when `keystore.properties` + `keystore/lifedeck-release.jks` are present (they are **git-ignored here on purpose**). Restore them any time from the private GitHub repo **`lifedeck-keystore`** in the same account, or let CI sign: GitHub Actions secrets (`KEYSTORE_B64`, `KEYSTORE_STORE_PASSWORD`, `KEYSTORE_KEY_ALIAS`, `KEYSTORE_KEY_PASSWORD`) make every tag build release-signed without any local files. Without either, the build falls back to the debug key.

---

## Google Sheets sync setup

LifeDeck ships **no Google API secrets**. You connect your own spreadsheet with a private Apps Script endpoint:

1. Create a Google Sheet.
2. **Extensions → Apps Script**, paste [`sync/apps-script/LifeDeckSync.gs`](sync/apps-script/LifeDeckSync.gs), save.
3. **Project Settings → Script properties** → add `SECRET` = a long random string (recommended).
4. **Deploy → New deployment → Web app**
   * Execute as: **Me**
   * Who has access: **Anyone with the link**
   * Copy the `…/exec` URL.
5. In the app: **Settings → Google Sheets sync** → enable, paste URL + secret → **Sync now**.

How it behaves:

* Offline edits queue locally (`sync_queue`) and are pushed when you’re online — retries are idempotent (upsert by UUID).
* Rows changed on another device (or in the sheet) are pulled and merged with last-writer-wins.
* Settings shows pending / in-flight / failed counts, last result and a **Retry failed** button. Nothing is ever silently discarded.

---

## Backups, export & import

* **Automatic snapshots** (daily/weekly/monthly, configurable) are written to app-private storage and listed in Settings with one-tap **Restore**.
* **Export JSON** (full database) or **Export CSV** (tasks) shares a file via the system share sheet.
* **Import backup** merges by id with last-writer-wins and logs a `IMPORTED` history event.
* Android Auto Backup covers the live database for device-to-device restores.

---

## Notifications: how reliability is achieved

| Concern | Handling |
|---|---|
| App closed | Exact alarms via `AlarmManagerCompat.setExactAndAllowWhileIdle` (inexact window fallback if permission missing) |
| Device reboot | `BootReceiver` on `BOOT_COMPLETED` re-arms everything from the database |
| Clock / time-zone change | Same receiver handles `TIME_SET` / `TIMEZONE_CHANGED` |
| App update | `MY_PACKAGE_REPLACED` re-arms |
| Data changed | Debounced reconcile on every DB write → alarms are recalculated immediately |
| Quiet/sleep/work/context windows | Notification times shift; the notification says *“Was 14:00, moved to 18:00 to fit your schedule”*; deadlines are never moved |
| Snooze / escalation / repeats | Each reminder row advances itself after firing (repeat → next occurrence, escalation → follow-up, else delivered) |
| Quick actions | Complete (with undo notification), Snooze (default duration configurable), Reschedule (opens the task) |
| Android 13+ permission | Requested on first launch; Settings → Troubleshooting shows status + **Send test** |
| Battery killing alarms | Troubleshooting screen deep-links to “ignore battery optimizations” and exact-alarm settings |

---

## Testing checklist (acceptance criteria)

See [docs/TESTING.md](docs/TESTING.md) for the full script. Highlights:

* [ ] Capture with voice and text; auto-classification and parsing of dates/priorities/repeats.
* [ ] Unscheduled items stay in Inbox; evening nudge appears at the configured time.
* [ ] Complete/edit/reschedule a task → dashboard, project %, history ledger and pending alarms all update.
* [ ] Kill the app → reminder still fires; reboot device → schedule re-arms.
* [ ] Quiet hours shift a notification (message states the shift), deadline unchanged.
* [ ] Goal hierarchy: completing the last step completes milestone → project → goal with a celebration.
* [ ] Shopping: purchase keeps the item searchable in the ledger with price/date; trip completes when all items are bought.
* [ ] “On this day” shows memories from previous years; anniversary toggles persist.
* [ ] Airplane mode → edits queue → reconnect → sync pushes, status shows counts, no duplicates.
* [ ] Restore a backup → identical data, no duplicate rows (LWW).

---

## Editing & rebuilding via GitHub

* Keep this repository in **your** GitHub account; every file is plain source (no generated code committed).
* Typical change loop: edit `app/src/main/java/...` → `./gradlew :app:assembleDebug` → install → run unit tests with `./gradlew :app:test`.
* Conventions: layers communicate only downward (ui → repo → db), all timestamps are epoch millis in device time zone, ids are UUIDv4 strings, new tables need a `Database.VERSION` migration.

---

## Security notes

* No Google client secrets, API keys or tokens are compiled into the app or committed to the repo.
* The Sheets endpoint URL + shared secret are **yours**, entered at runtime in Settings and stored in DataStore on the device only.
* Photos, backups and the database live in app-private storage; only exports you explicitly share leave the device.
* `*.jks`, `keystore.properties`, `secrets.properties`, `local.properties` are git-ignored **in this public repo** — the release keystore is backed up in the private repo `lifedeck-keystore` (same account) and as GitHub Actions secrets, so signing never depends on this machine.
