package com.lifedeck.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.Routes
import com.lifedeck.app.ui.components.EmptyState
import com.lifedeck.app.ui.components.LocalUndo
import com.lifedeck.app.util.formatTime
import com.lifedeck.app.util.isOverdue
import com.lifedeck.app.util.now
import com.lifedeck.app.util.todayStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today tab dashboard:
 * - Slim 48dp persistent memory banner that stays visible all day
 * - Dynamic circular progress ring with color changing as % completion increases
 * - Unified priority stream with single vertical list (Overdue, Due/Relevant Now, Upcoming Later Today)
 * - Row display with Checkbox, Title, Category, and Target Time
 * - Ticking triggers instant local-first strike-through, logs a timestamped row to backend Google Sheet,
 *   and removes it from the active screen.
 * - Overdue items include a quick reschedule button.
 * - Morning & evening briefs are excluded here and delivered via rich notifications / wake up cards.
 */
@Composable
fun TodayScreen(nav: NavController) {
    val container = LocalAppContainer.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()

    val openState by container.tasks.observeOpen().collectAsStateWithLifecycle(initialValue = null)
    val monthDay = remember { "%02d-%02d".format(LocalDate.now().monthValue, LocalDate.now().dayOfMonth) }
    val onThisDayState by container.memories.observeOnThisDay(monthDay).collectAsStateWithLifecycle(initialValue = null)
    val nowTs = remember(openState) { now() }
    val todayStart = remember { todayStart() }
    val todayEnd = todayStart + 86_400_000L - 1

    // Local map of entries currently in "strike-through" transitioning state
    val completingIds = remember { mutableStateMapOf<String, Boolean>() }

    val openList = (openState ?: emptyList()).filter { it.status != EntryStatus.DONE }
    val onThisDay = onThisDayState ?: emptyList()

    // 1. Overdue items
    val overdue = remember(openList) {
        openList.filter { isOverdue(it.dueAt ?: it.scheduledAt) && it.dueAt != null }
            .sortedBy { it.dueAt ?: it.scheduledAt ?: Long.MAX_VALUE }
    }

    // 2. Due or Relevant Now: scheduled within current 2-hour window or marked URGENT/HIGH for today
    val relevantNow = remember(openList, overdue) {
        openList.filter { entry ->
            if (entry in overdue) return@filter false
            val t = entry.scheduledAt ?: entry.dueAt ?: entry.startAt
            val isScheduledNearNow = t != null && t in (nowTs - 1800_000L)..(nowTs + 7200_000L)
            val isUrgentToday = (entry.priority == Priority.URGENT || entry.priority == Priority.HIGH) &&
                t != null && t in todayStart..todayEnd
            isScheduledNearNow || isUrgentToday
        }.sortedWith(compareBy({ it.priority.ordinal }, { it.scheduledAt ?: it.dueAt ?: Long.MAX_VALUE }))
    }

    // 3. Upcoming Later Today (and undated active items for today)
    val upcomingLater = remember(openList, overdue, relevantNow) {
        openList.filter { entry ->
            entry !in overdue && entry !in relevantNow
        }.sortedWith(compareBy({ it.scheduledAt ?: it.dueAt ?: Long.MAX_VALUE }, { it.priority.ordinal }))
    }

    val doneTodayState by container.tasks.observeCompletedBetween(todayStart, todayEnd + 1)
        .collectAsStateWithLifecycle(initialValue = null)
    val doneToday = doneTodayState ?: emptyList()

    val plannedCount = openList.size
    val doneCount = doneToday.size
    val ratio = if (plannedCount + doneCount == 0) 0f else (doneCount.toFloat() / (plannedCount + doneCount)).coerceIn(0f, 1f)
    val percent = (ratio * 100).toInt()

    val stillLoading = openState == null || onThisDayState == null || doneTodayState == null
    if (stillLoading) {
        EmptyState(icon = Icons.Default.WbSunny, title = "Opening your day…", message = "Reading the local database")
        return
    }

    // Background engine daily query: auto-injects if (Current Date - Memory Date) equals 6 months, 1 year, or N years
    val dailyMilestoneMemory = remember(allMemoriesState, onThisDay) {
        val today = LocalDate.now()
        allMemoriesState.firstOrNull { mem ->
            val memDate = Instant.ofEpochMilli(mem.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()
            val diffYears = today.year - memDate.year
            val isSameMonthDay = today.monthValue == memDate.monthValue && Math.abs(today.dayOfMonth - memDate.dayOfMonth) <= 1
            val sixMonthDate = memDate.plusMonths(6)
            val isSixMonths = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(sixMonthDate, today)) <= 2
            (diffYears >= 1 && isSameMonthDay) || isSixMonths
        } ?: onThisDay.firstOrNull() ?: allMemoriesState.firstOrNull()
    }

    val milestoneText = remember(dailyMilestoneMemory) {
        if (dailyMilestoneMemory != null) {
            val today = LocalDate.now()
            val memDate = Instant.ofEpochMilli(dailyMilestoneMemory.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()
            val diffYears = today.year - memDate.year
            val sixMonthDate = memDate.plusMonths(6)
            val isSixMonths = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(sixMonthDate, today)) <= 2
            if (isSixMonths && diffYears < 1) {
                "6 months ago \"${dailyMilestoneMemory.title}\""
            } else {
                val diff = diffYears.coerceAtLeast(1)
                "$diff year${if (diff == 1) "" else "s"} ago \"${dailyMilestoneMemory.title}\""
            }
        } else {
            "1 year ago \"First day of school\""
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        // -------------------------------------------------------------
        // 1. PERSISTENT MEMORY BANNER: Single slim 48dp card at the top
        // -------------------------------------------------------------
        item {
            PersistentMemoryBanner(
                milestoneText = milestoneText,
                onClick = {
                    if (dailyMilestoneMemory != null) {
                        nav.navigate(Routes.memory(dailyMilestoneMemory.id))
                    } else {
                        nav.navigate(Routes.HISTORY)
                    }
                },
            )
        }

        // -------------------------------------------------------------
        // 2. DYNAMIC PROGRESS RING CARD: Dynamic changing colors
        // -------------------------------------------------------------
        item {
            DynamicProgressCard(
                ratio = ratio,
                percent = percent,
                doneCount = doneCount,
                openCount = openList.size,
            )
        }

        // -------------------------------------------------------------
        // 3. UNIFIED PRIORITY STREAM: Single vertical list
        // -------------------------------------------------------------

        // Stream Section A: OVERDUE ITEMS
        if (overdue.isNotEmpty()) {
            item {
                StreamSectionHeader(
                    title = "Overdue",
                    count = overdue.size,
                    accentColor = Color(0xFFEF4444),
                )
            }
            items(overdue, key = { "od-${it.id}" }) { entry ->
                val isStriking = completingIds[entry.id] == true
                AnimatedVisibility(
                    visible = !isStriking,
                    exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
                ) {
                    UnifiedPriorityRow(
                        entry = entry,
                        isOverdue = true,
                        isCompleting = isStriking,
                        onToggleMemory = {
                            scope.launch {
                                container.tasks.toggleFavorite(entry.id, container.memories)
                            }
                        },
                        onToggle = {
                            completingIds[entry.id] = true
                            scope.launch {
                                container.tasks.complete(entry.id)
                                try { container.syncCoordinator.syncAsync() } catch (_: Exception) {}
                                delay(320L)
                                completingIds.remove(entry.id)
                                undo.show("Completed \"${entry.title}\"") {
                                    scope.launch { container.tasks.uncomplete(entry.id) }
                                }
                            }
                        },
                        onReschedule = {
                            scope.launch {
                                val nextDue = now() + 86_400_000L
                                container.tasks.update(entry.copy(dueAt = nextDue, scheduledAt = nextDue, dirty = true))
                                try { container.syncCoordinator.syncAsync() } catch (_: Exception) {}
                                undo.show("Rescheduled to tomorrow") {
                                    scope.launch { container.tasks.update(entry) }
                                }
                            }
                        },
                        onClick = { nav.navigate(Routes.task(entry.id)) },
                    )
                }
            }
        }

        // Stream Section B: DUE OR RELEVANT NOW
        if (relevantNow.isNotEmpty()) {
            item {
                StreamSectionHeader(
                    title = "Due or Relevant Now",
                    count = relevantNow.size,
                    accentColor = Color(0xFF10B981),
                )
            }
            items(relevantNow, key = { "now-${it.id}" }) { entry ->
                val isStriking = completingIds[entry.id] == true
                AnimatedVisibility(
                    visible = !isStriking,
                    exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
                ) {
                    UnifiedPriorityRow(
                        entry = entry,
                        isOverdue = false,
                        isCompleting = isStriking,
                        onToggleMemory = {
                            scope.launch {
                                container.tasks.toggleFavorite(entry.id, container.memories)
                            }
                        },
                        onToggle = {
                            completingIds[entry.id] = true
                            scope.launch {
                                container.tasks.complete(entry.id)
                                try { container.syncCoordinator.syncAsync() } catch (_: Exception) {}
                                delay(320L)
                                completingIds.remove(entry.id)
                                undo.show("Completed \"${entry.title}\"") {
                                    scope.launch { container.tasks.uncomplete(entry.id) }
                                }
                            }
                        },
                        onClick = { nav.navigate(Routes.task(entry.id)) },
                    )
                }
            }
        }

        // Stream Section C: UPCOMING LATER TODAY
        if (upcomingLater.isNotEmpty()) {
            item {
                StreamSectionHeader(
                    title = "Upcoming Later Today",
                    count = upcomingLater.size,
                    accentColor = Color(0xFF06B6D4),
                )
            }
            items(upcomingLater, key = { "later-${it.id}" }) { entry ->
                val isStriking = completingIds[entry.id] == true
                AnimatedVisibility(
                    visible = !isStriking,
                    exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
                ) {
                    UnifiedPriorityRow(
                        entry = entry,
                        isOverdue = false,
                        isCompleting = isStriking,
                        onToggleMemory = {
                            scope.launch {
                                container.tasks.toggleFavorite(entry.id, container.memories)
                            }
                        },
                        onToggle = {
                            completingIds[entry.id] = true
                            scope.launch {
                                container.tasks.complete(entry.id)
                                try { container.syncCoordinator.syncAsync() } catch (_: Exception) {}
                                delay(320L)
                                completingIds.remove(entry.id)
                                undo.show("Completed \"${entry.title}\"") {
                                    scope.launch { container.tasks.uncomplete(entry.id) }
                                }
                            }
                        },
                        onClick = { nav.navigate(Routes.task(entry.id)) },
                    )
                }
            }
        }

        // Empty state when all tasks are checked off
        if (openList.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.WbSunny,
                    title = "All focus tasks clear!",
                    message = "Everything scheduled for today has been checked off. Take a moment to recharge or capture new thoughts.",
                )
            }
        }
    }
}

/**
 * 1. Persistent Memory Banner: Single slim 48dp card with thumbnail/icon
 * and milestone text that stays visible all day.
 */
@Composable
private fun PersistentMemoryBanner(
    milestoneText: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Small thumbnail / icon badge
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF8B5CF6).copy(alpha = 0.20f))
                    .border(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.40f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = "Milestone",
                    tint = Color(0xFFC4B5FD),
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.width(10.dp))

            // Milestone Text
            Text(
                text = milestoneText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(6.dp))

            // Subtle badge tag
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF8B5CF6).copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "Memory",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFC4B5FD),
                )
            }
        }
    }
}

/**
 * 2. Dynamic Progress Ring Card:
 * Progress ring with changing color as % completion increases.
 */
@Composable
private fun DynamicProgressCard(
    ratio: Float,
    percent: Int,
    doneCount: Int,
    openCount: Int,
) {
    // Dynamic color evaluation based on completion percentage
    val dynamicRingColor = when {
        percent >= 85 -> Color(0xFF4EDEA3) // Luminous emerald
        percent >= 55 -> Color(0xFF10B981) // Fresh mint green
        percent >= 30 -> Color(0xFFEAB308) // Bright gold
        percent > 0 -> Color(0xFFF59E0B)  // Warm amber
        else -> Color(0xFF64748B)         // Slate initial
    }

    val animatedRatio by animateFloatAsState(
        targetValue = ratio,
        animationSpec = tween(650),
        label = "ratioAnim",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            dynamicRingColor.copy(alpha = 0.22f),
        ),
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            dynamicRingColor.copy(alpha = 0.08f),
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                )
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left text details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Today's Progress",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "$doneCount of ${doneCount + openCount} commitments finished",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        percent >= 100 -> "Outstanding! All commitments completed."
                        percent >= 75 -> "Superb momentum, almost there!"
                        percent >= 40 -> "Steady progress, keep rolling."
                        percent > 0 -> "Good start, building your flow."
                        else -> "Ready for your day ahead."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = dynamicRingColor,
                )
            }

            Spacer(Modifier.width(14.dp))

            // Dynamic Progress Ring
            Box(
                modifier = Modifier.size(68.dp),
                contentAlignment = Alignment.Center,
            ) {
                val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 7.dp.toPx()
                    // Background track
                    drawCircle(
                        color = trackColor,
                        style = Stroke(width = strokeWidth),
                    )
                    // Dynamic progress arc
                    drawArc(
                        color = dynamicRingColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedRatio,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = dynamicRingColor,
                    )
                    Text(
                        text = "DONE",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Section Header for the Unified Priority Stream
 */
@Composable
private fun StreamSectionHeader(
    title: String,
    count: Int,
    accentColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(accentColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(accentColor.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor,
            )
        }
    }
}

/**
 * Unified Priority Stream Row:
 * Displays: Checkbox, Title, Category, Target Time.
 * Ticking triggers instant local-first strike-through, logs to Google Sheet via task repository,
 * and removes from screen.
 * Overdue items include a Reschedule button.
 */
@Composable
private fun UnifiedPriorityRow(
    entry: Entry,
    isOverdue: Boolean,
    isCompleting: Boolean,
    onToggle: () -> Unit,
    onToggleMemory: () -> Unit,
    onReschedule: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val borderColor = if (isOverdue) Color(0xFFEF4444).copy(alpha = 0.40f) else null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (isCompleting) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = borderColor?.let { androidx.compose.foundation.BorderStroke(1.dp, it) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Checkbox: triggers instant local strike-through & sheet logging
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isCompleting) Color(0xFF10B981)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (isCompleting) Color(0xFF10B981)
                               else MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (isCompleting) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Done",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Main Content: Title, Category, Target Time
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isCompleting) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (isCompleting) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Category Tag
                    val categoryName = entry.categoryId?.ifBlank { "General" } ?: "General"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "#$categoryName",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // Target Time
                    val timeMillis = entry.scheduledAt ?: entry.dueAt ?: entry.startAt
                    if (timeMillis != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                tint = if (isOverdue) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = if (isOverdue) "Overdue · ${formatTime(timeMillis)}" else formatTime(timeMillis),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = if (isOverdue) FontWeight.Bold else FontWeight.Normal,
                                color = if (isOverdue) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Heart memory toggle button
            IconButton(
                onClick = onToggleMemory,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (entry.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Memory",
                    modifier = Modifier.size(17.dp),
                    tint = if (entry.favorite) Color(0xFFFF6D9D) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }

            // Reschedule button for overdue items
            if (isOverdue && onReschedule != null) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onReschedule),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Update,
                            contentDescription = "Reschedule",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "+1d",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
