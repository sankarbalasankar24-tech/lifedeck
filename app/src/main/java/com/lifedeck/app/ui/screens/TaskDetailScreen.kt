package com.lifedeck.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.data.model.EntryType
import com.lifedeck.app.data.model.HistoryAction
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.data.model.ReminderRecord
import com.lifedeck.app.data.model.ReminderStatus
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.components.LocalUndo
import com.lifedeck.app.ui.Routes
import com.lifedeck.app.ui.components.Chip
import com.lifedeck.app.ui.components.HistoryRow
import com.lifedeck.app.ui.components.LoadingState
import com.lifedeck.app.ui.components.PriorityDot
import com.lifedeck.app.ui.components.SectionHeader
import com.lifedeck.app.domain.RecurrenceEngine
import com.lifedeck.app.util.Photos
import com.lifedeck.app.util.dueLabel
import com.lifedeck.app.util.formatDateTime
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Full editor for one entry: dates, priority, category, recurrence, context
 * window, subtasks, checklist, dependencies, multiple reminders (with
 * escalation), photos, and its complete rescheduling history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(taskId: String, nav: NavController) {
    val container = LocalAppContainer.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()

    val entry by container.tasks.observeById(taskId).collectAsStateWithLifecycle(initialValue = null)
    val children by container.tasks.observeChildren(taskId).collectAsStateWithLifecycle(initialValue = emptyList())
    val reminders by container.tasks.observeReminders(taskId).collectAsStateWithLifecycle(initialValue = emptyList())
    val history by container.history.observeFor(taskId).collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by container.categories.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = com.lifedeck.app.data.repo.AppSettings())

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showAddReminder by remember { mutableStateOf(false) }
    var showAddSubtask by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var newChecklist by remember { mutableStateOf("") }
    var addDependencyOpen by remember { mutableStateOf(false) }

    val e = entry
    if (e == null) {
        LoadingState(label = "Loading task…")
        return
    }
    val done = e.status == EntryStatus.DONE
    var scopeName by remember(e.id, e.milestoneId, e.projectId, e.goalId) { mutableStateOf<String?>(null) }
    LaunchedEffect(e.id, e.milestoneId, e.projectId, e.goalId) {
        scopeName = when {
            e.milestoneId != null -> container.structure.milestone(e.milestoneId!!)?.title
            e.projectId != null -> container.structure.project(e.projectId!!)?.title
            e.goalId != null -> container.structure.goal(e.goalId!!)?.title
            else -> null
        }
    }

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val path = Photos.import(container.context, uri, taskId)
                if (path != null) {
                    container.tasks.update(e.copy(photos = e.photos + path), summary = "Photo added")
                    container.memories.createFromEntry(e, photos = listOf(path))
                }
            }
        }
    }

    Scaffold { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            // ---------------- header ----------------
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        scope.launch {
                            val nowFav = container.tasks.toggleFavorite(e.id)
                            if (nowFav) {
                                container.memories.createFromEntry(e)
                                undo.show("Added to memories") {
                                    container.tasks.toggleFavorite(e.id)
                                }
                            }
                        }
                    }) {
                        Icon(
                            if (e.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Mark as memory",
                            tint = if (e.favorite) Color(0xFFFF6D9D) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = "Add photo", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // ---------------- title & status ----------------
            item {
                var title by remember(e.id, e.title) { mutableStateOf(e.title) }
                var titleSaved by remember(e.id) { mutableStateOf(e.title) }
                fun persistTitle() {
                    if (title.isNotBlank() && title != titleSaved) {
                        val newTitle = title
                        titleSaved = newTitle
                        scope.launch { container.tasks.update(e.copy(title = newTitle), summary = "Renamed") }
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { newValue ->
                        title = newValue
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .onFocusChanged { state ->
                            if (!state.isFocused) persistTitle()
                        },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { persistTitle() }),
                    minLines = 1,
                    maxLines = 4,
                )
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Chip(
                        text = e.type.name.lowercase().replace('_', ' '),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Chip(
                        text = e.status.name.lowercase(),
                        containerColor = com.lifedeck.app.ui.components.statusColor(e.status.name).copy(alpha = 0.16f),
                        contentColor = com.lifedeck.app.ui.components.statusColor(e.status.name),
                    )
                    scopeName?.let { Chip(text = it) }
                    if (e.recurrence?.isActive == true) {
                        Chip(text = RecurrenceEngine.label(e.recurrence))
                    }
                }
            }

            // ---------------- progress ----------------
            item {
                if (e.checklistProgress != null || children.isNotEmpty()) {
                    val finishedSubs = children.count { it.status == EntryStatus.DONE }
                    Surface(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            val total = children.size
                            val finished = finishedSubs
                            if (total > 0) {
                                Row {
                                    Text(
                                        "Subtasks $finished/$total",
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("${(if (total == 0) 0 else finished * 100 / total)}%", style = MaterialTheme.typography.labelLarge)
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { if (total == 0) 0f else finished.toFloat() / total },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(7.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                            }
                            e.checklistProgress?.let { (ok, all) ->
                                Spacer(Modifier.height(8.dp))
                                Row {
                                    Text("Checklist $ok/$all", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { if (all == 0) 0f else ok.toFloat() / all },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(7.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    color = MaterialTheme.colorScheme.secondary,
                                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                            }
                        }
                    }
                }
            }

            // ---------------- schedule ----------------
            item {
                SectionHeader(title = "Schedule", subtitle = "Deadlines are never moved silently")
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlineAction(
                        label = e.dueAt?.let { "Due ${dueLabel(it)}" } ?: "Add date",
                        icon = Icons.Default.CalendarMonth,
                        modifier = Modifier.weight(1f),
                        onClick = { showDatePicker = true },
                    )
                    OutlineAction(
                        label = if (e.scheduledAt != null && !e.allDay) {
                            "At " + com.lifedeck.app.util.formatTime(e.scheduledAt)
                        } else "Add time",
                        icon = Icons.Default.Notifications,
                        modifier = Modifier.weight(1f),
                        onClick = { showTimePicker = true },
                    )
                }
            }

            // ---------------- fields ----------------
            item {
                SectionHeader(title = "Details")
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // priority
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Priority.entries.forEach { p ->
                            FilterChip(
                                selected = e.priority == p,
                                onClick = { scope.launch { container.tasks.update(e.copy(priority = p), summary = "Priority → ${p.name.lowercase()}") } },
                                label = { Text(p.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                                leadingIcon = { PriorityDot(p) },
                            )
                        }
                    }
                    // category
                    if (categories.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            FilterChip(
                                selected = e.categoryId == null,
                                onClick = { scope.launch { container.tasks.update(e.copy(categoryId = null), summary = "Category cleared") } },
                                label = { Text("No category") },
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(categories) { cat ->
                                    FilterChip(
                                        selected = e.categoryId == cat.id,
                                        onClick = {
                                            scope.launch {
                                                container.tasks.update(e.copy(categoryId = cat.id), summary = "Category → ${cat.name}")
                                            }
                                        },
                                        label = { Text(cat.name) },
                                    )
                                }
                            }
                        }
                    }
                    // location for appointments
                    if (e.isAppointment) {
                        OutlinedTextField(
                            value = e.location,
                            onValueChange = { v -> scope.launch { container.tasks.update(e.copy(location = v), summary = "Location updated") } },
                            label = { Text("Location") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // estimate
                    OutlinedTextField(
                        value = e.estimateMinutes?.toString() ?: "",
                        onValueChange = { v ->
                            scope.launch {
                                container.tasks.update(
                                    e.copy(estimateMinutes = v.filter { it.isDigit() }.toIntOrNull()),
                                    summary = "Estimate updated",
                                )
                            }
                        },
                        label = { Text("Estimated minutes") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // context window
                    Text("Remind me within a context window", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = e.contextHint == null,
                            onClick = { scope.launch { container.tasks.update(e.copy(contextHint = null)) } },
                            label = { Text("Any time") },
                        )
                        settings.contextWindows.keys.take(4).forEach { key ->
                            FilterChip(
                                selected = e.contextHint == key,
                                onClick = { scope.launch { container.tasks.update(e.copy(contextHint = key), summary = "Context → $key") } },
                                label = { Text(key) },
                            )
                        }
                    }
                    // notes
                    var notes by remember(e.id, e.notes) { mutableStateOf(e.notes) }
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes") },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { scope.launch { container.tasks.update(e.copy(notes = notes), summary = "Notes saved") } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save notes") }
                }
            }

            // ---------------- shopping fields ----------------
            if (e.isShopping) {
                item {
                    SectionHeader(title = "Shopping")
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = e.quantity ?: "",
                                onValueChange = { v -> scope.launch { container.tasks.update(e.copy(quantity = v.ifBlank { null })) } },
                                label = { Text("Qty") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = e.budget?.toString() ?: "",
                                onValueChange = { v -> scope.launch { container.tasks.update(e.copy(budget = v.toDoubleOrNull())) } },
                                label = { Text("Budget") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = e.store ?: "",
                            onValueChange = { v -> scope.launch { container.tasks.update(e.copy(store = v.ifBlank { null })) } },
                            label = { Text("Store / vendor") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (e.purchasedAt != null) {
                            Text(
                                "Purchased ${formatDateTime(e.purchasedAt)}" +
                                    (e.pricePaid?.let { " · paid %,.2f".format(it) } ?: ""),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    container.shopping.purchase(e.id, pricePaid = e.pricePaid)
                                    undo.show("Marked purchased") { container.shopping.unpurchase(e.id) }
                                }
                            }) { Text(if (e.purchasedAt == null) "Mark purchased" else "Purchased ✓") }
                            if (e.purchasedAt != null) {
                                OutlinedButton(onClick = {
                                    scope.launch { container.shopping.unpurchase(e.id) }
                                }) { Text("Undo") }
                            }
                        }
                    }
                }
            }

            // ---------------- subtasks ----------------
            item {
                SectionHeader(
                    title = "Subtasks",
                    subtitle = if (children.isEmpty()) "Break it down" else "${children.count { it.status == EntryStatus.DONE }} of ${children.size} done",
                    trailing = {
                        TextButton(onClick = { showAddSubtask = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Add")
                        }
                    },
                )
            }
            items(children, key = { "child-${it.id}" }) { child ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 3.dp)
                        .clickable { nav.navigate(Routes.task(child.id)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = child.status == EntryStatus.DONE,
                        onCheckedChange = {
                            scope.launch {
                                if (child.status == EntryStatus.DONE) container.tasks.uncomplete(child.id)
                                else {
                                    container.tasks.complete(child.id)
                                    undo.show("Subtask done") { container.tasks.uncomplete(child.id) }
                                }
                            }
                        },
                    )
                    Text(
                        child.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (child.status == EntryStatus.DONE) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (child.status == EntryStatus.DONE) {
                            androidx.compose.ui.text.style.TextDecoration.LineThrough
                        } else null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ---------------- checklist ----------------
            item {
                SectionHeader(title = "Checklist", subtitle = "Quick one-off checks")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newChecklist,
                        onValueChange = { newChecklist = it },
                        placeholder = { Text("Add an item…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        if (newChecklist.isBlank()) return@IconButton
                        scope.launch {
                            val items = e.checklist + com.lifedeck.app.data.model.ChecklistItem(
                                id = newId(),
                                text = newChecklist.trim(),
                            )
                            container.tasks.setChecklist(e.id, items)
                            newChecklist = ""
                        }
                    }) { Icon(Icons.Default.Add, contentDescription = "Add checklist item") }
                }
                e.checklist.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = item.done,
                            onCheckedChange = {
                                scope.launch { container.tasks.toggleChecklistItem(e.id, item.id) }
                            },
                        )
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (item.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            scope.launch {
                                container.tasks.setChecklist(e.id, e.checklist.filterNot { it.id == item.id })
                            }
                        }) { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp)) }
                    }
                }
            }

            // ---------------- dependencies ----------------
            item {
                SectionHeader(
                    title = "Depends on",
                    subtitle = "Blocked until these are done",
                    trailing = {
                        TextButton(onClick = { addDependencyOpen = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Add")
                        }
                    },
                )
                if (e.dependencyIds.isEmpty()) {
                    Text(
                        "No dependencies",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(e.dependencyIds, key = { "dep-$it" }) { depId ->
                val dep by container.tasks.observeById(depId).collectAsStateWithLifecycle(initialValue = null)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (dep?.status == EntryStatus.DONE) MaterialTheme.colorScheme.secondary
                        else Color(0xFFFFB300),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        dep?.title ?: "Missing item",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        scope.launch {
                            container.tasks.update(
                                e.copy(dependencyIds = e.dependencyIds - depId),
                                summary = "Dependency removed",
                            )
                        }
                    }) { Icon(Icons.Default.Close, contentDescription = "Remove dependency", modifier = Modifier.size(16.dp)) }
                }
            }

            // ---------------- reminders ----------------
            item {
                SectionHeader(
                    title = "Reminders",
                    subtitle = "Multiple per task · escalation and repeats supported",
                    trailing = {
                        TextButton(onClick = { showAddReminder = true }) {
                            Icon(Icons.Default.Notifications, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Add")
                        }
                    },
                )
            }
            items(reminders.filter { it.status != ReminderStatus.CANCELLED }, key = { "rem-${it.id}" }) { reminder ->
                ReminderRow(
                    reminder = reminder,
                    onRemove = {
                        scope.launch {
                            container.tasks.removeReminder(reminder.id, e.id)
                            container.scheduler.reconcileAsync()
                        }
                    },
                    onToggleEscalation = {
                        scope.launch {
                            container.tasks.updateReminder(
                                reminder.copy(
                                    escalationIntervalMinutes = if (reminder.escalationIntervalMinutes == null) settings.escalationIntervalMinutes else null,
                                    escalationMaxCount = if (reminder.escalationIntervalMinutes == null) settings.escalationMaxCount else 0,
                                    escalationFired = 0,
                                ),
                            )
                            container.scheduler.reconcileAsync()
                        }
                    },
                )
            }
            if (reminders.isEmpty()) {
                item {
                    Text(
                        "No reminders — add one, or enable default reminders in Settings.",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---------------- photos ----------------
            if (e.photos.isNotEmpty()) {
                item { SectionHeader(title = "Photos") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(e.photos) { path ->
                            val bitmap = remember(path) { Photos.decode(path, 640) }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Attachment",
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                }
            }

            // ---------------- history ----------------
            if (history.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "History & rescheduling",
                        subtitle = "${history.size} events · kept forever",
                    )
                }
                items(history.take(40), key = { it.id }) { event ->
                    HistoryRow(event)
                }
            }

            // ---------------- actions ----------------
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                if (done) {
                                    container.tasks.uncomplete(e.id)
                                    undo.show("Reopened \"${e.title}\"") { container.tasks.complete(e.id) }
                                } else {
                                    container.tasks.complete(e.id)
                                    undo.show("Completed \"${e.title}\"") { container.tasks.uncomplete(e.id) }
                                }
                                container.scheduler.reconcileAsync()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (done) "Reopen" else "Complete ✓") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val tomorrow = LocalDate.now().plusDays(1).atTime(9, 0)
                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                container.tasks.reschedule(e.id, dueAt = null, scheduledAt = tomorrow, keepExisting = false, reason = "Moved by user")
                                undo.show("Moved to tomorrow 09:00")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Tomorrow") }
                }
            }
        }
    }

    // ---------------- dialogs ----------------
    if (showDatePicker) {
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = e.dueAt ?: now(),
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                        val timePart = e.scheduledAt?.let {
                            val ldt = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime()
                            ldt.hour * 60 + ldt.minute
                        }
                        val newDue = date.atTime(timePart?.div(60) ?: 9, timePart?.rem(60) ?: 0)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        scope.launch {
                            container.tasks.reschedule(e.id, dueAt = newDue, scheduledAt = newDue, keepExisting = false, reason = "Date changed")
                            container.scheduler.reconcileAsync()
                        }
                    }
                    showDatePicker = false
                }) { Text("Save date") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            androidx.compose.material3.DatePicker(state = state, showModeToggle = false)
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val base = (e.dueAt ?: e.scheduledAt ?: now()).let {
                        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    val newAt = base.atTime(timeState.hour, timeState.minute)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    scope.launch {
                        container.tasks.reschedule(e.id, dueAt = newAt, scheduledAt = newAt, keepExisting = false, reason = "Time changed")
                        container.scheduler.reconcileAsync()
                    }
                    showTimePicker = false
                }) { Text("Save time") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = timeState) },
        )
    }

    if (showAddReminder) {
        AddReminderDialog(
            onDismiss = { showAddReminder = false },
            onAdd = { at, escalate ->
                scope.launch {
                    container.tasks.addManualReminder(
                        entryId = e.id,
                        fireAt = at,
                        escalationIntervalMinutes = if (escalate) settings.escalationIntervalMinutes else null,
                        escalationMaxCount = if (escalate) settings.escalationMaxCount else 0,
                    )
                    container.scheduler.reconcileAsync()
                }
                showAddReminder = false
            },
        )
    }

    if (showAddSubtask) {
        AddSubtaskDialog(
            onDismiss = { showAddSubtask = false },
            onCreate = { title ->
                scope.launch {
                    container.tasks.addSubtask(e.id, title)
                    container.scheduler.reconcileAsync()
                }
                showAddSubtask = false
            },
        )
    }

    if (addDependencyOpen) {
        DependencyPickerDialog(
            excludeId = e.id,
            onDismiss = { addDependencyOpen = false },
            onPick = { otherId ->
                scope.launch {
                    container.tasks.update(
                        e.copy(dependencyIds = (e.dependencyIds + otherId).distinct()),
                        summary = "Dependency added",
                    )
                }
                addDependencyOpen = false
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete \"${e.title}\"?") },
            text = { Text("It moves to trash with full undo — history is always kept.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    scope.launch {
                        container.tasks.delete(e.id)
                        nav.popBackStack()
                        undo.show("Deleted \"${e.title}\"") { container.tasks.restore(e.id) }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OutlineAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun ReminderRow(
    reminder: ReminderRecord,
    onRemove: () -> Unit,
    onToggleEscalation: () -> Unit,
) {
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 3.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = when (reminder.status) {
                    ReminderStatus.PENDING -> MaterialTheme.colorScheme.primary
                    ReminderStatus.FIRED -> MaterialTheme.colorScheme.onSurfaceVariant
                    ReminderStatus.SNOOZED -> Color(0xFFFFB300)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    formatDateTime(reminder.snoozedUntil ?: reminder.fireAt),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    listOfNotNull(
                        reminder.status.name.lowercase(),
                        reminder.escalationIntervalMinutes?.let { "escalate every ${it}m ×${reminder.escalationMaxCount}" },
                        reminder.repeat?.takeIf { it.isActive }?.let { RecurrenceEngine.label(it) },
                        if (reminder.origin.name == "AUTO") "auto" else "manual",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onToggleEscalation) {
                Text(if (reminder.escalationIntervalMinutes == null) "Escalate" else "Stop", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove reminder", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReminderDialog(onDismiss: () -> Unit, onAdd: (Long, Boolean) -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    val timeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
    var escalate by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(date.toString())
                }
                TimePicker(state = timeState)
                androidx.compose.material3.Switch(
                    checked = escalate,
                    onCheckedChange = { escalate = it },
                )
                Text(
                    if (escalate) "Escalate: re-alert every configured interval until handled"
                    else "Single alert (escalation can be toggled later)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val at = date.atTime(timeState.hour, timeState.minute)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                onAdd(at, escalate)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showDate) {
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = now())
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) {
            androidx.compose.material3.DatePicker(state = state, showModeToggle = false)
        }
    }
}

@Composable
private fun AddSubtaskDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add subtask") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Subtask") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onCreate(title.trim()) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DependencyPickerDialog(
    excludeId: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val open by container.tasks.observeOpen().collectAsStateWithLifecycle(initialValue = emptyList())
    val options = open.filter { it.id != excludeId }.take(30)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Depends on…") },
        text = {
            if (options.isEmpty()) {
                Text("No other open tasks to link.")
            } else {
                LazyColumn(Modifier.height(320.dp)) {
                    items(options, key = { it.id }) { other ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(other.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(other.title, modifier = Modifier.weight(1f), maxLines = 2)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
