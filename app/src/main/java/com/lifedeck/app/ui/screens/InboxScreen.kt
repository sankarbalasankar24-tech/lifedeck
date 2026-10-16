package com.lifedeck.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.filled.Search
import com.lifedeck.app.ui.components.GlobalSearchDialog
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.domain.CaptureParser
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.components.LocalUndo
import com.lifedeck.app.ui.Routes
import com.lifedeck.app.ui.components.Chip
import com.lifedeck.app.ui.components.EmptyState
import com.lifedeck.app.ui.components.ReorderColumn
import com.lifedeck.app.ui.components.SectionHeader
import com.lifedeck.app.ui.components.TaskCard
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import com.lifedeck.app.util.todayStart
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The whiteboard: everything captured without a home, waiting to be reviewed.
 * Items stay here until they get a date, priority, category or project — and
 * the end-of-day prompt (a banner after the configured hour) nudges review.
 */
@Composable
fun InboxScreen(nav: NavController) {
    val container = LocalAppContainer.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsStateWithLifecycle(
        initialValue = com.lifedeck.app.data.repo.AppSettings(),
    )

    val inbox by container.tasks.observeInbox().collectAsStateWithLifecycle(initialValue = emptyList())
    var draft by remember { mutableStateOf("") }
    var showCalendarPopup by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }

    fun capture(raw: String) {
        if (raw.isBlank()) return
        val parsed = CaptureParser.parse(raw)
        scope.launch {
            container.tasks.create(
                com.lifedeck.app.data.model.Entry(
                    id = newId(),
                    title = parsed.title,
                    notes = "",
                    type = parsed.type,
                    status = if (parsed.dueAt != null) EntryStatus.ACTIVE else EntryStatus.INBOX,
                    dueAt = parsed.dueAt,
                    scheduledAt = parsed.scheduledAt,
                    priority = parsed.priority,
                    recurrence = parsed.recurrence,
                    bucket = parsed.bucket,
                    contextHint = parsed.contextHint,
                    estimateMinutes = parsed.estimateMinutes,
                    createdAt = now(),
                    updatedAt = now(),
                ),
                summary = "Captured to inbox",
            )
            container.scheduler.reconcileAsync()
        }
        draft = ""
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Inbox", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Capture now, organize later",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Global Universal Search Trigger
            IconButton(
                onClick = { showSearchDialog = true },
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Universal Global Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Interactive Calendar Month Grid Trigger
            IconButton(
                onClick = { showCalendarPopup = true },
            ) {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = "Interactive Calendar Month Grid",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (inbox.isNotEmpty()) {
                Text(
                    "${inbox.size} waiting",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFF57F17),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        // ---- inline capture ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Whiteboard: type anything…") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { capture(draft) }, enabled = draft.isNotBlank()) {
                        Icon(Icons.Default.Send, contentDescription = "Add to inbox")
                    }
                },
            )
        }

        // ---- end-of-day prompt ----
        val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
        val promptDue = settings.inboxPromptEnabled &&
            hour >= settings.inboxPromptHour && inbox.isNotEmpty()
        if (promptDue) {
            Surface(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = Color(0xFFFFB300).copy(alpha = 0.16f),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.EventAvailable, contentDescription = null, tint = Color(0xFFF57F17))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "End-of-day review",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${inbox.size} capture${if (inbox.size == 1) "" else "s"} still have no date. Two minutes now saves them tomorrow.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { nav.navigate(Routes.EVENING) }) { Text("Review") }
                }
            }
        }

        if (inbox.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Inbox,
                title = "Inbox zero ✨",
                message = "Ideas, errands and plans you capture land here until you give them a home.",
                action = {
                    TextButton(onClick = { nav.navigate(Routes.TODAY) }) { Text("See today") }
                },
            )
        } else {
            SectionHeader(
                title = "Whiteboard",
                subtitle = "Tap to schedule · long-press to drag into order",
                trailing = {
                    TextButton(onClick = { nav.navigate(Routes.EVENING) }) { Text("Process all") }
                },
            )

            ReorderColumn(
                items = inbox.map { it.id },
                onMove = { from, to ->
                    val ordered = inbox.toMutableList()
                    val moved = ordered.removeAt(from)
                    ordered.add(to, moved)
                    scope.launch { container.tasks.reorder(ordered.map { it.id }) }
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { _, id, isDragging ->
                val entry = inbox.firstOrNull { it.id == id } ?: return@ReorderColumn
                InboxCard(
                    entry = entry,
                    onOpen = { nav.navigate(Routes.task(entry.id)) },
                    onScheduleToday = {
                        scope.launch {
                            container.tasks.processInbox(entry.id, dueAt = todayStart() + 36 * 360_000L)
                            undo.show("Scheduled \"${entry.title}\" for today") {
                                container.tasks.processInbox(entry.id, dueAt = null, activate = false)
                            }
                        }
                    },
                    onScheduleTomorrow = {
                        scope.launch {
                            val tomorrow = LocalDate.now().plusDays(1)
                            container.tasks.processInbox(
                                entry.id,
                                dueAt = tomorrow.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            )
                            undo.show("Moved \"${entry.title}\" to tomorrow") {
                                container.tasks.processInbox(entry.id, dueAt = null, activate = false)
                            }
                        }
                    },
                    isDragging = isDragging,
                )
            }
        }

        Spacer(Modifier.height(96.dp))
    }

        if (showSearchDialog) {
            GlobalSearchDialog(
                onDismiss = { showSearchDialog = false },
                nav = nav,
            )
        }

        if (showCalendarPopup) {
            InboxCalendarDialog(
                onDismiss = { showCalendarPopup = false },
                nav = nav,
            )
        }

}

@Composable
private fun InboxCard(
    entry: Entry,
    onOpen: () -> Unit,
    onScheduleToday: () -> Unit,
    onScheduleTomorrow: () -> Unit,
    isDragging: Boolean,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        val container = LocalAppContainer.current
        val scope = rememberCoroutineScope()
        TaskCard(
            entry = entry,
            onToggle = onScheduleToday,
            onToggleMemory = {
                scope.launch {
                    container.tasks.toggleFavorite(entry.id, container.memories)
                }
            },
            onClick = onOpen,
            trailing = {
                Chip(
                    text = entry.type.name.lowercase().replace('_', ' '),
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            },
        )
        Row(
            Modifier.padding(start = 4.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onScheduleToday) {
                Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.width(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Today")
            }
            TextButton(onClick = onScheduleTomorrow) {
                Icon(Icons.Default.AddTask, contentDescription = null, modifier = Modifier.width(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Tomorrow")
            }
        }
    }
}

@Composable
private fun InboxCalendarDialog(
    onDismiss: () -> Unit,
    nav: NavController,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var monthAnchor by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    val open by container.tasks.observeOpen().collectAsStateWithLifecycle(initialValue = emptyList())
    val history by container.history.observe(limit = 300).collectAsStateWithLifecycle(initialValue = emptyList())

    fun entriesOn(date: LocalDate): List<Entry> {
        val start = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = start + 86_400_000L
        return open.filter { e ->
            listOfNotNull(e.scheduledAt, e.dueAt, e.startAt).any { it in start until end }
        }.sortedBy { it.scheduledAt ?: it.dueAt ?: Long.MAX_VALUE }
    }

    val selectedEntries = entriesOn(selected)
    val dayHistory = history.filter {
        val d = java.time.Instant.ofEpochMilli(it.occurredAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        d == selected
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Calendar Overview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Completed & pending by date",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        selected = LocalDate.now()
                        monthAnchor = LocalDate.now().withDayOfMonth(1)
                    }) {
                        Text("Today")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { monthAnchor = monthAnchor.minusMonths(1) }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                    }
                    Text(
                        monthAnchor.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")),
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = { monthAnchor = monthAnchor.plusMonths(1) }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth()) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
                        Text(
                            d,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                val first = com.lifedeck.app.util.weekStart(monthAnchor)
                val days = (0 until 42).map { first.plusDays(it.toLong()) }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    userScrollEnabled = false,
                ) {
                    items(days, key = { it.toString() }) { date ->
                        val openCount = entriesOn(date).size
                        val doneCount = history.count {
                            java.time.Instant.ofEpochMilli(it.occurredAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == date
                        }
                        val inMonth = date.monthValue == monthAnchor.monthValue
                        val isToday = date == LocalDate.now()
                        val isSel = date == selected

                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .height(34.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isSel -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                        isToday -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> Color.Transparent
                                    }
                                )
                                .then(
                                    if (isSel) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    else Modifier
                                )
                                .clickable { selected = date },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (inMonth) {
                                        if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    },
                                    fontWeight = if (isToday || isSel) FontWeight.Bold else FontWeight.Normal,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (openCount > 0) {
                                        Box(
                                            Modifier
                                                .size(4.dp)
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(Color(0xFFF57F17))
                                        )
                                    }
                                    if (doneCount > 0) {
                                        Box(
                                            Modifier
                                                .size(4.dp)
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(Color(0xFF10B981))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            text = com.lifedeck.app.util.dateLabelFor(selected),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${selectedEntries.size} pending · ${dayHistory.size} completed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(6.dp))

                        if (selectedEntries.isEmpty() && dayHistory.isEmpty()) {
                            Text(
                                "No tasks scheduled for this day.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        } else {
                            selectedEntries.forEach { entry ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        onClick = {
                                            scope.launch { container.tasks.complete(entry.id) }
                                        },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Done",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        entry.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            scope.launch { container.tasks.toggleFavorite(entry.id, container.memories) }
                                        },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            if (entry.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = "Memory",
                                            modifier = Modifier.size(14.dp),
                                            tint = if (entry.favorite) Color(0xFFFF6D9D) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        )
                                    }
                                }
                            }
                            dayHistory.forEach { ev ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = Color(0xFF10B981),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        ev.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
