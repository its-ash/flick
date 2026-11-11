import 'package:flick/events/events_platform.dart';
import 'package:flick/events/events_store.dart';
import 'package:flick/events/gesture.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, Object?> configMap({Map<String, Object?> gestures = const {}}) => {
  'enabled': true,
  'running': true,
  'screenOff': true,
  'vibrate': false,
  'sensitivity': 'high',
  'gestures': {
    'pickup': {'enabled': true, 'action': 'wake'},
    'wave': {'enabled': false, 'action': 'wake'},
    'shake': {'enabled': true, 'action': 'flashlight'},
    'twist': {'enabled': true, 'action': 'camera'},
    'flip': {'enabled': false, 'action': 'dnd'},
    ...gestures,
  },
};

void main() {
  test('config parses every gesture', () {
    final c = EventsConfig.fromMap(configMap());
    expect(c.sensitivity, 'high');
    expect(c.vibrate, isFalse);
    expect(c.gestures.length, GestureKind.values.length);
    expect(c.gestures[GestureKind.twist]!.action, GestureAction.camera);
    expect(c.usedActions, {GestureAction.wake, GestureAction.flashlight, GestureAction.camera});
  });

  test('trained gestures are parsed', () {
    expect(EventsConfig.fromMap(configMap()).trained, isEmpty);
    final c = EventsConfig.fromMap({
      ...configMap(),
      'trained': {'shake': true, 'twist': false},
    });
    expect(c.trained, {'shake'});
  });

  test('flip counts as Do Not Disturb', () {
    final c = EventsConfig.fromMap(configMap(gestures: {'flip': {'enabled': true, 'action': 'wake'}}));
    expect(c.usedActions, contains(GestureAction.dnd));
  });

  test('unknown action falls back to wake', () {
    expect(GestureAction.of('nope'), GestureAction.wake);
  });

  test('missing lists only permissions the setup needs', () {
    final store = EventsStore()
      ..config = EventsConfig.fromMap(configMap())
      ..status = const EventsStatus({'notifications': true, 'battery': true, 'overlay': false, 'dnd': false});
    // Camera is used (needs overlay); nothing uses Do Not Disturb.
    expect(store.missing.map((m) => m.$1), ['overlay']);
  });
}
