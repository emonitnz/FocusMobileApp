package com.example.compx551a4.notification

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.service.notification.Condition

class DndController(
    private val context: Context//Context gives our class access to Android system services.
) {
   //NotificationManager is the Android system component that lets us work with notification-related settings, including the DND/Zen system.
    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)
    private var ruleId: String? = null //When Android creates our DND rule, addAutomaticZenRule() gives us an ID for that rule. We need to remember that ID so later we can say: activate this rule or deactivate this rule

    /**
     * Creates and registers the Focus Mode Do Not Disturb rule with Android.
     *
     * This creates the rule once. It does not decide when Focus Mode starts
     * or stops; setFocusMode() controls that later.
     */
    fun createRule() {

        // Start building an Android AutomaticZenRule.
        val rule = AutomaticZenRule.Builder(

            // The name Android will use for our DND rule.
            "Focus Mode",

            // A unique ID that identifies our Focus Mode condition.
            Uri.parse("condition://com.example.compx551a4/focus")
        )

            // Tell Android which activity can be opened to configure this rule.
            // We use our app's MainActivity.
            .setConfigurationActivity(
                android.content.ComponentName(
                    context,
                    com.example.compx551a4.MainActivity::class.java
                )
            )

            // Set what type of notification interruption is allowed
            // when this DND rule is active.
            // NONE means no notification interruptions.
            .setInterruptionFilter(
                NotificationManager.INTERRUPTION_FILTER_NONE
            )

            // Finish building the AutomaticZenRule object.
            .build()

        // Register the rule with Android's NotificationManager.
        // Android returns a unique ID for the rule.
        // We save that ID so we can activate/deactivate this rule later.
        ruleId = notificationManager.addAutomaticZenRule(rule)
    }

    /**
     * Turns Focus Mode ON or OFF.
     *
     * @param enabled true = Focus Mode ON
     *                false = Focus Mode OFF
     */
    fun setFocusMode(enabled: Boolean) {

        // Get the ID of the DND rule we created earlier.
        // If no rule has been created yet, stop here.
        val id = ruleId ?: return

        // Convert the Boolean into an Android Condition state.
        // true  -> Focus Mode is active
        // false -> Focus Mode is inactive
        val state = if (enabled) {
            Condition.STATE_TRUE
        } else {
            Condition.STATE_FALSE
        }

        // Create a condition describing the current state of Focus Mode.
        val condition = Condition(

            // The unique ID of our Focus Mode condition.
            Uri.parse("condition://com.example.compx551a4/focus"),

            // A human-readable description of the condition.
            "Focus Mode",

            // The current state: active or inactive.
            state,

            // Tells Android that the state change is caused by a change in context,
            // such as the app detecting that the user has entered or left Focus Mode.
            Condition.SOURCE_CONTEXT
        )

        // Tell Android that the state of our DND rule has changed.
        // If the condition is TRUE, Android activates the rule.
        // If the condition is FALSE, Android deactivates the rule.
        notificationManager.setAutomaticZenRuleState(
            id,
            condition
        )
    }
}
