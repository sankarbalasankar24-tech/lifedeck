package com.lifedeck.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.components.LocalUndo
import com.lifedeck.app.ui.Routes
import com.lifedeck.app.ui.components.Chip
import com.lifedeck.app.ui.components.EmptyState
import com.lifedeck.app.ui.components.GoalCard
import com.lifedeck.app.ui.components.MemoryCard
import com.lifedeck.app.ui.components.SectionHeader
import com.lifedeck.app.ui.components.SunArt
import com.lifedeck.app.ui.components.TaskCard
import com.lifedeck.app.ui.components.encouragementFor
import com.lifedeck.app.util.formatFullDate
import com.lifedeck.app.util.isOverdue
import com.lifedeck.app.util.now
import com.lifedeck.app.util.todayStart
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Morning dashboard: today's plan, highest-priority actions, overdue items,
 * time-sensitive shopping, appointments, goal progress, pending inbox items
 * and "this day last year" memories — all from the local database.
 */
@Composable
fun TodayScreen(nav: NavController) {
    val container = LocalAppContainer.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()

    // initialValue = null means "not emitted yet", which lets us tell a real
    // empty database apart from a screen that is still loading.
    val openState by container.tasks.observeOpen().collectAsStateWithLifecycle(initialValue = null)
    val inboxState by container.tasks.observeInbox().collectAsStateWithLifecycle(initialValue = null)
    val goalsState by container.structure.observeGoals().collectAsStateWithLifecycle(initialValue = null)
    val monthDay = remember { "%02d-%02d".format(LocalDate.now().monthValue, LocalDate.now().dayOfMonth) }
    val onThisDayState by container.memories.observeOnThisDay(monthDay).collectAsStateWithLifecycle(initialValue = null)

    val nowTs = remember(openState) { now() }
    val todayStart = remember { todayStart() }
    val todayEnd = todayStart + 86_400_000L - 1

    val openList = (openState ?: emptyList()).filter { it.status == EntryStatus.OPEN }
    val inbox = inboxState ?: emptyList()
    val goals = goalsState ?: emptyList()
    val onThisDay = onThisDayState ?: emptyList()

    val overdue = remember(openList) { openList.filter { isOverdue(it.dueAt ?: it.scheduledAt) && it.dueAt != null } }
    val dueToday = remember(openList) {
        openList.filter {
            val t = it.scheduledAt ?: it.dueAt ?: it.startAt
            t != null && t in todayStart..todayEnd
        }.sortedWith(
            compareBy({ it.priority.ordinal }, { it.scheduledAt ?: it.dueAt ?: Long.MAX_VALUE }),
        )
    }
    val appointments = remember(dueToday) { dueToday.filter { it.isAppointment } }
    val shoppingToday = remember(dueToday) { dueToday.filter { it.isShopping } }
    val undated = remember(openList) { openList.filter { it.dueAt == null && it.scheduledAt == null && it.startAt == null } }

    val doneTodayState by container.tasks.observeCompletedBetween(todayStart, todayEnd + 1)
        .collectAsStateWithLifecycle(initialValue = null)
    val doneToday = doneTodayState ?: emptyList()

    val plannedCount = dueToday.size + overdue.size
    val doneCount = doneToday.size
    val ratio = if (plannedCount + doneCount == 0) 0f else doneCount.toFloat() / (plannedCount + doneCount)
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }

    val stillLoading = openState == null || inboxState == null ||
        goalsState == null || onThisDayState == null || doneTodayState == null
    if (stillLoading) {
        EmptyState(icon = Icons.Default.WbSunny, title = "Opening your day…", message = "Reading the local database")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
    ) {
        // ---------- header ----------
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        greeting(hour),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        formatFullDate(nowTs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { nav.navigate(Routes.EVENING) }) {
                    Icon(Icons.Default.History, contentDescription = "Evening review")
                }
                IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        // ---------- encouragement + progress ----------
        item {
            Surface(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
            ) {
                val startColor = MaterialTheme.colorScheme.primary
                val endColor = MaterialTheme.colorScheme.secondary
                Column(
                    Modifier.background(
                        Brush.linearGradient(
                            listOf(startColor.copy(alpha = 0.10f), endColor.copy(alpha = 0.14f)),
                        ),
                    ).padding(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.TrackChanges,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                encouragementFor(ratio, hour),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "$doneCount completed · ${plannedCount + undated.size + inbox.size} open",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
        }

        // ---------- this day last year ----------
        if (onThisDay.isNotEmpty()) {
            item {
                SectionHeader(title = "On this day", subtitle = "Memories from previous years")
            }
            items(onThisDay.take(3), key = { "mem-${it.id}" }) { memory ->
                MemoryCard(
                    memory = memory,
                    onClick = { nav.navigate(Routes.memory(memory.id)) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }

        // ---------- overdue ----------
        if (overdue.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "⚠ Needs attention",
                    subtitle = "${overdue.size} overdue — reschedule or finish",
                )
            }
            items(overdue, key = { "overdue-${it.id}" }) { entry ->
                TaskRowWithActions(
                    entry = entry,
                    nav = nav,
                    onToggle = {
                        scope.launch {
                            container.tasks.complete(entry.id)
                            undo.show("Completed \"${entry.title}\"") {
                                container.tasks.uncomplete(entry.id)
                            }
                        }
                    },
                )
            }
        }

        // ---------- appointments ----------
        if (appointments.isNotEmpty()) {
            item { SectionHeader(title = "🗓 Appointments", subtitle = "Time-sensitive today") }
            items(appointments, key = { "appt-${it.id}" }) { entry ->
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

        // ---------- time-sensitive shopping ----------
        if (shoppingToday.isNotEmpty()) {
            item { SectionHeader(title = "🛒 Time to buy", subtitle = "Shopping scheduled for today") }
            items(shoppingToday, key = { "shop-${it.id}" }) { entry ->
                TaskCard(
                    entry = entry,
                    onToggle = {
                        scope.launch {
                            container.shopping.purchase(entry.id)
                            undo.show("Purchased \"${entry.title}\"") { container.shopping.unpurchase(entry.id) }
                        }
                    },
                    onClick = { nav.navigate(Routes.task(entry.id)) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }

        // ---------- top priorities ----------
        val top = dueToday.filter { it.priority == Priority.URGENT || it.priority == Priority.HIGH }
            .take(3)
            .filter { it !in appointments && it !in shoppingToday }
        if (top.isNotEmpty()) {
            item { SectionHeader(title = "🔥 Highest priority", subtitle = "Do these first") }
            items(top, key = { "top-${it.id}" }) { entry ->
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

        // ---------- rest of today ----------
        val rest = dueToday.filter { it !in appointments && it !in shoppingToday && it !in top }
        if (rest.isNotEmpty()) {
            item { SectionHeader(title = "Today's plan", subtitle = "Ordered by urgency and time · long-press to drag") }
            items(rest, key = { "today-${it.id}" }) { entry ->
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

        // ---------- goal progress ----------
        if (goals.isNotEmpty()) {
            item { SectionHeader(title = "🎯 Goal progress", subtitle = "Short and long-term") }
            items(goals.filter { it.status != com.lifedeck.app.data.model.LifeStatus.DONE }.take(4), key = { "goal-${it.id}" }) { goal ->
                GoalProgressRow(goalId = goal.id, title = goal.title, targetDate = goal.targetDate, nav = nav)
            }
        }

        // ---------- inbox prompt ----------
        if (inbox.isNotEmpty()) {
            item {
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { nav.navigate(Routes.INBOX) },
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFFFFB300).copy(alpha = 0.12f),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Inbox, contentDescription = null, tint = Color(0xFFF57F17))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${inbox.size} item${if (inbox.size == 1) "" else "s"} waiting in your inbox",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Give each one a date, priority or category so nothing is forgotten.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { nav.navigate(Routes.INBOX) }) { Text("Review") }
                    }
                }
            }
        }

        // ---------- all done ----------
        if (dueToday.isEmpty() && overdue.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.WbSunny,
                    title = if (hour >= 18) "Everything is wrapped up" else "Your day is clear",
                    message = if (hour >= 18)
                        "Nothing left today. Enjoy the evening — unfinished items will show up in your evening review."
                    else
                        "Capture something in Quick Add, or review your inbox for unscheduled ideas.",
                )
            }
        }
    }
}

@Composable
private fun TaskRowWithActions(entry: Entry, nav: NavController, onToggle: () -> Unit) {
    TaskCard(
        entry = entry,
        onToggle = onToggle,
        onClick = { nav.navigate(Routes.task(entry.id)) },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun GoalProgressRow(
    goalId: String,
    title: String,
    targetDate: Long?,
    nav: NavController,
) {
    val container = LocalAppContainer.current
    val progress by container.structure.observeGoalProgress(goalId)
        .collectAsStateWithLifecycle(initialValue = com.lifedeck.app.data.model.Progress(goalId, 0, 0))
    val pct = progress.percent
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { nav.navigate(Routes.goal(goalId)) },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "$pct%",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun StatPill(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private fun greeting(hour: Int): String = when {
    hour < 5 -> "Still up 🌌"
    hour < 12 -> "Good morning ☀️"
    hour < 17 -> "Good afternoon 🌤"
    hour < 21 -> "Good evening 🌙"
    else -> "Good night 🌠"
}
