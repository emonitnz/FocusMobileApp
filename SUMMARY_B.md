# Workload B — progress summary

Signal & calibration work completed so far, following the order in `PLAN_B.md`. Nothing here is
committed yet. All 112 unit tests pass (`./gradlew :app:testDebugUnitTest`), and `assembleDebug`
builds.

## Status against the plan

| Step | Component | Status |
|---|---|---|
| 1 | `signal/FocusStateMachine.kt` | Done |
| 2 | `signal/ArtifactRejector.kt` | Done |
| 3 | `signal/FocusPipeline.kt` (implements `FocusEngine`) | Done |
| 4 | `data/ThresholdStore.kt` | Done |
| 5 | `calibration/ThresholdFitter.kt` | Done (no leave-one-block-out CV yet) |
| 6 | `calibration/CalibrationSession.kt` + `fitCalibration` wiring | Done |
| 7 | Drift detection (`recalibrationSuggested`) | Done — new file `signal/DriftDetector.kt` |
| 7 | Session-start re-baseline | **Not done** (see open questions) |
| 8 | Offline evaluation | **Not done** |
| — | Swap into `AppContainer` | Tried, then **reverted** at the user's request |

## Files

**Main code**

- `signal/FocusStateMachine.kt`
- `signal/ArtifactRejector.kt` — also defines `RejectReason`
- `signal/FocusPipeline.kt` — also defines `RollingZScore`
- `signal/DriftDetector.kt` — new
- `calibration/ThresholdFitter.kt` — also defines `CalibrationSample` and `FitOutcome`
- `calibration/CalibrationSession.kt` — also defines `PreparedCalibration`, `DroppedBlock` and
  `DropReason`
- `data/ThresholdStore.kt` — also defines `CalibrationRecord`

**Tests** (`app/src/test/java/com/example/compx551a4/`)

| File | Tests |
|---|---|
| `signal/FocusStateMachineTest.kt` | 11 |
| `signal/ArtifactRejectorTest.kt` | 12 |
| `signal/FocusPipelineTest.kt` (includes `RollingZScoreTest`) | 8 + 5 |
| `signal/FocusPipelineCalibrationTest.kt` | 10 |
| `signal/DriftDetectorTest.kt` (includes `FocusPipelineDriftTest`) | 8 + 6 |
| `calibration/ThresholdFitterTest.kt` | 11 |
| `calibration/CalibrationSessionTest.kt` | 9 |
| `data/ThresholdStoreTest.kt` | 12 |

**Docs:** `PLAN_B.md` (the implementation order) and this file.

`AppContainer.kt` and the other shared files are unchanged.

## Components

### FocusStateMachine

The two-threshold state machine. It takes one z-score at a time, with its timestamp and the
current thresholds.

- Enters `FOCUSED` once the index has stayed above `T_high` for at least `entryDwellSec`.
- Enters `NOT_FOCUSED` once it has stayed below `T_low` for at least `exitDwellSec`.
- A value between the thresholds cancels any run in progress but keeps the current state, so the
  state can't flip back and forth at the boundary.
- Time is measured between timestamps: `now − runStart ≥ dwell`. At 1 Hz with N = 5, that is 6
  samples.
- Starts as `UNKNOWN`, then switches to `NOT_FOCUSED` on the first sample, so notifications get
  through until focus is established.
- If samples arrive more than 5 s apart (`maxGapMs`), any run in progress starts again.
- Thresholds are passed in on every call, so edits take effect on the next sample.

### ArtifactRejector

`check(epoch)` returns `null` if the epoch is accepted, otherwise a `RejectReason`. The checks run
in this order:

1. `SOURCE_INVALID` — the source marked the epoch invalid.
2. `POOR_CONTACT` — any electrode is worse than good contact.
3. `NON_FINITE` — any value is NaN or infinite, including the Pope index itself (alpha + theta = 0).
4. `MUSCLE` — beta is more than 1.3 × the median of the user's recent beta (last 60 epochs that
   passed checks 1–3; the check is skipped until there are 10).
5. `HOLDOFF` — the epoch is within 2 s of a rejection, end point included, so an artifact at t also
   rejects t+1 and t+2. Only a fresh artifact restarts the holdoff.

Muscle-rejected epochs still count towards the median. Otherwise a genuine lasting rise in beta
would get every later epoch rejected.

### FocusPipeline (implements `FocusEngine`)

Each epoch goes through: artifact rejection → Pope index (recomputed from `bands`) → rolling
z-score → state machine.

- Every epoch produces exactly one `FocusDecision`. It is marked `accepted = false` if the epoch was
  rejected, the window is still warming up, or there are no thresholds yet.
- `RollingZScore`: the window is the last 600 accepted values, with 30 needed before scoring
  starts. Each value is scored against the window *before* it is added. The class exposes `mean`
  and `sd`.
- Before calibration the state stays `UNKNOWN`, but the window still fills.
- `submit` and `reset` share a lock.
- With a `ThresholdStore` and a scope, saved thresholds load at startup. They only fill an empty
  slot, so a newer change isn't overwritten.
- `updateThresholds` saves the change before applying it, so invalid thresholds are refused.

What `fitCalibration` does with each outcome:

| Outcome | Thresholds in use | Saved |
|---|---|---|
| Separable | Replaced with the new ones | Added to history and saved as current |
| Not separable | Left as they were | Added to history only |
| Too few usable epochs | Left as they were | Nothing; the result reports AUC 0.5 |

### ThresholdStore

Two plain-text files under `filesDir/thresholds/`:

- `current.txt` — the thresholds in use. Written to a temp file, then renamed into place.
- `history.txt` — one line per calibration, appended and never rewritten.

Details:

- Stores only z-scores, ratios and counts, never raw µV².
- `CalibrationRecord` holds the fitted thresholds, AUC, the low and high class means, the block
  count, and optionally the Pope index mean and SD (the reference for drift detection).
- Records are `key=value` pairs separated by `;` rather than `org.json`, which doesn't run in plain
  JVM unit tests. Unknown keys are ignored.
- A corrupt history line is skipped; a corrupt `current.txt` reads as "not calibrated".
- Refuses to save non-finite thresholds, `T_low > T_high`, or negative dwell times.
- `recordCalibration(record, makeCurrent)` can add to the history without changing the thresholds
  in use.

### ThresholdFitter

`fit(samples, entryDwellSec, exitDwellSec, nowMs)` returns a `FitOutcome`.

1. Each sample's index (MEDIUM included) is turned into a z-score against the whole run's mean and
   SD. The ~9-minute run is close to the length of the pipeline's rolling window.
2. The decision point is halfway between the LOW and HIGH means.
3. Hysteresis: each threshold sits 15% of the LOW-to-HIGH separation from the midpoint, and at
   least 0.05 z away, so `T_low < T_high` always holds.
4. AUC is the Mann-Whitney probability that a random HIGH epoch scores above a random LOW one,
   with ties counting half. It doesn't depend on where the thresholds are set.

- `Fitted` carries a `separable` flag, false when HIGH isn't above LOW or AUC is below 0.6.
- `Insufficient` is returned when there are fewer than 10 LOW or HIGH samples.
- Dwell times are passed through unchanged.

### CalibrationSession

`prepare(blocks, epochs)` turns the blocks and epochs from D's `CalibrationViewModel` into
`CalibrationSample`s.

- **Engagement check:** MEDIUM and HIGH blocks are dropped for no score (`NO_TASK_SCORE`), zero
  attempts (`NO_ATTEMPTS`), or accuracy below 50% (`LOW_ACCURACY`). LOW blocks are always kept.
- **Settling:** the first 5 s of each block are discarded.
- **Artifacts:** all epochs go through a fresh `ArtifactRejector` in time order, so the fit uses
  the data the live pipeline would have accepted.
- Epochs are matched to blocks by timestamp, so both must use `System.currentTimeMillis()`.
- The result lists which blocks were kept and which were dropped, and why.

### DriftDetector

Compares the rolling window with the calibration run's Pope index mean and SD.

- **Drift:** the mean has moved more than 1 calibration SD, or the SD is more than 2× or less than
  ½× the calibration SD.
- **Waits for data:** nothing is judged until the window has 300 samples.
- **Must last:** drift has to hold for 120 consecutive checks, about 2 minutes.
- In the pipeline the prompt stays up until a successful calibration clears it; `reset()` doesn't
  clear it.
- The reference is set when a calibration succeeds, and restored at startup from the saved
  calibration matching the current thresholds' `fittedAtMs`.
- Placeholder thresholds are never checked. Editing the dwell times keeps the reference.

## Swapping into AppContainer

This one-line change (plus imports for `FocusPipeline` and `ThresholdStore`) builds and passes all
tests:

```kotlin
val focusEngine: FocusEngine = FocusPipeline(store = ThresholdStore(context), scope = applicationScope)
```

It was applied and then reverted. Raise it with the group first, as WORKLOADS.md requires.
`AppContainer.kt` also has an uncommitted `MuseEegSource` → `FakeEegSource` change that predates
this work, probably A's, which was left untouched.

## Open questions and things to raise

1. **Rolling normalisation cancels out long focus periods.** During a 15-minute focus period the
   window fills with focused values, the z-score falls back towards zero, and the user drops out of
   `FOCUSED` while still concentrating. Options: normalise against a session-start baseline, or stop
   updating the window while `FOCUSED`. Decide this before the re-baseline work, because it decides
   what the re-baseline does.
2. **The muscle check may reject real focus.** If focus genuinely raises 13–20 Hz beta by more than
   1.3×, the start of each focus period is thrown out. A model of the rules on `FakeEegSource`'s
   signal (beta about 3 → 7) gave 24% of epochs rejected as muscle and 42% rejected overall. Check
   against real OSC data.
3. **Artifact flagging overlaps with A.** A's sources set `artifacts` and `valid` with their own EMG
   threshold. Agree with A who owns which rule.
4. **A user who does nothing on the 2-back passes the engagement check.** Never pressing scores
   about 70%, because correct non-responses count. If D's serial subtraction task reported
   `meanReactionMs`, "no reaction time" would mean "never responded" for both tasks, and a one-line
   rule would catch it.
5. **One reading question decides each MEDIUM block.** A single wrong answer drops the block. That
   only affects normalisation, but `minAccuracy` could be set per condition.
6. **The result screen offers "Accept" even when nothing was applied.** For a non-separable run,
   D's screen still shows the thresholds and an Accept button. Raise with D.
7. **`FakeEegSource` can't produce a usable calibration.** Its focus cycle ignores which block is
   running, so calibration will likely be non-separable and nothing gets applied. Use the
   placeholder-thresholds developer option in settings when testing on it.
8. **The recalibration prompt isn't saved.** After an app restart it takes about 5 + 2 minutes of
   drifted signal to come back.

## Remaining work

- Session-start re-baseline (step 7), after deciding question 1.
- Leave-one-block-out evaluation: held-out balanced accuracy, since AUC alone doesn't change when a
  block is held out.
- Offline evaluation (step 8): per-user AUC on logged sessions, and the correlation between the
  focus index and task performance.
- Swap `FocusPipeline` into `AppContainer` once the group agrees.
