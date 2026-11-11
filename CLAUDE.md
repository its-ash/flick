# Flick (package: flick / com.itsash.flick)

Gesture actions that run in the background. A native Kotlin foreground service
reads the motion and proximity sensors, detects gestures and runs the action the
user picked. Flutter is only the settings UI.

## Gestures → actions

| Gesture | Sensor | Default action | Notes |
|---|---|---|---|
| Pick up | accelerometer (gravity) | wake screen | only while the screen is off; phone must rest flat ≥ 1 s first |
| Wave over | proximity (wake-up variant if present) | wake screen | only while the screen is off; near 80–1000 ms |
| Chop twice | linear acceleration (fallback: accelerometer + filter); proximity only if "Pocket gate" is on | flashlight | two sharp push+stop spikes 150–800 ms apart, any direction/orientation; see Gesture design |
| Twist twice | gyroscope X/Y (any orientation) | camera | 3 alternating peaks (out, back, out) within 1.6 s; adaptive threshold |
| Flip | accelerometer (gravity z) | Do Not Disturb (fixed) | face down 1.5 s → DND on; face up → previous mode back |

Actions: `wake`, `flashlight`, `camera`, `media` (play/pause), `dnd`. Sensitivity
low / normal / high scales thresholds ×1.3 / ×1 / ×0.75.

## Gesture design

- Twist threshold = max(floor × sensitivity, K × recent motion RMS) (`NoiseTracker`); floor =
  default or trained value (prefs `calib.twist`, Train = 3 gestures, 65 % of the median peak).
- Chop twice (`ChopDetector` in `ChopDetector.kt`, Motorola-style; key `chop`; replaced the
  shake detector v1.0.3 and the double-chop before it). Input: linear acceleration (fallback:
  accelerometer through `GravityFilter`), ~100 Hz, no gravity vector needed, any orientation.
  Chop = push spike |a| ≥ threshold (≤ 150 ms wide, ≤ 80 m/s²) followed within 180 ms by an
  opposite spike ≥ 0.5 × threshold along the push axis. Two chops on the same axis (|cos| ≥ 0.6),
  150–800 ms apart (push to push), fire. Between chops the signal must stay < 0.35 × threshold
  for 40 ms (a swing or shake never has that dip, so it never fires). First chop needs 600 ms
  without activity ≥ 0.5 × threshold before it (walking / jogging / stairs never are calm);
  slow noise RMS (clipped at 0.5 × threshold) > 0.3 × threshold = carried, nothing counts.
  Cooldown 1 s after a fire + 300 ms quiet; chops in that window are lost, so a 3rd chop never
  cancels. Known limit: running that starts from standstill with two hard heel strikes in a
  row can look like a double chop.
  Threshold (prefs `chop.threshold`, m/s², 6–20, default 12): Low / Medium / High = 16 / 12 / 8
  (setting the segmented control writes it), fine slider in Settings. Calibrate (Test screen):
  3 double chops with the easy threshold 6, then 60 % of the median of the weaker chop of each
  pair, plus the mean chop axis (optional ±45° cone, switch "Only my chop direction", off).
  Pocket gate (proximity near ignores chops) is optional and OFF by default; near = value < min(range, 3).
  Test screen: live motion bar, peak of the last burst vs the trigger line, Diagnostics card
  (`Diag.kt`: service running, background sample rate, torch state, last gesture / action + error).
- Non-detection fixes (v1.1): old prefs migrated (`prefs_version` 2 drops `calib.*`, `gesture.shake/chop.*`,
  resets sensitivity); proximity gate was a hard block and `near = value < maxRange` is wrong on phones
  whose far value is below max range (everything blocked); shake needed TYPE_GRAVITY events; torch
  errors were swallowed (now returned, logged, shown); torch camera probing failed as a whole when one
  camera id threw; wake-up sensors preferred with the screen-off option, no batching; `paused` is a
  3 s lease so a dead Test screen cannot leave actions off.
- All detectors are time based (ns timestamps), independent of sensor rate (100 Hz
  requested).
- `GestureEngine` owns sensors + detectors; `EventsService` (real actions) and
  `GestureProbe` (Test screen) both use it. Sensors are re-registered only when
  the needed sensor set changes.
- Own vibration (distinct pattern per gesture, `GestureEngine.PATTERNS`) mutes
  shake/twist for its length + 250 ms so it never feeds back into detection.
- Torch state comes from `CameraManager.TorchCallback` (`ActionRunner`), never a
  local flag.
- Test gestures screen (`lib/screens/test_screen.dart`, from Settings): live meter
  via EventChannel `com.itsash.flick/probe`, Train / Reset per gesture; while open
  `EventsService.paused` is true (no actions run).

### History: root cause of "second chop fails after the flashlight turns on" (chop since removed)
Old `ChopDetector`: 1.5 s cooldown after a fire; raw accelerometer high-pass with
alpha 0.8 (~80 ms time constant, swallowed soft chops); every spike counted (a chop
is an accelerate + brake pair, so a double chop is 4 spikes, matched only by a
250 ms refractory heuristic); and the 40 ms fire vibration fed a spike back into
the next window. A second double chop soon after the torch toggled landed in the
cooldown or paired with leaked spikes. Fixed by the push-then-stop detector,
platform linear acceleration, a 600 ms post-fire gap and vibration muting. Not
reproduced on hardware (no device here); verified with synthetic traces.

## Code layout

- `android/app/src/main/kotlin/com/itsash/flick/`
  - `GestureEngine.kt`, `GestureProbe.kt`: sensors/detectors glue, test + train feed.
  - `Detectors.kt`: pure detectors (no Android types), `NoiseTracker`, `Calibration`, `GravityFilter`. Unit
    tests: `android/app/src/test/kotlin/com/itsash/flick/DetectorsTest.kt`
    (`make test-native`). Change thresholds only together with tests.
  - `EventsService.kt`: `specialUse` foreground service. Registers only the
    sensors that enabled gestures need, holds a partial wake lock when "work with
    the screen off" is on (non-wake-up sensors stop with the CPU otherwise),
    reconfigures on any SharedPreferences change, `START_STICKY`.
  - `ActionRunner.kt`: torch via `CameraManager.setTorchMode`; wake via a
    `SCREEN_BRIGHT | ACQUIRE_CAUSES_WAKEUP` wake lock (`TURN_SCREEN_ON` declared);
    camera via `STILL_IMAGE_CAMERA(_SECURE)`. Android blocks background activity
    starts, so with "Display over other apps" granted it shows a 1 px overlay for
    the moment of the start (required from Android 15). DND needs notification
    policy access.
  - `EventsPrefs.kt`: SharedPreferences `flick_prefs`; `Gesture` and `Action`
    enums (keys shared with `lib/events/gesture.dart`, keep them in sync).
  - `SystemHooks.kt`: `BootReceiver` (BOOT_COMPLETED, MY_PACKAGE_REPLACED) and
    `EventsTileService` (Quick Settings toggle).
  - `MainActivity.kt`: MethodChannel `com.itsash.flick/control` (`getConfig`,
    `setEnabled`, `setOption`, `setGesture`, `status`, `openPermission`,
    `tryAction`, `addTile`).
- `lib/events/`: Dart mirror of the enums, channel wrapper, `EventsStore`
  (`missing` lists permissions the current setup needs).
- `lib/screens/`: home (switch, permissions, gestures) and settings
  (sensitivity, screen-off, vibrate, tile, try an action, Updates, Developer).

## Commands

- `make run`, `make build`, `make test` (analyze + Dart tests), `make test-native`
  (Kotlin detector tests), `make clean`.
- Icon: `assets/icon/*.svg` → `rsvg-convert -w 1024 -h 1024` to `*-1024.png`, then
  `dart run flutter_launcher_icons`. Status bar / tile icon:
  `android/app/src/main/res/drawable/ic_stat_events.xml`.

## Conventions

- minSdk 26. UI only from `../theme` (read `../theme/COMPONENTS.md`).
- Only network use: `UiUpdateSection` in Settings. No analytics.

## Release (Ash Store)

- `store.json` (id `flick`, repo its-ash/flick), `store.mk`, `index.html` +
  `CNAME` (`flick.itsash.in`). See `../ash-store/store-kit/STORE.md`.
- `make deploy` = bump version → build → commit, tag `vX.Y.Z`, push → GitHub
  release with `flick.apk` → topics, homepage, Pages. Never create releases,
  tags or asset names by hand.
- Do not edit `store.mk` here. Edit `../ash-store/store-kit/store.mk`, then run
  `make store-sync` at the workspace root.
