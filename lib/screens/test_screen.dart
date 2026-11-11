import 'dart:async';

import 'package:flutter/material.dart';
import 'package:theme/theme.dart';

import '../events/events_store.dart';

/// Live motion meters for Shake (with swing count) and Twist, a log of detected gestures, and the
/// optional "Train" flow (do the gesture 3 times, Flick learns your strength).
/// Real actions are paused while this screen is open.
class TestScreen extends StatefulWidget {
  const TestScreen({super.key, required this.store});

  final EventsStore store;

  @override
  State<TestScreen> createState() => _TestScreenState();
}

class _TestScreenState extends State<TestScreen> {
  StreamSubscription<Map<Object?, Object?>>? _sub;
  double _shake = 0, _shakeThr = 1.5, _twist = 0, _twistThr = 4.5;
  int _swings = 0, _swingsNeeded = 5;
  final List<String> _log = [];
  String? _training; // 'shake' | 'twist'
  int _reps = 0;

  EventsStore get store => widget.store;

  @override
  void initState() {
    super.initState();
    _sub = store.platform.probe().listen(_onEvent);
  }

  @override
  void dispose() {
    _sub?.cancel();
    store.platform.stopTraining();
    store.load();
    super.dispose();
  }

  void _onEvent(Map<Object?, Object?> e) {
    switch (e['type']) {
      case 'frame':
        setState(() {
          _shake = (e['shake'] as num).toDouble();
          _shakeThr = (e['shakeThr'] as num).toDouble();
          _swings = e['swings'] as int;
          _swingsNeeded = e['swingsNeeded'] as int;
          _twist = (e['twist'] as num).toDouble();
          _twistThr = (e['twistThr'] as num).toDouble();
        });
      case 'gesture':
        setState(() => _log.insert(0, e['gesture'] == 'shake' ? 'Side-to-side shake detected' : 'Twist twice detected'));
        if (_log.length > 5) _log.removeLast();
      case 'rep':
        setState(() => _reps = e['n'] as int);
      case 'trained':
        setState(() {
          _training = null;
          _reps = 0;
        });
        store.load();
        if (mounted) context.showToast('Saved. Flick now matches your strength.', status: UiStatus.success);
    }
  }

  Future<void> _train(String gesture) async {
    setState(() {
      _training = gesture;
      _reps = 0;
    });
    await store.platform.startTraining(gesture);
  }

  Future<void> _cancelTraining() async {
    await store.platform.stopTraining();
    setState(() {
      _training = null;
      _reps = 0;
    });
  }

  Future<void> _reset(String gesture) async {
    await store.platform.resetTraining(gesture);
    await store.load();
  }

  Widget _meter(String title, String gesture, double level, double thr, String unit, {int? swings, int? swingsNeeded}) {
    final trained = store.config?.trained.contains(gesture) ?? false;
    final training = _training == gesture;
    final max = thr * 2.5;
    return UiSection(
      title: title,
      subtitle: training
          ? 'Do it now, ${3 - _reps} more. Gesture $_reps of 3 done.'
          : trained
          ? 'Trained for you. Trigger level ${thr.toStringAsFixed(1)} $unit.'
          : 'Default strength. Trigger level ${thr.toStringAsFixed(1)} $unit.',
      child: UiColumn(
        gap: UiSpace.md,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Bar fills up to the trigger level at 40%: past that it is strong enough.
          UiProgressBar(
            (level / max).clamp(0.0, 1.0),
            status: level >= thr ? UiStatus.success : UiStatus.accent,
            label: 'Motion',
            height: 10,
          ),
          if (swings != null && swingsNeeded != null)
            UiProgressBar(
              (swings / swingsNeeded).clamp(0.0, 1.0),
              status: swings >= swingsNeeded ? UiStatus.success : UiStatus.accent,
              label: 'Swings $swings of $swingsNeeded',
              height: 10,
            ),
          UiRow(
            gap: UiSpace.sm,
            children: [
              if (training)
                UiButton('Cancel', variant: UiButtonVariant.secondary, onPressed: _cancelTraining)
              else ...[
                UiButton(
                  trained ? 'Train again' : 'Train',
                  icon: Icons.school_rounded,
                  variant: UiButtonVariant.secondary,
                  onPressed: _training == null ? () => _train(gesture) : null,
                ),
                if (trained) UiButton('Reset', variant: UiButtonVariant.link, onPressed: () => _reset(gesture)),
              ],
            ],
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return UiScaffold(
      appBar: const UiAppBar(title: 'Test gestures'),
      scrollable: true,
      body: ListenableBuilder(
        listenable: store,
        builder: (context, _) => UiColumn(
          gap: UiSpace.lg,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const UiBanner(
              'Actions are paused while this screen is open. Try a gesture and watch the meter.',
              icon: Icons.science_rounded,
            ),
            _meter(
              'Shake side to side',
              'shake',
              _shake,
              _shakeThr,
              'm/s²',
              swings: _swings,
              swingsNeeded: _swingsNeeded,
            ),
            _meter('Twist twice', 'twist', _twist, _twistThr, 'rad/s'),
            UiListGroup(
              header: 'Detected',
              footer: 'Train: do the gesture 3 times the way you like it. Flick learns how hard you move.',
              children: [
                if (_log.isEmpty)
                  const UiListTile('Nothing yet', icon: Icons.hourglass_empty_rounded)
                else
                  for (final l in _log) UiListTile(l, icon: Icons.check_circle_rounded),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
