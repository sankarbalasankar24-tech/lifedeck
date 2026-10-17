package com.lifedeck.app.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lifedeck.app.data.model.Priority
import com.lifedeck.app.data.model.ShoppingBucket
import com.lifedeck.app.data.model.TimeWindow
import com.lifedeck.app.util.decode
import com.lifedeck.app.util.encode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.settingsStore by preferencesDataStore(name = "lifedeck_settings")

/**
 * Everything the user can configure. Stored as one versioned JSON document so
 * new preferences get defaults without migrations.
 */
@Serializable
data class AppSettings(
    // ---- appearance ----
    val themeMode: String = "SYSTEM", // SYSTEM | LIGHT | DARK
    val themePalette: String = "BOTANICAL_DARK",
    val textScale: Float = 1.0f,      // accessibility: 0.85 .. 1.5
    val dynamicColor: Boolean = true,
    // ---- schedules (minutes of day) ----
    val workStartMinute: Int = 9 * 60,
    val workEndMinute: Int = 18 * 60,
    val lunchStartMinute: Int = 13 * 60,
    val lunchEndMinute: Int = 14 * 60,
    val sleepStartMinute: Int = 23 * 60,
    val sleepEndMinute: Int = 7 * 60,
    val quietHoursEnabled: Boolean = true,
    val quietStartMinute: Int = 22 * 60,
    val quietEndMinute: Int = 7 * 60,
    /** Named context windows for context-aware reminders ("lunch", "after duty"...). */
    val contextWindows: Map<String, TimeWindow> = defaultContexts(),
    /** Shift reminder *notifications* (never deadlines) into allowed windows. */
    val respectReminderWindows: Boolean = true,
    /** Do not notify during work hours unless the task says otherwise. */
    val avoidWorkHours: Boolean = false,
    // ---- summaries ----
    val morningSummaryHour: Int = 7,
    val morningSummaryMinute: Int = 0,
    val eveningSummaryHour: Int = 21,
    val eveningSummaryMinute: Int = 0,
    val morningSummaryEnabled: Boolean = true,
    val eveningSummaryEnabled: Boolean = true,
    val inboxNudgeEnabled: Boolean = true,
    val inboxNudgeHour: Int = 20,
    val inboxNudgeMinute: Int = 30,
    // ---- notifications ----
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val defaultLeadMinutes: Int = 10,
    val defaultReminderForNewTasks: Boolean = true,
    val escalationIntervalMinutes: Int = 10,
    val escalationMaxCount: Int = 3,
    val snoozeMinutes: Int = 15,
    val exactAlarmsPreferred: Boolean = true,
    // ---- behavior ----
    val autoClassify: Boolean = true,
    val autoCompleteParents: Boolean = true,
    val celebrateMilestones: Boolean = true,
    val celebrateConfetti: Boolean = true,
    val celebrateSound: Boolean = true,
    val inboxPromptEnabled: Boolean = true,
    val inboxPromptHour: Int = 20,
    val inboxPromptMinute: Int = 0,
    val defaultPriority: Priority = Priority.NORMAL,
    val defaultShoppingBucket: ShoppingBucket = ShoppingBucket.NOW,
    val undoSeconds: Int = 8,
    // ---- sync ----
    val syncEnabled: Boolean = false,
    val syncUrl: String = "",
    val syncSecret: String = "",
    val syncIntervalMinutes: Int = 30,
    val syncOnMobileData: Boolean = true,
    val lastSyncAt: Long = 0L,
    val lastSyncError: String = "",
    val lastSyncResult: String = "",
    // ---- backups ----
    val backupFrequency: String = "WEEKLY", // OFF | DAILY | WEEKLY | MONTHLY
    val lastBackupAt: Long = 0L,
    val keepBackups: Int = 20,
) {
    companion object {
        fun defaultContexts(): Map<String, TimeWindow> = mapOf(
            "lunch" to TimeWindow(13 * 60, 14 * 60),
            "after work" to TimeWindow(18 * 60, 19 * 60 + 30),
            "morning" to TimeWindow(7 * 60, 9 * 60),
            "evening" to TimeWindow(19 * 60, 22 * 60),
        )
    }
}

/** Repository for all user preferences (DataStore-backed, offline-first). */
class SettingsRepository(private val context: Context) {

    private val key = stringPreferencesKey("settings_v1")

    val flow: Flow<AppSettings> = context.settingsStore.data
        .map { prefs -> decode<AppSettings>(prefs[key]) ?: AppSettings() }

    suspend fun get(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            val current = decode<AppSettings>(prefs[key]) ?: AppSettings()
            prefs[key] = encode(transform(current))
        }
    }

    suspend fun set(transform: (AppSettings) -> AppSettings) = update(transform)

    suspend fun ensureDefaults() {
        // Data class defaults are the defaults; nothing to seed yet.
    }
}
