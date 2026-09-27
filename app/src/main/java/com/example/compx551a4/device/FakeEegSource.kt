package com.example.compx551a4.device

import com.example.compx551a4.core.ArtifactFlags
import com.example.compx551a4.core.BandPowers
import com.example.compx551a4.core.ContactQuality
import com.example.compx551a4.core.DeviceState
import com.example.compx551a4.core.DeviceStatus
import com.example.compx551a4.core.EegSource
import com.example.compx551a4.core.FocusEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

class FakeEegSource(
    private val periodMs: Long = 1_000L,
    private val cycleLengthEpochs: Int = 180,
    private val artifactRate: Double = 0.05,
    private val poorContactEveryEpochs: Int = 97,
    private val random: Random = Random(seed = 42),
) : EegSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var epochCount = 0L

    private val _status = MutableStateFlow(DeviceStatus.DISCONNECTED)
    override val status: StateFlow<DeviceStatus> = _status

    private val _epochs = MutableSharedFlow<FocusEpoch>(extraBufferCapacity = 8)
    override val epochs: Flow<FocusEpoch> = _epochs.asSharedFlow()

    override suspend fun start() {
        if (job?.isActive == true) return

        _status.value = DeviceStatus(DeviceState.SCANNING, deviceName = "Fake Muse S")
        delay(400)
        _status.value = DeviceStatus(DeviceState.CONNECTING, deviceName = "Fake Muse S")
        delay(600)
        _status.value = DeviceStatus(
            state = DeviceState.CONNECTED,
            deviceName = "Fake Muse S",
            batteryPct = 87,
            contact = ContactQuality(1, 1, 1, 1),
        )
        epochCount = 0

        job = scope.launch {
            while (true) {
                emitNextEpoch()
                delay(periodMs)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _status.value = DeviceStatus.DISCONNECTED
    }

    private suspend fun emitNextEpoch() {
        val now = System.currentTimeMillis()
        val n = epochCount++

        if (poorContactEveryEpochs > 0 && n > 0 && n % poorContactEveryEpochs < 5) {
            val badContact = ContactQuality(tp9 = 3, af7 = 2, af8 = 2, tp10 = 4)
            _status.value = _status.value.copy(state = DeviceState.POOR_CONTACT, contact = badContact)
            _epochs.emit(
                FocusEpoch(
                    timestampMs = now,
                    focusIndex = 0.0,
                    bands = BandPowers(0.0, 0.0, 0.0, 0.0),
                    contact = badContact,
                    artifacts = ArtifactFlags.NONE,
                    valid = false,
                )
            )
            return
        } else if (_status.value.state == DeviceState.POOR_CONTACT) {
            _status.value = _status.value.copy(
                state = DeviceState.CONNECTED,
                contact = ContactQuality(1, 1, 1, 1),
            )
        }

        val phase = (n % cycleLengthEpochs).toDouble() / cycleLengthEpochs
        val trueFocus = (sin(phase * 2 * Math.PI - Math.PI / 2) + 1) / 2 // 0..1

        fun jitter(scale: Double) = (random.nextDouble() - 0.5) * scale
        val beta = 3.0 + trueFocus * 4.0 + jitter(0.6)
        val alpha = 4.0 - trueFocus * 2.0 + jitter(0.8)
        val theta = 3.0 - trueFocus * 1.0 + jitter(0.6)
        val emg = 1.0 + jitter(0.3)

        val isArtifact = random.nextDouble() < artifactRate
        val flags = if (isArtifact) randomArtifactFlags() else ArtifactFlags.NONE

        val bands = BandPowers(
            alphaTemporal = alpha.coerceAtLeast(0.1),
            beta1320 = beta.coerceAtLeast(0.1),
            theta = theta.coerceAtLeast(0.1),
            emgBroadband = emg.coerceAtLeast(0.0),
        )

        _epochs.emit(
            FocusEpoch(
                timestampMs = now,
                focusIndex = bands.engagementIndex,
                bands = bands,
                contact = _status.value.contact,
                artifacts = flags,
                valid = !isArtifact,
            )
        )
    }

    private fun randomArtifactFlags(): ArtifactFlags = when (random.nextInt(4)) {
        0 -> ArtifactFlags(blink = true)
        1 -> ArtifactFlags(jawClench = true)
        2 -> ArtifactFlags(movement = true)
        else -> ArtifactFlags(emgBroadband = true)
    }
}
