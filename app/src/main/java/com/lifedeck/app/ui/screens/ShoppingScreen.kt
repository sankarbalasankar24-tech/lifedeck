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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.data.model.EntryType
import com.lifedeck.app.data.model.ShoppingBucket
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.components.LocalUndo
import com.lifedeck.app.ui.Routes
import com.lifedeck.app.ui.components.EmptyState
import com.lifedeck.app.ui.components.SectionHeader
import com.lifedeck.app.ui.components.TaskCard
import com.lifedeck.app.util.dueLabel
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import com.lifedeck.app.util.todayStart
import kotlinx.coroutines.launch

/**
 * Shopping & errands: immediate / this-week / future buckets, grouped trips,
 * budget tracking and a permanent purchase ledger.
 */
@Composable
fun ShoppingScreen(nav: NavController) {
    val container = LocalAppContainer.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    val items by container.shopping.observeAllItems().collectAsStateWithLifecycle(initialValue = emptyList())
    val trips by container.shopping.observeTrips().collectAsStateWithLifecycle(initialValue = emptyList())
    val monthStart = remember { todayStart() - 29 * 86_400_000L }
    val itemsForBudget by container.shopping.observeAllItems().collectAsStateWithLifecycle(initialValue = emptyList())
    val spentThisMonth by androidx.compose.runtime.remember(monthStart, itemsForBudget.size) {
        kotlinx.coroutines.flow.flow {
            emit(container.shopping.budgetUsedBetween(monthStart, now()))
        }
    }.collectAsStateWithLifecycle(initialValue = 0.0)

    var showQuickAdd by remember { mutableStateOf(false) }
    var showCreateTrip by remember { mutableStateOf(false) }
    var pricePromptFor by remember { mutableStateOf<String?>(null) }

    val active = items.filter { it.status != EntryStatus.DONE }
    val bought = items.filter { it.status == EntryStatus.DONE && it.purchasedAt != null }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Shopping", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Budgeted, scheduled and never lost",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showCreateTrip = true }) {
                Icon(Icons.Default.LocalShipping, contentDescription = "New trip")
            }
            IconButton(onClick = { showQuickAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add item")
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("List") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Trips (${trips.count { it.status.name == "ACTIVE" }})") })
        }

        when (tab) {
            0 -> ShoppingListTab(
                active = active,
                onPurchase = { entry ->
                    scope.launch { container.shopping.purchase(entry.id) }
                },
                onOpen = { nav.navigate(Routes.task(it)) },
                onAdd = { showQuickAdd = true },
            )
            1 -> TripsTab(
                trips = trips,
                onOpen = { nav.navigate(Routes.trip(it.id)) },
                onCreate = { showCreateTrip = true },
            )
            else -> LedgerTab(
                bought = bought,
                spentThisMonth = spentThisMonth,
                onOpen = { nav.navigate(Routes.task(it)) },
            )
        }
    }

    // quick add dialog
    if (showQuickAdd) {
        var title by remember { mutableStateOf("") }
        var quantity by remember { mutableStateOf("") }
        var budget by remember { mutableStateOf("") }
        var store by remember { mutableStateOf("") }
        var bucket by remember { mutableStateOf(ShoppingBucket.NOW) }
        AlertDialog(
            onDismissRequest = { showQuickAdd = false },
            title = { Text("Add to shopping list") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Item") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = quantity, onValueChange = { quantity = it }, label = { Text("Qty") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = budget, onValueChange = { budget = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Budget") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = store, onValueChange = { store = it }, label = { Text("Store") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShoppingBucket.entries.forEach { b ->
                            FilterChip(
                                selected = bucket == b,
                                onClick = { bucket = b },
                                label = { Text(b.name.lowercase()) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    scope.launch {
                        container.shopping.quickAdd(
                            title = title.trim(),
                            bucket = bucket,
                            quantity = quantity.ifBlank { null },
                            budget = budget.toDoubleOrNull(),
                            store = store.ifBlank { null },
                        )
                        container.scheduler.reconcileAsync()
                    }
                    showQuickAdd = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showQuickAdd = false }) { Text("Cancel") } },
        )
    }

    // price prompt after purchase
    pricePromptFor?.let { entryId ->
        var price by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                pricePromptFor = null
                scope.launch {
                    container.shopping.purchase(entryId)
                    undo.show("Marked purchased") { container.shopping.unpurchase(entryId) }
                }
            },
            title = { Text("Purchased ✓") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Record the price? (optional — feeds the ledger)", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Price paid") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.shopping.purchase(entryId, pricePaid = price.toDoubleOrNull())
                        undo.show("Marked purchased") { container.shopping.unpurchase(entryId) }
                    }
                    pricePromptFor = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.shopping.purchase(entryId)
                        undo.show("Marked purchased") { container.shopping.unpurchase(entryId) }
                    }
                    pricePromptFor = null
                }) { Text("Skip") }
            },
        )
    }

    // create trip
    if (showCreateTrip) {
        var title by remember { mutableStateOf("") }
        var store by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateTrip = false },
            title = { Text("Plan a shopping trip") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Trip name") }, placeholder = { Text("Weekly grocery run") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = store, onValueChange = { store = it }, label = { Text("Store (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    scope.launch {
                        container.shopping.createTrip(
                            com.lifedeck.app.data.model.ShoppingTrip(
                                id = newId(),
                                title = title.trim(),
                                store = store.trim(),
                                plannedAt = now(),
                                createdAt = now(),
                                updatedAt = now(),
                            ),
                        )
                    }
                    showCreateTrip = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateTrip = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ShoppingListTab(
    active: List<com.lifedeck.app.data.model.Entry>,
    onPurchase: (com.lifedeck.app.data.model.Entry) -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val nowItems = active.filter { (it.bucket ?: ShoppingBucket.NOW) == ShoppingBucket.NOW }
    val weekItems = active.filter { it.bucket == ShoppingBucket.WEEK }
    val futureItems = active.filter { it.bucket == ShoppingBucket.FUTURE }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        if (active.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.ShoppingCart,
                    title = "Nothing on the list",
                    message = "Capture items as you think of them — they'll sort into now, this week and later.",
                    action = { Button(onClick = onAdd) { Text("Add item") } },
                )
            }
        }
        bucketSection(this, "🛒 Now", nowItems, onPurchase, onOpen)
        bucketSection(this, "📅 This week", weekItems, onPurchase, onOpen)
        bucketSection(this, "🗓 Later", futureItems, onPurchase, onOpen)
    }
}

private fun bucketSection(
    scope: androidx.compose.foundation.lazy.LazyListScope,
    title: String,
    list: List<com.lifedeck.app.data.model.Entry>,
    onPurchase: (com.lifedeck.app.data.model.Entry) -> Unit,
    onOpen: (String) -> Unit,
) {
    if (list.isEmpty()) return
    scope.item { SectionHeader(title = title, subtitle = "${list.size} item${if (list.size == 1) "" else "s"}") }
    scope.items(list, key = { it.id }) { entry ->
        val container = LocalAppContainer.current
        val coroutineScope = rememberCoroutineScope()
        TaskCard(
            entry = entry,
            onToggle = { onPurchase(entry) },
            onClick = { onOpen(entry.id) },
            onToggleMemory = {
                coroutineScope.launch {
                    container.tasks.toggleFavorite(entry.id, container.memories)
                }
            },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TripsTab(
    trips: List<com.lifedeck.app.data.model.ShoppingTrip>,
    onOpen: (com.lifedeck.app.data.model.ShoppingTrip) -> Unit,
    onCreate: () -> Unit,
) {
    val container = LocalAppContainer.current
    if (trips.isEmpty()) {
        EmptyState(
            icon = Icons.Default.LocalShipping,
            title = "No trips planned",
            message = "Group items into one errand — one store, one stop, one checklist.",
            action = { Button(onClick = onCreate) { Text("Plan a trip") } },
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(trips, key = { it.id }) { trip ->
            val tripItems by container.tasks.observeByTrip(trip.id)
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val boughtCount = tripItems.count { it.status == EntryStatus.DONE }
            Surface(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOpen(trip) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(trip.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(
                                    trip.store.ifBlank { null },
                                    trip.plannedAt?.let { dueLabel(it) },
                                    trip.budget?.let { "budget %,.0f".format(it) },
                                ).joinToString(" · ").ifBlank { "No details" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "$boughtCount/${tripItems.size}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (tripItems.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { if (tripItems.isEmpty()) 0f else boughtCount.toFloat() / tripItems.size },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                                .clip(RoundedCornerShape(16.dp)),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerTab(
    bought: List<com.lifedeck.app.data.model.Entry>,
    spentThisMonth: Double,
    onOpen: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Surface(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Inventory, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Last 30 days: %,.2f".format(spentThisMonth),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${bought.size} purchases recorded forever",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (bought.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.Inventory,
                    title = "No purchases yet",
                    message = "Everything you buy is recorded here with date, price and notes — searchable forever.",
                )
            }
        }
        items(bought, key = { "b-${it.id}" }) { entry ->
            TaskCard(
                entry = entry,
                onToggle = { onOpen(entry.id) },
                onClick = { onOpen(entry.id) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}
