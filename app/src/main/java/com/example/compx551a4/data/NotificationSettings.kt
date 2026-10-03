package com.example.compx551a4.data

import android.content.Context
import android.content.SharedPreferences
import com.example.compx551a4.core.NotificationPolicy
import com.example.compx551a4.core.PolicySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

// [C] Persisted mode, max hold, allowlist.

/**
 * The breakthrough allowlist and max-hold valve, in SharedPreferences. Read once at construction
 * (a handful of keys, on the main thread is fine), written on every change.
 */
class NotificationSettings(context: Context) : NotificationPolicy {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    override val settings: StateFlow<PolicySettings> = _settings.asStateFlow()

    override suspend fun update(settings: PolicySettings) {
        _settings.value = settings
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putInt(KEY_MAX_HOLD, settings.maxHoldMinutes)
                .putBoolean(KEY_CALLS, settings.allowCalls)
                .putBoolean(KEY_ALARMS, settings.allowAlarms)
                .putBoolean(KEY_REPEAT, settings.allowRepeatCallers)
                .putBoolean(KEY_STARRED, settings.allowStarredContacts)
                .putBoolean(KEY_TWO_FACTOR, settings.allowTwoFactorCodes)
                .putStringSet(KEY_PACKAGES, settings.allowedPackages)
                .commit()
        }
    }

    private fun load(): PolicySettings {
        val defaults = PolicySettings()
        return PolicySettings(
            maxHoldMinutes = prefs.getInt(KEY_MAX_HOLD, defaults.maxHoldMinutes),
            allowCalls = prefs.getBoolean(KEY_CALLS, defaults.allowCalls),
            allowAlarms = prefs.getBoolean(KEY_ALARMS, defaults.allowAlarms),
            allowRepeatCallers = prefs.getBoolean(KEY_REPEAT, defaults.allowRepeatCallers),
            allowStarredContacts = prefs.getBoolean(KEY_STARRED, defaults.allowStarredContacts),
            allowTwoFactorCodes = prefs.getBoolean(KEY_TWO_FACTOR, defaults.allowTwoFactorCodes),
            allowedPackages = prefs.getStringSet(KEY_PACKAGES, null)?.toSet() ?: defaults.allowedPackages,
        )
    }

    private companion object {
        const val PREFS = "notification_policy"
        const val KEY_MAX_HOLD = "maxHoldMinutes"
        const val KEY_CALLS = "allowCalls"
        const val KEY_ALARMS = "allowAlarms"
        const val KEY_REPEAT = "allowRepeatCallers"
        const val KEY_STARRED = "allowStarredContacts"
        const val KEY_TWO_FACTOR = "allowTwoFactorCodes"
        const val KEY_PACKAGES = "allowedPackages"
    }
}
