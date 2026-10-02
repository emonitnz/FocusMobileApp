package com.example.compx551a4.calibration

import com.example.compx551a4.core.CalibrationBlock
import com.example.compx551a4.core.CalibrationCondition
import com.example.compx551a4.core.FocusEpoch
import com.example.compx551a4.signal.ArtifactRejector

// [B] 3 conditions x 3 blocks, order-randomised. Collects epochs + task performance.
//
// D's CalibrationViewModel runs the protocol itself — block order, timing, task screens — and hands
// over the finished blocks and every recorded epoch. This turns that into labelled samples for
// ThresholdFitter.

/** Why a block was left out of the fit. */
enum class DropReason {
    /** A task block (MEDIUM / HIGH) reported no score at all — the user never responded. */
    NO_TASK_SCORE,

    /** The task reported a score but zero attempts. */
    NO_ATTEMPTS,

    /** Accuracy below [CalibrationSession.minAccuracy]: guessing or mashing, not working. */
    LOW_ACCURACY,
}

data class DroppedBlock(val block: CalibrationBlock, val reason: DropReason)

/**
 * [samples] are what ThresholdFitter consumes. [keptBlocks] and [dropped] together are every input
 * block, so the result screen and the writeup can say what was excluded and why.
 */
data class PreparedCalibration(
    val samples: List<CalibrationSample>,
    val keptBlocks: List<CalibrationBlock>,
    val dropped: List<DroppedBlock>,
)

/**
 * Turns a finished calibration run into labelled samples.
 *
 * For each block:
 * 1. **Engagement.** MEDIUM and HIGH blocks are dropped when their [com.example.compx551a4.core.TaskScore]
 *    shows the user clearly was not doing the task: no score, no attempts, or accuracy below
 *    [minAccuracy]. A HIGH block where the user disengaged is a low-demand recording labelled
 *    high-demand — the worst data the fitter can receive. LOW has no task and is always kept.
 * 2. **Settling.** Epochs in the first [settleMs] of the block are discarded. They cover the
 *    switch into the task, and with 2-4 s analysis windows, the first epochs still contain signal
 *    from before the block started. A short block settles for [settleFraction] of its length
 *    instead, so the developer fast mode (6 s blocks) is not left with almost nothing to fit.
 * 3. **Artifacts.** Every epoch goes through a fresh [ArtifactRejector], in time order, so the
 *    thresholds are fitted on exactly the data the live pipeline would have accepted.
 *
 * Epochs are matched to blocks by timestamp, so both must come from the same clock
 * (`System.currentTimeMillis()`). Each sample's [CalibrationSample.block] is the block's position
 * in the input list.
 */
class CalibrationSession(
    val settleMs: Long = DEFAULT_SETTLE_MS,
    val settleFraction: Double = DEFAULT_SETTLE_FRACTION,
    val minAccuracy: Double = DEFAULT_MIN_ACCURACY,
    private val newRejector: () -> ArtifactRejector = { ArtifactRejector() },
) {
    fun prepare(blocks: List<CalibrationBlock>, epochs: List<FocusEpoch>): PreparedCalibration {
        val accepted = acceptedEpochs(epochs)

        val samples = mutableListOf<CalibrationSample>()
        val kept = mutableListOf<CalibrationBlock>()
        val dropped = mutableListOf<DroppedBlock>()

        blocks.forEachIndexed { position, block ->
            val reason = dropReason(block)
            if (reason != null) {
                dropped += DroppedBlock(block, reason)
                return@forEachIndexed
            }
            kept += block

            val from = block.startedAtMs + settleFor(block)
            val to = block.startedAtMs + block.durationMs
            accepted
                .filter { it.timestampMs in from..to }
                .mapTo(samples) { CalibrationSample(block.condition, position, it.bands.engagementIndex) }
        }
        return PreparedCalibration(samples, kept, dropped)
    }

    /** Runs the whole recording through one rejector so the holdoff and muscle median carry across. */
    private fun acceptedEpochs(epochs: List<FocusEpoch>): List<FocusEpoch> {
        val rejector = newRejector()
        return epochs.sortedBy { it.timestampMs }.filter { rejector.check(it) == null }
    }

    /** [settleMs], or [settleFraction] of the block when that is shorter. */
    fun settleFor(block: CalibrationBlock): Long =
        minOf(settleMs, (block.durationMs * settleFraction).toLong())

    private fun dropReason(block: CalibrationBlock): DropReason? {
        if (block.condition == CalibrationCondition.LOW) return null
        val score = block.taskScore ?: return DropReason.NO_TASK_SCORE
        if (score.total == 0) return DropReason.NO_ATTEMPTS
        if (score.accuracy < minAccuracy) return DropReason.LOW_ACCURACY
        return null
    }

    companion object {
        /** Covers a 4 s analysis window plus a second or so of orienting to the task. */
        const val DEFAULT_SETTLE_MS = 5_000L

        /** 5 s of a 60 s block, so a full-length run settles for exactly [DEFAULT_SETTLE_MS]. */
        const val DEFAULT_SETTLE_FRACTION = 1.0 / 12

        /**
         * Pressing at random on the 2-back scores about 50%, so below this the user was not doing
         * the task. Serial subtraction done properly scores well above it.
         */
        const val DEFAULT_MIN_ACCURACY = 0.5
    }
}