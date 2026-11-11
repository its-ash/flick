import 'package:flutter/material.dart';
import 'package:theme/theme.dart';

import '../events/events_store.dart';
import '../events/gesture.dart';
import 'settings_screen.dart';

/// Master switch, missing permissions, and one row per gesture with its action.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.store});

  final EventsStore store;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late final AppLifecycleListener _lifecycle;

  EventsStore get store => widget.store;

  @override
  void initState() {
    super.initState();
    store.load();
    // Back from a system permission screen: re-check.
    _lifecycle = AppLifecycleListener(onResume: store.load);
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _pickAction(GestureKind g) async {
    final current = store.config!.gestures[g]!.action;
    final picked = await UiActionSheet.show<GestureAction>(
      context,
      title: g.title,
      message: 'What should happen?',
      actions: [
        for (final a in GestureAction.values)
          UiSheetAction(a.label, value: a, icon: a == current ? Icons.check_rounded : a.icon),
      ],
    );
    if (picked != null) await store.setGesture(g, action: picked);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) => UiScaffold(
        appBar: UiAppBar(
          title: 'Flick',
          actions: [
            UiIconButton(
              Icons.settings_rounded,
              tooltip: 'Settings',
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute<void>(builder: (_) => SettingsScreen(store: store)),
              ),
            ),
          ],
        ),
        scrollable: true,
        body: store.ready ? _content(context) : const Center(child: UiSpinner(size: 28)),
      ),
    );
  }

  Widget _content(BuildContext context) {
    final c = store.config!, s = store.status!;
    final missing = store.missing;
    return UiColumn(
      gap: UiSpace.lg,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        UiListGroup(
          children: [
            UiSwitchTile(
              'Gestures',
              subtitle: c.enabled
                  ? (c.running ? 'On. Flick listens in the background.' : 'Starting…')
                  : 'Off. Turn on to use gestures with the app closed.',
              icon: Icons.gesture_rounded,
              status: c.enabled ? UiStatus.success : UiStatus.neutral,
              value: c.enabled,
              onChanged: store.setEnabled,
            ),
          ],
        ),
        if (missing.isNotEmpty)
          UiListGroup(
            header: 'Allow',
            footer: 'Tap a row, allow it, then come back.',
            children: [
              for (final (key, title, why) in missing)
                UiListTile(
                  title,
                  subtitle: why,
                  icon: Icons.lock_open_rounded,
                  iconStatus: UiStatus.warning,
                  chevron: true,
                  onTap: () => store.platform.openPermission(key),
                ),
            ],
          ),
        UiListGroup(
          header: 'Gestures',
          footer: 'Pick up and Wave work while the screen is off. The others work any time.',
          children: [for (final g in GestureKind.values) ..._gestureRows(g, s.has(g.sensor))],
        ),
      ],
    );
  }

  List<Widget> _gestureRows(GestureKind g, bool supported) {
    final setting = store.config!.gestures[g]!;
    return [
      UiSwitchTile(
        g.title,
        subtitle: supported ? g.description : 'This phone has no ${g.sensor.name}.',
        icon: g.icon,
        value: setting.enabled && supported,
        onChanged: supported ? (v) => store.setGesture(g, enabled: v) : null,
      ),
      if (setting.enabled && supported && !g.fixedAction)
        UiListTile(
          'Then',
          icon: setting.action.icon,
          iconStatus: UiStatus.info,
          value: setting.action.label,
          dense: true,
          chevron: true,
          onTap: () => _pickAction(g),
        ),
    ];
  }
}
