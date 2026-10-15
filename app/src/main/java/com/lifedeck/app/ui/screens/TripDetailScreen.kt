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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.Routes
import com.lifedeck.app.ui.components.EmptyState
import com.lifedeck.app.ui.components.SectionHeader
import com.lifedeck.app.ui.components.TaskCard
import com.lifedeck.app.util.dueLabel
import kotlinx.coroutines.launch

/**
 * One errand, one list: see everything planned for a trip, pull in extra
 * items from the shopping list, and close the trip when the bags are packed.
 */
@Composable
fun TripDetailScreen(tripId: String, nav: NavController) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val trip by container.shopping.observeTrip(tripId).collectAsStateWithLifecycle(initialValue = null)
    val tripItems by container.tasks.observeByTrip(tripId).collectAsStateWithLifecycle(initialValue = emptyList())
    val allShopping by container.shopping.observeAllItems().collectAsStateWithLifecycle(initialValue = emptyList())
    var showAddItems by remember { mutableStateOf(false) }

    val t = trip ?: return
    val bought = tripItems.count { it.status == EntryStatus.DONE }
    val others = allShopping.filter { it.tripId == null && it.status != EntryStatus.DONE }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        t.store.ifBlank { null },
                        t.plannedAt?.let { dueLabel(it) },
                        t.budget?.let { "budget %,.0f".format(it) },
                    ).joinToString(" · ").ifBlank { "No details" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                scope.launch { container.shopping.completeTrip(t.id) }
            }) { Text("Finish") }
        }

        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row {
                        Text(
                            "$bought of ${tripItems.size} in basket",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (tripItems.isNotEmpty()) {
                            Text("${(bought * 100 / tripItems.size)}%", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Spacer(Modifier.height(4.dp).let { it })
                    LinearProgressIndicator(
                        progress = { if (tripItems.isEmpty()) 0f else bought.toFloat() / tripItems.size },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }

            item {
                SectionHeader(
                    title = "On this trip",
                    subtitle = "Check items off as you go",
                    trailing = {
                        TextButton(onClick = { showAddItems = true }) { Text("Add items") }
                    },
                )
            }

            if (tripItems.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.ShoppingCart,
                        title = "Empty trip",
                        message = "Add items from your shopping list so this errand runs in one stop.",
                        action = { Button(onClick = { showAddItems = true }) { Text("Add items") } },
                    )
                }
            }

            items(tripItems, key = { it.id }) { entry ->
                TaskCard(
                    entry = entry,
                    onToggle = {
                        scope.launch {
                            if (entry.status == EntryStatus.DONE) container.shopping.unpurchase(entry.id)
                            else container.shopping.purchase(entry.id)
                        }
                    },
                    onClick = { nav.navigate(Routes.task(entry.id)) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { nav.navigate(Routes.SHOPPING) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Back to shopping") }
                    Button(
                        onClick = {
                            scope.launch { container.shopping.completeTrip(t.id) }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Trip complete ✓") }
                }
            }
        }
    }

    if (showAddItems) {
        var selected by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { showAddItems = false },
            title = { Text("Add items to this trip") },
            text = {
                if (others.isEmpty()) {
                    Text("No other open shopping items — add them from the Shopping tab first.")
                } else {
                    LazyColumn(Modifier.padding(top = 4.dp)) {
                        items(others, key = { it.id }) { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (selected.contains(item.id)) selected - item.id else selected + item.id
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = selected.contains(item.id),
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + item.id else selected - item.id
                                    },
                                )
                                Text(item.title, modifier = Modifier.weight(1f), maxLines = 2)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        scope.launch { container.shopping.assignItemsToTrip(tripId, selected.toList()) }
                        showAddItems = false
                    },
                ) { Text("Add ${selected.size}") }
            },
            dismissButton = { TextButton(onClick = { showAddItems = false }) { Text("Cancel") } },
        )
    }
}
