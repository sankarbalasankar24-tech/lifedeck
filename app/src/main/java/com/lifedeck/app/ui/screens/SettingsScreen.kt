package com.lifedeck.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lifedeck.app.LifeDeckApp
import com.lifedeck.app.data.repo.AppSettings
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.util.formatFullDate
import com.lifedeck.app.util.hourOf
import com.lifedeck.app.util.minuteOf
import com.lifedeck.app.util.minutesOfDay
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import kotlinx.coroutines.launch
import java.io.File

/**
 * Everything configurable: appearance, schedules and quiet windows, summary
 * times, notification behaviour and troubleshooting, Google Sheets sync,
 * backups/export/import, and categories.
 */
@Composable
fun SettingsScreen(nav: NavController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())

    val syncStats by container.sync.observeStats().collectAsStateWithLifecycle(
        initialValue = com.lifedeck.app.data.repo.SyncRepository.SyncStats(),
    )
    val queue by container.sync.observeQueue().collectAsStateWithLifecycle(initialValue = emptyList())
    val backups by container.backup.observeBackups().collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by container.categories.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())

    var syncStatus by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var timeField by remember { mutableStateOf<Pair<String, Int>?>(null) } // key → minuteOfDay

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { container.settings.update(transform) }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column {
                    Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Local-first · your data stays on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------------------------------------------------- appearance
        item { SettingsHeader(Icons.Default.Palette, "Appearance") }
        item {
            SettingRow("Theme") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark").forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { update { it.copy(themeMode = mode) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
        item {
            SettingRow("Text size · ${(settings.textScale * 100).toInt()}%") {
                Slider(
                    value = settings.textScale,
                    onValueChange = { v -> update { it.copy(textScale = v) } },
                    valueRange = 0.85f..1.5f,
                )
            }
        }

        // ---------------------------------------------------- summaries
        item { SettingsHeader(Icons.Default.Schedule, "Daily summaries") }
        item {
            SettingRow("Morning summary") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { timeField = "morning" to minutesOfDay(settings.morningSummaryHour, settings.morningSummaryMinute) }) {
                        Text("%02d:%02d".format(settings.morningSummaryHour, settings.morningSummaryMinute))
                    }
                    Switch(
                        checked = settings.morningSummaryEnabled,
                        onCheckedChange = { v -> update { it.copy(morningSummaryEnabled = v) } },
                    )
                }
            }
        }
        item {
            SettingRow("Evening review") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { timeField = "evening" to minutesOfDay(settings.eveningSummaryHour, settings.eveningSummaryMinute) }) {
                        Text("%02d:%02d".format(settings.eveningSummaryHour, settings.eveningSummaryMinute))
                    }
                    Switch(
                        checked = settings.eveningSummaryEnabled,
                        onCheckedChange = { v -> update { it.copy(eveningSummaryEnabled = v) } },
                    )
                }
            }
        }
        item {
            SettingRow("End-of-day inbox prompt") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { timeField = "inbox" to minutesOfDay(settings.inboxPromptHour, settings.inboxPromptMinute) }) {
                        Text("%02d:%02d".format(settings.inboxPromptHour, settings.inboxPromptMinute))
                    }
                    Switch(
                        checked = settings.inboxPromptEnabled,
                        onCheckedChange = { v -> update { it.copy(inboxPromptEnabled = v, inboxNudgeEnabled = v) } },
                    )
                }
            }
        }

        // ---------------------------------------------------- schedules
        item { SettingsHeader(Icons.Default.Schedule, "Work, lunch & quiet hours") }
        item {
            TimeSetting("Work starts", settings.workStartMinute) { timeField = "workStart" to settings.workStartMinute }
        }
        item {
            TimeSetting("Work ends", settings.workEndMinute) { timeField = "workEnd" to settings.workEndMinute }
        }
        item {
            TimeSetting("Lunch starts", settings.lunchStartMinute) { timeField = "lunchStart" to settings.lunchStartMinute }
        }
        item {
            TimeSetting("Lunch ends", settings.lunchEndMinute) { timeField = "lunchEnd" to settings.lunchEndMinute }
        }
        item {
            TimeSetting("Sleep starts", settings.sleepStartMinute) { timeField = "sleepStart" to settings.sleepStartMinute }
        }
        item {
            TimeSetting("Sleep ends", settings.sleepEndMinute) { timeField = "sleepEnd" to settings.sleepEndMinute }
        }
        item {
            SettingRow("Quiet hours") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { timeField = "quietStart" to settings.quietStartMinute }) {
                        Text(fmtClock(settings.quietStartMinute) + " – " + fmtClock(settings.quietEndMinute))
                    }
                    Switch(checked = settings.quietHoursEnabled, onCheckedChange = { v -> update { it.copy(quietHoursEnabled = v) } })
                }
            }
        }
        item {
            SettingRow("Shift notifications into allowed windows (deadlines never move)") {
                Switch(checked = settings.respectReminderWindows, onCheckedChange = { v -> update { it.copy(respectReminderWindows = v) } })
            }
        }
        item {
            SettingRow("Avoid notifying during work hours") {
                Switch(checked = settings.avoidWorkHours, onCheckedChange = { v -> update { it.copy(avoidWorkHours = v) } })
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("Context windows", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    settings.contextWindows.entries.joinToString(" · ") { "${it.key} ${hourOf(it.value.startMinute)}:00" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Attach one to a task (@lunch) and its reminder fires inside that window.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------------------------------------------- notifications
        item { SettingsHeader(Icons.Default.NotificationsActive, "Notifications & reminders") }
        item {
            SettingRow("Notifications enabled") {
                Switch(checked = settings.notificationsEnabled, onCheckedChange = { v -> update { it.copy(notificationsEnabled = v) } })
            }
        }
        item {
            SettingRow("Sound") {
                Switch(checked = settings.soundEnabled, onCheckedChange = { v -> update { it.copy(soundEnabled = v) } })
            }
        }
        item {
            SettingRow("Vibration") {
                Switch(checked = settings.vibrateEnabled, onCheckedChange = { v -> update { it.copy(vibrateEnabled = v) } })
            }
        }
        item {
            SettingRow("Default lead time") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "None", 5 to "5m", 10 to "10m", 30 to "30m", 60 to "1h").forEach { (m, label) ->
                        FilterChip(
                            selected = settings.defaultLeadMinutes == m,
                            onClick = { update { it.copy(defaultLeadMinutes = m) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
        item {
            SettingRow("Escalate every ${settings.escalationIntervalMinutes}m (×${settings.escalationMaxCount})") {
                Switch(
                    checked = settings.escalationIntervalMinutes > 0,
                    onCheckedChange = { v ->
                        update { s ->
                            s.copy(
                                escalationIntervalMinutes = if (v) 10 else 0,
                                escalationMaxCount = if (v) 3 else 0,
                            )
                        }
                    },
                )
            }
        }
        item {
            SettingRow("Snooze duration · ${settings.snoozeMinutes}m") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 30, 60).forEach { m ->
                        FilterChip(selected = settings.snoozeMinutes == m, onClick = { update { it.copy(snoozeMinutes = m) } }, label = { Text("${m}m") })
                    }
                }
            }
        }
        item {
            SettingRow("Undo window · ${settings.undoSeconds}s") {
                Slider(
                    value = settings.undoSeconds.toFloat(),
                    onValueChange = { v -> update { it.copy(undoSeconds = v.toInt()) } },
                    valueRange = 3f..20f,
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Troubleshooting", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { container.scheduler.sendTestNotification() }) { Text("Send test") }
                }
                TroubleshootRow("Notifications permission", if (notificationsAllowed()) "allowed" else "blocked") {
                    if (!notificationsAllowed()) {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        runCatching { context.startActivity(intent) }
                    }
                }
                TroubleshootRow(
                    "Exact alarms",
                    if (container.scheduler.canScheduleExact()) "allowed" else "blocked",
                ) {
                    if (!container.scheduler.canScheduleExact() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(Uri.parse("package:${context.packageName}")),
                            )
                        }
                    }
                }
                TroubleshootRow(
                    "Battery optimization",
                    if (isIgnoringBatteryOptimizations(context)) "ignored (good)" else "restricting",
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }
                TroubleshootRow("Last alarm reconcile", formatTimeLabel(container.scheduler.lastReconcileAt())) {
                    scope.launch { runCatching { container.scheduler.reconcile() } }
                }
                Text(
                    "If reminders only fail when the app is closed, allow background activity and remove battery restrictions for LifeDeck.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ---------------------------------------------------- behavior
        item { SettingsHeader(Icons.Default.Settings, "Behaviour") }
        item {
            SettingRow("Auto-classify captures (tasks, shopping, appointments…)") {
                Switch(checked = settings.autoClassify, onCheckedChange = { v -> update { it.copy(autoClassify = v) } })
            }
        }
        item {
            SettingRow("Auto-complete parents when subtasks finish") {
                Switch(checked = settings.autoCompleteParents, onCheckedChange = { v -> update { it.copy(autoCompleteParents = v) } })
            }
        }
        item {
            SettingRow("Celebrate milestones & goals") {
                Switch(checked = settings.celebrateMilestones, onCheckedChange = { v -> update { it.copy(celebrateMilestones = v) } })
            }
        }
        item {
            SettingRow("Confetti") {
                Switch(checked = settings.celebrateConfetti, onCheckedChange = { v -> update { it.copy(celebrateConfetti = v) } })
            }
        }

        // ---------------------------------------------------- sync
        item { SettingsHeader(Icons.Default.CloudSync, "Google Sheets sync") }
        item {
            SettingRow("Sync enabled") {
                Switch(checked = settings.syncEnabled, onCheckedChange = { v -> update { it.copy(syncEnabled = v) } })
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                OutlinedTextField(
                    value = settings.syncUrl,
                    onValueChange = { v -> update { it.copy(syncUrl = v.trim()) } },
                    label = { Text("Apps Script Web App URL") },
                    placeholder = { Text("https://script.google.com/macros/s/…/exec") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.syncSecret,
                    onValueChange = { v -> update { it.copy(syncSecret = v) } },
                    label = { Text("Shared secret (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "No Google secrets are embedded in the app: you deploy the included Apps Script into your own spreadsheet and paste its URL here. See README → Sync.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    enabled = !busy && settings.syncUrl.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            val health = container.sheets.healthCheck()
                            syncStatus = if (health.first) "Connected ✓" else "Failed: ${health.second}"
                            if (health.first) {
                                val res = container.syncCoordinator.syncNow()
                                syncStatus = res.error?.let { "Sync error: $it" } ?: "Synced ✓ ${res.pushed} pushed · ${res.pulled} pulled"
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (busy) "Working…" else "Sync now") }
                OutlinedButton(
                    enabled = syncStats.failed > 0,
                    onClick = {
                        scope.launch {
                            container.sync.retryFailed()
                            container.syncCoordinator.syncAsync()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Retry failed (${syncStats.failed})") }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    "Pending changes: ${syncStats.pending} · in flight: ${syncStats.inFlight} · failed: ${syncStats.failed}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Last sync: " + if (settings.lastSyncAt == 0L) "never" else formatFullDate(settings.lastSyncAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.lastSyncResult.isNotBlank()) {
                    Text(
                        settings.lastSyncResult,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (settings.lastSyncError.isBlank()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    )
                }
                if (syncStatus.isNotBlank()) {
                    Text(syncStatus, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (queue.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    queue.take(5).forEach { item ->
                        Text(
                            "· ${item.entityType.name.lowercase()} ${item.op.name.lowercase()} · ${item.state.lowercase()}${if (item.lastError.isNotBlank()) " (" + item.lastError.take(60) + ")" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------- backups
        item { SettingsHeader(Icons.Default.Backup, "Backup, export & import") }
        item {
            SettingRow("Automatic backups") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("OFF" to "Off", "DAILY" to "Daily", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly").forEach { (f, label) ->
                        FilterChip(selected = settings.backupFrequency == f, onClick = { update { it.copy(backupFrequency = f) } }, label = { Text(label) })
                    }
                }
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = {
                    scope.launch {
                        runCatching {
                            container.backup.createBackup("MANUAL", "From settings")
                            container.backup.pruneBackups(settings.keepBackups)
                            container.settings.update { it.copy(lastBackupAt = now()) }
                        }
                    }
                }, modifier = Modifier.weight(1f)) { Text("Back up now") }
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching {
                            val json = container.backup.exportJson()
                            val file = File(context.cacheDir, "lifedeck-export.json")
                            file.writeText(json)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file,
                            )
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Export LifeDeck data"))
                        }
                    }
                }, modifier = Modifier.weight(1f)) { Text("Export JSON") }
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching {
                            val csv = container.backup.exportCsv()
                            val file = File(context.cacheDir, "lifedeck-tasks.csv")
                            file.writeText(csv)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file,
                            )
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Export CSV"))
                        }
                    }
                }, modifier = Modifier.weight(1f)) { Text("Export CSV") }
            }
        }

        // import launcher declared at composable level below
        item {
            ImportRow { json ->
                scope.launch {
                    runCatching {
                        val result = container.backup.importJson(json, container.history)
                        syncStatus = ""
                        container.syncCoordinator.syncAsync()
                        container.scheduler.reconcileAsync()
                        android.widget.Toast.makeText(context, "Restored: ${result.message}", android.widget.Toast.LENGTH_LONG).show()
                    }.onFailure {
                        android.widget.Toast.makeText(context, "Import failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("Snapshots", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (backups.isEmpty()) {
                    Text(
                        "No snapshots yet — automatic backups appear here and can be restored with one tap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(backups, key = { it.id }) { backup ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${backup.kind.lowercase()} · ${formatFullDate(backup.createdAt)}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${backup.rowCount} rows · ${backup.sizeBytes / 1024} KB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    scope.launch {
                        runCatching {
                            val result = container.backup.restoreBackup(backup.fileName, container.history)
                            container.scheduler.reconcileAsync()
                            android.widget.Toast.makeText(context, "Restored: ${result.message}", android.widget.Toast.LENGTH_LONG).show()
                        }.onFailure {
                            android.widget.Toast.makeText(context, "Restore failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Restore") }
                TextButton(onClick = {
                    scope.launch { container.backup.deleteBackup(backup.fileName) }
                }) { Text("Delete") }
            }
        }

        // ---------------------------------------------------- categories
        item { SettingsHeader(Icons.Default.Category, "Categories") }
        items(categories, key = { it.id }) { category ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(14.dp)
                        .height(14.dp)
                        .background(
                            androidx.compose.ui.graphics.Color(category.color),
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Text(category.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (category.builtIn) {
                    Text("built-in", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    TextButton(onClick = {
                        scope.launch { container.categories.delete(category.id) }
                    }) { Text("Delete") }
                }
            }
        }
        item {
            TextButton(
                onClick = { showAddCategory = true },
                modifier = Modifier.padding(horizontal = 20.dp),
            ) { Text("+ Add category") }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(
                "LifeDeck 1.0.0 · local-first personal life management\n" +
                    "Data lives on this device, mirrors to your Google Sheet when configured, and is never sold or uploaded anywhere else.",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ---------------------------------------------------- time picker dialog
    timeField?.let { (key, minute) ->
        val state = rememberTimePickerState(initialHour = hourOf(minute), initialMinute = minuteOf(minute), is24Hour = true)
        AlertDialog(
            onDismissRequest = { timeField = null },
            confirmButton = {
                TextButton(onClick = {
                    val newMinute = minutesOfDay(state.hour, state.minute)
                    update { s ->
                        when (key) {
                            "morning" -> s.copy(morningSummaryHour = state.hour, morningSummaryMinute = state.minute)
                            "evening" -> s.copy(eveningSummaryHour = state.hour, eveningSummaryMinute = state.minute)
                            "inbox" -> s.copy(inboxPromptHour = state.hour, inboxPromptMinute = state.minute, inboxNudgeHour = state.hour, inboxNudgeMinute = state.minute)
                            "workStart" -> s.copy(workStartMinute = newMinute)
                            "workEnd" -> s.copy(workEndMinute = newMinute)
                            "lunchStart" -> s.copy(lunchStartMinute = newMinute)
                            "lunchEnd" -> s.copy(lunchEndMinute = newMinute)
                            "sleepStart" -> s.copy(sleepStartMinute = newMinute)
                            "sleepEnd" -> s.copy(sleepEndMinute = newMinute)
                            "quietStart" -> s.copy(quietStartMinute = newMinute)
                            else -> s.copy(quietEndMinute = newMinute)
                        }
                    }
                    timeField = null
                    scope.launch { runCatching { container.scheduler.reconcile() } }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { timeField = null }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }

    // ---------------------------------------------------- add category
    if (showAddCategory) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddCategory = false },
            title = { Text("New category") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch {
                        container.categories.create(
                            name.trim(),
                            color = 0xFF3D5AFE + (categories.size * 7919L),
                        )
                    }
                    showAddCategory = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showAddCategory = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ helpers

@Composable
private fun SettingsHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.width(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingRow(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun TimeSetting(title: String, minuteOfDay: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onClick) {
            Text("%02d:%02d".format(hourOf(minuteOfDay), minuteOf(minuteOfDay)))
        }
    }
}

@Composable
private fun TroubleshootRow(label: String, status: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            color = if (status in setOf("allowed", "ignored (good)")) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onClick) { Text("Fix") }
    }
}

@Composable
private fun ImportRow(onImport: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver
                    .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (text != null) onImport(text)
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = { launcher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            modifier = Modifier.weight(1f),
        ) { Text("Import backup") }
    }
}

private fun notificationsAllowed(): Boolean {
    val app = LifeDeckApp.instance
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return androidx.core.content.ContextCompat.checkSelfPermission(
        app, android.Manifest.permission.POST_NOTIFICATIONS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    return try {
        val mgr = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        mgr.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        false
    }
}

private fun fmtClock(minuteOfDay: Int): String =
    "%02d:%02d".format(hourOf(minuteOfDay), minuteOf(minuteOfDay))

private fun formatTimeLabel(millis: Long): String =
    if (millis == 0L) "never" else com.lifedeck.app.util.formatDateTime(millis)
