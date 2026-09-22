package com.example.compx551a4.ui.onboarding

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

// [D] Every platform read the onboarding flow needs, in one place and free of Compose, so the
// ViewModel stays testable and the screen stays declarative.

enum class PermissionStepId {
    NOTIFICATIONS,
    BLUETOOTH,

    /** Special access: granted in Settings, not by a dialog. */
    LISTENER,
    DND,
    BATTERY,
}

data class PermissionStep(
    val id: PermissionStepId,
    val granted: Boolean,
) {
    /**
     * Required means "without this the app cannot do the thing it exists for". DND access and the
     * listener are the two that actually hold notifications back; the rest degrade rather than break.
     */
    val required: Boolean get() = id == PermissionStepId.DND || id == PermissionStepId.BLUETOOTH

    /** Runtime dialog vs. a trip to Settings — the two need completely different handling. */
    val isRuntimePermission: Boolean
        get() = id == PermissionStepId.NOTIFICATIONS || id == PermissionStepId.BLUETOOTH
}

object PermissionChecks {

    fun readAll(context: Context): List<PermissionStep> = PermissionStepId.entries.map { id ->
        PermissionStep(id = id, granted = isGranted(context, id))
    }

    fun isGranted(context: Context, id: PermissionStepId): Boolean = when (id) {
        PermissionStepId.NOTIFICATIONS -> runtimePermissionsFor(id).all { context.hasPermission(it) }
        PermissionStepId.BLUETOOTH -> runtimePermissionsFor(id).all { context.hasPermission(it) }
        PermissionStepId.LISTENER ->
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        PermissionStepId.DND ->
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationPolicyAccessGranted == true

        PermissionStepId.BATTERY ->
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    /**
     * Empty for the Settings-based steps. POST_NOTIFICATIONS only exists on 33+, and Bluetooth
     * scanning was gated behind location before 31 — on older devices the app must ask for
     * ACCESS_FINE_LOCATION instead, which is why the onboarding copy explains it.
     */
    fun runtimePermissionsFor(id: PermissionStepId): Array<String> = when (id) {
        PermissionStepId.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }

        PermissionStepId.BLUETOOTH ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        else -> emptyArray()
    }

    /**
     * These intents return no result — the user grants in Settings and navigates back — so the
     * caller must re-read state on resume rather than waiting for a callback.
     */
    fun settingsIntentFor(context: Context, id: PermissionStepId): Intent? = when (id) {
        PermissionStepId.LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        PermissionStepId.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        PermissionStepId.BATTERY ->
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ).takeIf { it.resolveActivity(context.packageManager) != null }
                // Some OEM builds refuse the direct request; the full list always resolves.
                ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        else -> null
    }

    private fun Context.hasPermission(name: String) =
        ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED
}
