import 'package:flutter/services.dart';

import 'gesture.dart';

/// One gesture's saved setting.
class GestureSetting {
  const GestureSetting({required this.enabled, required this.action});

  final bool enabled;
  final GestureAction action;
}

/// Everything the background service reads, as saved on the phone.
class EventsConfig {
  const EventsConfig({
    required this.enabled,
    required this.running,
    required this.screenOff,
    required this.vibrate,
    required this.sensitivity,
    required this.gestures,
    this.trained = const {},
    this.chopThreshold = 30,
    this.pocketGate = false,
    this.chopAxisOnly = false,
    this.hasAxis = false,
  });

  factory EventsConfig.fromMap(Map<Object?, Object?> map) {
    final g = (map['gestures'] as Map<Object?, Object?>?) ?? const {};
    return EventsConfig(
      enabled: map['enabled'] as bool? ?? false,
      running: map['running'] as bool? ?? false,
      screenOff: map['screenOff'] as bool? ?? true,
      vibrate: map['vibrate'] as bool? ?? true,
      sensitivity: map['sensitivity'] as String? ?? 'normal',
      chopThreshold: (map['chopThreshold'] as num?)?.toDouble() ?? 30,
      pocketGate: map['pocketGate'] as bool? ?? false,
      chopAxisOnly: map['chopAxisOnly'] as bool? ?? false,
      hasAxis: map['hasAxis'] as bool? ?? false,
      trained: {
        for (final e in ((map['trained'] as Map<Object?, Object?>?) ?? const {}).entries)
          if (e.value == true) e.key as String,
      },
      gestures: {
        for (final kind in GestureKind.values)
          kind: GestureSetting(
            enabled: ((g[kind.key] as Map<Object?, Object?>?)?['enabled'] as bool?) ?? false,
            action: GestureAction.of((g[kind.key] as Map<Object?, Object?>?)?['action'] as String?),
          ),
      },
    );
  }

  final bool enabled;
  final bool running;
  final bool screenOff;
  final bool vibrate;
  final String sensitivity;

  /// Chop trigger level in m/s² (6-20): Low 16, Normal 12, High 8, or anything between.
  final double chopThreshold;

  /// Ignore chops while the proximity sensor is covered (off by default).
  final bool pocketGate;

  /// Only accept chops near the direction learned by Calibrate.
  final bool chopAxisOnly;
  final bool hasAxis;
  final Map<GestureKind, GestureSetting> gestures;

  /// Keys of gestures (chop, twist) that have a personal trained strength.
  final Set<String> trained;

  /// Actions used by the enabled gestures (flip counts as Do Not Disturb).
  Set<GestureAction> get usedActions => {
    for (final e in gestures.entries)
      if (e.value.enabled) e.key.fixedAction ? GestureAction.dnd : e.value.action,
  };
}

/// Permissions and sensors on this phone.
class EventsStatus {
  const EventsStatus(this._map);

  final Map<Object?, Object?> _map;

  bool _b(String k) => _map[k] as bool? ?? false;
  bool get notifications => _b('notifications');
  bool get overlay => _b('overlay');
  bool get dnd => _b('dnd');
  bool get battery => _b('battery');
  bool get tileSupported => _b('tileSupported');
  bool has(GestureSensor s) => _b(s.name);
}

/// Dart side of MethodChannel "com.itsash.flick/control" (MainActivity.kt).
class EventsPlatform {
  const EventsPlatform();

  static const _channel = MethodChannel('com.itsash.flick/control');

  Future<EventsConfig> config() async =>
      EventsConfig.fromMap(await _channel.invokeMapMethod<Object?, Object?>('getConfig') ?? const {});

  Future<EventsStatus> status() async =>
      EventsStatus(await _channel.invokeMapMethod<Object?, Object?>('status') ?? const {});

  Future<void> setEnabled(bool enabled) => _channel.invokeMethod('setEnabled', {'enabled': enabled});

  Future<void> setOption(String key, Object value) => _channel.invokeMethod('setOption', {'key': key, 'value': value});

  Future<void> setGesture(GestureKind g, {required bool enabled, required GestureAction action}) =>
      _channel.invokeMethod('setGesture', {'gesture': g.key, 'enabled': enabled, 'action': action.key});

  /// "notifications" | "overlay" | "dnd" | "battery"
  Future<void> openPermission(String name) => _channel.invokeMethod('openPermission', {'name': name});

  /// Runs the action now. Returns null on success or the error text.
  Future<String?> tryAction(GestureAction a) => _channel.invokeMethod<String>('tryAction', {'action': a.key});

  Future<void> startTraining(String gesture) => _channel.invokeMethod('startTraining', {'gesture': gesture});

  Future<void> stopTraining() => _channel.invokeMethod('stopTraining');

  Future<void> resetTraining(String gesture) => _channel.invokeMethod('resetTraining', {'gesture': gesture});

  /// Live motion levels and detected gestures while the Test screen is open.
  /// Listening pauses the real actions; cancelling resumes them.
  Stream<Map<Object?, Object?>> probe() =>
      _probe.receiveBroadcastStream().map((e) => e as Map<Object?, Object?>);

  static const _probe = EventChannel('com.itsash.flick/probe');

  Future<bool> addTile() async => await _channel.invokeMethod<bool>('addTile') ?? false;
}
