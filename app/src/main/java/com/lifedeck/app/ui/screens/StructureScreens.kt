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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lifedeck.app.data.model.LifeStatus
import com.lifedeck.app.data.model.Milestone
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.data.model.Project
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.components.LocalUndo
import com.lifedeck.app.ui.Routes
import com.lifedeck.app.ui.components.Chip
import com.lifedeck.app.ui.components.EmptyState
import com.lifedeck.app.ui.components.MilestoneRow
import com.lifedeck.app.ui.components.ProjectCard
import com.lifedeck.app.ui.components.SectionHeader
import com.lifedeck.app.ui.components.TaskCard
import com.lifedeck.app.util.dueLabel
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import kotlinx.coroutines.launch

/** Shared detail scaffold for goal / project screens. */
@Composable
private fun DetailScaffold(
    title: String,
    subtitle: String,
    nav: NavController,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            actions?.invoke()
        }
        content(PaddingValues(0.dp))
    }
}

// ===================================================================== GOAL

@Composable
fun GoalDetailScreen(goalId: String, nav: NavController) {
    val container = LocalAppContainer.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()

    val goal by container.structure.observeGoal(goalId).collectAsStateWithLifecycle(initialValue = null)
    val progress by container.structure.observeGoalProgress(goalId)
        .collectAsStateWithLifecycle(initialValue = com.lifedeck.app.data.model.Progress(goalId, 0, 0))
    val projects by container.structure.observeProjects(goalId = goalId, includeDone = true)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val entries by container.tasks.observeByGoal(goalId).collectAsStateWithLifecycle(initialValue = emptyList())

    var nextAction by remember { mutableStateOf<String?>(null) }
    var showAddProject by remember { mutableStateOf(false) }

    LaunchedEffect(goalId, progress.done) {
        nextAction = container.structure.nextActionForGoal(goalId)?.title
    }

    val g = goal ?: return

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text(g.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(
                            g.targetDate?.let { "Target ${dueLabel(it)}" },
                            g.costEstimate?.let { "Budget %,.0f".format(it) },
                            g.status.name.lowercase(),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    scope.launch {
                        val done = g.status == LifeStatus.DONE
                        container.structure.updateGoal(
                            g.copy(
                                status = if (done) LifeStatus.ACTIVE else LifeStatus.DONE,
                                completedAt = if (done) null else now(),
                            ),
                            summary = if (done) "Reopened" else "Marked achieved",
                        )
                    }
                }) {
                    Icon(
                        Icons.Default.Flag,
                        contentDescription = "Toggle goal status",
                        tint = if (g.status == LifeStatus.DONE) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Surface(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Overall progress",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${progress.percent}%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(9.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${progress.done} of ${progress.total} items done · " +
                            "${progress.milestonesDone}/${progress.milestonesTotal} milestones",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (progress.overdue > 0) {
                        Text(
                            "${progress.overdue} overdue",
                            style = MaterialTheme.typography.labelMedium,
                            color = androidx.compose.ui.graphics.Color(0xFFE53935),
                        )
                    }
                    if (nextAction != null) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.AddTask,
                                    contentDescription = null,
                                    modifier = Modifier.width(16.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Next step: $nextAction",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (g.description.isNotBlank()) {
            item {
                Text(
                    g.description,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- projects ----
        item {
            SectionHeader(
                title = "Projects",
                subtitle = "Milestones live inside each project",
                trailing = {
                    TextButton(onClick = { showAddProject = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                },
            )
        }
        items(projects, key = { "p-${it.id}" }) { project ->
            val pp by container.structure.observeProjectProgress(project.id)
                .collectAsStateWithLifecycle(initialValue = com.lifedeck.app.data.model.Progress(project.id, 0, 0))
            ProjectCard(
                project = project,
                percent = pp.percent,
                milestoneDone = pp.milestonesDone,
                milestoneTotal = pp.milestonesTotal,
                onClick = { nav.navigate(Routes.project(project.id)) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // ---- open steps linked directly to the goal ----
        val directSteps = entries.filter { it.projectId == null && it.status != com.lifedeck.app.data.model.EntryStatus.DONE }
        if (directSteps.isNotEmpty()) {
            item { SectionHeader(title = "Actionable steps", subtitle = "Directly attached to this goal") }
            items(directSteps, key = { "e-${it.id}" }) { entry ->
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

        if (projects.isEmpty() && directSteps.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Flag,
                    title = "Break it down",
                    message = "Add a project (e.g. \"Research brands\") with milestones and steps, and progress rolls up automatically.",
                )
            }
        }
    }

    if (showAddProject) {
        CreateProjectDialog(
            goalId = goalId,
            onDismiss = { showAddProject = false },
            onCreate = { title, milestones ->
                scope.launch {
                    val projectId = container.structure.createProject(
                        Project(
                            id = newId(),
                            goalId = goalId,
                            title = title,
                            status = LifeStatus.ACTIVE,
                            createdAt = now(),
                            updatedAt = now(),
                        ),
                    )
                    milestones.forEach { ms ->
                        container.structure.createMilestone(
                            Milestone(
                                id = newId(),
                                projectId = projectId,
                                title = ms,
                                createdAt = now(),
                                updatedAt = now(),
                            ),
                        )
                    }
                    showAddProject = false
                    nav.navigate(Routes.project(projectId))
                }
            },
        )
    }
}

// ================================================================ PROJECT

@Composable
fun ProjectDetailScreen(projectId: String, nav: NavController) {
    val container = LocalAppContainer.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()

    val project by container.structure.observeProject(projectId)
        .collectAsStateWithLifecycle(initialValue = null)
    val progress by container.structure.observeProjectProgress(projectId)
        .collectAsStateWithLifecycle(initialValue = com.lifedeck.app.data.model.Progress(projectId, 0, 0))
    val milestones by container.structure.observeMilestones(projectId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val entries by container.tasks.observeByProject(projectId)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var showAddMilestone by remember { mutableStateOf(false) }
    var showAddStep by remember { mutableStateOf(false) }

    val p = project ?: return

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(
                            p.dueDate?.let { "Due ${dueLabel(it)}" },
                            p.costEstimate?.let { "Cost %,.0f".format(it) },
                            p.status.name.lowercase(),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                p.goalId?.let { gid ->
                    TextButton(onClick = { nav.navigate(Routes.goal(gid)) }) { Text("Goal") }
                }
            }
        }

        item {
            Surface(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        Text("Progress", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            "${progress.percent}%",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(9.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "Milestones",
                subtitle = "Completing steps rolls progress up automatically",
                trailing = {
                    TextButton(onClick = { showAddMilestone = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Milestone")
                    }
                },
            )
        }
        items(milestones, key = { "m-${it.id}" }) { milestone ->
            val mp by container.structure.observeMilestoneProgress(milestone.id)
                .collectAsStateWithLifecycle(initialValue = com.lifedeck.app.data.model.Progress(milestone.id, 0, 0))
            MilestoneRow(
                milestone = milestone,
                percent = mp.percent,
                onClick = {
                    // Tapping a milestone filters the steps below by scrolling; simple toggle:
                    scope.launch {
                        val done = milestone.status == LifeStatus.DONE
                        container.structure.updateMilestone(
                            milestone.copy(
                                status = if (done) LifeStatus.ACTIVE else LifeStatus.DONE,
                                completedAt = if (done) null else now(),
                            ),
                            summary = if (done) "Reopened" else "Milestone completed",
                        )
                        if (!done) undo.show("Milestone \"${milestone.title}\" done") {
                            container.structure.updateMilestone(
                                milestone.copy(status = LifeStatus.ACTIVE, completedAt = null),
                                summary = "Undo",
                            )
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // ---- steps grouped by milestone ----
        milestones.forEach { milestone ->
            val steps = entries.filter { it.milestoneId == milestone.id && it.status != com.lifedeck.app.data.model.EntryStatus.DONE }
            if (steps.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = milestone.title,
                        subtitle = "Steps",
                    )
                }
                items(steps, key = { "s-${it.id}" }) { entry ->
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
        }

        val loose = entries.filter { it.milestoneId == null && it.status != com.lifedeck.app.data.model.EntryStatus.DONE }
        if (loose.isNotEmpty()) {
            item { SectionHeader(title = "Other steps") }
            items(loose, key = { "l-${it.id}" }) { entry ->
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

        val finished = entries.filter { it.status == com.lifedeck.app.data.model.EntryStatus.DONE }
        if (finished.isNotEmpty()) {
            item { SectionHeader(title = "Completed steps", subtitle = "${finished.size} done · kept forever") }
            items(finished, key = { "d-${it.id}" }) { entry ->
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

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { showAddStep = true }, modifier = Modifier.weight(1f)) { Text("Add step") }
                Button(
                    onClick = {
                        scope.launch {
                            val done = p.status == LifeStatus.DONE
                            container.structure.updateProject(
                                p.copy(status = if (done) LifeStatus.ACTIVE else LifeStatus.DONE, completedAt = if (done) null else now()),
                                summary = if (done) "Reopened" else "Project complete",
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (p.status == LifeStatus.DONE) "Reopen project" else "Complete project") }
            }
        }
    }

    if (showAddMilestone) {
        CreateMilestoneDialog(
            onDismiss = { showAddMilestone = false },
            onCreate = { title ->
                scope.launch {
                    container.structure.createMilestone(
                        Milestone(
                            id = newId(),
                            projectId = projectId,
                            title = title,
                            orderIndex = milestones.size + 1,
                            createdAt = now(),
                            updatedAt = now(),
                        ),
                    )
                }
                showAddMilestone = false
            },
        )
    }

    if (showAddStep) {
        CreateStepDialog(
            milestones = milestones,
            onDismiss = { showAddStep = false },
            onCreate = { title, milestoneId, priority ->
                scope.launch {
                    val goalId = p.goalId
                    val entry = com.lifedeck.app.data.model.Entry(
                        id = newId(),
                        title = title,
                        type = com.lifedeck.app.data.model.EntryType.PROJECT_STEP,
                        status = com.lifedeck.app.data.model.EntryStatus.ACTIVE,
                        priority = priority,
                        projectId = projectId,
                        milestoneId = milestoneId,
                        goalId = goalId,
                        createdAt = now(),
                        updatedAt = now(),
                    )
                    container.tasks.create(entry, summary = "Step added to ${p.title}")
                    container.scheduler.reconcileAsync()
                }
                showAddStep = false
            },
        )
    }
}

// ================================================================ dialogs

@Composable
private fun CreateProjectDialog(goalId: String, onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var title by remember { mutableStateOf("") }
    var milestonesText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Project name") },
                    placeholder = { Text("Install geyser") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = milestonesText,
                    onValueChange = { milestonesText = it },
                    label = { Text("Milestones (one per line)") },
                    placeholder = { Text("Research specs\nCompare brands\nRequest quotations") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val list = milestonesText.lines().map { it.trim() }.filter { it.isNotBlank() }
                    onCreate(title.trim(), list)
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CreateMilestoneDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New milestone") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Milestone") },
                placeholder = { Text("Request quotations") },
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
private fun CreateStepDialog(
    milestones: List<Milestone>,
    onDismiss: () -> Unit,
    onCreate: (String, String?, Priority) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var milestoneId by remember { mutableStateOf<String?>(null) }
    var priority by remember { mutableStateOf(Priority.NORMAL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New step") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("What needs doing?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (milestones.isNotEmpty()) {
                    Text("Milestone", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChipSimple("None", milestoneId == null) { milestoneId = null }
                        milestones.take(3).forEach { m ->
                            FilterChipSimple(m.title.take(14), milestoneId == m.id) { milestoneId = m.id }
                        }
                    }
                }
                Text("Priority", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Priority.entries.forEach { p ->
                        FilterChipSimple(
                            label = p.name.lowercase().replaceFirstChar { it.uppercase() }.take(7),
                            selected = priority == p,
                            onClick = { priority = p },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onCreate(title.trim(), milestoneId, priority) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FilterChipSimple(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
    )
}
