import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'screens/startup_animation_screen.dart';
import 'services/settings_service.dart';

import 'theme/app_theme.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Lock to portrait: detection coordinates and guidance assume an upright image.
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  await SettingsService.instance.initialize();
  runApp(const FindItApp());
}

class FindItApp extends StatelessWidget {
  const FindItApp({super.key});

  static final GlobalKey<NavigatorState> navigatorKey =
      GlobalKey<NavigatorState>();

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: navigatorKey,
      title: 'FindIt',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.darkTheme,
      home: const StartupAnimationScreen(),
    );
  }
}
