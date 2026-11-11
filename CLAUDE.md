# Flick (package: flick / com.itsash.flick)

Gesture actions that run in the background. A native Kotlin foreground service
reads the motion and proximity sensors, detects gestures and runs the action the
user picked. Flutter is only the settings UI.

## Gestures → actions

| Gesture | Sensor | Default action | Notes |
|---|---|---|---|
| Pick up | accelerometer (gravity) | wake screen | only while the screen is off; phone must rest flat ≥ 1 s first |
| Wave over | proximity (wake-up variant if present) | wake screen | only while the screen is off; near 80–1000 ms |
| Chop twice | linear acceleration (fallback: accelerometer + filter) | flashlight | two push-then-stop chops 150–900 ms apart, any direction; adaptive threshold |
| Twist twice | gyroscope X/Y (any orientation) | camera | 3 alternating peaks (out, back, out) within 1.6 s; adaptive threshold |
| Flip | accelerometer (gravity z) | Do Not Disturb (fixed) | face down 1.5 s → DND on; face up → previous mode back |

Actions: `wake`, `flashlight`, `camera`, `media` (play/pause), `dnd`. Sensitivity
low / normal / high scales thresholds ×1.3 / ×1 / ×0.75.

## Gesture design

- Threshold = max(floor × sensitivity, K × recent motion RMS) (`NoiseTracker`):
  walking raises it, rest lowers it back. Floor = built-in default or the user's
  trained value (prefs `calib.chop` / `calib.twist`, set by Train = 3 gestures,
  65 % of the median peak, `Calibration`).
- Chop = strong push then opposite-sign stop within 20–250 ms, checked on 9
  directions. The first chop must be isolated; a third push within 250 ms of the
  second cancels (shaking). Fired chops do not block the next series; the next first
  chop may come 600 ms after a fire. Slow rhythmic chopping (period > ~300 ms) can
  still fire once at its start.
- All detectors are time based (ns timestamps), independent of sensor rate (100 Hz
  requested).
- `GestureEngine` owns sensors + detectors; `EventsService` (real actions) and
  `GestureProbe` (Test screen) both use it. Sensors are re-registered only when
  the needed sensor set changes.
- Own vibration (distinct pattern per gesture, `GestureEngine.PATTERNS`) mutes
  chop/twist for its length + 250 ms so it never feeds back into detection.
- Torch state comes from `CameraManager.TorchCallback` (`ActionRunner`), never a
  local flag.
- Test gestures screen (`lib/screens/test_screen.dart`, from Settings): live meter
  via EventChannel `com.itsash.flick/probe`, Train / Reset per gesture; while open
  `EventsService.paused` is true (no actions run).

### Root cause of "second chop fails after the flashlight turns on"
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
