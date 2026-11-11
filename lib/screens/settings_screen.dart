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
                subtitle: 'High catches gentler moves but may trigger by accident.',
                child: UiSegmentedControl<String>(
                  const [
                    UiSegment('low', label: 'Low'),
                    UiSegment('normal', label: 'Normal'),
                    UiSegment('high', label: 'High'),
                  ],
                  value: c.sensitivity,
                  expand: true,
                  onChanged: (v) => store.setOption('sensitivity', v),
                ),
              ),
              UiListGroup(
                header: 'Background',
                footer:
                    'Android pauses motion sensors when the screen is off. Keeping them awake lets Shake and Twist '
                    'work with the screen off, at some battery cost.',
                children: [
                  UiSwitchTile(
                    'Work with the screen off',
                    icon: Icons.battery_charging_full_rounded,
                    value: c.screenOff,
                    onChanged: (v) => store.setOption('screenOff', v),
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
                footer: 'See a live motion meter, and teach Flick how hard you shake and twist.',
                children: [
                  UiListTile(
                    'Test gestures',
                    subtitle: 'Live meter and training',
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
                    UiListTile(a.label, icon: a.icon, onTap: () => store.platform.tryAction(a)),
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
