package com.lifedeck.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lifedeck.app.data.model.Goal
import com.lifedeck.app.data.model.LifeStatus
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.Routes
import com.lifedeck.app.ui.components.Chip
import com.lifedeck.app.ui.components.EmptyState
import com.lifedeck.app.ui.components.GoalCard
import com.lifedeck.app.ui.components.ProjectCard
import com.lifedeck.app.ui.components.SectionHeader
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import kotlinx.coroutines.launch

/**
 * Projects & Goals: long-term goals with their projects, milestone progress,
 * next actions, target dates and estimated costs.
 */
@Composable
fun ProjectsScreen(nav: NavController) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val goals by container.structure.observeGoals(includeDone = true)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val projects by container.structure.observeProjects(includeDone = true)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var showCreate by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }

    val visibleGoals = goals.filter { showDone || it.status != LifeStatus.DONE }
    val looseProjects = projects.filter { it.goalId == null && (showDone || it.status != LifeStatus.DONE) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Projects & Goals", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Break big plans into steps that finish themselves",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showDone = !showDone }) { Text(if (showDone) "Hide done" else "Show done") }
            }
        }

        if (visibleGoals.isEmpty() && looseProjects.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.TrackChanges,
                    title = "No goals yet",
                    message = "Solar panels, a new car, bathroom geysers — name the outcome, then add projects and milestones.",
                    action = {
                        Button(onClick = { showCreate = true }) { Text("Create your first goal") }
                    },
                )
            }
        }

        items(visibleGoals, key = { "goal-${it.id}" }) { goal ->
            GoalProgressItem(goal = goal, nav = nav)
        }

        if (looseProjects.isNotEmpty()) {
            item { SectionHeader(title = "Standalone projects", subtitle = "Not attached to a goal") }
            items(looseProjects, key = { "proj-${it.id}" }) { project ->
                LooseProjectItem(project = project, nav = nav)
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { showCreate = true },
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("New goal")
            }
        }
    }

    if (showCreate) {
        CreateGoalDialog(
            onDismiss = { showCreate = false },
            onCreate = { title, description, targetDate, cost ->
                scope.launch {
                    val id = container.structure.createGoal(
                        Goal(
                            id = newId(),
                            title = title,
                            description = description,
                            targetDate = targetDate,
                            costEstimate = cost,
                            status = LifeStatus.ACTIVE,
                            createdAt = now(),
                            updatedAt = now(),
                        ),
                    )
                    showCreate = false
                    nav.navigate(Routes.goal(id))
                }
            },
        )
    }
}

@Composable
private fun GoalProgressItem(goal: Goal, nav: NavController) {
    val container = LocalAppContainer.current
    val progress by container.structure.observeGoalProgress(goal.id)
        .collectAsStateWithLifecycle(initialValue = com.lifedeck.app.data.model.Progress(goal.id, 0, 0))
    var nextAction by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(goal.id) {
        nextAction = container.structure.nextActionForGoal(goal.id)?.title
    }
    val overdueSteps = remember(progress) { progress.overdue }

    GoalCard(
        goal = goal,
        percent = progress.percent,
        nextAction = nextAction,
        overdueSteps = overdueSteps,
        onClick = { nav.navigate(Routes.goal(goal.id)) },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
    )
}

@Composable
private fun LooseProjectItem(project: com.lifedeck.app.data.model.Project, nav: NavController) {
    val container = LocalAppContainer.current
    val progress by container.structure.observeProjectProgress(project.id)
        .collectAsStateWithLifecycle(initialValue = com.lifedeck.app.data.model.Progress(project.id, 0, 0))
    ProjectCard(
        project = project,
        percent = progress.percent,
        milestoneDone = progress.milestonesDone,
        milestoneTotal = progress.milestonesTotal,
        onClick = { nav.navigate(Routes.project(project.id)) },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun CreateGoalDialog(onDismiss: () -> Unit, onCreate: (String, String, Long?, Double?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var targetDate by remember { mutableStateOf<Long?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New goal", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Goal") },
                    placeholder = { Text("Install solar panels") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Why it matters (optional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cost,
                    onValueChange = { cost = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Estimated cost (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = targetDate != null,
                        onClick = {
                            targetDate = if (targetDate == null) {
                                System.currentTimeMillis() + 90L * 86_400_000L
                            } else null
                        },
                        label = { Text(if (targetDate == null) "No deadline" else "Target in 90 days") },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onCreate(title.trim(), description.trim(), targetDate, cost.toDoubleOrNull()) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
