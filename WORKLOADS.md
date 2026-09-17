# Workload split

Four people, four sets of files. Nobody edits anybody else's. See `DESIGN.md`
for what is being built.

```
core/Models.kt        core/Contracts.kt      AppContainer.kt      <- shared, sprint 0
device/MuseEegSource.kt  device/OscEegSource.kt  device/FakeEegSource.kt   \
service/FocusService.kt                                                    |  A
signal/FocusPipeline.kt  signal/ArtifactRejector.kt  signal/FocusStateMachine.kt  \
calibration/CalibrationSession.kt  calibration/ThresholdFitter.kt                 |  B
data/ThresholdStore.kt                                                            /
notification/FocusNotificationListener.kt  notification/DndController.kt   \
notification/DeferralQueue.kt  notification/BreakthroughPolicy.kt          |  C
data/NotificationSettings.kt                                              /
MainActivity.kt  ui/*.kt  data/SessionStore.kt                            <- D
```

## Sprint 0 — everyone, one sitting

Write `core/Models.kt`, `core/Contracts.kt`, `AppContainer.kt`,
`AndroidManifest.xml` and `app/build.gradle.kts` together. Then stop touching
them; later changes are a group decision.

`Contracts.kt` holds four interfaces and is the entire coupling between the
workloads:

| Interface | Implemented by | Used by |
|---|---|---|
| `EegSource` — emits `FocusEpoch` | A | B, and D for status display |
| `FocusEngine` — epochs in, `FocusState` out | B | A (runs it), C and D (observe) |
| `Suppressor` — apply a focus state, flush | C | A |
| `SessionRecorder` — log decisions and deferrals | D | B, C |

Everyone codes against fakes for the interfaces they don't own.
`FakeEegSource` is A's first deliverable for exactly this reason — B, C and D
must be able to run the app with no headset on day one.

## A — Sensing & service
Muse BLE via LibMuse, the Mind Monitor OSC prototyping source, and the
foreground service that hosts the decision loop. Also owns the Android platform
fight: `connectedDevice` service type, reconnect backoff, Doze and OEM battery
killers.

Riskiest workload — the LibMuse/headset compatibility unknowns in DESIGN.md §5.
Get `OscEegSource` working first so a hardware problem doesn't block the team.

## B — Signal & calibration
The research contribution. Pope index with temporal-channel alpha, beta clamped
to 13–20 Hz, the four artifact rejection rules, rolling z-scores, the
two-threshold state machine. Then the calibration protocol, threshold fitting
and the AUC numbers for the writeup.

Almost all pure Kotlin, so it's the most testable workload and can be developed
offline against logged OSC data before any hardware works.

## C — Notifications
`AutomaticZenRule` for v1 suppression, the listener service in log-only mode,
the deferral queue with its max-hold safety valve, the single grouped catch-up
summary, the breakthrough allowlist, and fail-open on every failure path.
Snooze-based true deferral is this workload's stretch goal.

Its only input is a focus state, so it's the cleanest isolation of the four.

## D — UI, onboarding & stats
Every screen. The permission onboarding is real work, not boilerplate —
DESIGN.md calls notification-listener access a known drop-off point.

Note the calibration seam: **D builds the task screens** (fixation cross,
reading passage, 2-back, serial subtraction) and logs task performance; **B owns
the protocol and the fitting**. They meet at `CalibrationBlock` in `Models.kt`.

## Shared files, and the rule for each

| File | Rule |
|---|---|
| `core/*`, `AppContainer.kt` | Frozen after sprint 0. |
| `AndroidManifest.xml` | All permissions, the service, the receiver and the listener declared in sprint 0. One person applies later edits. |
| `build.gradle.kts`, `libs.versions.toml` | Announce before adding a dependency. |
| `res/values/strings.xml` | Don't add to it — each workload has its own `strings_*.xml`. |
| `ui/theme/` | D only. |
