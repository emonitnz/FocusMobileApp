package com.example.compx551a4.notification

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.util.Log
import com.example.compx551a4.MainActivity
import com.example.compx551a4.core.PolicySettings

// [C] AutomaticZenRule on/off, driven by the focus state.

/** What [NotificationController] needs from the platform, so it can be tested without Android. */
interface FocusModeGate {
    /** Idempotent. Must never throw: without policy access it simply does nothing (fail open). */
    fun setFocusMode(enabled: Boolean)

    /** Re-applies the breakthrough allowlist to the rule. */
    fun updatePolicy(settings: PolicySettings)
}

/**
 * Owns the app's "Focus Mode" [AutomaticZenRule].
 *
 * The rule is registered lazily, on the first call that needs it, because the container is built
 * before onboarding has granted notification-policy access. An existing rule from an earlier
 * install is reused rather than duplicated, found by its condition id. Every platform call is
 * guarded: if access is missing or revoked the rule is left alone and notifications are delivered
 * normally, which is the fail-open behaviour DESIGN.md §3 requires.
 */
class DndController(private val context: Context) : FocusModeGate {

    private val notificationManager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private var ruleId: String? = null
    private var policy: PolicySettings = PolicySettings()
    private var enabled = false

    private val hasAccess: Boolean
        get() = notificationManager?.isNotificationPolicyAccessGranted == true

    @Synchronized
    override fun setFocusMode(enabled: Boolean) {
        val manager = notificationManager ?: return
        if (!hasAccess) {
            if (enabled) Log.w(TAG, "DND access not granted — focus mode not applied (fail open)")
            this.enabled = false
            return
        }
        val id = ensureRule(manager) ?: return
        val condition = Condition(
            CONDITION_ID,
            RULE_NAME,
            if (enabled) Condition.STATE_TRUE else Condition.STATE_FALSE,
            Condition.SOURCE_CONTEXT,
        )
        runCatching { manager.setAutomaticZenRuleState(id, condition) }
            .onSuccess { this.enabled = enabled; Log.d(TAG, "focus mode -> $enabled") }
            .onFailure { Log.w(TAG, "setAutomaticZenRuleState failed", it) }
    }

    @Synchronized
    override fun updatePolicy(settings: PolicySettings) {
        policy = settings
        val manager = notificationManager ?: return
        if (!hasAccess) return
        val id = ensureRule(manager) ?: return
        runCatching { manager.updateAutomaticZenRule(id, buildRule()) }
            .onFailure { Log.w(TAG, "updateAutomaticZenRule failed", it) }
    }

    /** Registers the rule if needed and returns its id, or null when the platform refused. */
    private fun ensureRule(manager: NotificationManager): String? {
        ruleId?.let { return it }
        val existing = runCatching { manager.automaticZenRules }.getOrNull().orEmpty()
            .entries.firstOrNull { it.value.conditionId == CONDITION_ID }
        if (existing != null) {
            ruleId = existing.key
            return existing.key
        }
        return runCatching { manager.addAutomaticZenRule(buildRule()) }
            .onFailure { Log.w(TAG, "addAutomaticZenRule failed", it) }
            .getOrNull()
            ?.also { ruleId = it }
    }

    private fun buildRule(): AutomaticZenRule {
        val configuration = ComponentName(context, MainActivity::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AutomaticZenRule.Builder(RULE_NAME, CONDITION_ID)
                .setConfigurationActivity(configuration)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                .setZenPolicy(BreakthroughPolicy.zenPolicyFor(policy))
                .setEnabled(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AutomaticZenRule(
                RULE_NAME,
                configuration,
                CONDITION_ID,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                true,
            )
        }
    }

    companion object {
        private const val TAG = "DndController"
        private const val RULE_NAME = "Focus Mode"
        val CONDITION_ID: Uri = Uri.parse("condition://com.example.compx551a4/focus")
    }
}
