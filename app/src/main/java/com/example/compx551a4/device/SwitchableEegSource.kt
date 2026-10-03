package com.example.compx551a4.device

import com.example.compx551a4.core.DeviceStatus
import com.example.compx551a4.core.EegSource
import com.example.compx551a4.core.FocusEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// [A] The "Demo mode" developer switch: the real headset or the synthetic one, chosen at runtime.

/**
 * Delegates to [real] or [fake] depending on [demoMode], so the whole app — calibration, the
 * service, suppression — can be exercised without a headset. Flipping the switch while a source is
 * running moves the connection across, so consumers only ever see one device's status and epochs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableEegSource(
    private val real: EegSource,
    private val fake: EegSource,
    demoMode: Flow<Boolean>,
    private val scope: CoroutineScope,
) : EegSource {

    private val active = MutableStateFlow(real)

    @Volatile
    private var wanted = false

    override val status: StateFlow<DeviceStatus> = active
        .flatMapLatest { it.status }
        .stateIn(scope, SharingStarted.Eagerly, DeviceStatus.DISCONNECTED)

    override val epochs: Flow<FocusEpoch> = active.flatMapLatest { it.epochs }

    init {
        scope.launch {
            demoMode.distinctUntilChanged().collect { demo ->
                val next = if (demo) fake else real
                val previous = active.value
                if (next === previous) return@collect
                if (wanted) previous.stop()
                active.value = next
                if (wanted) next.start()
            }
        }
    }

    override suspend fun start() {
        wanted = true
        active.value.start()
    }

    override fun stop() {
        wanted = false
        active.value.stop()
    }
}
