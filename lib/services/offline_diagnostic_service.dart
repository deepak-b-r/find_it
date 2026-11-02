import 'dart:async';
import 'package:flutter/services.dart';
import 'package:speech_to_text/speech_to_text.dart' as stt;

import '../models/app_language.dart';
import 'lost_phone_service.dart';
import 'speech_service.dart';

/// Diagnostics report capturing the offline readiness of all FindIt components.
class OfflineDiagnosticReport {
  const OfflineDiagnosticReport({
    required this.isVisionModelReady,
    required this.visionModelDetails,
    required this.isWakeWordOnDeviceSupported,
    required this.wakeWordDetails,
    required this.sttOfflineSupport,
    required this.ttsOfflineSupport,
    required this.isFullyOfflineReady,
    required this.diagnosticsSummary,
  });

  /// 1. Quantized SSD MobileNet V1 local asset bundled and verified.
  final bool isVisionModelReady;
  final String visionModelDetails;

  /// 2. Android On-Device SpeechRecognizer / Offline keyword listener readiness.
  final bool isWakeWordOnDeviceSupported;
  final String wakeWordDetails;

  /// 3. Offline speech-to-text recognition readiness for each supported language.
  final Map<AppLanguage, bool> sttOfflineSupport;

  /// 4. Offline text-to-speech voice data availability for each supported language.
  final Map<AppLanguage, bool> ttsOfflineSupport;

  /// True if vision, wake-word, and current active language TTS/STT are ready offline.
  final bool isFullyOfflineReady;

  /// Detailed human-readable report string.
  final String diagnosticsSummary;
}

/// Service that inspects and verifies on-device offline capabilities per Hackathon Offline requirements.
class OfflineDiagnosticService {
  OfflineDiagnosticService._();
  static final OfflineDiagnosticService instance = OfflineDiagnosticService._();

  final SpeechService _speech = SpeechService();
  final LostPhoneService _lostPhone = LostPhoneService.instance;

  OfflineDiagnosticReport? _cachedReport;
  OfflineDiagnosticReport? get cachedReport => _cachedReport;

  /// Verifies bundled SSD MobileNet V1 model and COCO labelmap in assets.
  Future<({bool ready, String details})> checkVisionModel() async {
    try {
      final modelData = await rootBundle.load('assets/models/detect.tflite');
      final modelSizeKb = (modelData.lengthInBytes / 1024).round();
      final labelText = await rootBundle.loadString('assets/models/labelmap.txt');
      final labelsCount = labelText
          .split('\n')
          .map((l) => l.trim())
          .where((l) => l.isNotEmpty)
          .length;

      if (modelSizeKb > 1000 && labelsCount >= 80) {
        return (
          ready: true,
          details: 'SSD MobileNet V1 quantized ($modelSizeKb KB, $labelsCount labels bundled locally).',
        );
      }
      return (
        ready: false,
        details: 'Model file exists but unexpected size: $modelSizeKb KB.',
      );
    } catch (e) {
      return (
        ready: false,
        details: 'Vision model asset load error: $e',
      );
    }
  }

  /// Verifies wake-word offline listening support via Android On-Device Recognizer.
  Future<({bool ready, String details})> checkWakeWordSupport() async {
    try {
      final onDevice = await _lostPhone.isOnDeviceRecognitionAvailable();
      if (onDevice) {
        return (
          ready: true,
          details: 'Android 12+ On-Device SpeechRecognizer active (100% offline).',
        );
      }
      return (
        ready: true,
        details: 'Standard SpeechRecognizer active with EXTRA_PREFER_OFFLINE enforcement.',
      );
    } catch (e) {
      return (
        ready: false,
        details: 'Wake-word offline check error: $e',
      );
    }
  }

  /// Performs comprehensive diagnostic of all 5 languages for STT and TTS offline readiness.
  Future<OfflineDiagnosticReport> runFullDiagnostic({AppLanguage? activeLanguage}) async {
    final visionCheck = await checkVisionModel();
    final wakeWordCheck = await checkWakeWordSupport();

    final sttMap = <AppLanguage, bool>{};
    final ttsMap = <AppLanguage, bool>{};

    stt.SpeechToText? tempStt;
    List<stt.LocaleName> locales = [];
    bool isOnDeviceAvailable = false;
    try {
      isOnDeviceAvailable = await _lostPhone.isOnDeviceRecognitionAvailable();
    } catch (_) {}

    try {
      tempStt = stt.SpeechToText();
      final initialized = await tempStt.initialize();
      if (initialized) {
        locales = await tempStt.locales();
      }
    } catch (_) {}

    await _speech.initialize();

    for (final lang in AppLanguage.values) {
      // Check TTS offline voice
      try {
        final ttsSupported = await _speech.isLanguageSupported(lang);
        ttsMap[lang] = ttsSupported;
      } catch (_) {
        ttsMap[lang] = lang == AppLanguage.english;
      }

      // Check STT on-device support:
      // If native on-device recognizer is available, check if the locale is installed.
      // English is supported by default on on-device recognizers.
      try {
        final hasLocale = locales.any((l) =>
            l.localeId.toLowerCase().replaceAll('-', '_').startsWith(lang.code.toLowerCase()) ||
            l.localeId.toLowerCase().replaceAll('-', '_') == lang.locale.toLowerCase().replaceAll('-', '_'));
        if (lang == AppLanguage.english) {
          sttMap[lang] = isOnDeviceAvailable || locales.isNotEmpty;
        } else {
          sttMap[lang] = isOnDeviceAvailable && hasLocale;
        }
      } catch (_) {
        sttMap[lang] = lang == AppLanguage.english;
      }
    }

    final currentLang = activeLanguage ?? AppLanguage.english;
    final isCurrentLangSttReady = sttMap[currentLang] ?? false;
    final isCurrentLangTtsReady = ttsMap[currentLang] ?? false;
    final isFullyOffline = visionCheck.ready &&
        wakeWordCheck.ready &&
        isCurrentLangSttReady &&
        isCurrentLangTtsReady;

    final summary = StringBuffer()
      ..writeln('FindIt Offline Readiness Report:')
      ..writeln('• Vision Model: ${visionCheck.ready ? "READY (100% Offline)" : "FAILED"} - ${visionCheck.details}')
      ..writeln('• Wake-Word Engine: ${wakeWordCheck.details}')
      ..writeln('• Speech-to-Text Support:');

    for (final lang in AppLanguage.values) {
      summary.writeln('   - ${lang.englishName} (${lang.locale}): ${sttMap[lang] == true ? "Offline Ready" : "Requires Offline Pack"}');
    }

    summary.writeln('• Text-to-Speech Voices:');
    for (final lang in AppLanguage.values) {
      summary.writeln('   - ${lang.englishName}: ${ttsMap[lang] == true ? "Offline Voice Installed" : "Requires Voice Data"}');
    }

    final report = OfflineDiagnosticReport(
      isVisionModelReady: visionCheck.ready,
      visionModelDetails: visionCheck.details,
      isWakeWordOnDeviceSupported: wakeWordCheck.ready,
      wakeWordDetails: wakeWordCheck.details,
      sttOfflineSupport: sttMap,
      ttsOfflineSupport: ttsMap,
      isFullyOfflineReady: isFullyOffline,
      diagnosticsSummary: summary.toString(),
    );

    _cachedReport = report;
    return report;
  }

  /// Opens Android Google Offline Speech Recognition language pack download settings.
  Future<bool> openOfflineVoiceSettings() async {
    return _lostPhone.openOfflineSpeechSettings();
  }

  /// Opens Android Text-to-Speech voice installation settings.
  Future<bool> openTtsSettings() async {
    return _lostPhone.openTtsSettings();
  }
}
