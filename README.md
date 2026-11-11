# Flick

Phone gestures that work in the background: chop for light, twist for camera.

**[Website](https://flick.itsash.in)** · **[Download APK](https://github.com/its-ash/flick/releases/latest/download/flick.apk)** · Get it in **[Ash Store](https://store.itsash.in)**

## Features

- **Chop twice**: flashlight on / off.
- **Twist twice**: open the camera (also from the lock screen).
- **Pick up** or **wave over** the phone while the screen is off: light up the screen.
- **Flip face down**: Do Not Disturb until you turn it back over.
- **Choose the action** for each gesture: screen on, flashlight, camera, play / pause music, Do Not Disturb.
- **Runs in the background** as a native Kotlin foreground service, starts again after a reboot.
- **Quick Settings tile** to turn gestures on and off, plus sensitivity, vibration feedback and a
  "work with the screen off" option in Settings.
- **Private**: no account. The only network call is the update check in Settings.

## Install

- **Ash Store**: install [Ash Store](https://store.itsash.in) once, then install and update Flick from it.
- **APK**: download the [latest APK](https://github.com/its-ash/flick/releases/latest/download/flick.apk) on your phone and open it. Allow your browser to install unknown apps if Android asks.

Requires Android 8.0 (API 26) or later. Turn on **Gestures** in the app, then allow what it asks for:
notifications, running in the background, "Display over other apps" (to open the camera) and
Do Not Disturb access (for Flip). Some phones (Xiaomi, Oppo, Vivo, Samsung) also need Flick
set to "No restrictions" in battery settings to keep running.

## Develop

Needs Flutter ≥ 3.44 and the shared theme package at `../theme`.

```bash
make run           # run on a connected device
make test          # flutter analyze + flutter test
make test-native   # JVM tests for the Kotlin gesture detectors
make build         # release APK
make deploy        # bump version, build, tag, push, GitHub release (see store.mk)
```

## Release

`make deploy` follows the Ash Store kit (`store.mk`, `store.json`): it bumps the
version in `pubspec.yaml`, builds, commits, tags `vX.Y.Z`, pushes, and publishes
a GitHub release with `flick.apk`. Ash Store and the website pick up the new
version from that release.

## Project layout

```
lib/
├── main.dart
├── events/
│   ├── gesture.dart              # GestureKind + GestureAction (mirror the Kotlin enums)
│   ├── events_platform.dart      # MethodChannel to MainActivity.kt
│   └── events_store.dart         # config, permissions, what is still missing
└── screens/
    ├── home_screen.dart          # Gestures switch, permissions, gesture → action
    └── settings_screen.dart      # sensitivity, screen-off, tile, try an action
android/app/src/main/kotlin/com/itsash/flick/
├── EventsService.kt              # foreground service: sensors → detectors → actions
├── Detectors.kt                  # pure chop / twist / pickup / wave / flip detectors
├── ActionRunner.kt               # flashlight, camera, wake, media, Do Not Disturb
├── EventsPrefs.kt                # shared settings (Gesture, Action enums)
├── SystemHooks.kt                # boot receiver + Quick Settings tile
└── MainActivity.kt               # channel handlers
android/app/src/test/…/DetectorsTest.kt
```

See `CLAUDE.md` for details.

## License

No license file is included yet.

Built by [itsash.in](https://itsash.in).
