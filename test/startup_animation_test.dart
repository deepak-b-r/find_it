import 'package:findit/screens/home_screen.dart';
import 'package:findit/screens/language_selection_screen.dart';
import 'package:findit/screens/startup_animation_screen.dart';
import 'package:findit/services/settings_service.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await SettingsService.instance.initialize();

    // Mock platform channels used by subsequent screens (volume keys, tts, vibration, lost phone)
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.findit.findit/volume_keys'),
      (call) async => true,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('flutter_tts'),
      (call) async => 1,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('vibration'),
      (call) async => true,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.findit.findit/lost_phone'),
      (call) async => false,
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.findit.findit/volume_keys'),
      null,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('flutter_tts'),
      null,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('vibration'),
      null,
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.findit.findit/lost_phone'),
      null,
    );
  });

  group('StartupAnimationScreen Widget Tests', () {
    testWidgets('mounts smoothly with black background and handles video gracefully',
        (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: StartupAnimationScreen(),
        ),
      );

      // Verify Scaffold with black background is rendered
      final scaffold = tester.widget<Scaffold>(find.byType(Scaffold));
      expect(scaffold.backgroundColor, Colors.black);

      // Let initialization and graceful error/fallback resolve
      await tester.pumpAndSettle();

      // On first launch, it should transition to LanguageSelectionScreen
      expect(find.byType(LanguageSelectionScreen), findsOneWidget);
    });

    testWidgets('transitions to HomeScreen when not first launch',
        (tester) async {
      SettingsService.instance.setFirstLaunchCompleted();
      expect(SettingsService.instance.isFirstLaunch, false);

      await tester.pumpWidget(
        const MaterialApp(
          home: StartupAnimationScreen(),
        ),
      );

      // Settle transitions
      await tester.pumpAndSettle();

      // Should transition to HomeScreen
      expect(find.byType(HomeScreen), findsOneWidget);
    });

    testWidgets('tap to skip immediately navigates to destination',
        (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: StartupAnimationScreen(),
        ),
      );

      // Tap the screen (tap-to-skip)
      await tester.tap(find.byType(GestureDetector), warnIfMissed: false);
      await tester.pumpAndSettle();

      expect(find.byType(LanguageSelectionScreen), findsOneWidget);
    });
  });
}
