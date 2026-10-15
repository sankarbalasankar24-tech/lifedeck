package com.lifedeck.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.ui.components.LocalUndo
import com.lifedeck.app.util.Photos
import com.lifedeck.app.util.formatFullDate
import com.lifedeck.app.util.now
import kotlinx.coroutines.launch

/**
 * Memory detail: edit the story, attach photos, and choose six-month /
 * yearly anniversary reminders. Tied back to the originating task or goal.
 */
@Composable
fun MemoryDetailScreen(memoryId: String, nav: NavController) {
    val container = LocalAppContainer.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()

    val memory by container.memories.observeById(memoryId).collectAsStateWithLifecycle(initialValue = null)
    var showDelete by remember { mutableStateOf(false) }
    var savedFlash by remember { mutableStateOf(false) }

    val m = memory ?: return

    var title by remember(m.id, m.title) { mutableStateOf(m.title) }
    var description by remember(m.id, m.description) { mutableStateOf(m.description) }

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val path = Photos.import(container.context, uri, m.id)
                if (path != null) container.memories.update(m.copy(photos = m.photos + path))
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = "Add photo", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { showDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        item {
            Text(
                formatFullDate(m.occurredAt),
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        item {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 3,
            )
        }

        item {
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                label = { Text("What made it special?") },
                minLines = 3,
                maxLines = 8,
            )
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            container.memories.update(m.copy(title = title.trim().ifBlank { m.title }, description = description))
                            savedFlash = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (savedFlash) "Saved ✓" else "Save") }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Anniversary reminders", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Yearly reminder", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "On this day each year",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = m.remindYearly,
                        onCheckedChange = { checked ->
                            scope.launch { container.memories.update(m.copy(remindYearly = checked)) }
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Six-month reminder", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "A gentle echo half a year later",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = m.remindInSixMonths,
                        onCheckedChange = { checked ->
                            scope.launch { container.memories.update(m.copy(remindInSixMonths = checked)) }
                        },
                    )
                }
                m.nextReminderAt?.let { at ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Next reminder: ${formatFullDate(at)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (m.photos.isNotEmpty()) {
            item { Spacer(Modifier.height(12.dp)) }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(m.photos) { path ->
                        val bitmap = remember(path) { Photos.decode(path, 900) }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Memory photo",
                                modifier = Modifier
                                    .size(220.dp)
                                    .clip(RoundedCornerShape(18.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }
        }

        m.entryId?.let { entryId ->
            item {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { nav.navigate(com.lifedeck.app.ui.Routes.task(entryId)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                ) { Text("Open the original task") }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete this memory?") },
            text = { Text("The original record stays in history. You can undo right after.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    scope.launch {
                        container.memories.delete(m.id)
                        nav.popBackStack()
                        undo.show("Memory deleted") {
                            container.memories.update(m.copy(deleted = false, updatedAt = now()))
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}
