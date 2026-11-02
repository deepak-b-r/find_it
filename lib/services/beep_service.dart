import 'package:flutter/services.dart';
import 'guidance_engine.dart';

/// Progressive audio feedback generating non-overlapping beeps as the user closes in.
///
/// Features:
/// - Progressive cadence: slow interval when far, rapid when near.
/// - Tuned volume and short durations (40-70ms) for pleasant, non-jarring feedback.
/// - Designed to be paused during active voice instructions to prevent audio clutter.
/// - Zero-asset audio synthesis via native ToneGenerator.
class BeepService {
  BeepService._();
  static final BeepService instance = BeepService._();

  static const MethodChannel _channel = MethodChannel('com.findit.findit/beeper');

  DateTime _lastBeepTime = DateTime.fromMillisecondsSinceEpoch(0);

  /// Interval in milliseconds between beeps based on visual proximity.
  /// Far = Slow, distinct interval (~1100ms) with loud feedback.
  /// Approaching = Medium cadence (~550ms).
  /// Close = Fast cadence (~250ms).
  /// Very Close = Rapid, high-frequency parking-sensor style cadence (~120ms).
  int intervalMsFor(Proximity proximity) => switch (proximity) {
        Proximity.far => 1100,
        Proximity.approaching => 550,
        Proximity.close => 250,
        Proximity.veryClose => 120,
      };

  /// Plays a single synthesized tone with full loud volume (100).
  Future<void> singleBeep({int durationMs = 60, int volume = 100}) async {
    try {
      await _channel.invokeMethod<bool>('beep', {
        'durationMs': durationMs,
        'volume': volume,
      });
    } catch (_) {
      // Platform channel unavailable or in test environment
    }
  }

  /// Triggers a progressive beep if the cadence interval for [proximity] has elapsed.
  /// When far: plays slow, distinct intervals but loud.
  /// When closer: plays rapidly and continuously.
  Future<bool> proximityTick(Proximity proximity, {bool isSpeechActive = false}) async {
    final now = DateTime.now();
    final interval = intervalMsFor(proximity);

    if (now.difference(_lastBeepTime).inMilliseconds >= interval) {
      _lastBeepTime = now;
      final duration = switch (proximity) {
        Proximity.far => 75,
        Proximity.approaching => 65,
        Proximity.close => 55,
        Proximity.veryClose => 45,
      };
      await singleBeep(durationMs: duration, volume: 100);
      return true;
    }
    return false;
  }
}
