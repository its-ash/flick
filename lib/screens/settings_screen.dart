import 'package:flutter/material.dart';
import 'package:theme/theme.dart';

import '../events/events_store.dart';
import '../events/gesture.dart';
import 'test_screen.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, required this.store});

  final EventsStore store;

  @override
  Widget build(BuildContext context) {
    return UiScaffold(
      appBar: const UiAppBar(title: 'Settings'),
      scrollable: true,
      body: ListenableBuilder(
        listenable: store,
        builder: (context, _) {
          final c = store.config;
          final s = store.status;
          if (c == null || s == null) return const Center(child: UiSpinner());
          return UiColumn(
            gap: UiSpace.lg,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              UiSection(
                title: 'Sensitivity',
                subtitle:
                    'Chop trigger level: Low 40, Medium 30, High 22 m/s². High catches gentler chops but may trigger by accident.',
                child: UiColumn(
                  gap: UiSpace.md,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    UiSegmentedControl<String>(
                      const [
                        UiSegment('low', label: 'Low'),
                        UiSegment('normal', label: 'Medium'),
                        UiSegment('high', label: 'High'),
                      ],
                      value: c.sensitivity,
                      expand: true,
                      onChanged: (v) => store.setOption('sensitivity', v),
                    ),
                    UiSlider(
                      value: c.chopThreshold.clamp(15.0, 60.0),
                      min: 15,
                      max: 60,
                      divisions: 45,
                      label: 'Chop trigger level (m/s²)',
                      format: (v) => v.toStringAsFixed(1),
                      onChangeEnd: (v) => store.setOption('chopThreshold', v),
                    ),
                  ],
                ),
              ),
              UiListGroup(
                header: 'Background',
                footer:
                    'Android pauses motion sensors when the screen is off. Keeping them awake lets Chop twice and Twist '
                    'work with the screen off, at some battery cost. Pocket gate: Motorola ignores chops while the '
                    'proximity sensor is covered; on some phones that sensor stays covered by mistake, so it is off.',
                children: [
                  if (s.chopSensor)
                    UiSwitchTile(
                      "Use the phone's chop sensor",
                      subtitle:
                          'Motorola detects Chop twice on its sensor chip: faster, works with the screen off, '
                          'no battery cost. Turn off Chop in Moto Actions so the light does not toggle twice. '
                          'Sensitivity and Calibrate then do not apply.',
                      icon: Icons.memory_rounded,
                      value: c.chopHardware,
                      onChanged: (v) => store.setOption('chopHardware', v),
                    ),
                  UiSwitchTile(
                    'Work with the screen off',
                    icon: Icons.battery_charging_full_rounded,
                    value: c.screenOff,
                    onChanged: (v) => store.setOption('screenOff', v),
                  ),
                  UiSwitchTile(
                    'Pocket gate',
                    subtitle: 'Ignore chops while the phone is covered (proximity sensor).',
                    icon: Icons.sensors_rounded,
                    value: c.pocketGate,
                    onChanged: (v) => store.setOption('pocketGate', v),
                  ),
                  if (c.hasAxis)
                    UiSwitchTile(
                      'Only my chop direction',
                      subtitle: 'Ignore chops that are not within 45° of the direction learned in Calibrate.',
                      icon: Icons.open_with_rounded,
                      value: c.chopAxisOnly,
                      onChanged: (v) => store.setOption('chopAxisOnly', v),
                    ),
                  UiSwitchTile(
                    'Vibrate on a gesture',
                    icon: Icons.vibration_rounded,
                    value: c.vibrate,
                    onChanged: (v) => store.setOption('vibrate', v),
                  ),
                  if (s.tileSupported)
                    UiListTile(
                      'Add Quick Settings tile',
                      subtitle: 'Turn gestures on and off from the notification shade.',
                      icon: Icons.dashboard_customize_rounded,
                      chevron: true,
                      onTap: () async {
                        final added = await store.platform.addTile();
                        if (context.mounted && added) context.showToast('Tile added.', status: UiStatus.success);
                      },
                    ),
                ],
              ),
              UiListGroup(
                header: 'Gestures',
                footer: 'See the peak of each chop, calibrate Flick to your chop, and check why a gesture does nothing.',
                children: [
                  UiListTile(
                    'Test gestures',
                    subtitle: 'Live peak meter, Calibrate, Diagnostics',
                    icon: Icons.speed_rounded,
                    chevron: true,
                    onTap: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(builder: (_) => TestScreen(store: store)),
                    ),
                  ),
                ],
              ),
              UiListGroup(
                header: 'Try an action',
                footer: 'Runs the action now, to check it works on this phone.',
                children: [
                  for (final a in GestureAction.values)
                    UiListTile(
                      a.label,
                      icon: a.icon,
                      onTap: () async {
                        final err = await store.platform.tryAction(a);
                        if (context.mounted && err != null) context.showToast(err, status: UiStatus.danger);
                      },
                    ),
                ],
              ),
              const UiAppearanceSection(),
              const UiUpdateSection(storeId: 'flick'),
              const UiDeveloperSection(),
            ],
          );
        },
      ),
    );
  }
}
