package com.example.compx551a4.ui.model

import com.example.compx551a4.core.ContactQuality
import com.example.compx551a4.core.DeferredNotification
import com.example.compx551a4.core.DeviceStatus
import com.example.compx551a4.core.EegSource
import com.example.compx551a4.core.FocusEngine
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.FocusThresholds
import com.example.compx551a4.core.ServiceController
import com.example.compx551a4.core.Suppressor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The slice of live state more than one screen needs: home shows it, and calibration gates on it.
 * Combined once here rather than assembled independently in two ViewModels.
 */
data class LiveStatus(
    val device: DeviceStatus = DeviceStatus.DISCONNECTED,
    val focusState: FocusState = FocusState.UNKNOWN,
    val thresholds: FocusThresholds? = null,
    val recalibrationSuggested: Boolean = false,
    val deferred: List<DeferredNotification> = emptyList(),
    val serviceRunning: Boolean = false,
) {
    val contact: ContactQuality get() = device.contact
    val calibrated: Boolean get() = thresholds != null
    val deferredCount: Int get() = deferred.size

    /**
     * Whether the signal is good enough to act on. Deliberately strict: DESIGN.md §3 would rather
     * deliver a notification that could have been held than hold one on a bad reading.
     */
    val signalTrustworthy: Boolean get() = device.usable && contact.worst <= 2
}

fun liveStatusFlow(
    eegSource: EegSource,
    focusEngine: FocusEngine,
    suppressor: Suppressor,
    serviceController: ServiceController,
): Flow<LiveStatus> =
    combine(
        eegSource.status,
        focusEngine.state,
        focusEngine.thresholds,
        focusEngine.recalibrationSuggested,
    ) { device, state, thresholds, recalibrate ->
        LiveStatus(
            device = device,
            focusState = state,
            thresholds = thresholds,
            recalibrationSuggested = recalibrate,
        )
    }
        .combine(suppressor.deferred) { status, deferred -> status.copy(deferred = deferred) }
        .combine(serviceController.running) { status, running -> status.copy(serviceRunning = running) }
