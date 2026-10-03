package com.example.compx551a4.signal

import com.example.compx551a4.calibration.CalibrationSession
import com.example.compx551a4.calibration.FitOutcome
import com.example.compx551a4.calibration.ThresholdFitter
import com.example.compx551a4.core.CalibrationBlock
import com.example.compx551a4.core.CalibrationResult
import com.example.compx551a4.core.FocusDecision
import com.example.compx551a4.core.FocusEngine
import com.example.compx551a4.core.FocusEpoch
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.FocusThresholds
import com.example.compx551a4.data.CalibrationRecord
import com.example.compx551a4.data.ThresholdStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

// [B] Epoching, band powers (beta 13-20 Hz), Pope index, rolling z-score. Implements FocusEngine.

/**
 * Workload B's [FocusEngine]: artifact rejection -> Pope index -> rolling z-score -> state machine.
 *
 * The index is recomputed from [FocusEpoch.bands] as [com.example.compx551a4.core.BandPowers.engagementIndex]
 * rather than trusting [FocusEpoch.focusIndex], so the definition lives in one place whichever source
 * produced the epoch.
 *
 * Every submitted epoch produces exactly one [FocusDecision]. It is `accepted = false`, and does not
 * touch the state machine, when the epoch was rejected, the rolling window is still warming up, or
 * there are no thresholds yet. Accepted-but-uncalibrated epochs still feed the window, so it is warm
 * by the time a calibration lands.
 *
 * With a [store], thresholds survive restarts: they are loaded on [scope] at construction and saved
 * on every change. Without one (tests, previews) everything stays in memory.
 */
class FocusPipeline(
    initialThresholds: FocusThresholds? = null,
    private val store: ThresholdStore? = null,
    scope: CoroutineScope? = null,
    /**
     * Accepted epochs averaged before thresholding. 1 means none. The raw index at 1 Hz is noisy
     * enough that a single dip resets the entry dwell, so a few seconds of averaging is what lets
     * a genuinely focused stretch ever satisfy "sustained above T_high".
     */
    smoothingWindow: Int = 1,
    /**
     * Score against the calibration run's mean and SD when a fit is available, and only fall back
     * to the rolling window without one. The thresholds were fitted on that distribution, and a
     * rolling window re-centres on whatever the user has been doing, so a long focus period
     * normalises itself away (SUMMARY_B.md, open question 1). The rolling window still runs
     * underneath so the drift detector can ask for a recalibration when the signal moves.
     */
    private val useCalibrationReference: Boolean = true,
    private val rejector: ArtifactRejector = ArtifactRejector(),
    private val stateMachine: FocusStateMachine = FocusStateMachine(),
    private val normalizer: RollingZScore = RollingZScore(),
    private val drift: DriftDetector = DriftDetector(),
    private val session: CalibrationSession = CalibrationSession(),
    private val fitter: ThresholdFitter = ThresholdFitter(),
    private val clock: () -> Long = System::currentTimeMillis,
) : FocusEngine {

    /** [submit] runs on the service's collector while [reset] may come from the UI. */
    private val lock = Any()

    private val smoother = MovingAverage(smoothingWindow)

    private val _state = MutableStateFlow(FocusState.UNKNOWN)
    override val state: StateFlow<FocusState> = _state.asStateFlow()

    private val _decisions = MutableSharedFlow<FocusDecision>(replay = 0, extraBufferCapacity = 128)
    override val decisions: Flow<FocusDecision> = _decisions.asSharedFlow()

    private val _thresholds = MutableStateFlow(initialThresholds)
    override val thresholds: StateFlow<FocusThresholds?> = _thresholds.asStateFlow()

    /**
     * Raised by [drift] and kept up until a separable calibration lands. Not cleared by [reset]:
     * the stale thresholds are still the ones in use next session.
     */
    private val _recalibrationSuggested = MutableStateFlow(false)
    override val recalibrationSuggested: StateFlow<Boolean> = _recalibrationSuggested.asStateFlow()

    init {
        if (store != null && scope != null) {
            scope.launch {
                // Only fills an empty slot: a calibration or settings edit that lands before the
                // load finishes is newer than what is on disk.
                store.loadCurrent()?.let { _thresholds.compareAndSet(null, it) }

                // The drift reference is the calibration the thresholds in use came from.
                val fittedAt = _thresholds.value?.fittedAtMs ?: return@launch
                val record = store.history().lastOrNull { it.fittedAtMs == fittedAt } ?: return@launch
                val mean = record.indexMean ?: return@launch
                val sd = record.indexSd ?: return@launch
                synchronized(lock) {
                    if (drift.reference == null) drift.reference = DriftDetector.Reference(fittedAt, mean, sd)
                }
            }
        }
    }

    override fun submit(epoch: FocusEpoch) {
        val decision = synchronized(lock) { decide(epoch) }
        _decisions.tryEmit(decision)
    }

    private fun decide(epoch: FocusEpoch): FocusDecision {
        val index = epoch.bands.engagementIndex

        if (rejector.check(epoch) != null) return decision(epoch, index, z = 0.0, accepted = false)

        val smoothed = smoother.push(index)
        val reference = drift.reference.takeIf { useCalibrationReference && it != null && it.sd > MIN_REFERENCE_SD }
        // The window always holds the raw index: it is what the drift detector compares against
        // the calibration run, and smoothing would shrink its SD and look like drift.
        val rollingZ = normalizer.score(smoothed)
        normalizer.push(index)
        val z = if (reference != null) (smoothed - reference.mean) / reference.sd else rollingZ
        val t = _thresholds.value
        if (z == null || t == null) return decision(epoch, index, z = z ?: 0.0, accepted = false)

        _state.value = stateMachine.update(z, epoch.timestampMs, t)
        if (drift.check(normalizer.mean, normalizer.sd, normalizer.size, t.fittedAtMs)) {
            _recalibrationSuggested.value = true
        }
        return decision(epoch, index, z, accepted = true)
    }

    private fun decision(epoch: FocusEpoch, index: Double, z: Double, accepted: Boolean) = FocusDecision(
        timestampMs = epoch.timestampMs,
        state = _state.value,
        rawIndex = index,
        normalizedZ = z,
        accepted = accepted,
    )

    /** Saved before it is applied, so invalid thresholds are refused rather than half-applied. */
    override suspend fun updateThresholds(thresholds: FocusThresholds) {
        store?.saveCurrent(thresholds)
        _thresholds.value = thresholds
    }

    override fun reset() {
        synchronized(lock) {
            rejector.reset()
            stateMachine.reset()
            normalizer.reset()
            smoother.reset()
            drift.reset()
            _state.value = FocusState.UNKNOWN
        }
    }

    /**
     * Prepares the run ([CalibrationSession]), fits it ([ThresholdFitter]) and records it.
     *
     * - **Separable:** saved to the history, made current, and applied immediately.
     * - **Not separable** (HIGH not clearly above LOW): saved to the history for the evaluation, but
     *   the thresholds in use are left alone. Acting on an index that does not track this user's
     *   engagement would suppress notifications at random; leaving them alone fails open.
     * - **Too few usable epochs:** nothing is saved, and the result reports chance (AUC 0.5) so the
     *   result screen shows a poor run rather than the app crashing.
     *
     * The returned result always carries every input block, so the task accuracy the result screen
     * shows includes blocks that were dropped for disengagement.
     */
    override suspend fun fitCalibration(
        blocks: List<CalibrationBlock>,
        epochs: List<FocusEpoch>,
    ): CalibrationResult {
        val current = _thresholds.value ?: FocusThresholds.PLACEHOLDER
        val prepared = session.prepare(blocks, epochs)

        return when (val fit = fitter.fit(prepared.samples, current.entryDwellSec, current.exitDwellSec, clock())) {
            is FitOutcome.Insufficient -> CalibrationResult(
                thresholds = current,
                auc = 0.5,
                blocks = blocks,
                lowMeanZ = 0.0,
                highMeanZ = 0.0,
            )
            is FitOutcome.Fitted -> {
                val record = CalibrationRecord(
                    thresholds = fit.thresholds,
                    auc = fit.auc,
                    lowMeanZ = fit.lowMeanZ,
                    highMeanZ = fit.highMeanZ,
                    blockCount = prepared.keptBlocks.size,
                    indexMean = fit.indexMean,
                    indexSd = fit.indexSd,
                )
                store?.recordCalibration(record, makeCurrent = fit.separable)
                if (fit.separable) {
                    synchronized(lock) {
                        drift.reference = DriftDetector.Reference(fit.thresholds.fittedAtMs, fit.indexMean, fit.indexSd)
                    }
                    _thresholds.value = fit.thresholds
                    _recalibrationSuggested.value = false
                }
                CalibrationResult(
                    thresholds = fit.thresholds,
                    auc = fit.auc,
                    blocks = blocks,
                    lowMeanZ = fit.lowMeanZ,
                    highMeanZ = fit.highMeanZ,
                )
            }
        }
    }
}

/** A calibration whose index barely varied cannot score anything; fall back to the window. */
private const val MIN_REFERENCE_SD = 1e-9

/** Mean of the last [windowSize] values pushed; the mean of what is there until it fills. */
class MovingAverage(private val windowSize: Int) {
    init {
        require(windowSize >= 1) { "windowSize must be at least 1" }
    }

    private val window = ArrayDeque<Double>()

    fun push(value: Double): Double {
        window.addLast(value)
        if (window.size > windowSize) window.removeFirst()
        return window.average()
    }

    fun reset() = window.clear()
}

/**
 * Rolling z-score over the last [windowSize] values (default 10 min at ~1 Hz, DESIGN.md §1).
 *
 * Each value is scored against the window *before* it is added, so a sudden excursion is not
 * partly cancelled by its own contribution to the mean and SD. Returns null until [warmup] values
 * are in the window. A flat window (SD ≈ 0) scores 0.
 *
 * Kept separate from the pipeline so calibration fitting can normalise exactly the same way.
 */
class RollingZScore(
    private val windowSize: Int = DEFAULT_WINDOW,
    private val warmup: Int = DEFAULT_WARMUP,
) {
    init {
        require(warmup in 2..windowSize) { "warmup must be in 2..windowSize" }
    }

    private val window = ArrayDeque<Double>()

    val size: Int get() = window.size

    /** Mean of the window; NaN when empty. */
    val mean: Double get() = window.average()

    /** Sample SD of the window; 0 with fewer than two values. */
    val sd: Double
        get() {
            if (window.size < 2) return 0.0
            val m = mean
            return sqrt(window.sumOf { (it - m) * (it - m) } / (window.size - 1))
        }

    /** Scores [value] against the current window, then adds it. */
    fun push(value: Double): Double? {
        val z = score(value)
        window.addLast(value)
        if (window.size > windowSize) window.removeFirst()
        return z
    }

    /** Scores [value] against the current window without adding it; null until warmed up. */
    fun score(value: Double): Double? {
        if (window.size < warmup) return null
        val sd = sd
        return if (sd < MIN_SD) 0.0 else (value - mean) / sd
    }

    fun reset() = window.clear()

    companion object {
        const val DEFAULT_WINDOW = 600
        const val DEFAULT_WARMUP = 30
        private const val MIN_SD = 1e-9
    }
}