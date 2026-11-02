import 'dart:async';
import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

import '../services/settings_service.dart';
import 'home_screen.dart';
import 'language_selection_screen.dart';

/// Screen — High-performance, Accessible Startup Animation.
///
/// Plays the bundled Iris startup animation (`iris-animation-preview.mp4`)
/// on fresh application launch before transitioning smoothly to the target screen.
///
/// Principles:
/// - Centers and scales the video preserving its natural aspect ratio.
/// - Respects the natural video duration (2.5s) without artificial delay.
/// - Provides graceful error and timeout fallback to prevent any black screen or hanging.
/// - Once completed, uses pushReplacement so the animation never replays during normal app use or background resume.
class StartupAnimationScreen extends StatefulWidget {
  const StartupAnimationScreen({super.key});

  @override
  State<StartupAnimationScreen> createState() => _StartupAnimationScreenState();
}

class _StartupAnimationScreenState extends State<StartupAnimationScreen> {
  late VideoPlayerController _controller;
  bool _isInitialized = false;
  bool _hasNavigated = false;
  Timer? _fallbackTimer;

  // Fallback safety timeout: transitions if video playback doesn't report end
  static const Duration _maxTimeout = Duration(milliseconds: 3200);

  @override
  void initState() {
    super.initState();
    _initializeAndPlay();
  }

  Future<void> _initializeAndPlay() async {
    // Safety timer ensures the app never gets stuck on splash screen
    _fallbackTimer = Timer(_maxTimeout, () {
      if (mounted && !_hasNavigated) {
        _navigateToDestination();
      }
    });

    _controller = VideoPlayerController.asset(
      'assets/animation/iris-animation-preview.mp4',
    );

    _controller.addListener(_videoPlayerListener);

    try {
      await _controller.initialize();
      if (!mounted) return;

      // Ensure audio is muted to prevent ducking or interference with system TTS
      await _controller.setVolume(0.0);
      await _controller.setLooping(false);

      setState(() {
        _isInitialized = true;
      });

      await _controller.play();
    } catch (e) {
      debugPrint('Startup animation initialization error: $e');
      if (mounted && !_hasNavigated) {
        _navigateToDestination();
      }
    }
  }

  void _videoPlayerListener() {
    if (!mounted || _hasNavigated) return;

    if (_controller.value.hasError) {
      debugPrint('Startup animation error: ${_controller.value.errorDescription}');
      _navigateToDestination();
      return;
    }

    if (_controller.value.isInitialized) {
      final position = _controller.value.position;
      final duration = _controller.value.duration;

      // Video reached the end
      if (position >= duration && duration > Duration.zero) {
        _navigateToDestination();
      }
    }
  }

  void _navigateToDestination() {
    if (_hasNavigated || !mounted) return;
    _hasNavigated = true;
    _fallbackTimer?.cancel();

    final isFirstLaunch = SettingsService.instance.isFirstLaunch;
    final Widget destination = isFirstLaunch
        ? const LanguageSelectionScreen(isFirstLaunch: true)
        : const HomeScreen();

    Navigator.of(context).pushReplacement(
      PageRouteBuilder(
        pageBuilder: (context, animation, secondaryAnimation) => destination,
        transitionsBuilder: (context, animation, secondaryAnimation, child) {
          return FadeTransition(
            opacity: animation,
            child: child,
          );
        },
        transitionDuration: const Duration(milliseconds: 300),
      ),
    );
  }

  @override
  void dispose() {
    _fallbackTimer?.cancel();
    _controller.removeListener(_videoPlayerListener);
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: _navigateToDestination, // Tap-to-skip for accessibility
          child: Center(
            child: _isInitialized
                ? AspectRatio(
                    aspectRatio: _controller.value.aspectRatio,
                    child: VideoPlayer(_controller),
                  )
                : const SizedBox.shrink(),
          ),
        ),
      ),
    );
  }
}
