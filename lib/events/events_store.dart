import 'package:flutter/foundation.dart';

import 'events_platform.dart';
import 'gesture.dart';

/// UI state: the saved config and the phone's permissions. Every change is
/// saved natively first, so the background service picks it up even when the
/// app is closed.
class EventsStore extends ChangeNotifier {
  EventsStore({this.platform = const EventsPlatform()});

  final EventsPlatform platform;

  EventsConfig? config;
  EventsStatus? status;

  bool get ready => config != null && status != null;

  Future<void> load() async {
    config = await platform.config();
    status = await platform.status();
    notifyListeners();
  }

  Future<void> setEnabled(bool on) async {
    await platform.setEnabled(on);
    // The service starts asynchronously; read back after it had a moment.
    await Future<void>.delayed(const Duration(milliseconds: 300));
    await load();
  }

  Future<void> setGesture(GestureKind g, {bool? enabled, GestureAction? action}) async {
    final current = config!.gestures[g]!;
    await platform.setGesture(g, enabled: enabled ?? current.enabled, action: action ?? current.action);
    await load();
  }

  Future<void> setOption(String key, Object value) async {
    await platform.setOption(key, value);
    await load();
  }

  /// Permissions the current setup still needs, as (key, title, why).
  List<(String, String, String)> get missing {
    final c = config, s = status;
    if (c == null || s == null) return const [];
    final used = c.usedActions;
    return [
      if (!s.battery)
        ('battery', 'Run in the background', 'Stops Android from closing Flick to save battery.'),
      if (used.contains(GestureAction.camera) && !s.overlay)
        ('overlay', 'Display over other apps', 'Needed to open the camera when Flick is in the background.'),
      if (used.contains(GestureAction.dnd) && !s.dnd)
        ('dnd', 'Do Not Disturb access', 'Needed to turn Do Not Disturb on and off.'),
    ];
  }
}
