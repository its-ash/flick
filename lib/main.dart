import 'package:flutter/material.dart';
import 'package:theme/theme.dart';

import 'events/events_store.dart';
import 'screens/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(FlickApp(store: EventsStore()));
}

class FlickApp extends StatelessWidget {
  const FlickApp({super.key, required this.store});

  final EventsStore store;

  @override
  Widget build(BuildContext context) {
    return UiApp(
      title: 'Flick',
      home: HomeScreen(store: store),
    );
  }
}
