package com.example.compx551a4.notification

import android.app.Notification
import android.os.Build
import android.service.notification.ZenPolicy
import androidx.annotation.RequiresApi
import com.example.compx551a4.core.DeferredNotification
import com.example.compx551a4.core.PolicySettings

// [C] Which notifications break through a focus period, and how that maps onto the platform.
//
// DESIGN.md §3: the AutomaticZenRule gives us calls, repeat callers and starred contacts for free,
// so the platform enforces the allowlist. This class only translates PolicySettings into the
// ZenPolicy the rule carries, and classifies what the listener saw so the queue and the stats can
// tell a held notification from one the platform let through.

object BreakthroughPolicy {

    /** The rule's policy on API 29+. Everything is silenced except what [settings] allows. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun zenPolicyFor(settings: PolicySettings): ZenPolicy {
        val calls = when {
            settings.allowCalls -> ZenPolicy.PEOPLE_TYPE_ANYONE
            settings.allowStarredContacts -> ZenPolicy.PEOPLE_TYPE_STARRED
            else -> ZenPolicy.PEOPLE_TYPE_NONE
        }
        val messages = if (settings.allowStarredContacts) ZenPolicy.PEOPLE_TYPE_STARRED else ZenPolicy.PEOPLE_TYPE_NONE
        return ZenPolicy.Builder()
            .disallowAllSounds()
            .allowAlarms(settings.allowAlarms)
            .allowCalls(calls)
            .allowRepeatCallers(settings.allowRepeatCallers)
            .allowMessages(messages)
            .build()
    }

    /**
     * True when the platform would have let this notification through regardless of the rule, so
     * it is recorded as delivered rather than held. Category is the only signal the listener gets;
     * starred-contact and repeat-caller decisions live inside the system and are not visible here.
     */
    fun isBreakthrough(notification: DeferredNotification, settings: PolicySettings): Boolean {
        if (notification.packageName in settings.allowedPackages) return true
        return when (notification.category) {
            Notification.CATEGORY_CALL -> settings.allowCalls
            Notification.CATEGORY_ALARM -> settings.allowAlarms
            else -> false
        }
    }
}
