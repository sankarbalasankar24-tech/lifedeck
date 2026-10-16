package com.lifedeck.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.data.model.EntryType
import com.lifedeck.app.data.model.Goal
import com.lifedeck.app.data.model.HistoryEvent
import com.lifedeck.app.data.model.Memory
import com.lifedeck.app.data.model.Milestone
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.data.model.Project
import com.lifedeck.app.data.model.ShoppingBucket
import com.lifedeck.app.domain.RecurrenceEngine
import com.lifedeck.app.util.dueLabel
import com.lifedeck.app.util.formatDuration
import com.lifedeck.app.util.formatTime
import com.lifedeck.app.util.isOverdue

/**
 * The universal card for tasks, appointments, shopping items, steps and ideas.
 * One-tap completion on the left, tap anywhere else to open details.
 */
@Composable
fun TaskCard(
    entry: Entry,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showScope: String? = null,
    onToggleMemory: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val done = entry.status == EntryStatus.DONE
    val overdue = !done && isOverdue(entry.dueAt ?: entry.scheduledAt)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (done) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surface,
        tonalElevation = if (done) 0.dp else 1.dp,
        shadowElevation = if (done) 0.dp else 1.dp,
        border = if (overdue) BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.5f)) else null,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompletionCircle(done = done, accent = priorityColor(entry.priority), onClick = onToggle)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.priority == Priority.URGENT || entry.priority == Priority.HIGH) {
                        PriorityDot(entry.priority)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onToggleMemory != null) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = onToggleMemory,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                if (entry.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Memory",
                                modifier = Modifier.size(14.dp),
                                tint = if (entry.favorite) Color(0xFFFF6D9D) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    } else if (entry.favorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = "Memory",
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFFFF6D9D),
                        )
                    }
                }
                CardMetaRow(entry = entry, done = done, overdue = overdue, showScope = showScope)
            }
            trailing?.invoke() ?: Spacer(Modifier.width(0.dp))
        }
    }
}

@Composable
private fun CardMetaRow(entry: Entry, done: Boolean, overdue: Boolean, showScope: String?) {
    val meta = mutableListOf<@Composable () -> Unit>()
    val primaryMetaColor = if (overdue) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant

    Spacer(Modifier.height(4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        // Date / time
        val whenMillis = entry.scheduledAt ?: entry.dueAt ?: entry.startAt
        if (whenMillis != null && !done) {
            Icon(
                if (entry.isAppointment) Icons.Default.Event else Icons.Default.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = primaryMetaColor,
            )
            Text(
                dueLabel(whenMillis) + (if (!entry.allDay && entry.scheduledAt != null) " · ${formatTime(entry.scheduledAt)}" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = primaryMetaColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (entry.purchasedAt != null) {
            Icon(
                Icons.Default.ShoppingBag,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "Bought ${dueLabel(entry.purchasedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        // Checklist progress
        entry.checklistProgress?.let { (finished, total) ->
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "$finished/$total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Recurrence
        entry.recurrence?.takeIf { it.isActive }?.let { rule ->
            Icon(
                Icons.Default.Repeat,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                RecurrenceEngine.label(rule),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Estimate
        entry.estimateMinutes?.let {
            Icon(Icons.Outlined.AccessTime, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                formatDuration(it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Location (appointments)
        if (entry.location.isNotBlank()) {
            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                entry.location,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Shopping specifics
        if (entry.isShopping && !done) {
            val bits = buildList {
                entry.quantity?.takeIf { it.isNotBlank() }?.let { add(it) }
                entry.store?.takeIf { it.isNotBlank() }?.let { add(it) }
                entry.budget?.let { add("budget %.0f".format(it)) }
            }
            if (bits.isNotEmpty()) {
                Icon(Icons.Default.Store, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    bits.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.bucket?.let { bucket ->
                Spacer(Modifier.width(2.dp))
                Chip(
                    text = when (bucket) {
                        ShoppingBucket.NOW -> "Now"
                        ShoppingBucket.WEEK -> "This week"
                        ShoppingBucket.FUTURE -> "Later"
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        // Scope hint (project/milestone/goal name)
        if (!showScope.isNullOrBlank()) {
            Spacer(Modifier.width(2.dp))
            Text(
                showScope,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Blocked by dependency
        if (entry.dependencyIds.isNotEmpty() && !done) {
            Icon(Icons.Default.Loop, contentDescription = "Has dependencies", modifier = Modifier.size(13.dp), tint = Color(0xFFFFB300))
        }
    }
}

/** Animated circular checkbox used by every task row. */
@Composable
fun CompletionCircle(done: Boolean, accent: Color, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (done) 1.08f else 1f,
        animationSpec = tween(220),
        label = "checkScale",
    )
    Box(
        modifier = Modifier
            .size(28.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                if (done) accent
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
                width = 2.dp,
                color = if (done) accent else accent.copy(alpha = 0.65f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = done) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Done",
                modifier = Modifier.size(18.dp),
                tint = Color.White,
            )
        }
    }
}

/** Goal card with progress bar and target date. */
@Composable
fun GoalCard(
    goal: Goal,
    percent: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    nextAction: String? = null,
    overdueSteps: Int = 0,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        goal.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    goal.targetDate?.let {
                        Text(
                            "Target ${dueLabel(it)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (goal.favorite) {
                    Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFFF6D9D))
                    Spacer(Modifier.width(6.dp))
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        "$percent%",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            ProgressBar(fraction = percent / 100f, color = MaterialTheme.colorScheme.primary)
            if (nextAction != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Next: $nextAction",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (overdueSteps > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "$overdueSteps step${if (overdueSteps == 1) "" else "s"} overdue",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFE53935),
                )
            }
        }
    }
}

/** Compact project row inside a goal. */
@Composable
fun ProjectCard(
    project: Project,
    percent: Int,
    milestoneDone: Int,
    milestoneTotal: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            ProgressBar(fraction = percent / 100f, color = MaterialTheme.colorScheme.primary)
            if (milestoneTotal > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "$milestoneDone of $milestoneTotal milestones",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Milestone row with its own progress and target date. */
@Composable
fun MilestoneRow(
    milestone: Milestone,
    percent: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = milestone.status == com.lifedeck.app.data.model.LifeStatus.DONE
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (done) MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                } else {
                    Text(
                        "$percent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    milestone.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                milestone.targetDate?.let {
                    Text(
                        "Target ${dueLabel(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOverdue(it) && !done) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!done && percent > 0) {
                Spacer(Modifier.width(8.dp))
                ProgressBar(
                    fraction = percent / 100f,
                    modifier = Modifier.width(56.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Memory card: warm and photo-friendly. */
@Composable
fun MemoryCard(memory: Memory, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF6D9D).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFFF6D9D))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        memory.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        com.lifedeck.app.util.formatFullDate(memory.occurredAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (memory.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    memory.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Timeline row for the history/activity feed. */
@Composable
fun HistoryRow(event: HistoryEvent, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(historyColor(event.action.name).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                historyIcon(event.action.name),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = historyColor(event.action.name),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                event.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.summary.isNotBlank()) {
                Text(
                    event.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            com.lifedeck.app.util.formatDate(event.occurredAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun historyColor(action: String): Color = when (action) {
    "COMPLETED", "MILESTONE_DONE", "PROJECT_DONE", "GOAL_DONE" -> Color(0xFF00BFA5)
    "RESCHEDULED", "SKIPPED" -> Color(0xFFFFB300)
    "DELETED" -> Color(0xFFE53935)
    "PURCHASED" -> Color(0xFF3D5AFE)
    "MEMORY_CREATED" -> Color(0xFFFF6D9D)
    else -> MaterialThemeColorFallback
}

// Neutral fallback so the function stays pure (no Composable context needed).
private val MaterialThemeColorFallback = Color(0xFF667085)

fun historyIcon(action: String): androidx.compose.ui.graphics.vector.ImageVector = when (action) {
    "COMPLETED" -> Icons.Default.Check
    "MILESTONE_DONE", "PROJECT_DONE", "GOAL_DONE" -> Icons.Default.Flag
    "RESCHEDULED", "SKIPPED" -> Icons.Default.Repeat
    "DELETED" -> Icons.Default.DeleteSweep
    "PURCHASED" -> Icons.Default.ShoppingBag
    "MEMORY_CREATED" -> Icons.Default.FavoriteBorder
    "REMINDER_FIRED" -> Icons.Default.Notifications
    else -> Icons.Default.Loop
}
