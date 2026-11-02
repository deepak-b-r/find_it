import 'package:flutter_test/flutter_test.dart';
import 'package:findit/services/offline_diagnostic_service.dart';
import 'package:findit/models/app_language.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('OfflineDiagnosticReport Model & Service Tests', () {
    test('OfflineDiagnosticReport initializes and formats summary correctly', () {
      final report = OfflineDiagnosticReport(
        isFullyOfflineReady: true,
        isVisionModelReady: true,
        visionModelDetails: 'SSD MobileNet V1 4374 KB bundled locally',
        isWakeWordOnDeviceSupported: true,
        wakeWordDetails: 'Android 12+ On-Device SpeechRecognizer active',
        sttOfflineSupport: {
          AppLanguage.english: true,
          AppLanguage.hindi: false,
          AppLanguage.kannada: false,
          AppLanguage.telugu: false,
          AppLanguage.tamil: false,
        },
        ttsOfflineSupport: {
          AppLanguage.english: true,
          AppLanguage.hindi: true,
          AppLanguage.kannada: false,
          AppLanguage.telugu: false,
          AppLanguage.tamil: false,
        },
        diagnosticsSummary: 'FindIt Offline Diagnostics: All Core Systems Ready.',
      );

      expect(report.isFullyOfflineReady, isTrue);
      expect(report.isVisionModelReady, isTrue);
      expect(report.isWakeWordOnDeviceSupported, isTrue);
      expect(report.sttOfflineSupport[AppLanguage.english], isTrue);
      expect(report.sttOfflineSupport[AppLanguage.kannada], isFalse);
      expect(report.ttsOfflineSupport[AppLanguage.hindi], isTrue);
      expect(report.diagnosticsSummary, contains('All Core Systems Ready'));
    });

    test('OfflineDiagnosticReport correctly marks incomplete offline status', () {
      final report = OfflineDiagnosticReport(
        isFullyOfflineReady: false,
        isVisionModelReady: true,
        visionModelDetails: 'SSD MobileNet V1 4374 KB bundled locally',
        isWakeWordOnDeviceSupported: true,
        wakeWordDetails: 'Android 12+ On-Device SpeechRecognizer active',
        sttOfflineSupport: {
          AppLanguage.kannada: false,
        },
        ttsOfflineSupport: {
          AppLanguage.kannada: true,
        },
        diagnosticsSummary: 'Offline Pack Required for Kannada STT',
      );

      expect(report.isFullyOfflineReady, isFalse);
      expect(report.sttOfflineSupport[AppLanguage.kannada], isFalse);
    });
  });
}
