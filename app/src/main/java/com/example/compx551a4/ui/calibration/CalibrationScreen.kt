package com.example.compx551a4.ui.calibration

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.compx551a4.R
import com.example.compx551a4.core.CalibrationBlock
import com.example.compx551a4.core.CalibrationCondition
import com.example.compx551a4.core.CalibrationResult
import com.example.compx551a4.core.ContactQuality
import com.example.compx551a4.core.EegSource
import com.example.compx551a4.core.FocusEngine
import com.example.compx551a4.core.FocusEpoch
import com.example.compx551a4.core.TaskScore
import com.example.compx551a4.data.HighDemandTask
import com.example.compx551a4.data.UiPreferences
import com.example.compx551a4.ui.calibration.tasks.DEFAULT_ITEM_MS
import com.example.compx551a4.ui.calibration.tasks.FixationTask
import com.example.compx551a4.ui.calibration.tasks.NBackTask
import com.example.compx551a4.ui.calibration.tasks.READING_PASSAGES
import com.example.compx551a4.ui.calibration.tasks.ReadingQuestion
import com.example.compx551a4.ui.calibration.tasks.ReadingTask
import com.example.compx551a4.ui.calibration.tasks.SerialSubtractionTask
import com.example.compx551a4.ui.calibration.tasks.TaskScaffold
import com.example.compx551a4.ui.common.ContactQualityMeter
import com.example.compx551a4.ui.common.NoticeCard
import com.example.compx551a4.ui.common.ScreenPadding
import com.example.compx551a4.ui.common.SectionCard
import com.example.compx551a4.ui.common.SeparationBar
import com.example.compx551a4.ui.common.formatDecimal
import com.example.compx551a4.ui.common.formatPercent
import com.example.compx551a4.ui.container
import com.example.compx551a4.ui.model.LiveStatus
import com.example.compx551a4.ui.model.liveStatusFlow
import com.example.compx551a4.ui.theme.CalibrationTaskTheme
import com.example.compx551a4.ui.theme.COMPX551A4Theme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

// [D] The four task screens (fixation, reading, 2-back, subtraction) + progress + result.
//
// The seam with workload B: D runs the protocol and logs what the user did; B fits the thresholds
// from the blocks and epochs handed over at the end. The blocks themselves are CalibrationBlock in
// core/Models.kt, which is the only thing the two workloads have to agree on.

sealed interface CalPhase {
    data object Intro : CalPhase

    /** Contact or connection is not good enough to start the next block. */
    data class Blocked(val disconnected: Boolean, val contact: ContactQuality) : CalPhase

    data class Baseline(val remainingMs: Long, val totalMs: Long) : CalPhase
    data class Countdown(val secondsLeft: Int) : CalPhase
    data class Running(
        val condition: CalibrationCondition,
        val remainingMs: Long,
        val totalMs: Long,
        val passageIndex: Int,
        val seed: Int,
    ) : CalPhase

    data class Question(val passageIndex: Int) : CalPhase
    data object Rest : CalPhase
    data object Fitting : CalPhase
    data class Done(val result: CalibrationResult) : CalPhase
}

data class CalibrationUiState(
    val phase: CalPhase = CalPhase.Intro,
    val blockNumber: Int = 0,
    val totalBlocks: Int = TOTAL_BLOCKS,
    val highDemandTask: HighDemandTask = HighDemandTask.N_BACK,
)

/** Three conditions, three blocks each — DESIGN.md §2. */
const val TOTAL_BLOCKS = 9

class CalibrationViewModel(
    private val eegSource: EegSource,
    private val focusEngine: FocusEngine,
    private val liveStatus: Flow<LiveStatus>,
    uiPreferences: UiPreferences,
    private val fullBlockMs: Long = 60_000L,
    private val fullBaselineMs: Long = 45_000L,
    private val random: Random = Random.Default,
) : ViewModel() {

    private var fast = false
    private val blockMs: Long get() = if (fast) FAST_BLOCK_MS else fullBlockMs
    private val baselineMs: Long get() = if (fast) FAST_BASELINE_MS else fullBaselineMs
    private val restMs: Long get() = if (fast) FAST_REST_MS else REST_MS
    private val countdownFrom: Int get() = if (fast) 1 else 5

    private val _uiState = MutableStateFlow(CalibrationUiState())
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    /**
     * Written by the capture coroutine and read by the protocol coroutine, both on
     * Dispatchers.Default — so every touch of it goes through [epochsLock].
     */
    private val epochs = mutableListOf<FocusEpoch>()
    private val epochsLock = Mutex()
    private val blocks = mutableListOf<CalibrationBlock>()
    private var recording = false
    private var latestScore: TaskScore? = null
    private var answer: CompletableDeferred<Int?>? = null
    private var skipBlock: CompletableDeferred<Unit>? = null

    private var status: LiveStatus = LiveStatus()
    private var run: Job? = null
    private var capture: Job? = null

    init {
        viewModelScope.launch { liveStatus.collect { status = it } }
        viewModelScope.launch {
            uiPreferences.prefs.collect {
                fast = it.fastCalibration
                _uiState.value = _uiState.value.copy(highDemandTask = it.highDemandTask)
            }
        }
    }

    fun start() {
        if (run?.isActive == true) return
        blocks.clear()
        run = viewModelScope.launch {
            epochsLock.withLock { epochs.clear() }
            runProtocol()
        }
    }

    /** Called when the screen leaves, and by the abort button. Nothing is persisted. */
    fun abort() {
        run?.cancel()
        capture?.cancel()
        recording = false
        if (!status.serviceRunning) eegSource.stop()
        _uiState.value = CalibrationUiState(highDemandTask = _uiState.value.highDemandTask)
    }

    fun reportScore(score: TaskScore) {
        latestScore = score
    }

    fun answerQuestion(index: Int?) {
        answer?.complete(index)
    }

    /** "Finished reading": ends the current reading block now rather than at the timer. */
    fun finishBlockEarly() {
        skipBlock?.complete(Unit)
    }

    private suspend fun runProtocol() {
        // The service may not be running — calibration needs its own signal either way.
        if (!status.serviceRunning) eegSource.start()
        capture = viewModelScope.launch {
            eegSource.epochs.collect { epoch ->
                if (recording) epochsLock.withLock { epochs += epoch }
            }
        }

        focusEngine.reset()
        countdownPhase(baselineMs) { remaining ->
            CalPhase.Baseline(remaining, baselineMs)
        }

        // Order-randomised so a systematic effect of time-into-session (fatigue, drying gel, a
        // settling headband) cannot masquerade as a condition effect.
        val queue = ArrayDeque(
            CalibrationCondition.entries
                .flatMap { condition -> List(TOTAL_BLOCKS / 3) { condition } }
                .shuffled(random),
        )

        var completed = 0
        var redone = 0
        var mediumSeen = 0

        while (queue.isNotEmpty()) {
            val condition = queue.removeFirst()
            _uiState.value = _uiState.value.copy(
                blockNumber = completed + 1,
                totalBlocks = completed + 1 + queue.size,
            )

            awaitUsableSignal()

            for (second in countdownFrom downTo 1) {
                _uiState.value = _uiState.value.copy(phase = CalPhase.Countdown(second))
                delay(1_000)
            }

            val passageIndex = mediumSeen % READING_PASSAGES.size
            if (condition == CalibrationCondition.MEDIUM) mediumSeen++

            latestScore = null
            val startedAt = System.currentTimeMillis()
            val epochsBefore = epochsLock.withLock { epochs.size }
            // Only reading can end early: a fixation or 2-back block that stops short is just less data.
            val skip = if (condition == CalibrationCondition.MEDIUM) CompletableDeferred<Unit>() else null
            skipBlock = skip
            recording = true
            countdownPhase(blockMs, skip) { remaining ->
                CalPhase.Running(condition, remaining, blockMs, passageIndex, seed = completed)
            }
            recording = false
            skipBlock = null
            val durationMs = minOf(blockMs, System.currentTimeMillis() - startedAt)

            // Asked after recording stops: answering is a different task from reading, and its
            // epochs would be mislabelled as MEDIUM.
            if (condition == CalibrationCondition.MEDIUM) {
                _uiState.value = _uiState.value.copy(phase = CalPhase.Question(passageIndex))
                val deferred = CompletableDeferred<Int?>().also { answer = it }
                val chosen = deferred.await()
                latestScore = TaskScore(
                    correct = if (chosen == READING_PASSAGES[passageIndex].correctIndex) 1 else 0,
                    total = 1,
                )
            }

            val collected = epochsLock.withLock { epochs.drop(epochsBefore) }
            val validFraction = if (collected.isEmpty()) 0.0 else
                collected.count { it.valid }.toDouble() / collected.size

            if (validFraction < MIN_VALID_FRACTION && redone < MAX_REDOS) {
                // Too much of this block was artifact. Drop it and queue a replacement rather than
                // fitting thresholds to data that was mostly blinks.
                redone++
                epochsLock.withLock { repeat(collected.size) { epochs.removeAt(epochs.lastIndex) } }
                queue.addLast(condition)
            } else {
                blocks += CalibrationBlock(
                    condition = condition,
                    startedAtMs = startedAt,
                    durationMs = durationMs,
                    taskScore = latestScore,
                )
                completed++
            }

            if (queue.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(phase = CalPhase.Rest)
                delay(restMs)
            }
        }

        capture?.cancel()
        if (!status.serviceRunning) eegSource.stop()

        _uiState.value = _uiState.value.copy(phase = CalPhase.Fitting)
        val result = focusEngine.fitCalibration(blocks, epochsLock.withLock { epochs.toList() })
        _uiState.value = _uiState.value.copy(phase = CalPhase.Done(result))
    }

    /**
     * "Use these thresholds". A separable fit is applied by the engine itself; a poor one is left
     * alone so the app fails open. The user has read the result and chosen to go ahead regardless,
     * so apply exactly what the screen showed them.
     */
    fun accept(onDone: () -> Unit) {
        val result = (_uiState.value.phase as? CalPhase.Done)?.result
        viewModelScope.launch {
            if (result != null && focusEngine.thresholds.value != result.thresholds) {
                focusEngine.updateThresholds(result.thresholds)
            }
            onDone()
        }
    }

    /**
     * Holds the protocol until the headset is worn properly. Nine minutes of unusable data is a far
     * worse outcome than a pause here, and this is the only moment the user can act on it without
     * disturbing a block in progress.
     */
    private suspend fun awaitUsableSignal() {
        while (!status.signalTrustworthy) {
            _uiState.value = _uiState.value.copy(
                phase = CalPhase.Blocked(
                    disconnected = !status.device.usable,
                    contact = status.contact,
                ),
            )
            delay(500)
        }
    }

    /** Ticks [phase] down to zero, or until [skip] completes if one is given. */
    private suspend fun countdownPhase(
        totalMs: Long,
        skip: CompletableDeferred<Unit>? = null,
        phase: (Long) -> CalPhase,
    ) {
        var remaining = totalMs
        while (remaining > 0 && skip?.isCompleted != true) {
            _uiState.value = _uiState.value.copy(phase = phase(remaining))
            delay(TICK_MS)
            remaining -= TICK_MS
        }
    }

    override fun onCleared() {
        super.onCleared()
        abort()
    }

    companion object {
        private const val TICK_MS = 250L
        private const val REST_MS = 8_000L

        // Developer option: same protocol, same phases, a tenth of the wall clock.
        const val FAST_BLOCK_MS = 6_000L
        private const val FAST_BASELINE_MS = 4_000L
        private const val FAST_REST_MS = 1_500L

        /** Below this share of clean epochs a block is not worth fitting to. */
        private const val MIN_VALID_FRACTION = 0.5

        /** Bounded so a headset that never seats properly ends the run instead of looping. */
        private const val MAX_REDOS = 3

        val Factory = viewModelFactory {
            initializer {
                val c = container()
                CalibrationViewModel(
                    eegSource = c.eegSource,
                    focusEngine = c.focusEngine,
                    liveStatus = liveStatusFlow(c.eegSource, c.focusEngine, c.suppressor, c.serviceController),
                    uiPreferences = c.uiPreferences,
                )
            }
        }
    }
}

@Composable
fun CalibrationScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalibrationViewModel = viewModel(factory = CalibrationViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CalibrationWindowEffects()

    CalibrationContent(
        state = state,
        onStart = viewModel::start,
        onAbort = { viewModel.abort(); onDone() },
        onScore = viewModel::reportScore,
        onAnswer = viewModel::answerQuestion,
        onFinishReading = viewModel::finishBlockEarly,
        onAccept = { viewModel.accept(onDone) },
        onRedo = viewModel::start,
        modifier = modifier,
    )
}

/**
 * Two window-level concerns this screen owns for as long as it is shown:
 *
 * - the screen must not time out — nine minutes of fixation involves no touch input at all;
 * - the status bar icons must be light, because the task screens are dark whatever the system theme
 *   is. Without this they are drawn dark-on-dark and the clock disappears.
 */
@Composable
private fun CalibrationWindowEffects() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val wasLightStatusBars = controller?.isAppearanceLightStatusBars
        val wasLightNavBars = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            wasLightStatusBars?.let { controller?.isAppearanceLightStatusBars = it }
            wasLightNavBars?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun CalibrationContent(
    state: CalibrationUiState,
    onStart: () -> Unit,
    onAbort: () -> Unit,
    onScore: (TaskScore) -> Unit,
    onAnswer: (Int?) -> Unit,
    onFinishReading: () -> Unit,
    onAccept: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val phase = state.phase) {
        // Every recording phase is wrapped in the fixed calibration theme, so the visual field is
        // identical across conditions and across devices.
        is CalPhase.Baseline, is CalPhase.Running, is CalPhase.Countdown -> CalibrationTaskTheme {
            // Background full-bleed, content inside the safe area: the colour reaches the screen
            // edges while the dot and the timer stay clear of the cutout and the gesture bar.
            Box(
                modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                TaskPhase(phase, state, onScore, onFinishReading, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
            }
        }

        else -> {
            val inset = modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            when (phase) {
                is CalPhase.Intro -> IntroPhase(onStart, onAbort, inset)
                is CalPhase.Blocked -> BlockedPhase(phase, onAbort, inset)
                is CalPhase.Question -> QuestionPhase(phase, onAnswer, inset)
                is CalPhase.Rest -> CentredMessage(stringResource(R.string.cal_rest), inset)
                is CalPhase.Fitting -> FittingPhase(inset)
                is CalPhase.Done -> ResultPhase(phase.result, onAccept, onRedo, inset)
                else -> Unit
            }
        }
    }
}

@Composable
private fun TaskPhase(
    phase: CalPhase,
    state: CalibrationUiState,
    onScore: (TaskScore) -> Unit,
    onFinishReading: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (phase) {
        is CalPhase.Countdown -> Box(
            modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.cal_countdown, phase.secondsLeft),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        is CalPhase.Baseline -> TaskScaffold(
            instruction = stringResource(R.string.cal_baseline_body),
            progress = 1f - phase.remainingMs.toFloat() / phase.totalMs,
            remainingMs = phase.remainingMs,
            modifier = modifier,
        ) {
            FixationTask()
        }

        is CalPhase.Running -> TaskScaffold(
            instruction = stringResource(phase.condition.instructionRes(state.highDemandTask)),
            progress = 1f - phase.remainingMs.toFloat() / phase.totalMs,
            remainingMs = phase.remainingMs,
            modifier = modifier,
            blockLabel = stringResource(R.string.cal_block_progress, state.blockNumber, state.totalBlocks),
        ) {
            when (phase.condition) {
                CalibrationCondition.LOW -> FixationTask()
                CalibrationCondition.MEDIUM -> ReadingTask(
                    READING_PASSAGES[phase.passageIndex],
                    onFinished = onFinishReading,
                )
                CalibrationCondition.HIGH -> when (state.highDemandTask) {
                    // Fast mode shortens the block tenfold; the 2-back has to speed up with it or a
                    // six-second block ends before a single item can be scored.
                    HighDemandTask.N_BACK -> NBackTask(
                        onScore = onScore,
                        seed = phase.seed,
                        itemMs = if (phase.totalMs <= CalibrationViewModel.FAST_BLOCK_MS) FAST_NBACK_ITEM_MS else DEFAULT_ITEM_MS,
                    )
                    HighDemandTask.SERIAL_SUBTRACTION ->
                        SerialSubtractionTask(onScore = onScore, seed = phase.seed)
                }
            }
        }

        else -> Unit
    }
}

@Composable
private fun IntroPhase(onStart: () -> Unit, onAbort: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.cal_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.cal_intro_body), style = MaterialTheme.typography.bodyMedium)
        NoticeCard(text = stringResource(R.string.cal_intro_rules), icon = Icons.Default.Warning)
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cal_intro_start))
        }
        TextButton(onClick = onAbort, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun BlockedPhase(
    phase: CalPhase.Blocked,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.cal_blocked_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(
                if (phase.disconnected) R.string.cal_blocked_disconnected else R.string.cal_blocked_body,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionCard(title = stringResource(R.string.home_device_title)) {
            ContactQualityMeter(phase.contact)
        }
        TextButton(onClick = onAbort, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cal_abort))
        }
    }
}

@Composable
private fun QuestionPhase(
    phase: CalPhase.Question,
    onAnswer: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReadingQuestion(passage = READING_PASSAGES[phase.passageIndex], onAnswer = onAnswer)
    }
}

@Composable
private fun CentredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(ScreenPadding), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FittingPhase(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.cal_fitting),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun ResultPhase(
    result: CalibrationResult,
    onAccept: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (result.fitted) R.string.cal_result_title else R.string.cal_result_title_insufficient),
            style = MaterialTheme.typography.headlineSmall,
        )

        SectionCard(title = stringResource(R.string.cal_result_auc)) {
            if (result.fitted) {
                Text(
                    formatDecimal(result.auc),
                    fontSize = 40.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                // DESIGN.md §7: poor separability for some users is a legitimate finding about consumer
                // EEG, not a failure. The copy says so rather than implying the user did it wrong.
                Text(
                    stringResource(
                        when {
                            result.auc >= 0.75 -> R.string.cal_result_auc_good
                            result.auc >= 0.60 -> R.string.cal_result_auc_fair
                            else -> R.string.cal_result_auc_poor
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SeparationBar(lowMeanZ = result.lowMeanZ, highMeanZ = result.highMeanZ)
                Text(
                    stringResource(
                        R.string.cal_result_means,
                        formatDecimal(result.lowMeanZ),
                        formatDecimal(result.highMeanZ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // No fit happened, so no number: a "0.50" here would read as a measured chance
                // result when nothing was measured at all.
                Text(
                    "—",
                    fontSize = 40.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.cal_result_insufficient),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(
                    R.string.cal_result_thresholds,
                    formatDecimal(result.thresholds.tHighZ),
                    formatDecimal(result.thresholds.tLowZ),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Task accuracy is the objective check that the user actually engaged (DESIGN.md §2), so it
        // is reported, not hidden in a log.
        val reading = result.blocks.filter { it.condition == CalibrationCondition.MEDIUM }
        val hard = result.blocks.filter { it.condition == CalibrationCondition.HIGH }
        Text(
            stringResource(
                R.string.cal_result_tasks,
                formatAccuracy(reading.mapNotNull { it.taskScore }),
                formatAccuracy(hard.mapNotNull { it.taskScore }),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (result.fitted) {
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cal_result_accept))
            }
            OutlinedButton(onClick = onRedo, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cal_result_redo))
            }
        } else {
            // Running again is the right action, so it gets the primary button; keeping what is
            // already in use (or the placeholder) is the escape hatch.
            Button(onClick = onRedo, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cal_result_redo))
            }
            OutlinedButton(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cal_result_keep_current))
            }
        }
    }
}

@Composable
@ReadOnlyComposable
private fun formatAccuracy(scores: List<TaskScore>): String {
    val total = scores.sumOf { it.total }
    if (total == 0) return "–"
    return formatPercent(scores.sumOf { it.correct }.toDouble() / total)
}

/** Ten items in a six-second block: enough to score eight of them. */
private const val FAST_NBACK_ITEM_MS = 600L

private fun CalibrationCondition.instructionRes(highTask: HighDemandTask): Int = when (this) {
    CalibrationCondition.LOW -> R.string.cal_task_fixation
    CalibrationCondition.MEDIUM -> R.string.cal_task_reading
    CalibrationCondition.HIGH -> when (highTask) {
        HighDemandTask.N_BACK -> R.string.cal_task_nback
        HighDemandTask.SERIAL_SUBTRACTION -> R.string.cal_task_subtraction
    }
}

@Preview(showBackground = true)
@Composable
private fun CalibrationIntroPreview() {
    COMPX551A4Theme { IntroPhase(onStart = {}, onAbort = {}) }
}

@Preview(showBackground = true)
@Composable
private fun CalibrationResultPreview() {
    COMPX551A4Theme {
        ResultPhase(
            result = CalibrationResult(
                thresholds = com.example.compx551a4.core.FocusThresholds.PLACEHOLDER.copy(auc = 0.81),
                auc = 0.81,
                blocks = listOf(
                    CalibrationBlock(CalibrationCondition.MEDIUM, 0L, 60_000L, TaskScore(3, 3)),
                    CalibrationBlock(CalibrationCondition.HIGH, 0L, 60_000L, TaskScore(22, 28, 3)),
                ),
                lowMeanZ = -0.62,
                highMeanZ = 0.74,
            ),
            onAccept = {},
            onRedo = {},
        )
    }
}
