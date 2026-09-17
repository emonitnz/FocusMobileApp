# Focus-Aware Notification Deferral

Android app that uses a Muse EEG headset to suppress notifications while the user is
concentrating, and delivers them in a batch once concentration breaks.

## 1. Signal pipeline

```
Muse (BLE, 4ch @ 256Hz)
  -> LibMuse band powers + MuseArtifactPacket + HSI
  -> artifact rejection
  -> focus index (2-4s windows, 50-75% overlap, ~1Hz decisions)
  -> rolling normalisation (last 5-10 min)
  -> Schmitt-trigger state machine
  -> FOCUSED / NOT_FOCUSED
```

**Focus index.** Start from the Pope et al. (1995) engagement index, `beta / (alpha + theta)`.
Alpha taken from the temporal channels (TP9/TP10), which are less eye-contaminated than the
frontal pair.

**Artifact rejection** — epochs are discarded when any of:

- `MuseArtifactPacket` flags blink or jaw clench
- HSI is poor on any of the four electrodes
- 20-40 Hz power rises broadband alongside beta (signature of forehead EMG, not cognition)
- accelerometer shows significant head movement

Beta is band-limited to ~13-20 Hz for the same reason: 13-30 Hz at a forehead electrode is
substantially frontalis/temporalis muscle activity. Without this gating the app degenerates
into a jaw-clench detector.

**State machine.**

- Enter `FOCUSED` when index > `T_high` sustained for `N` seconds (entry dwell stops noise
  spikes from silencing the phone)
- Exit only when index < `T_low` sustained for `M` seconds — `M` is the user-adjustable
  timeout that lets brief pauses pass without releasing notifications
- Two thresholds, not one, to prevent chatter at the boundary

## 2. Calibration

Three conditions x three ~60 s blocks, order-randomised. ~9 minutes.

| Condition | Task |
|---|---|
| Low | fixation cross / slowly drifting dot |
| Medium | reading passage + comprehension question |
| High | 2-back or serial subtraction by 7s |

**Eyes open in every condition, with matched visual input.** Eye closure multiplies alpha
several-fold (Berger effect), far more than any attention effect — a "relax with eyes closed"
baseline produces an eyes-open/eyes-closed classifier that looks excellent in calibration and
fails in real use.

The medium and high blocks log task performance, giving objective confirmation that the user
actually engaged rather than relying on self-report. Thresholds are fitted from the high-vs-low
separation: midpoint of class means as a baseline, optionally LDA or logistic regression over
2-3 features.

**Persistence.** Store thresholds as **ratios and z-scores, never raw µV²**. Absolute band power
drifts with electrode placement, hair, skin moisture, headband tension, time of day and caffeine,
so a raw number calibrated on Tuesday is wrong on Thursday. Each session begins with a 30-60 s
re-baseline (low-demand condition), framed to the user as settling in. Calibration history is
kept, with a recalibration prompt when the running distribution shifts substantially.

## 3. Notification suppression

**v1 — Do Not Disturb.** `AutomaticZenRule` + `setAutomaticZenRuleState`, toggled by focus state.
This is the platform's intended API for the pattern, and it provides the breakthrough categories
(calls, repeat callers, starred contacts) for free. A `NotificationListenerService` runs in
log-only mode so the app knows what arrived during the focus period.

Trade-off: notifications land silently in the shade rather than being genuinely held. The
catch-up summary compensates.

**v2 (stretch) — true deferral.** `NotificationListenerService.snoozeNotification(key, durationMs)`
with a re-snooze loop while focus holds. Verify snooze semantics on the target API level before
committing. Note that cancel-and-repost is not a viable fallback: an app cannot post a
notification on behalf of another package. The original's `PendingIntent` can be held and fired
from a summary notification, but the original's own update behaviour is lost.

**Catch-up delivery.** On focus break, deliver a single grouped summary — never a burst of
heads-up notifications, which is a worse interruption than the problem being solved.

**Fail-open.** Headset off, BLE dropped, battery dead, or poor electrode contact all deliver
notifications normally. Silently swallowing messages is the failure that gets the app uninstalled.

## 4. Features

### Core

- Enable/disable the deferral service
- Foreground service maintaining the BLE connection and decision loop
- Calibration flow (section 2)
- Persisted per-user thresholds
- Hysteresis state machine with user-adjustable timeout
- DND-based suppression + catch-up summary
- Breakthrough allowlist: calls, alarms, specific contacts, repeat callers, 2FA codes
- Fail-open on any sensor or connection failure

### Should-have

- Max hold time — never defer beyond X minutes regardless of state (safety valve)
- Headset-removed detection (HSI bad on all channels + accelerometer) → flush queue
- Session stats: focus duration, longest streak, notifications deferred, time-of-day pattern
- Optional raw EEG logging for offline analysis

### Stretch

- Experience sampling ("were you focused around 2:14pm?") for real-world accuracy labels
- Online threshold adaptation from user corrections
- Snooze-based true deferral as a second suppression mode

## 5. Android constraints

- **Foreground service** is mandatory: `foregroundServiceType="connectedDevice"` with the
  matching `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission on Android 14+
- **Permissions**: `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (31+), `POST_NOTIFICATIONS` (33+),
  `ACCESS_NOTIFICATION_POLICY` for DND, plus notification listener special access which the
  user must grant manually in Settings — build a proper onboarding flow, it is a known drop-off point
- **Doze and OEM battery killers** will terminate the service (Samsung, Xiaomi, OnePlus; see
  dontkillmyapp.com). Request battery-optimisation exemption and expect this during demos
- **Auto-reconnect with backoff** on BLE drop
- **Throughput**: decisions at ~1 Hz from 2-4 s windows, not per-sample. Raw logging off by default
- Verify LibMuse version support for the specific headset model; SDK/model compatibility has
  been uneven

**Prototyping shortcut.** Mind Monitor streams Muse data over OSC/UDP. Use it to collect and
analyse calibration data from day one while SDK integration proceeds in parallel — decoupling
the signal-processing work from the Android work is worth a lot on a fixed timeline.

## 6. Design decisions worth noting

**Screen-on does not release the queue.** The user may be concentrating on a phone task, so
device use must not count as a focus break. This is the opposite of most focus apps' assumption,
and it is also the hardest case for signal quality: phone-in-hand means head movement.

## 7. Evaluation

- **Offline**: replay logged calibration sessions, report per-user AUC of the focus index
  against condition labels, with leave-one-block-out cross validation
- **In the wild**: experience-sampling responses vs. the state machine's output
- **Behavioural**: correlate the index against task performance in the medium/high blocks

Poor separability for some users is a legitimate finding about consumer EEG, not a project
failure — provided it is measured and reported.

## 8. Future work

Sensor fusion as a robustness hedge: screen state, foreground app, device stillness and calendar
context could supplement the EEG index, degrading gracefully when the headset is off. Excluded
from scope here to keep the research question clean: *can consumer EEG alone do this?*
