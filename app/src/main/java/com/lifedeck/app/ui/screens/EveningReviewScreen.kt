package com.lifedeck.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.components.LocalUndo
import com.lifedeck.app.ui.Routes
import com.lifedeck.app.ui.components.Chip
import com.lifedeck.app.ui.components.EmptyState
import com.lifedeck.app.ui.components.HistoryRow
import com.lifedeck.app.ui.components.SectionHeader
import com.lifedeck.app.ui.components.TaskCard
import com.lifedeck.app.util.dayHeading
import com.lifedeck.app.util.formatTime
import com.lifedeck.app.util.isOverdue
import com.lifedeck.app.util.now
import com.lifedeck.app.util.todayStart
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Evening review: completed, partially completed, missed and unscheduled
 * tasks with one-tap complete / skip / change date / reschedule. Nothing
 * unfinished ever disappears silently — it lands here and stays visible.
 */
@Composable
fun EveningReviewScreen(nav: NavController) {
    val container = LocalAppContainer.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()

    val open by container.tasks.observeOpen().collectAsStateWithLifecycle(initialValue = emptyList())
    val inbox by container.tasks.observeInbox().collectAsStateWithLifecycle(initialValue = emptyList())
    val todayStart = remember { todayStart() }
    val todayEnd = todayStart + 86_400_000L
    val completed by container.tasks.observeCompletedBetween(todayStart, todayEnd)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val historyToday by container.history.observeBetween(todayStart, todayEnd)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val lateNight = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 18 }

    var datePickFor by remember { mutableStateOf<String?>(null) }

    val missed = open.filter {
        val t = it.dueAt ?: it.scheduledAt
        t != null && t < todayStart
    }
    val partial = open.filter { e ->
        e.checklistProgress?.let { (done, total) -> total > 0 && done in 1 until total } == true
    }
    val stillPlanned = open.filter {
        val t = it.scheduledAt ?: it.dueAt ?: it.startAt
        t != null && t in todayStart until todayEnd && it !in missed && it !in partial
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Default.History, contentDescription = "Back")
                }
                Column {
                    Text(
                        "Evening review",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        dayHeading(now()) + (if (lateNight) " · wrap-up time 🌙" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------- score card ----------
        item {
            Surface(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
            ) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ScoreCell(completed.size, "Completed", MaterialTheme.colorScheme.secondary)
                    ScoreCell(missed.size, "Missed", androidx.compose.ui.graphics.Color(0xFFE53935))
                    ScoreCell(stillPlanned.size, "Still open", MaterialTheme.colorScheme.primary)
                    ScoreCell(inbox.size, "Unscheduled", androidx.compose.ui.graphics.Color(0xFFFFB300))
                }
            }
        }

        // ---------- encouragement ----------
        item {
            Text(
                when {
                    completed.isEmpty() && missed.isEmpty() && stillPlanned.isEmpty() ->
                        "Nothing scheduled today — capture tomorrow's first step while it's fresh."
                    completed.size >= completed.size + missed.size && missed.isEmpty() ->
                        "A clean sweep. Rest well 🌙"
                    missed.isNotEmpty() ->
                        "${missed.size} slipped today. Reschedule them now and tomorrow starts lighter."
                    else -> "unfinished work stays visible — pick what moves to tomorrow."
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------- missed ----------
        if (missed.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "⏰ Missed",
                    subtitle = "One tap each: complete, tomorrow, skip or pick a date",
                )
            }
            items(missed, key = { "missed-${it.id}" }) { entry ->
                ActionRow(
                    entry = entry,
                    onOpen = { nav.navigate(Routes.task(entry.id)) },
                    onComplete = {
                        scope.launch {
                            container.tasks.complete(entry.id)
                            undo.show("Completed \"${entry.title}\"") { container.tasks.uncomplete(entry.id) }
                        }
                    },
                    onTomorrow = {
                        scope.launch {
                            container.tasks.reschedule(
                                entry.id,
                                dueAt = null,
                                scheduledAt = tomorrow9(),
                                keepExisting = false,
                                reason = "Evening review",
                            )
                            undo.show("Moved to tomorrow") {
                                container.tasks.reschedule(entry.id, dueAt = null, scheduledAt = null, keepExisting = false)
                            }
                        }
                    },
                    onSkip = {
                        scope.launch {
                            container.tasks.skip(entry.id)
                            undo.show("Skipped \"${entry.title}\"") { container.tasks.uncomplete(entry.id) }
                        }
                    },
                    onPickDate = { datePickFor = entry.id },
                )
            }
        }

        // ---------- still planned ----------
        if (stillPlanned.isNotEmpty()) {
            item { SectionHeader(title = "🔄 Still open for today", subtitle = "Reschedule or finish") }
            items(stillPlanned, key = { "open-${it.id}" }) { entry ->
                ActionRow(
                    entry = entry,
                    onOpen = { nav.navigate(Routes.task(entry.id)) },
                    onComplete = {
                        scope.launch {
                            container.tasks.complete(entry.id)
                            undo.show("Completed \"${entry.title}\"") { container.tasks.uncomplete(entry.id) }
                        }
                    },
                    onTomorrow = {
                        scope.launch {
                            container.tasks.reschedule(entry.id, dueAt = null, scheduledAt = tomorrow9(), keepExisting = false, reason = "Evening review")
                            undo.show("Moved to tomorrow") { container.tasks.uncomplete(entry.id) }
                        }
                    },
                    onSkip = {
                        scope.launch {
                            container.tasks.skip(entry.id)
                            undo.show("Skipped \"${entry.title}\"") { container.tasks.uncomplete(entry.id) }
                        }
                    },
                    onPickDate = { datePickFor = entry.id },
                )
            }
        }

        // ---------- partial ----------
        if (partial.isNotEmpty()) {
            item { SectionHeader(title = "🩹 Partially completed", subtitle = "Checklist in progress") }
            items(partial, key = { "partial-${it.id}" }) { entry ->
                TaskCard(
                    entry = entry,
                    onToggle = {
                        scope.launch {
                            container.tasks.complete(entry.id)
                            undo.show("Completed \"${entry.title}\"") { container.tasks.uncomplete(entry.id) }
                        }
                    },
                    onClick = { nav.navigate(Routes.task(entry.id)) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }

        // ---------- unscheduled ----------
        if (inbox.isNotEmpty()) {
            item { SectionHeader(title = "📥 Unscheduled", subtitle = "Give each a date or leave for later") }
            items(inbox, key = { "inbox-${it.id}" }) { entry ->
                ActionRow(
                    entry = entry,
                    onOpen = { nav.navigate(Routes.task(entry.id)) },
                    onComplete = {
                        scope.launch {
                            container.tasks.processInbox(entry.id, dueAt = now())
                            container.tasks.complete(entry.id)
                            undo.show("Completed \"${entry.title}\"") { container.tasks.uncomplete(entry.id) }
                        }
                    },
                    onTomorrow = {
                        scope.launch {
                            container.tasks.processInbox(entry.id, dueAt = tomorrow9())
                            undo.show("Scheduled for tomorrow") {
                                container.tasks.processInbox(entry.id, dueAt = null, activate = false)
                            }
                        }
                    },
                    onSkip = {
                        scope.launch {
                            container.tasks.skip(entry.id)
                            undo.show("Skipped") { container.tasks.uncomplete(entry.id) }
                        }
                    },
                    onPickDate = { datePickFor = entry.id },
                )
            }
        }

        // ---------- completed ----------
        if (completed.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "✅ Completed today",
                    subtitle = "${completed.size} finished · kept forever in History",
                    trailing = {
                        androidx.compose.material3.TextButton(onClick = { nav.navigate(Routes.HISTORY) }) {
                            Text("History")
                        }
                    },
                )
            }
            items(completed, key = { "done-${it.id}" }) { entry ->
                TaskCard(
                    entry = entry,
                    onToggle = {
                        scope.launch {
                            container.tasks.uncomplete(entry.id)
                            undo.show("Reopened \"${entry.title}\"") { container.tasks.complete(entry.id) }
                        }
                    },
                    onClick = { nav.navigate(Routes.task(entry.id)) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }

        // ---------- log of the day ----------
        if (historyToday.isNotEmpty()) {
            item { SectionHeader(title = "📜 Today's activity", subtitle = "Everything that happened") }
            items(historyToday.take(25), key = { it.id }) { event ->
                HistoryRow(event)
            }
        }

        if (missed.isEmpty() && stillPlanned.isEmpty() && completed.isEmpty() && inbox.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.CheckCircle,
                    title = "Nothing to review",
                    message = "When tasks are due, missed or unscheduled, they show up here so nothing slips away.",
                )
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { nav.navigate(Routes.INBOX) },
                    modifier = Modifier.weight(1f),
                ) { Text("Review inbox") }
                Button(
                    onClick = { nav.navigate(Routes.TODAY) },
                    modifier = Modifier.weight(1f),
                ) { Text("Back to today") }
            }
        }
    }

    // ---- date picker for "change date" ----
    datePickFor?.let { entryId ->
        var selected by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = now(),
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { datePickFor = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = java.time.Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC")).toLocalDate()
                        scope.launch {
                            container.tasks.reschedule(
                                entryId,
                                dueAt = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                                keepExisting = false,
                                reason = "Picked in evening review",
                            )
                            undo.show("Rescheduled to $date")
                        }
                    }
                    datePickFor = null
                }) { Text("Move here") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { datePickFor = null }) { Text("Cancel") }
            },
        ) {
            androidx.compose.material3.DatePicker(state = state, showModeToggle = false)
        }
    }
}

@Composable
private fun ScoreCell(value: Int, label: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionRow(
    entry: Entry,
    onOpen: () -> Unit,
    onComplete: () -> Unit,
    onTomorrow: () -> Unit,
    onSkip: () -> Unit,
    onPickDate: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        TaskCard(entry = entry, onToggle = onComplete, onClick = onOpen)
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.TextButton(onClick = onComplete) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.width(15.dp), tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(4.dp))
                Text("Complete")
            }
            androidx.compose.material3.TextButton(onClick = onTomorrow) {
                Icon(Icons.Default.EventRepeat, contentDescription = null, modifier = Modifier.width(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Tomorrow")
            }
            androidx.compose.material3.TextButton(onClick = onPickDate) {
                Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.width(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Date")
            }
            androidx.compose.material3.TextButton(onClick = onSkip) {
                Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.width(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Skip")
            }
        }
    }
}

private fun tomorrow9(): Long =
    LocalDate.now().plusDays(1).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
