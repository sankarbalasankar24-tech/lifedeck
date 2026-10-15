package com.lifedeck.app.ui.quickadd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifedeck.app.data.model.Entry
import com.lifedeck.app.data.model.EntryStatus
import com.lifedeck.app.data.model.EntryType
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.data.model.Recurrence
import com.lifedeck.app.data.model.RecurrenceFreq
import com.lifedeck.app.data.model.ShoppingBucket
import com.lifedeck.app.domain.CaptureParser
import com.lifedeck.app.ui.LocalAppContainer
import com.lifedeck.app.util.formatDate
import com.lifedeck.app.util.formatTime
import com.lifedeck.app.util.newId
import com.lifedeck.app.util.now
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Persistent quick-capture bottom sheet: type or talk, the parser figures out
 * type/date/priority/recurrence, and every detail stays editable.
 * Stays open after saving so several ideas can be captured in a row.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(
    visible: Boolean,
    initialText: String,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val categories by container.categories.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var text by remember(visible) { mutableStateOf(initialText) }
    var notes by remember(visible) { mutableStateOf("") }
    var typeOverride by remember(visible) { mutableStateOf<EntryType?>(null) }
    var priorityOverride by remember(visible) { mutableStateOf<Priority?>(null) }
    var dateOverride by remember(visible) { mutableStateOf<LocalDate?>(null) }
    var timeOverride by remember(visible) { mutableStateOf<Pair<Int, Int>?>(null) }
    var leadMinutes by remember(visible) { mutableStateOf<Int?>(null) }
    var recurrenceOverride by remember(visible) { mutableStateOf<Recurrence?>(null) }
    var categoryId by remember(visible) { mutableStateOf<String?>(null) }
    var bucketOverride by remember(visible) { mutableStateOf<ShoppingBucket?>(null) }
    var savedFlash by remember(visible) { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }

    val parsed = remember(text, typeOverride, dateOverride, timeOverride, recurrenceOverride, priorityOverride) {
        if (text.isBlank()) null else CaptureParser.parse(text)
    }
    val effectiveType = typeOverride ?: parsed?.type ?: EntryType.TASK
    val effectivePriority = priorityOverride ?: parsed?.priority ?: Priority.NORMAL
    val effectiveRecurrence = recurrenceOverride ?: parsed?.recurrence
    val effectiveDate = dateOverride
    val effectiveTime = timeOverride

    // ---- voice input ----
    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }
    val listener = remember {
        object : RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.firstOrNull()
                if (!heard.isNullOrBlank()) {
                    text = if (text.isBlank()) heard else "$text $heard"
                    savedFlash = false
                }
                listening = false
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!heard.isNullOrBlank()) text = heard
            }

            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) {
                listening = false
                Toast.makeText(context, "Voice capture unavailable (code $error)", Toast.LENGTH_SHORT).show()
            }

            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        }
    }
    androidx.compose.runtime.DisposableEffect(visible) {
        recognizer.setRecognitionListener(listener)
        onDispose { recognizer.cancel(); recognizer.destroy() }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            listening = true
            recognizer.startListening(speechIntent)
        } else {
            Toast.makeText(context, "Microphone permission is needed for voice capture", Toast.LENGTH_LONG).show()
        }
    }

    fun startVoice() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            listening = true
            recognizer.startListening(speechIntent)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun save(closeAfter: Boolean) {
        if (text.isBlank()) return
        val base = parsed ?: CaptureParser.parse(text)
        val type = typeOverride ?: base.type
        val date = dateOverride
        val time = timeOverride
        val due: Long? = when {
            date != null && time != null -> atMillis(date, time.first, time.second)
            date != null -> atMillis(date, 0, 0)
            time != null -> atMillis(LocalDate.now(), time.first, time.second)
            else -> base.dueAt
        }
        val entry = Entry(
            id = newId(),
            title = base.title,
            notes = notes,
            type = type,
            status = if (due == null) EntryStatus.INBOX else EntryStatus.ACTIVE,
            dueAt = due,
            scheduledAt = due,
            allDay = time == null,
            priority = priorityOverride ?: base.priority,
            recurrence = recurrenceOverride ?: base.recurrence,
            categoryId = categoryId,
            bucket = bucketOverride ?: base.bucket,
            contextHint = base.contextHint,
            estimateMinutes = base.estimateMinutes,
            createdAt = now(),
            updatedAt = now(),
        )
        scope.launch {
            container.tasks.create(entry, summary = "Quick capture")
            leadMinutes?.let { lead ->
                if (due != null) {
                    container.tasks.addManualReminder(
                        entryId = entry.id,
                        fireAt = due - lead * 60_000L,
                        escalationIntervalMinutes = null,
                    )
                }
            }
            container.scheduler.reconcileAsync()
        }
        savedFlash = true
        text = ""
        notes = ""
        if (closeAfter) onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .imePadding()
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Quick Add",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (savedFlash) {
                    ChipLike("✓ Added", Color(0xFF00BFA5))
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            Spacer(Modifier.height(10.dp))

            // ---- capture field + mic ----
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; savedFlash = false },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("Buy milk tomorrow at 6pm #home, call HR during lunch…")
                },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save(closeAfter = false) }),
                trailingIcon = {
                    IconButton(onClick = { if (listening) { recognizer.stopListening(); listening = false } else startVoice() }) {
                        Icon(
                            if (listening) Icons.Default.RecordVoiceOver else Icons.Default.Mic,
                            contentDescription = "Voice input",
                            tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            if (listening) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Listening…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            // ---- parse preview ----
            if (parsed != null) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = { typeOverride = null },
                        label = { Text(typeLabel(effectiveType)) },
                        leadingIcon = { Icon(Icons.Default.Sell, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    )
                    (dateOverride?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                        ?: parsed.dueAt)?.let { at ->
                        AssistChip(onClick = { showDate = true }, label = { Text(formatDate(at)) },
                            leadingIcon = { Icon(Icons.Default.CalendarToday, null, Modifier.size(14.dp)) })
                    }
                    if (effectivePriority != Priority.NORMAL) {
                        AssistChip(onClick = { }, label = { Text(effectivePriority.name.lowercase()) })
                    }
                    effectiveRecurrence?.takeIf { it.isActive }?.let { rule ->
                        AssistChip(onClick = { }, label = { Text(com.lifedeck.app.domain.RecurrenceEngine.label(rule)) },
                            leadingIcon = { Icon(Icons.Default.Repeat, null, Modifier.size(14.dp)) })
                    }
                    if (parsed.type == EntryType.SHOPPING && bucketOverride == null) {
                        AssistChip(onClick = { }, label = { Text("Shopping") })
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // ---- type + priority ----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField(
                    label = "Type",
                    modifier = Modifier.weight(1f),
                    value = typeLabel(effectiveType),
                    options = EntryType.entries.map { typeLabel(it) },
                    onSelect = { index -> typeOverride = EntryType.entries[index] },
                )
                DropdownField(
                    label = "Priority",
                    modifier = Modifier.weight(1f),
                    value = effectivePriority.name.lowercase().replaceFirstChar { it.uppercase() },
                    options = Priority.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                    onSelect = { index -> priorityOverride = Priority.entries[index] },
                )
            }

            // ---- date / time / recurrence ----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showDate = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.CalendarToday, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(effectiveDate?.let { formatDate(it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) } ?: "Date")
                }
                OutlinedButton(
                    onClick = { showTime = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(effectiveTime?.let { "%02d:%02d".format(it.first, it.second) } ?: "Time")
                }
            }
            Spacer(Modifier.height(6.dp))
            DropdownField(
                label = "Repeats",
                modifier = Modifier.fillMaxWidth(),
                value = com.lifedeck.app.domain.RecurrenceEngine.label(effectiveRecurrence),
                options = listOf("Does not repeat", "Every day", "Every weekday", "Every week", "Every month", "Every year"),
                onSelect = { index ->
                    recurrenceOverride = when (index) {
                        0 -> null
                        1 -> Recurrence(RecurrenceFreq.DAILY)
                        2 -> Recurrence(RecurrenceFreq.WEEKDAYS)
                        3 -> Recurrence(RecurrenceFreq.WEEKLY)
                        4 -> Recurrence(RecurrenceFreq.MONTHLY)
                        else -> Recurrence(RecurrenceFreq.YEARLY)
                    }
                },
            )

            Spacer(Modifier.height(6.dp))
            DropdownField(
                label = "Category",
                modifier = Modifier.fillMaxWidth(),
                value = categories.firstOrNull { it.id == categoryId }?.name ?: "None",
                options = listOf("None") + categories.map { it.name },
                onSelect = { index ->
                    categoryId = if (index == 0) null else categories.getOrNull(index - 1)?.id
                },
            )

            // ---- reminder lead time ----
            Spacer(Modifier.height(8.dp))
            Text("Remind me before the time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val leads = listOf("None" to null, "10 min" to 10, "30 min" to 30, "1 hour" to 60, "1 day" to 1440)
                leads.forEach { (label, minutes) ->
                    FilterChip(
                        selected = leadMinutes == minutes,
                        onClick = { leadMinutes = minutes },
                        label = { Text(label) },
                    )
                }
            }

            // ---- shopping bucket ----
            if (effectiveType == EntryType.SHOPPING) {
                Spacer(Modifier.height(8.dp))
                Text("Shopping window", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShoppingBucket.entries.forEach { bucket ->
                        FilterChip(
                            selected = (bucketOverride ?: ShoppingBucket.NOW) == bucket,
                            onClick = { bucketOverride = bucket },
                            label = { Text(bucketLabel(bucket)) },
                        )
                    }
                }
            }

            // ---- notes ----
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Notes (optional)") },
                minLines = 1,
                maxLines = 3,
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { save(closeAfter = true) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Save") }
                OutlinedButton(
                    onClick = { save(closeAfter = false) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Save & add another") }
            }
            if (text.isBlank() && savedFlash) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Capture saved — type the next one whenever you're ready.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }

    // ---- date picker ----
    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (dateOverride?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                ?: now()),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        dateOverride = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state, showModeToggle = false)
        }
    }

    // ---- time picker ----
    if (showTime) {
        val timeState = rememberTimePickerState(
            initialHour = timeOverride?.first ?: 9,
            initialMinute = timeOverride?.second ?: 0,
            is24Hour = true,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    timeOverride = timeState.hour to timeState.minute
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = timeState) },
        )
    }
}

@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose $label")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            singleLine = true,
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { index, option ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(index); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ChipLike(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun atMillis(date: LocalDate, hour: Int, minute: Int): Long =
    date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun typeLabel(type: EntryType): String = when (type) {
    EntryType.TASK -> "Task"
    EntryType.SHOPPING -> "Shopping"
    EntryType.APPOINTMENT -> "Appointment"
    EntryType.IDEA -> "Idea"
    EntryType.ERRAND -> "Errand"
    EntryType.PROJECT_STEP -> "Project step"
    EntryType.GOAL_STEP -> "Goal step"
    EntryType.HABIT -> "Habit"
    EntryType.MEMORY_EVENT -> "Memory"
}

private fun bucketLabel(bucket: ShoppingBucket): String = when (bucket) {
    ShoppingBucket.NOW -> "Now"
    ShoppingBucket.WEEK -> "This week"
    ShoppingBucket.FUTURE -> "Later"
}
