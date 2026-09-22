package com.example.compx551a4.ui.preview

import com.example.compx551a4.core.ArtifactFlags
import com.example.compx551a4.core.BandPowers
import com.example.compx551a4.core.CalibrationBlock
import com.example.compx551a4.core.CalibrationCondition
import com.example.compx551a4.core.CalibrationResult
import com.example.compx551a4.core.ContactQuality
import com.example.compx551a4.core.DeferredNotification
import com.example.compx551a4.core.DeviceState
import com.example.compx551a4.core.DeviceStatus
import com.example.compx551a4.core.EegSource
import com.example.compx551a4.core.FlushReason
import com.example.compx551a4.core.FocusDecision
import com.example.compx551a4.core.FocusEngine
import com.example.compx551a4.core.FocusEpoch
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.FocusThresholds
import com.example.compx551a4.core.NotificationPolicy
import com.example.compx551a4.core.PolicySettings
import com.example.compx551a4.core.ServiceController
import com.example.compx551a4.core.SessionRecorder
import com.example.compx551a4.core.Suppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// [D] In-memory stand-ins for workloads A, B and C.
//
// WORKLOADS.md: "Everyone codes against fakes for the interfaces they don't own." A's FakeEegSource
// is the intended article, but it does not exist yet and D cannot wait for it — so this file lets
// the entire UI run, preview and unit-test with no headset and no A/B/C code present. It is also
// what the "Demo mode" developer option runs on.
//
// Delete this in favour of the real implementations at integration. If anything in ui/ has to
// change when that happens, the contract boundary was drawn in the wrong place.

// ---------------------------------------------------------------------------
// Synthetic signal
// ---------------------------------------------------------------------------

/**
 * Generates a plausible engagement index: a slow sine (focus waxes and wanes over minutes) plus
 * pink-ish noise, with occasional blinks, jaw clenches and stretches of poor contact so the UI's
 * degraded paths are exercised rather than assumed.
 */
class SyntheticSignal(seed: Int = 7) {

    private val random = Random(seed)
    private var tick = 0
    private var poorContactUntil = 0

    fun next(timestampMs: Long): FocusEpoch {
        tick++

        val slow = sin(tick / 90.0) * 0.9 + sin(tick / 23.0) * 0.25
        val noise = random.nextDouble(-0.35, 0.35)
        val index = (1.4 + slow + noise).coerceAtLeast(0.05)

        if (poorContactUntil < tick && random.nextDouble() < 0.004) {
            poorContactUntil = tick + random.nextInt(8, 25)
        }
        val poor = tick <= poorContactUntil
        val contact = if (poor) {
            ContactQuality(random.nextInt(2, 5), random.nextInt(2, 5), 2, random.nextInt(1, 4))
        } else {
            ContactQuality(1, 1, 1, 1)
        }

        val artifacts = ArtifactFlags(
            blink = random.nextDouble() < 0.05,
            jawClench = random.nextDouble() < 0.02,
            movement = random.nextDouble() < 0.03,
            emgBroadband = random.nextDouble() < 0.02,
        )

        val theta = 1.0 + random.nextDouble(-0.1, 0.1)
        val alpha = max(0.2, 1.6 - slow * 0.5 + random.nextDouble(-0.1, 0.1))
        val beta = index * (alpha + theta)

        return FocusEpoch(
            timestampMs = timestampMs,
            focusIndex = index,
            bands = BandPowers(
                alphaTemporal = alpha,
                beta1320 = beta,
                theta = theta,
                emgBroadband = if (artifacts.emgBroadband) 2.4 else random.nextDouble(0.1, 0.4),
            ),
            contact = contact,
            artifacts = artifacts,
            valid = !artifacts.any && contact.worst <= 2,
        )
    }

    /** Biases the generator toward a calibration condition, so fitting has something to separate. */
    fun nextFor(condition: CalibrationCondition, timestampMs: Long): FocusEpoch {
        val base = next(timestampMs)
        val shift = when (condition) {
            CalibrationCondition.LOW -> -0.55
            CalibrationCondition.MEDIUM -> 0.0
            CalibrationCondition.HIGH -> 0.55
        }
        return base.copy(focusIndex = (base.focusIndex + shift).coerceAtLeast(0.05))
    }
}

// ---------------------------------------------------------------------------
// Workload A stand-ins
// ---------------------------------------------------------------------------

class DemoEegSource(
    private val scope: CoroutineScope,
    private val signal: SyntheticSignal = SyntheticSignal(),
    private val tickMs: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : EegSource {

    private val _status = MutableStateFlow(DeviceStatus.DISCONNECTED)
    override val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    private val _epochs = MutableSharedFlow<FocusEpoch>(replay = 0, extraBufferCapacity = 64)
    override val epochs: Flow<FocusEpoch> = _epochs.asSharedFlow()

    private var job: Job? = null

    override suspend fun start() {
        if (job?.isActive == true) return
        _status.value = DeviceStatus(DeviceState.SCANNING)
        job = scope.launch {
            delay(1_200)
            _status.value = DeviceStatus(DeviceState.CONNECTING, deviceName = DEVICE_NAME)
            delay(800)
            while (true) {
                val epoch = signal.next(clock())
                _status.value = DeviceStatus(
                    state = if (epoch.contact.worst > 2) DeviceState.POOR_CONTACT else DeviceState.CONNECTED,
                    deviceName = DEVICE_NAME,
                    batteryPct = 84,
                    contact = epoch.contact,
                )
                _epochs.emit(epoch)
                delay(tickMs)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _status.value = DeviceStatus.DISCONNECTED
    }

    private companion object {
        const val DEVICE_NAME = "Muse-DEMO"
    }
}

// ---------------------------------------------------------------------------
// Workload B stand-in
// ---------------------------------------------------------------------------

/**
 * A deliberately simple version of the real thing: rolling z-score over the last [WINDOW] accepted
 * epochs (DESIGN.md §1's 5-10 minute normalisation) feeding a two-threshold Schmitt trigger with
 * entry and exit dwell.
 *
 * It is not the research contribution — B's pipeline replaces it — but it is close enough in shape
 * that the UI is exercised against realistic behaviour rather than a square wave.
 */
class DemoFocusEngine(
    initialThresholds: FocusThresholds? = null,
) : FocusEngine {

    private val window = ArrayDeque<Double>()
    private var focusedForSec = 0
    private var unfocusedForSec = 0

    private val _state = MutableStateFlow(FocusState.UNKNOWN)
    override val state: StateFlow<FocusState> = _state.asStateFlow()

    private val _decisions = MutableSharedFlow<FocusDecision>(replay = 0, extraBufferCapacity = 128)
    override val decisions: Flow<FocusDecision> = _decisions.asSharedFlow()

    private val _thresholds = MutableStateFlow(initialThresholds)
    override val thresholds: StateFlow<FocusThresholds?> = _thresholds.asStateFlow()

    private val _recalibrationSuggested = MutableStateFlow(false)
    override val recalibrationSuggested: StateFlow<Boolean> = _recalibrationSuggested.asStateFlow()

    override fun submit(epoch: FocusEpoch) {
        val t = _thresholds.value
        if (!epoch.valid || t == null) {
            // Rejected epochs do not advance the dwell timers — a blink must not end a focus period.
            emit(epoch, normalizedZ = 0.0, accepted = false)
            return
        }

        window.addLast(epoch.focusIndex)
        if (window.size > WINDOW) window.removeFirst()

        if (window.size < WARMUP) {
            // No usable z-score yet, so this epoch feeds no decision. Reported as not accepted so
            // the live chart draws a gap instead of a flat line along zero, which would read as a
            // perfectly steady signal rather than as "nothing known yet".
            _state.value = FocusState.UNKNOWN
            emit(epoch, normalizedZ = 0.0, accepted = false)
            return
        }

        val z = zScore(epoch.focusIndex)
        when {
            z > t.tHighZ -> { focusedForSec++; unfocusedForSec = 0 }
            z < t.tLowZ -> { unfocusedForSec++; focusedForSec = 0 }
            else -> { focusedForSec = 0; unfocusedForSec = 0 }
        }

        _state.value = when {
            _state.value != FocusState.FOCUSED && focusedForSec >= t.entryDwellSec -> FocusState.FOCUSED
            _state.value == FocusState.FOCUSED && unfocusedForSec >= t.exitDwellSec -> FocusState.NOT_FOCUSED
            _state.value == FocusState.UNKNOWN -> FocusState.NOT_FOCUSED
            else -> _state.value
        }
        emit(epoch, normalizedZ = z, accepted = true)
    }

    private fun emit(epoch: FocusEpoch, normalizedZ: Double, accepted: Boolean) {
        _decisions.tryEmit(
            FocusDecision(
                timestampMs = epoch.timestampMs,
                state = _state.value,
                rawIndex = epoch.focusIndex,
                normalizedZ = normalizedZ,
                accepted = accepted,
            )
        )
    }

    private fun zScore(value: Double): Double {
        val mean = window.average()
        val sd = sqrt(window.sumOf { (it - mean) * (it - mean) } / window.size)
        return if (sd < 1e-6) 0.0 else (value - mean) / sd
    }

    override suspend fun updateThresholds(thresholds: FocusThresholds) {
        _thresholds.value = thresholds
    }

    override fun reset() {
        window.clear()
        focusedForSec = 0
        unfocusedForSec = 0
        _state.value = FocusState.UNKNOWN
    }

    /**
     * Midpoint-of-class-means fitting with a Mann-Whitney AUC, per DESIGN.md §2's baseline. B
     * replaces this with LDA or logistic regression over 2-3 features; the return shape is what the
     * result screen renders either way.
     */
    override suspend fun fitCalibration(
        blocks: List<CalibrationBlock>,
        epochs: List<FocusEpoch>,
    ): CalibrationResult {
        val valid = epochs.filter { it.valid }
        val mean = valid.map { it.focusIndex }.average().takeIf { !it.isNaN() } ?: 0.0
        val sd = sqrt(valid.sumOf { (it.focusIndex - mean) * (it.focusIndex - mean) } / max(1, valid.size))
            .takeIf { it > 1e-6 } ?: 1.0

        fun zOf(condition: CalibrationCondition): List<Double> = blocks
            .filter { it.condition == condition }
            .flatMap { block ->
                valid.filter { it.timestampMs in block.startedAtMs..(block.startedAtMs + block.durationMs) }
            }
            .map { (it.focusIndex - mean) / sd }

        val low = zOf(CalibrationCondition.LOW)
        val high = zOf(CalibrationCondition.HIGH)
        val lowMean = low.average().takeIf { !it.isNaN() } ?: -0.5
        val highMean = high.average().takeIf { !it.isNaN() } ?: 0.5
        val midpoint = (lowMean + highMean) / 2.0
        val margin = abs(highMean - lowMean) * 0.15

        val fitted = FocusThresholds(
            tHighZ = midpoint + margin,
            tLowZ = midpoint - margin,
            entryDwellSec = _thresholds.value?.entryDwellSec ?: FocusThresholds.PLACEHOLDER.entryDwellSec,
            exitDwellSec = _thresholds.value?.exitDwellSec ?: FocusThresholds.PLACEHOLDER.exitDwellSec,
            fittedAtMs = System.currentTimeMillis(),
            auc = auc(low, high),
        )
        _thresholds.value = fitted
        _recalibrationSuggested.value = false
        return CalibrationResult(fitted, fitted.auc, blocks, lowMean, highMean)
    }

    /** Mann-Whitney U as a proportion of concordant pairs. 0.5 is chance. */
    private fun auc(low: List<Double>, high: List<Double>): Double {
        if (low.isEmpty() || high.isEmpty()) return 0.5
        var concordant = 0.0
        for (h in high) for (l in low) {
            concordant += when {
                h > l -> 1.0
                h == l -> 0.5
                else -> 0.0
            }
        }
        return concordant / (high.size * low.size)
    }

    private companion object {
        /** ~10 minutes of 1 Hz decisions. */
        const val WINDOW = 600

        /** Below this the rolling statistics are not worth trusting. */
        const val WARMUP = 30
    }
}

// ---------------------------------------------------------------------------
// Workload C stand-ins
// ---------------------------------------------------------------------------

class DemoSuppressor(
    private val scope: CoroutineScope,
    private val recorder: SessionRecorder,
    private val clock: () -> Long = System::currentTimeMillis,
) : Suppressor {

    private val _deferred = MutableStateFlow<List<DeferredNotification>>(emptyList())
    override val deferred: StateFlow<List<DeferredNotification>> = _deferred.asStateFlow()

    private var arrivals: Job? = null

    override suspend fun apply(state: FocusState) {
        if (state == FocusState.FOCUSED) startArrivals() else stopArrivals()
        // Fail open: anything other than a confident FOCUSED releases the queue (DESIGN.md §3).
        if (state != FocusState.FOCUSED && _deferred.value.isNotEmpty()) {
            flush(FlushReason.FOCUS_BROKE)
        }
    }

    override suspend fun flush(reason: FlushReason) {
        val count = _deferred.value.size
        _deferred.value = emptyList()
        if (count > 0) recorder.onFlush(count, reason, clock())
    }

    private fun startArrivals() {
        if (arrivals?.isActive == true) return
        arrivals = scope.launch {
            var n = _deferred.value.size
            while (true) {
                delay(9_000)
                val sample = SAMPLE_APPS.random()
                val notification = DeferredNotification(
                    key = "demo-${n++}",
                    packageName = sample.second,
                    appLabel = sample.first,
                    title = SAMPLE_TITLES.random(),
                    postedAtMs = clock(),
                )
                _deferred.value = _deferred.value + notification
                recorder.onDeferred(notification)
            }
        }
    }

    private fun stopArrivals() {
        arrivals?.cancel()
        arrivals = null
    }

    private companion object {
        val SAMPLE_APPS = listOf(
            "Messages" to "com.google.android.apps.messaging",
            "Slack" to "com.Slack",
            "Gmail" to "com.google.android.gm",
            "Instagram" to "com.instagram.android",
        )
        val SAMPLE_TITLES = listOf(
            "Are you free this afternoon?",
            "3 new messages",
            "Build finished",
            "Your order has shipped",
        )
    }
}

class DemoNotificationPolicy : NotificationPolicy {
    private val _settings = MutableStateFlow(PolicySettings())
    override val settings: StateFlow<PolicySettings> = _settings.asStateFlow()

    override suspend fun update(settings: PolicySettings) {
        _settings.value = settings
    }
}

// ---------------------------------------------------------------------------
// The wiring A's FocusService will own
// ---------------------------------------------------------------------------

/**
 * Stands in for FocusService: epochs in, engine decides, suppressor applies, recorder logs.
 *
 * This loop is workload A's to own once FocusService exists; it lives here only so the UI has
 * something to switch on and off before then.
 */
class DemoServiceController(
    private val scope: CoroutineScope,
    private val source: EegSource,
    private val engine: FocusEngine,
    private val suppressor: Suppressor,
    private val recorder: SessionRecorder,
    private val clock: () -> Long = System::currentTimeMillis,
) : ServiceController {

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    override fun start() {
        if (_running.value) return
        _running.value = true
        engine.reset()
        job = scope.launch {
            recorder.onSessionStart(clock())
            launch { source.start() }
            launch {
                engine.decisions.collect { decision ->
                    recorder.onDecision(decision)
                    suppressor.apply(decision.state)
                }
            }
            source.epochs.collect { engine.submit(it) }
        }
    }

    override fun stop() {
        if (!_running.value) return
        _running.value = false
        job?.cancel()
        job = null
        source.stop()
        scope.launch {
            suppressor.flush(FlushReason.SERVICE_STOPPED)
            recorder.onSessionEnd(clock())
        }
    }
}
