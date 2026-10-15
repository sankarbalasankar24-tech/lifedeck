package com.lifedeck.app.ui.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.components.LocalUndo
import com.lifedeck.app.ui.Routes
import com.lifedeck.app.ui.components.EmptyState
import com.lifedeck.app.ui.components.SectionHeader
import com.lifedeck.app.ui.components.TaskCard
import com.lifedeck.app.util.dateLabelFor
import com.lifedeck.app.util.formatTime
import com.lifedeck.app.util.weekStart
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Calendar: month grid with density dots, week strip, day agenda and a
 * chronological activity timeline for the selected day.
 */
@Composable
fun CalendarScreen(nav: NavController) {
    val container = LocalAppContainer.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(CalendarMode.MONTH) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var monthAnchor by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }

    val open by container.tasks.observeOpen().collectAsStateWithLifecycle(initialValue = emptyList())
    val inbox by container.tasks.observeInbox().collectAsStateWithLifecycle(initialValue = emptyList())
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Calendar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Days, weeks, months and the full timeline",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { selected = LocalDate.now(); monthAnchor = LocalDate.now().withDayOfMonth(1) }) { Text("Today") }
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    CalendarMode.entries.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = CalendarMode.entries.size),
                        ) { Text(m.label) }
                    }
                }
            }
        }

        // ---------- month header ----------
        if (mode == CalendarMode.MONTH || mode == CalendarMode.WEEK) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        monthAnchor = if (mode == CalendarMode.MONTH) monthAnchor.minusMonths(1)
                        else weekStart(selected.minusWeeks(1))
                    }) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous") }
                    Text(
                        if (mode == CalendarMode.MONTH) monthAnchor.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))
                        else weekStart(selected).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")) + " week",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = {
                        monthAnchor = if (mode == CalendarMode.MONTH) monthAnchor.plusMonths(1)
                        else weekStart(selected.plusWeeks(1))
                    }) { Icon(Icons.Default.ChevronRight, contentDescription = "Next") }
                }
            }
        }

        // ---------- weekday header ----------
        if (mode == CalendarMode.MONTH) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
                        Text(
                            d,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // ---------- month grid ----------
            item {
                val first = weekStart(monthAnchor)
                val days = (0 until 42).map { first.plusDays(it.toLong()) }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .padding(horizontal = 12.dp),
                    userScrollEnabled = false,
                ) {
                    items(days, key = { it.toString() }) { date ->
                        val count = entriesOn(date).size
                        val inMonth = date.monthValue == monthAnchor.monthValue
                        DayCell(
                            date = date,
                            count = count,
                            inMonth = inMonth,
                            isToday = date == LocalDate.now(),
                            isSelected = date == selected,
                            onClick = { selected = date; mode = CalendarMode.DAY },
                        )
                    }
                }
            }
        }

        // ---------- week strip ----------
        if (mode == CalendarMode.WEEK) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (0 until 7).forEach { i ->
                        val date = weekStart(selected).plusDays(i.toLong())
                        DayCell(
                            date = date,
                            count = entriesOn(date).size,
                            inMonth = true,
                            isToday = date == LocalDate.now(),
                            isSelected = date == selected,
                            modifier = Modifier.weight(1f),
                            onClick = { selected = date },
                        )
                    }
                }
            }
        }

        // ---------- agenda ----------
        item {
            SectionHeader(
                title = dateLabelFor(selected),
                subtitle = "${selectedEntries.size} scheduled · ${dayHistory.size} events",
            )
        }
        if (selectedEntries.isEmpty() && dayHistory.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Event,
                    title = "Nothing on this day",
                    message = "Tap another date, or capture something for this day.",
                )
            }
        }
        items(selectedEntries, key = { "cal-${it.id}" }) { entry ->
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

        // ---------- timeline ----------
        if (dayHistory.isNotEmpty()) {
            item { SectionHeader(title = "Timeline", subtitle = "Everything that happened on this day") }
            items(dayHistory.take(30), key = { it.id }) { event ->
                com.lifedeck.app.ui.components.HistoryRow(
                    event = event,
                    onClick = {
                        if (event.entityType.name == "ENTRY") nav.navigate(Routes.task(event.entityId))
                    },
                )
            }
        }

        // ---------- unscheduled reminder ----------
        if (inbox.isNotEmpty()) {
            item {
                Text(
                    "${inbox.size} unscheduled items are not on any date yet — review them in the Inbox.",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF57F17),
                )
            }
        }
    }
}

private enum class CalendarMode(val label: String) {
    MONTH("Month"), WEEK("Week"), DAY("Day")
}

@Composable
private fun DayCell(
    date: LocalDate,
    count: Int,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isToday -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val fg = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isToday -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> if (inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    }
    Column(
        modifier = modifier
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            color = fg,
        )
        Box(
            Modifier
                .size(if (count > 0) 6.dp else 0.dp)
                .clip(CircleShape)
                .background(if (isSelected) Color.White else MaterialTheme.colorScheme.secondary),
        )
        if (count > 1) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.8f),
            )
        }
    }
}
