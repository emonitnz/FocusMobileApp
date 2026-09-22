package com.example.compx551a4.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// [D] UI-layer preferences: onboarding progress and developer options.
// Distinct from NotificationSettings (C) and ThresholdStore (B), which persist domain state.

/** Which HIGH-demand calibration task to run. DESIGN.md §2 offers either. */
enum class HighDemandTask { N_BACK, SERIAL_SUBTRACTION }

data class UiPrefs(
    val onboardingComplete: Boolean = false,
    val demoMode: Boolean = false,
    val rawLoggingEnabled: Boolean = false,
    val highDemandTask: HighDemandTask = HighDemandTask.N_BACK,
    /** Shortens every calibration block so the protocol can be exercised without a nine-minute wait. */
    val fastCalibration: Boolean = false,
)

private val Context.uiPreferencesStore: DataStore<Preferences> by preferencesDataStore("ui_prefs")

class UiPreferences(context: Context) {

    private val store = context.applicationContext.uiPreferencesStore

    val prefs: Flow<UiPrefs> = store.data.map { p ->
        UiPrefs(
            onboardingComplete = p[KEY_ONBOARDING_COMPLETE] ?: false,
            demoMode = p[KEY_DEMO_MODE] ?: false,
            rawLoggingEnabled = p[KEY_RAW_LOGGING] ?: false,
            highDemandTask = p[KEY_HIGH_TASK]
                ?.let { runCatching { HighDemandTask.valueOf(it) }.getOrNull() }
                ?: HighDemandTask.N_BACK,
            fastCalibration = p[KEY_FAST_CALIBRATION] ?: false,
        )
    }

    suspend fun setOnboardingComplete(complete: Boolean) =
        store.edit { it[KEY_ONBOARDING_COMPLETE] = complete }

    suspend fun setDemoMode(enabled: Boolean) =
        store.edit { it[KEY_DEMO_MODE] = enabled }

    suspend fun setRawLogging(enabled: Boolean) =
        store.edit { it[KEY_RAW_LOGGING] = enabled }

    suspend fun setHighDemandTask(task: HighDemandTask) =
        store.edit { it[KEY_HIGH_TASK] = task.name }

    suspend fun setFastCalibration(enabled: Boolean) =
        store.edit { it[KEY_FAST_CALIBRATION] = enabled }

    private companion object {
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_DEMO_MODE = booleanPreferencesKey("demo_mode")
        val KEY_RAW_LOGGING = booleanPreferencesKey("raw_logging")
        val KEY_HIGH_TASK = stringPreferencesKey("high_demand_task")
        val KEY_FAST_CALIBRATION = booleanPreferencesKey("fast_calibration")
    }
}
