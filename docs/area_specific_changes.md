# Changes made in each workload's area during integration

Integration was done on `main` between 3 Oct 12:30 and 14:35 while testing on a Galaxy S23
Ultra (Android 16) with Muse-3973. Everything below is on `main`; the commit hashes are in
`git log`. This is the list of things each of you should read before describing your area in
the report or at the code review, because they change behaviour you own. Nothing here changes
the interfaces in `core/Contracts.kt` except one added member, noted under "Shared".

Where a change exists because of something observed on the phone, the observation is given, so
you can decide whether you agree with the fix.

---

## Workload A — sensing and service

### `service/FocusService.kt`

- **Engine reset at session start.** `runDecisionLoop()` now calls `focusEngine.reset()`
  first, as `Contracts.kt` says the engine expects.
- **Stopping fails open.** `onDestroy()` applies `UNKNOWN` to the suppressor (DND off) and
  flushes the queue as `SERVICE_STOPPED` before closing the session. Previously only the
  recorder was told.
- **Signal loss has a 10 s grace period** (`SIGNAL_GRACE_MS`). The old code mapped any
  non-`CONNECTED` status to `UNKNOWN` immediately. On the phone a single electrode reading 2
  for four seconds released the whole queue and toggled DND off and on. Now the status has to
  be unusable for 10 s continuously (`mapLatest` + `delay`, cancelled if it recovers).
- **Loss then does three things**: flush as `HEADSET_REMOVED` if anything is held, apply
  `UNKNOWN`, and `focusEngine.reset()`. The reset is so a reconnect has to re-establish focus
  through the dwell rather than resuming `FOCUSED` the instant the link is back.
- **The session ends itself after 2 min without usable signal**
  (`STOP_AFTER_SIGNAL_LOSS_MS`, `stopSelf()`). Richie asked for the master switch to go off
  when the headset is gone. Reconnect backoff still runs until then.
- **`watchForHeadsetRemoved()` removed.** It counted consecutive *emissions* of a `StateFlow`,
  which only emits on change, so it never counted five of anything. The grace-period path
  above replaces it.
- **`appliedState` published** through a companion `MutableStateFlow`, exposed by
  `AndroidServiceController.appliedState`. See "Shared".

### `device/MuseEegSource.kt`

- **`start()` refuses to mark itself started without Bluetooth permission.** Previously a
  launch before onboarding set `stopped = false` with no scan running, and every later
  `start()` returned early, so the headset was never found until the app was killed.
- **No contact state or epochs until `CONNECTED`** (`linkUp` flag, checked in `emitEpoch()`).
  During a reconnect attempt the tick was deriving `POOR_CONTACT` from an empty horseshoe,
  so a switched-off headset showed "Poor contact" instead of "Connecting".
- **A dropped headband is not reconnected to from LibMuse's stale list entry.**
  `rescanAfterDrop()` records `muse.lastDiscoveredTime`; `connectToAvailableMuse()` only takes
  an entry whose `lastDiscoveredTime` is newer, i.e. the headband is advertising again.
  Because a rediscovery never fires `museListChanged()`, `beginScan()` now polls the list every
  second (`scanPollJob`) until something connects. Observed: with the headband switched off,
  the app cycled Disconnected (1 s) / Poor contact (30 s) until this.
- Your `MUSE_CONNECTION_FIXES.md` line "uncommitted on branch a-sensing-service" was updated
  to say it was merged through PR #5.

### `device/ForegroundEegSource.kt` and `MainActivity.kt`

- `MainActivity` calls `hold()` from `onResume()` rather than `onStart()`, because the runtime
  permission dialog only pauses the activity and the first real scan has to happen after that
  grant. `ForegroundEegSource` itself is unchanged.

### Removed

- `SwitchableEegSource` and the Settings "Demo mode" switch existed for about an hour and were
  removed again at Richie's request. `FakeEegSource` and `OscEegSource` are untouched; the
  container comment still names them as drop-ins.

### Things I did not change but you may want to

- `startListening()`/`stopListening()` are still called from whichever thread `start()`/`stop()`
  run on; LibMuse's warning about that stands.
- Your `valid` requires every sensor at exactly 1. Roughly one epoch in three was rejected for
  blinks on the phone; AF8 at 2 was the next most common cause.

---

## Workload B — signal and calibration

Your state machine, fitter, session, drift detector, artifact rejector and store are untouched.
The changes are in `FocusPipeline` and are **opt-in constructor parameters with defaults that
preserve your tested behaviour**; `AppContainer` is what turns them on. All your tests pass
unchanged; five more were added in `FocusPipelineSmoothingTest`.

### `signal/FocusPipeline.kt`

- **`smoothingWindow: Int = 1`** (container passes 8). Accepted epochs are averaged
  (`MovingAverage`) before thresholding. **Why:** the raw 1 Hz index is noisy enough that a
  single dip into the dead band resets `highSinceMs`, so "sustained above T_high for 10 s"
  was unreachable. Richie's real calibration gave T_high = +0.23, T_low = +0.07 (AUC 0.67);
  over an 11-minute reading session the longest unbroken run above T_high was 8 s.
- **`useCalibrationReference: Boolean = true`.** When `drift.reference` is set (loaded from
  the history, or after a separable fit), z is `(smoothed − reference.mean) / reference.sd`.
  Without a reference it falls back to the rolling window as before. **Why:** this is your
  open question 1 in `SUMMARY_B.md`: the rolling window re-centres on whatever the user has
  been doing, so a long focus period normalises itself to zero. The thresholds were fitted
  against the calibration run's distribution, so that is what live samples are now scored
  against. The rolling window still runs on the **raw** index underneath, so `DriftDetector`
  sees exactly what it did before and the recalibration prompt still works.
- `RollingZScore` gained `score(value)`: scores against the window without adding to it. It
  is used so the smoothed value is scored while the raw value feeds the window.
- The `Insufficient` branch of `fitCalibration()` now sets `CalibrationResult.fitted = false`
  (see "Shared") so the result screen can say "not enough clean data" instead of showing an
  AUC of 0.50 that was never measured.

**Evidence.** Replaying the 620 epochs logged during Richie's reading session through the live
rules, with his thresholds: rolling window, no smoothing → 0% of the time `FOCUSED`;
calibration reference, no smoothing → 87%; calibration reference + 8-epoch smoothing → 98%.
Live afterwards: reading this chat gave a smoothed z of about +0.4; counting backwards from
300 in sevens gave +2.0 with 100% of samples above T_high; idly looking at the screen gave
+0.1, inside the dead band, and the state stayed `NOT_FOCUSED`. All three match what the
user was doing.

### Things you should decide

- Whether the calibration reference should drift slowly (a very long EMA) rather than being
  fixed until recalibration. Right now it is fixed and the drift detector is the only
  correction.
- Fast calibration (6 s blocks) always returns `Insufficient`: three LOW blocks give at most
  ~15 epochs before rejection, and you need 10 per class. The UI now reports that honestly;
  it is not a bug in the fitter.
- The muscle check (`beta > 1.3 × median`) is unchanged.

---

## Workload C — notifications

This is the area that changed most, because the merged code could not run: the listener's
controller was an unassigned `lateinit`, the zen rule was registered in a constructor before
policy access existed, `NotificationSettings.kt` was empty, and `flush()` had a TODO where
the catch-up summary should be. Every file in `notification/` and `data/NotificationSettings.kt`
is different; read them rather than diffing. Ten unit tests in `NotificationControllerTest`
describe the intended behaviour.

### `notification/DndController.kt`

- Implements a small `FocusModeGate` interface so the controller can be tested without
  Android.
- The rule is created **lazily** on first use, **reused** if a rule with our condition id
  already exists (otherwise every launch added another), and every platform call is guarded:
  without policy access it does nothing and logs, which is the fail-open behaviour DESIGN.md
  §3 asks for.
- Interruption filter is `PRIORITY` with a `ZenPolicy` built from `PolicySettings` (API 29+),
  so the platform enforces calls / repeat callers / starred contacts / alarms. Below API 29 the
  legacy constructor is used and the system's own priority settings apply.
- `updatePolicy()` pushes a changed `PolicySettings` into the existing rule.

### `notification/BreakthroughPolicy.kt`

- Was an empty class. Now maps `PolicySettings` → `ZenPolicy`, and classifies a seen
  notification as breakthrough (call/alarm category or an allow-listed package) so it is
  flagged in the queue. The platform decides the actual breakthrough; the listener cannot.

### `notification/NotificationController.kt`

- Takes the gate, a `DeferralQueue`, a `CatchUpPresenter`, the `SessionRecorder`, the
  `NotificationPolicy` and a scope.
- `apply()`: `FOCUSED` → rule on; anything else → rule off, then if the queue is non-empty
  `flush(FOCUS_BROKE)`.
- `handleNotification()` only queues while focused, records `onDeferred`, and starts the
  **max-hold timer** on the first item; `flush()` posts one summary via the presenter,
  records `onFlush`, and clears. `handleRemoved()` drops items the user dismissed.
- Policy changes are collected and forwarded to the gate.

### `notification/CatchUpNotifier.kt` (new)

- The one grouped summary (DESIGN.md §3): `InboxStyle`, its own channel at default
  importance so it sounds but never heads-up, subtitle giving the flush reason.

### `notification/FocusNotificationListener.kt`

- Resolves the controller from `FocusApp.container`. Skips our own package, ongoing and
  foreground-service notifications and group summaries. Uses the app label rather than the
  package name. Forwards removals.

### `notification/DeferralQueue.kt`

- Keyed on the platform notification key so an updated notification replaces its entry;
  synchronized; `remove(key)`.

### `data/NotificationSettings.kt`

- Was a comment. Now a `SharedPreferences`-backed `NotificationPolicy`.

### `AndroidManifest.xml`

- `MainActivity` gained the `android.app.action.AUTOMATIC_ZEN_RULE` intent filter and the
  `ruleType` / `ruleInstanceLimit` meta-data, because the rule names it as its configuration
  activity.

### Known limits, worth a sentence in the report

- Starred-contact and repeat-caller decisions happen inside the system; the app can only
  flag what it saw.
- The "allowed packages" set in `PolicySettings` is flagged in the queue but nothing lets
  those packages through, because a DND policy has no per-app allowlist.
- `allowTwoFactorCodes` was removed from `PolicySettings` and the Settings screen: nothing
  could honour it.

---

## Shared files

- `core/Contracts.kt`: `ServiceController` gained `val appliedState: StateFlow<FocusState>`,
  "the state the suppressor is actually applying". The home screen shows this instead of
  `FocusEngine.state`, so the pill agrees with the DND rule during the grace period and when
  no session is running. `DemoServiceController` in `ui/preview` implements it as the
  engine's state.
- `core/Models.kt`: `DeferredNotification.category` (from C's PR), `CalibrationResult.fitted`
  (default `true`), `PolicySettings.allowTwoFactorCodes` removed.
- `AppContainer.kt`: real implementations wired; `FocusPipeline(store, scope,
  smoothingWindow = 8)`; `NotificationController(DndController, DeferralQueue,
  CatchUpNotifier, sessionRecorder, NotificationSettings, applicationScope)`.
- `gradle/libs.versions.toml`: AGP kept at 9.4.0 (one PR had downgraded it to 9.3.3).
- The app is now called "stfu" (`app_name`).

---

## Workload D (for completeness)

Calibration: "Use these thresholds" applies the shown thresholds when the engine refused a
poor fit; the 2-back never repeats a letter, runs at 1.5 s per item (0.6 s in fast mode);
reading blocks have a "Finished reading" button that ends the block and records the real
duration; the fixation dot is driven from the frame clock because the phone has animations
off in Developer options; a result with `fitted = false` shows "Not enough clean data".
Home: chart x axis is a fixed 120 s with points entering from the right; the pill shows
`appliedState`. Settings: the "Verification codes", "Log raw EEG" and "Demo mode" switches
were removed.
