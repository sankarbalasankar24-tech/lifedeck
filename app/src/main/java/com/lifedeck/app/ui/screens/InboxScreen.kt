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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
            if (inbox.isNotEmpty()) {
                Text(
                    "${inbox.size} waiting",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFF57F17),
                    modifier = Modifier.padding(end = 12.dp),
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
        TaskCard(
            entry = entry,
            onToggle = onScheduleToday,
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
