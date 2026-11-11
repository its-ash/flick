import 'package:flutter/material.dart';

/// What a gesture does. Keys match `Action` in EventsPrefs.kt.
enum GestureAction {
  wake('wake', 'Turn on the screen', Icons.light_mode_rounded),
  flashlight('flashlight', 'Flashlight on / off', Icons.flashlight_on_rounded),
  camera('camera', 'Open the camera', Icons.photo_camera_rounded),
  media('media', 'Play / pause music', Icons.play_circle_rounded),
  dnd('dnd', 'Do Not Disturb on / off', Icons.do_not_disturb_on_rounded);

  const GestureAction(this.key, this.label, this.icon);

  final String key;
  final String label;
  final IconData icon;

  static GestureAction of(String? key) => values.firstWhere((a) => a.key == key, orElse: () => wake);
}

/// The sensor a gesture needs. Keys match the `status()` map in MainActivity.kt.
enum GestureSensor { accelerometer, gyroscope, proximity }

/// The gestures. Keys match `Gesture` in EventsPrefs.kt.
enum GestureKind {
  pickup(
    'pickup',
    'Pick up',
    'Lift the phone off the table while the screen is off.',
    Icons.back_hand_rounded,
    GestureSensor.accelerometer,
  ),
  wave(
    'wave',
    'Wave over',
    'Move your hand over the top of the screen while it is off.',
    Icons.waving_hand_rounded,
    GestureSensor.proximity,
  ),
  chop(
    'chop',
    'Chop twice',
    'Hold the phone like a knife and chop down twice to turn the flashlight on or off.',
    Icons.flashlight_on_rounded,
    GestureSensor.accelerometer,
  ),
  twist(
    'twist',
    'Twist twice',
    'Turn your wrist back and forth twice, quickly.',
    Icons.screen_rotation_rounded,
    GestureSensor.gyroscope,
  ),
  flip(
    'flip',
    'Flip to silence',
    'Put the phone face down for Do Not Disturb. Turn it over to end it.',
    Icons.flip_rounded,
    GestureSensor.accelerometer,
    fixedAction: true,
  );

  const GestureKind(this.key, this.title, this.description, this.icon, this.sensor, {this.fixedAction = false});

  final String key;
  final String title;
  final String description;
  final IconData icon;
  final GestureSensor sensor;

  /// Flip always toggles Do Not Disturb; the others can run any action.
  final bool fixedAction;
}
