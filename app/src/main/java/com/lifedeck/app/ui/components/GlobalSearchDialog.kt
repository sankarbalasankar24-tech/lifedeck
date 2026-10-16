package com.lifedeck.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.lifedeck.app.data.model.EntityKind
import com.lifedeck.app.data.model.SearchHit
import com.lifedeck.app.data.repo.SearchRepository
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.Routes
import com.lifedeck.app.util.formatDate

@Composable
fun GlobalSearchDialog(
    onDismiss: () -> Unit,
    nav: NavController,
) {
    val container = LocalAppContainer.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }

    LaunchedEffect(query) {
        results = container.search.search(SearchRepository.Filters(query = query))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Universal Global Search",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search all tasks, notes, goals, memories…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )

                Spacer(Modifier.height(12.dp))

                if (results.isEmpty() && query.isNotBlank()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No matching entries found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(results, key = { "${it.kind.name}-${it.id}" }) { hit ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onDismiss()
                                        when (hit.kind) {
                                            EntityKind.ENTRY -> nav.navigate(Routes.task(hit.id))
                                            EntityKind.GOAL -> nav.navigate(Routes.goal(hit.id))
                                            EntityKind.PROJECT -> nav.navigate(Routes.project(hit.id))
                                            EntityKind.MEMORY -> nav.navigate(Routes.memory(hit.id))
                                            else -> Unit
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val icon = when (hit.kind) {
                                        EntityKind.MEMORY -> Icons.Default.Favorite
                                        EntityKind.GOAL -> Icons.Default.Flag
                                        EntityKind.PROJECT -> Icons.Default.Folder
                                        else -> if (hit.status == "DONE") Icons.Default.Check else Icons.Default.TaskAlt
                                    }
                                    val tint = when (hit.kind) {
                                        EntityKind.MEMORY -> Color(0xFFFF6D9D)
                                        EntityKind.GOAL -> Color(0xFFF59E0B)
                                        EntityKind.PROJECT -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = tint,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            hit.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            hit.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        formatDate(hit.occurredAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
