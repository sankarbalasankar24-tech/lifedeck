# LifeDeck test & acceptance script

Automated: `./gradlew :app:test` (capture parser, recurrence engine).

Manual script for a physical device/emulator (API 34+ recommended).

## 1. Quick capture

- [ ] Tap **+ Quick Add** → type `buy milk tomorrow at 6pm #home !high` → preview shows
      Shopping · tomorrow · high priority → Save.
- [ ] Voice: mic button → grant microphone → speak "call dentist next week" → text appears.
- [ ] Auto-classification: `meeting with HR at 12` → Appointment, `idea: app for chores` → Idea,
      `install geyser` → project step heuristics, `#goal run marathon` → goal step.
- [ ] Recurrence: `water plants every 3 days` → chip shows "Every 3 days".
- [ ] Save & add another keeps the sheet open with an "Added ✓" flash.
- [ ] Untouched capture with no date lands in **Inbox**; with a date it becomes active.

## 2. Inbox / whiteboard

- [ ] Open Inbox → items listed with type chips; "Today"/"Tomorrow" one-taps schedule them (undo works).
- [ ] Long-press drag reorders; order persists after app restart.
- [ ] After the configured prompt hour (default 20:00) an "End-of-day review" banner appears.
- [ ] Banner → Evening review shows them under "Unscheduled".

## 3. Today dashboard

- [ ] Morning: greeting, completion bar, stat tiles (Today/Overdue/Inbox/Meetings) match the lists below.
- [ ] Overdue items shown with red border + ⚠ section; completing them moves them to History (undo available).
- [ ] Appointments, time-sensitive shopping, top priorities and goal progress bars all render.
- [ ] "On this day" shows memories from a previous year (create one a year back to test).
- [ ] Empty state when nothing is planned.

## 4. Evening review

- [ ] Sections: Completed / Missed / Still open / Partially completed / Unscheduled + day activity.
- [ ] One-tap **Complete · Tomorrow · Date · Skip** each work and are undoable.
- [ ] Unfinished tasks never disappear — they stay listed or move visibly to tomorrow.

## 5. Reminders (the critical path)

- [ ] Add a reminder 2 minutes ahead → kill the app → notification arrives with
      **Complete / Snooze / Reschedule** actions.
- [ ] Complete from the notification → task completes in app → undo notification appears.
- [ ] Snooze 15m → fires again in 15m.
- [ ] Reboot the device → all future reminders still fire.
- [ ] Change the system clock / time zone → schedule stays correct (receiver re-arms).
- [ ] Quiet hours: schedule a reminder inside quiet hours → it shifts to quiet end and the
      notification states "Was 22:30, moved to 07:00…"; the task deadline itself is unchanged.
- [ ] Escalate toggle on a reminder → second alert after the interval while unhandled.
- [ ] Repeating task: complete → next occurrence created with advanced date.
- [ ] Settings → Troubleshooting: Send test notification works; exact-alarm and battery rows show status.

## 6. Goals / projects / milestones

- [ ] Create goal → add project with milestones → add steps.
- [ ] Completing the last step of a milestone → milestone completes (celebration), project % rises,
      then goal completes with 🌟 celebration (confetti toggle respected).
- [ ] Uncompleting a step re-opens the milestone (roll-up is computed, never stale).
- [ ] Goal detail shows next recommended action, overdue steps, target date, cost.

## 7. Shopping

- [ ] Add item with qty/budget/store → appears under Now/This week/Later bucket.
- [ ] Tick an item → price prompt → price recorded; item moves to Ledger with date+price.
- [ ] Create a trip, add items, buy all → trip auto-completes.
- [ ] Ledger search: bought items still findable in History → Search.

## 8. Calendar, history, memories

- [ ] Month grid shows dots for busy days; tap a day → agenda + timeline.
- [ ] Week/Day modes switch; Today button recenters.
- [ ] Timeline filters (Completed/Rescheduled/Purchased/Memories/Created/Deleted) work.
- [ ] Search with keyword + kind + status + date-range filters returns hits from every entity type.
- [ ] Heart a completed task → memory created; anniversary toggles persist; "On this day" section works.

## 9. Offline, sync, backups

- [ ] Airplane mode → capture/edit/complete several items → Settings shows growing "pending changes".
- [ ] Reconnect → Sync now → pending reaches 0, "OK · N pushed".
- [ ] Two devices / restoring a backup: no duplicate ids, newer edit wins (LWW).
- [ ] Sync failure (bad URL) → failed count + error text + Retry failed works; data never lost.
- [ ] Back up now → snapshot listed → restore → identical data.
- [ ] Export JSON and CSV → share sheet opens; Import backup restores with a toast count.

## 10. Design / accessibility

- [ ] Light and dark theme follow system (and manual override).
- [ ] Text size slider 85%–150% scales the whole UI without clipping.
- [ ] Every list has empty/loading states; destructive actions offer Undo.
- [ ] Common flows (complete a task, add an item) take ≤2 taps.
