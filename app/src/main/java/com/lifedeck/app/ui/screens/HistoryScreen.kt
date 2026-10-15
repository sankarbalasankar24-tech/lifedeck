package com.lifedeck.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.Memory
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.Routes
import com.lifedeck.app.ui.components.Chip
import com.lifedeck.app.ui.components.EmptyState
import com.lifedeck.app.ui.components.HistoryRow
import com.lifedeck.app.ui.components.MemoryCard
import com.lifedeck.app.ui.components.SectionHeader
import com.lifedeck.app.ui.components.SunArt
import com.lifedeck.app.util.dayHeading
import com.lifedeck.app.util.formatFullDate
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * History & Memories: permanent record of completed work, purchases and
 * milestones (timeline), the searchable journal, and the memory album with
 * "on this day" anniversaries.
 */
@Composable
fun HistoryScreen(nav: NavController) {
    val container = LocalAppContainer.current
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("History & Memories", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Nothing finished is ever forgotten",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Memories") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Timeline") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Search") })
        }

        when (tab) {
            0 -> MemoriesTab(nav)
            1 -> TimelineTab(nav)
            else -> SearchTab(nav)
        }
    }
}

// ================================================================ MEMORIES

@Composable
private fun MemoriesTab(nav: NavController) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val memories by container.memories.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val monthDay = remember { "%02d-%02d".format(LocalDate.now().monthValue, LocalDate.now().dayOfMonth) }
    val onThisDay by container.memories.observeOnThisDay(monthDay).collectAsStateWithLifecycle(initialValue = emptyList())
    val upcoming by container.memories.observeUpcomingAnniversaries(30).collectAsStateWithLifecycle(initialValue = emptyList())
    var showCreate by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (onThisDay.isNotEmpty()) {
            item {
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("On this day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(
                            "From previous years — tap to revisit",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                        )
                        Spacer(Modifier.height(8.dp))
                        onThisDay.take(3).forEach { m ->
                            Text(
                                "· ${m.title} — ${formatFullDate(m.occurredAt)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        if (upcoming.isNotEmpty()) {
            item { SectionHeader(title = "Upcoming anniversaries", subtitle = "Within the next 30 days") }
            items(upcoming, key = { "ann-${it.id}" }) { m ->
                MemoryCard(memory = m, onClick = { nav.navigate(Routes.memory(m.id)) }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
        }

        item {
            SectionHeader(
                title = "All memories",
                subtitle = "${memories.size} kept forever",
                trailing = {
                    TextButton(onClick = { showCreate = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("New")
                    }
                },
            )
        }

        if (memories.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.FavoriteBorder,
                    title = "No memories yet",
                    message = "Heart a completed task, milestone or goal — it becomes a memory with anniversary reminders.",
                    action = { Button(onClick = { showCreate = true }) { Text("Record a memory") } },
                )
            }
        }

        items(memories, key = { it.id }) { memory ->
            MemoryCard(
                memory = memory,
                onClick = { nav.navigate(Routes.memory(memory.id)) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }

    if (showCreate) {
        var title by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New memory") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("What happened?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Details") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    scope.launch {
                        container.memories.create(
                            Memory(
                                id = newId(),
                                title = title.trim(),
                                description = description.trim(),
                                occurredAt = now(),
                                remindYearly = true,
                                createdAt = now(),
                                updatedAt = now(),
                            ),
                        )
                    }
                    showCreate = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
        )
    }
}

// ================================================================ TIMELINE

@Composable
private fun TimelineTab(nav: NavController) {
    val container = LocalAppContainer.current
    val events by container.history.observe(limit = 500).collectAsStateWithLifecycle(initialValue = emptyList())
    var filter by remember { mutableStateOf("All") }

    val filters = listOf(
        "All", "Completed", "Rescheduled", "Purchased", "Memories", "Created", "Deleted",
    )
    val filtered = events.filter { e ->
        when (filter) {
            "All" -> true
            "Completed" -> e.action.name in setOf("COMPLETED", "MILESTONE_DONE", "PROJECT_DONE", "GOAL_DONE")
            "Rescheduled" -> e.action.name in setOf("RESCHEDULED", "SKIPPED")
            "Purchased" -> e.action.name in setOf("PURCHASED", "UNPURCHASED")
            "Memories" -> e.action.name == "MEMORY_CREATED" || e.entityType == EntityKind.MEMORY
            "Created" -> e.action.name == "CREATED"
            "Deleted" -> e.action.name in setOf("DELETED", "RESTORED")
            else -> true
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filters) { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.History,
                    title = "Nothing here yet",
                    message = "Completed tasks, purchases, reschedules and milestones all land in this timeline automatically.",
                )
            }
        }

        var lastHeading = ""
        filtered.forEach { event ->
            val heading = dayHeading(event.occurredAt)
            if (heading != lastHeading) {
                lastHeading = heading
                item(key = "h-$heading-${event.id}") {
                    SectionHeader(title = heading)
                }
            }
            item(key = event.id) {
                HistoryRow(
                    event = event,
                    onClick = {
                        when (event.entityType) {
                            EntityKind.ENTRY -> nav.navigate(Routes.task(event.entityId))
                            EntityKind.GOAL -> nav.navigate(Routes.goal(event.entityId))
                            EntityKind.PROJECT -> nav.navigate(Routes.project(event.entityId))
                            EntityKind.MEMORY -> nav.navigate(Routes.memory(event.entityId))
                            else -> Unit
                        }
                    },
                )
            }
        }
    }
}

// ================================================================== SEARCH

@Composable
private fun SearchTab(nav: NavController) {
    val container = LocalAppContainer.current
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<EntityKind?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var rangeDays by remember { mutableStateOf<Int?>(null) }
    var results by remember { mutableStateOf<List<com.lifedeck.app.data.model.SearchHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(query, kind, status, rangeDays) {
        if (query.isBlank() && kind == null && status == null && rangeDays == null) {
            results = emptyList()
            searched = false
        } else {
            val now = now()
            val filters = com.lifedeck.app.data.repo.SearchRepository.Filters(
                query = query,
                from = rangeDays?.let { now - it * 86_400_000L },
                status = status,
                kinds = kind?.let { setOf(it) } ?: com.lifedeck.app.data.repo.SearchRepository.Filters().kinds,
            )
            results = container.search.search(filters)
            searched = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            placeholder = { Text("Search tasks, purchases, goals, notes, memories…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = kind == null,
                    onClick = { kind = null },
                    label = { Text("Everything") },
                )
            }
            items(
                listOf(
                    EntityKind.ENTRY to "Tasks",
                    EntityKind.GOAL to "Goals",
                    EntityKind.PROJECT to "Projects",
                    EntityKind.MEMORY to "Memories",
                    EntityKind.HISTORY to "Ledger",
                ),
                key = { it.first.name },
            ) { (k, label) ->
                FilterChip(selected = kind == k, onClick = { kind = if (kind == k) null else k }, label = { Text(label) })
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(listOf(null to "Any time", 1 to "Today", 7 to "7 days", 30 to "30 days", 365 to "Year"), key = { it.second }) { (days, label) ->
                FilterChip(selected = rangeDays == days, onClick = { rangeDays = days }, label = { Text(label) })
            }
            items(listOf("DONE" to "Done", "OPEN" to "Open", "INBOX" to "Inbox"), key = { it.first }) { (st, label) ->
                FilterChip(selected = status == st, onClick = { status = if (status == st) null else st }, label = { Text(label) })
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (!searched) {
                item {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "Everything is searchable",
                        message = "Active and completed tasks, shopping history, projects, goals, appointments, notes and memories — with filters.",
                    )
                }
            } else if (results.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "No matches",
                        message = "Try a different keyword, widen the date range, or clear the filters.",
                    )
                }
            } else {
                item { SectionHeader(title = "${results.size} result${if (results.size == 1) "" else "s"}") }
                items(results, key = { "${it.kind}-${it.id}" }) { hit ->
                    androidx.compose.material3.Surface(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                            .clickable {
                                when (hit.kind) {
                                    EntityKind.ENTRY -> nav.navigate(Routes.task(hit.id))
                                    EntityKind.GOAL -> nav.navigate(Routes.goal(hit.id))
                                    EntityKind.PROJECT -> nav.navigate(Routes.project(hit.id))
                                    EntityKind.MEMORY -> nav.navigate(Routes.memory(hit.id))
                                    else -> Unit
                                }
                            },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(hit.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2)
                                Text(
                                    hit.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Chip(
                                text = hit.kind.name.lowercase(),
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
