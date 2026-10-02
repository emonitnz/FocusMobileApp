# Muse 2 connection fixes

Summary of the changes made to get the Muse 2 headset (`Muse-3973`) detected, connected and
producing usable focus readings. Verified on a Samsung phone running Android 12 (API 31) with
LibMuse 8.0.9.

All changes are uncommitted on branch `a-sensing-service`.

## Files changed

| File | Status | Lines | Change |
| --- | --- | --- | --- |
| `app/src/main/AndroidManifest.xml` | Modified | +4 / −1 | `neverForLocation` flag on `BLUETOOTH_SCAN` (§1) |
| `app/src/main/java/com/example/compx551a4/device/MuseEegSource.kt` | Modified | +49 / −23 | Reconnect to already-known headband (§2); fresh-value band tracking, NaN-safe averages, stricter `valid` (§3) |
| `app/src/main/java/com/example/compx551a4/device/ForegroundEegSource.kt` | New | +35 | Wrapper keeping the headset connected while the app is in the foreground (§4) |
| `app/src/main/java/com/example/compx551a4/AppContainer.kt` | Modified | +3 / −1 | Wraps `MuseEegSource` in `ForegroundEegSource` (§4) |
| `app/src/main/java/com/example/compx551a4/MainActivity.kt` | Modified | +19 | `onStart` → `hold()`, `onStop` → `release()` (§4) |
| `MUSE_CONNECTION_FIXES.md` | New | — | This document |

## 1. Headset never detected — `AndroidManifest.xml`

**Symptom:** the app scanned forever; `museListChanged()` never fired, even though the phone's
Bluetooth stack could see `Muse-3973` advertising.

**Cause:** `BLUETOOTH_SCAN` was declared without `android:usesPermissionFlags="neverForLocation"`.
On Android 12+, a scan without that flag only receives results if the app also holds
`ACCESS_FINE_LOCATION` — but that permission is capped at `maxSdkVersion="30"`, so on 12+ it can
never be granted. Android silently dropped every scan result. LibMuse runs a plain
`BluetoothLeScanner.startScan` (filtered on the Muse service UUID), so it was affected, and its
`startListening()` swallows exceptions, so nothing surfaced.

**Fix:**

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="s" />
```

This matches the onboarding copy, which already says the app does not use location.

## 2. Reconnect never completed — `MuseEegSource.kt`

**Symptom:** after stopping and restarting a session, the app scanned but never reconnected.

**Cause:** LibMuse keeps an internal list of headbands it has seen. A scan that finds an address
already in that list only updates a timestamp (`constructMu02` → `rediscoveredAt`) and does **not**
call `museListChanged()`. The app was waiting for a callback that would never come. Entries expire
after ~30 s without advertising, so this hit restarts within that window.

**Fix:** the selection logic moved into `connectToAvailableMuse()` (`@Synchronized`), which is
called both from `museListChanged()` and directly after `startListening()` in `beginScan()`, so a
headband already in the list connects immediately.

## 3. Focus index always `NaN` / stale — `MuseEegSource.kt`

**Symptom:** epochs marked `valid=true` with `focusIndex=NaN`; once values did appear, they sat on
the same number for a minute at a time.

**Cause:** for the `*_RELATIVE` band packets, LibMuse sends `NaN` for a channel until it has its
first clean window, then **repeats the last clean value** while it rejects the current window
(blinks, poor contact). The app averaged all four channels, so one `NaN` channel made the whole
index `NaN`, and repeated values were treated as fresh data. In one session AF7/AF8 alpha did not
change for ~3.5 minutes.

**Fix:**

- `mergeFresh()` keeps only per-channel values that changed since the previous packet (tracked in
  `lastRaw`); within a 1 s tick a channel with nothing fresh stays `NaN`. Band lists are cleared
  after each epoch.
- `average()` / `averageOf()` skip `NaN` channels and return `NaN` (not `0.0`) when none remain,
  so missing data can no longer produce a fake focus value.
- `valid` now requires a finite `focusIndex` (replacing the "lists have 4 entries" check).
- Band state and `lastRaw` are reset on each new connection.
- The epoch debug log line now includes `bands=...` (raw per-channel values) for tuning; remove
  if not wanted.

**Result:** first focus value appeared ~1 s after contact settled (previously ~90 s), and the
index changes every epoch. Remaining invalid epochs are genuine (no contact, blinks).

## 4. Connect whenever the app is open — `ForegroundEegSource.kt`, `AppContainer.kt`, `MainActivity.kt`

**Symptom:** the headset only connected once calibration started (or a session, which requires
calibration first), and connecting ate into the 45 s baseline countdown.

**Change:**

- New `device/ForegroundEegSource.kt` wraps the real source. `hold()` connects; `release()`
  disconnects unless a session is running. While held, `stop()` calls from calibration and
  `FocusService` are ignored, so finishing either does not drop the headset with the app open.
- `AppContainer.eegSource` is now `ForegroundEegSource(MuseEegSource(context))`; consumers still
  see a plain `EegSource`.
- `MainActivity.onStart()` calls `hold()`; `onStop()` calls
  `release(keepConnected = serviceController.running.value)`, skipped on configuration changes
  (rotation).

**Behaviour to be aware of:** the headset disconnects when the screen times out or locks (unless a
focus session is running) and reconnects in ~3 s on return. Calibration keeps the screen on, so it
is unaffected.

## Verified on device

| Scenario | Result |
| --- | --- |
| First scan after fix | Found within ~0.2–1 s of advertising; `CONNECTED` ~3 s later |
| Headset worn | Contact settles to `[1, 1, 1, 1]`; finite, changing focus index |
| Screen timeout → wake | Clean disconnect; reconnect via existing-list path in ~3 s |
| App open on Home, no calibration | Connects automatically |

## Open items

- Blink rejection is strict: ~1 in 3 good-contact epochs was invalid due to blinks.
- `ContactQuality.allGood` requires every sensor at exactly 1; a single 2 ("mediocre") invalidates
  the epoch. AF8 (right forehead) was the most frequent weak sensor.
- `MuseEegSource` calls `startListening()`/`stopListening()` from whichever thread `start()`/
  `stop()` run on, while LibMuse's callbacks arrive on the main thread; LibMuse warns these should
  be called from the same thread. No failures observed.
