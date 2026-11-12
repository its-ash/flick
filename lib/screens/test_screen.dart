import 'dart:async';

import 'package:flutter/material.dart';
import 'package:theme/theme.dart';

import '../events/events_store.dart';
import '../events/gesture.dart';

/// Live meters for Chop twice (current motion and the peak of each chop against the trigger line) and Twist,
/// a log of detected gestures, the "Calibrate" flow (chop twice, 3 times: Flick learns your strength and
/// direction), and a Diagnostics card that shows where the background path breaks.
/// Real actions are paused while this screen is open.
class TestScreen extends StatefulWidget {
  const TestScreen({super.key, required this.store});

  final EventsStore store;

  @override
  State<TestScreen> createState() => _TestScreenState();
}

class _TestScreenState extends State<TestScreen> {
  StreamSubscription<Map<Object?, Object?>>? _sub;
  Map<Object?, Object?> _d = const {};
  final List<String> _log = [];
  String? _training; // 'chop' | 'twist'
  int _reps = 0;
  int _lastBurstSeq = 0;
  double _lastBurstPeak = 0;
  int _peakCount = 0;

  EventsStore get store => widget.store;

  double _n(String k, [double def = 0]) => (_d[k] as num?)?.toDouble() ?? def;
  int _i(String k) => (_d[k] as num?)?.toInt() ?? 0;

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
          _d = e;
          final seq = _i('burstSeq');
          if (seq != _lastBurstSeq) {
            _lastBurstSeq = seq;
            _lastBurstPeak = _n('burstPeak');
            _peakCount++;
          }
        });
      case 'gesture':
        setState(() => _log.insert(0, e['gesture'] == 'chop' ? 'Chop twice detected' : 'Twist twice detected'));
        if (_log.length > 5) _log.removeLast();
      case 'rep':
        setState(() => _reps = e['n'] as int);
      case 'trained':
        setState(() {
          _training = null;
          _reps = 0;
        });
        store.load();
        if (mounted) context.showToast('Saved. Flick now matches your chop.', status: UiStatus.success);
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

  Widget _trainButtons(String gesture, {required String label}) {
    final trained = store.config?.trained.contains(gesture) ?? false;
    final training = _training == gesture;
    return UiRow(
      gap: UiSpace.sm,
      children: [
        if (training)
          UiButton('Cancel', variant: UiButtonVariant.secondary, onPressed: _cancelTraining)
        else ...[
          UiButton(
            trained ? '$label again' : label,
            icon: Icons.school_rounded,
            variant: UiButtonVariant.secondary,
            onPressed: _training == null ? () => _train(gesture) : null,
          ),
          if (trained) UiButton('Reset', variant: UiButtonVariant.link, onPressed: () => _reset(gesture)),
        ],
      ],
    );
  }

  Widget _chopCard() {
    final thr = _n('chopThr', 30);
    final level = _n('chop');
    final training = _training == 'chop';
    final trained = store.config?.trained.contains('chop') ?? false;
    final max = (thr * 2).clamp(40.0, 100.0);
    final peakOk = _lastBurstPeak >= thr;
    return UiSection(
      title: 'Chop twice',
      subtitle: training
          ? 'Chop twice now, ${3 - _reps} more times. Done: $_reps of 3.'
          : '${trained ? 'Calibrated for you. ' : ''}Trigger level ${thr.toStringAsFixed(1)} m/s². '
                'Hold the phone like a knife and chop down twice.',
      child: UiColumn(
        gap: UiSpace.md,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          UiProgressBar(
            (level / max).clamp(0.0, 1.0),
            status: level >= thr ? UiStatus.success : UiStatus.accent,
            label: 'Motion now ${level.toStringAsFixed(1)} m/s²',
            height: 10,
          ),
          // The peak of the last burst of motion against the trigger line (the marker is at the trigger level).
          Stack(
            alignment: Alignment.centerLeft,
            children: [
              UiProgressBar(
                (_lastBurstPeak / max).clamp(0.0, 1.0),
                status: peakOk ? UiStatus.success : UiStatus.warning,
                label: _peakCount == 0
                    ? 'Last peak: chop to see it'
                    : 'Last peak ${_lastBurstPeak.toStringAsFixed(1)} m/s² '
                          '${peakOk ? 'is above' : 'is below'} the line ${thr.toStringAsFixed(1)}',
                height: 10,
              ),
            ],
          ),
          Text(
            'A chop counts when its peak is above the trigger level and ends with a quick stop. '
            'Two of them within a second toggle the flashlight. '
            '${_i('pending') > 0 ? 'First chop seen, waiting for the second.' : ''}',
            style: context.text.bodySmall,
          ),
          _trainButtons('chop', label: 'Calibrate'),
        ],
      ),
    );
  }

  Widget _twistCard() {
    final thr = _n('twistThr', 4.5);
    final level = _n('twist');
    final training = _training == 'twist';
    final trained = store.config?.trained.contains('twist') ?? false;
    return UiSection(
      title: 'Twist twice',
      subtitle: training
          ? 'Do it now, ${3 - _reps} more. Gesture $_reps of 3 done.'
          : '${trained ? 'Trained for you' : 'Default strength'}. Trigger level ${thr.toStringAsFixed(1)} rad/s.',
      child: UiColumn(
        gap: UiSpace.md,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          UiProgressBar(
            (level / (thr * 2.5)).clamp(0.0, 1.0),
            status: level >= thr ? UiStatus.success : UiStatus.accent,
            label: 'Motion',
            height: 10,
          ),
          _trainButtons('twist', label: 'Train'),
        ],
      ),
    );
  }

  String _ago(num s) => s < 0 ? 'never' : (s < 2 ? 'just now' : '${s.toInt()} s ago');

  Widget _diagnostics() {
    if (_d.isEmpty) return const UiListGroup(header: 'Diagnostics', children: [UiListTile('Waiting for sensors…')]);
    final running = _d['serviceRunning'] == true;
    final enabled = _d['serviceEnabled'] == true;
    final svcHz = _i('serviceHz');
    final svcAgo = _n('serviceSampleAgo', -1);
    final svcLive = running && svcHz > 0 && svcAgo >= 0 && svcAgo <= 3;
    final probeHz = _i('probeHz');
    final torchAvail = _d['torchAvailable'] == true;
    final lastAction = (_d['lastAction'] as String?) ?? '';
    final lastError = _d['lastActionError'] as String?;
    final lastGesture = (_d['lastGesture'] as String?) ?? '';
    final near = _d['near'] == true;
    final gate = _d['pocketGate'] == true;
    final service = (_d['serviceSensor'] as String?) ?? '';
    return UiListGroup(
      header: 'Diagnostics',
      footer: 'If a chop shows in the meter above but the flashlight does not toggle, read the lines here from top to bottom.',
      children: [
        UiListTile(
          'Background service',
          subtitle: running
              ? 'Running'
              : enabled
              ? 'Not running yet. Switch Gestures off and on on the home screen.'
              : 'Off. Turn on Gestures on the home screen.',
          icon: Icons.settings_suggest_rounded,
          iconStatus: running ? UiStatus.success : UiStatus.danger,
        ),
        UiListTile(
          'Sensors in the background',
          subtitle: !running
              ? 'Service is not running'
              : svcLive
              ? '$svcHz samples per second, ${service.isEmpty ? 'motion sensor' : service}'
              : 'No samples arriving. Allow "Run in the background" and keep "Work with the screen off" on.',
          icon: Icons.sensors_rounded,
          iconStatus: svcLive ? UiStatus.success : UiStatus.danger,
        ),
        UiListTile(
          'Sensors on this screen',
          subtitle: probeHz > 0
              ? '$probeHz samples per second, ${(_d['probeSensor'] as String?) ?? ''}'
              : 'No samples yet. This phone may have no motion sensor.',
          icon: Icons.speed_rounded,
          iconStatus: probeHz > 0 ? UiStatus.success : UiStatus.warning,
        ),
        UiListTile(
          'Proximity sensor',
          subtitle: !gate
              ? 'Not used (pocket gate is off)'
              : near
              ? 'Covered: chops are ignored'
              : 'Clear: chops are accepted',
          icon: Icons.sensors_rounded,
          iconStatus: gate && near ? UiStatus.warning : UiStatus.neutral,
        ),
        UiListTile(
          'Flashlight',
          subtitle: torchAvail
              ? 'Available, now ${_d['torchOn'] == true ? 'on' : 'off'}. Tap to try it.'
              : 'No flash found on this phone.',
          icon: Icons.flashlight_on_rounded,
          iconStatus: torchAvail ? UiStatus.success : UiStatus.danger,
          onTap: () async {
            final err = await store.platform.tryAction(GestureAction.flashlight);
            if (mounted) {
              context.showToast(err ?? 'Flashlight toggled.', status: err == null ? UiStatus.success : UiStatus.danger);
            }
          },
        ),
        UiListTile(
          'Last gesture',
          subtitle: lastGesture.isEmpty
              ? 'None yet'
              : '$lastGesture, ${_ago(_n('lastGestureAgo', -1))} (${_d['lastGestureSource'] == 'test' ? 'this screen' : 'background'})',
          icon: Icons.gesture_rounded,
          iconStatus: lastGesture.isEmpty ? UiStatus.neutral : UiStatus.info,
        ),
        UiListTile(
          'Last action',
          subtitle: lastAction.isEmpty
              ? 'None yet (actions are paused on this screen)'
              : '$lastAction, ${_ago(_n('lastActionAgo', -1))}: ${lastError ?? 'ok'}',
          icon: Icons.bolt_rounded,
          iconStatus: lastAction.isEmpty
              ? UiStatus.neutral
              : lastError == null
              ? UiStatus.success
              : UiStatus.danger,
        ),
      ],
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
              'Actions are paused while this screen is open. Try a gesture and watch the meters.',
              icon: Icons.science_rounded,
            ),
            _chopCard(),
            _twistCard(),
            UiListGroup(
              header: 'Detected',
              footer: 'Calibrate: chop twice, 3 times, the way you like it. Flick learns how hard and which way you chop.',
              children: [
                if (_log.isEmpty)
                  const UiListTile('Nothing yet', icon: Icons.hourglass_empty_rounded)
                else
                  for (final l in _log) UiListTile(l, icon: Icons.check_circle_rounded),
              ],
            ),
            _diagnostics(),
          ],
        ),
      ),
    );
  }
}
