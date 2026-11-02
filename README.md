# FindIt — AI Spatial Object Finder & Assistant for the Visually Impaired

<p align="center">
  <img src="findit_screen.png" alt="FindIt Application HUD & Guidance Preview" width="720" style="border-radius: 12px; box-shadow: 0 8px 24px rgba(0,0,0,0.5);" />
</p>

<p align="center">
  <b>FindIt empowers blind and low-vision individuals to locate everyday personal objects independently and retrieve misplaced phones through real-time, 100% on-device neural vision, closed-loop spatial audio, and tactile guidance.</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Flutter-3.8+-02569B?style=for-the-badge&logo=flutter&logoColor=white" alt="Flutter" />
  <img src="https://img.shields.io/badge/Dart-3.8+-0175C2?style=for-the-badge&logo=dart&logoColor=white" alt="Dart" />
  <img src="https://img.shields.io/badge/Platform-Android%20(API%2021--35)-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/AI%20Inference-100%25%20On--Device%20(TFLite)-FF6F00?style=for-the-badge&logo=tensorflow&logoColor=white" alt="TensorFlow Lite" />
  <img src="https://img.shields.io/badge/Languages-5%20Indian%20Languages-E91E63?style=for-the-badge" alt="Multilingual" />
  <img src="https://img.shields.io/badge/Tests-67%20Passed-brightgreen?style=for-the-badge" alt="Tests" />
  <img src="https://img.shields.io/badge/Static%20Analysis-0%20Issues-brightgreen?style=for-the-badge" alt="Analysis" />
  <img src="https://img.shields.io/badge/Accessibility-WCAG%20AAA%20%7C%20TalkBack-9C27B0?style=for-the-badge" alt="Accessibility" />
</p>

---

## 📑 Table of Contents

- [The Assistive Mission & Problem Statement](#-the-assistive-mission--problem-statement)
- [How FindIt Works (The Core Loop)](#-how-findit-works-the-core-loop)
- [Key Features & Innovations](#-key-features--innovations)
  - [1. 100% On-Device Neural Vision (Zero-Cloud)](#1-100-on-device-neural-vision-zero-cloud)
  - [2. Closed-Loop Spatial Steering & Guidance](#2-closed-loop-spatial-steering--guidance)
  - [3. Progressive Cadence Beeps & Tactile Haptics](#3-progressive-cadence-beeps--tactile-haptics)
  - [4. Cautious Reach & 5-Second Interactive Check-In](#4-cautious-reach--5-second-interactive-check-in)
  - [5. "Hey Iris" Voice Assistant & Lost Phone Mode](#5-hey-iris-voice-assistant--lost-phone-mode)
  - [6. Screen-Free Hardware Button Onboarding](#6-screen-free-hardware-button-onboarding)
  - [7. Offline Diagnostic Suite & Iris Splash Experience](#7-offline-diagnostic-suite--iris-splash-experience)
  - [8. Radical Engineering Honesty](#8-radical-engineering-honesty)
- [10 Supported Everyday Objects](#-10-supported-everyday-objects)
- [Multilingual Support (5 Indian Languages)](#-multilingual-support-5-indian-languages)
- [System Architecture & Data Flow](#-system-architecture--data-flow)
- [Tech Stack & Android Platform Integrations](#-tech-stack--android-platform-integrations)
- [Project Directory Structure](#-project-directory-structure)
- [Automated Test Suite & Quality Verification](#-automated-test-suite--quality-verification)
- [Getting Started & Build Instructions](#-getting-started--build-instructions)
- [Permissions & Offline Setup](#-permissions--offline-setup)
- [Safety & Privacy Commitment](#-safety--privacy-commitment)
- [Hackathon Jury & Technical Reference](#-hackathon-jury--technical-reference)

---

## 🎯 The Assistive Mission & Problem Statement

Visually impaired individuals frequently misplace essential personal items in familiar indoor environments—such as water bottles, phones, cups, books, bags, or laptop accessories.

### Current Gaps in Assistive Tools:
- **Passive scene describer apps** recite paragraphs of visual text instead of offering immediate, low-latency, actionable steering commands to guide the user's hand to the object.
- **Volunteer video calls** (*Be My Eyes*) introduce severe privacy concerns inside personal bedrooms or offices and fail completely without active high-speed internet.
- **Hardware Bluetooth tags** require recurring battery replacements, costly physical tags for every belonging, and only work when the item already has a tag attached.
- **Muted or misplaced phones** cannot be spotted visually by a blind individual, and standard ringers are ineffective when silent mode or Do-Not-Disturb is engaged.

### The FindIt Solution:
FindIt transforms a standard Android smartphone into an active, tactile spatial guide. By pairing **local quantized computer vision (TensorFlow Lite / LiteRT)** on an isolated background thread with **directional voice steering, native progressive cadence beeps, and proximity vibration**, FindIt guides the user step-by-step directly to their misplaced belongings—with **zero internet connection required**, total privacy, and no subscription fees.

---

## 🔄 How FindIt Works (The Core Loop)

```
┌──────────────┐     ┌───────────────┐     ┌───────────────┐     ┌──────────────┐     ┌──────────────┐
│  VOICE / TAP │ ──> │ CAMERA STREAM │ ──> │ ON-DEVICE AI  │ ──> │ TRACKER &    │ ──> │ MULTIMODAL   │ ──> FOUND!
│ "Find bottle"│     │ (YUV420 280ms)│     │ (SSD MobileNet│     │ GUIDANCE FSM │     │ VOICE/HAPTIC │     "Extend hand
└──────────────┘     └───────────────┘     │  Background)  │     └──────────────┘     │ PROXIMITY    │      cautiously"
                                           └───────────────┘                          └──────────────┘
```

1. **Invoke Intent:** Tap the high-contrast mic or say *"Hey Iris, find my bottle"* (or tap the tactile object catalog card).
2. **Camera Ingestion:** High-efficiency YUV420 camera frames are throttled (1 frame every 280ms) and dispatched to an isolated background Dart isolate.
3. **Local Inference:** The quantized neural network detects bounding boxes and labels in 45–65ms with zero network transmission.
4. **Temporal Tracking:** IoU-based tracking with Exponential Moving Average (EMA) filters noise and maintains stable continuity across missed frames.
5. **Spatial Steering:** The guidance engine calculates horizontal position across 5 quadrants (`farLeft`, `left`, `center`, `right`, `farRight`) and emits spoken directional cues (*"Move left"*, *"Move slowly forward"*).
6. **Progressive Proximity Audio & Haptics:** Native low-latency beeps scale their tempo from 1400ms down to 200ms as distance closes, while tactile pulses accelerate.
7. **Cautious Reach State:** When centered and within reach, Iris announces *"Stop. Bottle found. It may be within reach. Extend your hand cautiously."*
8. **Interactive Check-In:** After a 5-second physical reaching window, Iris asks *"Did you find the bottle? Say yes or no"*, confirming recovery via spoken voice or high-contrast tactile buttons.

---

## ✨ Key Features & Innovations

### 1. 100% On-Device Neural Vision (Zero-Cloud)
- **Model:** Quantized **SSD MobileNet V1** (`assets/models/detect.tflite`, 4.18 MB) trained on COCO.
- **Background Isolate:** Neural computation runs strictly off the main thread in a dedicated background worker isolate. The main UI thread never drops frames and maintains 60 FPS fluidity.
- **Fast Integer Color Math:** Custom BT.601 integer math converts camera YUV420 planes directly to $300 \times 300 \times 3$ uint8 tensors, including automatic 0°, 90°, 180°, and 270° sensor orientation correction.
- **Complete Privacy:** Camera frames never leave the device hardware or touch external servers.

### 2. Closed-Loop Spatial Steering & Guidance
- **5 Horizontal Zones:** `Far Left` (<25%), `Left` (25–38%), `Center` (38–62%), `Right` (62–75%), `Far Right` (>75%).
- **Smooth Temporal Filtering:** Bounding boxes are filtered across consecutive frames using Intersection over Union (IoU) and exponential smoothing ($\alpha = 0.45$). Transient one-frame false positives are filtered out.
- **Target-Loss Directional Scanning:** If an object is panned out of view, FindIt recalls its last known horizontal quadrant and immediately instructs: *"Target lost. Scan slightly left"* or *"Target lost. Scan slightly right"*, pausing forward motion cues to keep the user safe from obstacles.

### 3. Progressive Cadence Beeps & Tactile Haptics
- **Native Android ToneGenerator:** Implemented natively in Kotlin via `android.media.ToneGenerator` (`BeepService`), avoiding MP3 decoding latency.
- **Proximity Cadence:**
  - `Far` ($<4\%$ area): 1400ms interval (40ms tone duration)
  - `Approaching` ($4–9\%$ area): 750ms interval (50ms tone duration)
  - `Close` ($9–18\%$ area): 380ms interval (65ms tone duration)
  - `Very Close` ($\ge 18\%$ area): 200ms interval (75ms tone duration)
- **Zero Audio Collision (Speech Ducking):** `BeepService` automatically mutes beeping while TTS voice guidance is speaking, preventing acoustic clutter for the blind user.
- **Tactile Haptic Actuation:** Rapid directional pulses and a celebratory triple-pulse vibration upon final object acquisition.

### 4. Cautious Reach & 5-Second Interactive Check-In
- **Safety-First Arrival:** When an object fills $>18\%$ of the frame and is centered, FindIt instructs the user: *"Stop. [Object] found. It may be within reach. Extend your hand cautiously."*
- **Acoustic Settling Gap:** Using `speakAndWait()`, the app guarantees 100% voice completion plus a 400ms acoustic settling window before opening the microphone, eliminating speaker-to-microphone acoustic feedback.
- **Spoken Yes/No Validation:** Iris inquires: *"Did you find the [object]? Say yes or no."*
  - **Affirmative** (*"Yes"*, *"हाँ"*, *"ಹೌದು"*, *"అవును"*, *"ஆம்"*): Confirms object recovery, triggers celebration haptics, and opens the Found screen.
  - **Negative** (*"No"*, *"नहीं"*, *"ಇಲ್ಲ"*, *"లేదు"*, *"இல்லை"*): Restores tracking and camera guidance seamlessly.
- **Touch Fallback:** High-contrast, tactile on-screen `YES` / `NO` buttons accommodate users in noisy environments.

### 5. "Hey Iris" Voice Assistant & Lost Phone Mode
- **Hands-Free Wake Word:** Listens for *"Hey Iris"* / *"Iris"* and natural phonetic variants across all supported languages.
- **Native Android Foreground Service (`LostPhoneService`):** Compliant with Android's `FOREGROUND_SERVICE_MICROPHONE` requirements, running with persistent notification and partial wake lock.
- **Silent & DND Override:** When triggered with *"Where are you"* or *"Find my phone"*, FindIt forces device audio stream volume to maximum, sounds a siren, and activates an SOS vibration rhythm.
- **Android 10–15 Background Activity Launch (BAL) Compliance:** Uses `setFullScreenIntent(pendingIntent, true)` with `IMPORTANCE_HIGH` public notifications to present a heads-up wake-up card even when the screen is locked.
- **Battery Protection:** Auto-times out after 20 minutes of continuous background monitoring and automatically shuts down if the battery drops below 15%.

### 6. Screen-Free Hardware Button Onboarding
- **Zero-Vision Setup:** On first launch, FindIt speaks an automated audio walkthrough sequentially in English, Hindi, Kannada, Telugu, and Tamil.
- **Hardware Volume Button Navigation:**
  - `Volume Up`: Cycle forward (+1).
  - `Volume Down`: Cycle backward (-1).
  - **Circular Wrapping:** Volume Down from Option 1 (English) wraps directly to Option 5 (Tamil); Volume Up from Option 5 wraps to Option 1.
  - **Confirmation:** Simultaneous volume press (chord), 600ms long press, or double-tap anywhere on screen.
- **Voice Name Onboarding:** Users can speak their name to receive a warm personalized greeting.
- **Mobility Disclaimer:** Closes setup with an essential reminder: *"FindIt supplements, but does not replace, your white cane or mobility guide."*

### 7. Offline Diagnostic Suite & Iris Splash Experience
- **Offline Diagnostics Service (`OfflineDiagnosticService`):** Inspects and validates bundled vision models, on-device Android SpeechRecognizer support, and offline TTS voice packages for all 5 languages.
- **One-Tap System Shortcuts:** Direct links from Settings to Android's native *Offline Speech Recognition* and *Text-to-Speech Engine* settings to install language packs.
- **Accessible Iris Video Splash Screen (`StartupAnimationScreen`):** Plays high-resolution iris animation (`iris-animation-preview.mp4`) with tap-to-skip and safe fallback timers.

### 8. Radical Engineering Honesty
- **No Hallucinated Detections:** FindIt never displays fake boxes or simulated distance measurements.
- **No Metric Depth Exaggeration:** Without hardware LiDAR, metric centimeters cannot be reliably computed. Proximity is computed strictly from 2D bounding box normalized screen area.
- **Unsupported Items Handled Honestly:** Everyday items not reliably detectable by the mobile COCO model (keys, wallets, spectacles, coins) are explicitly identified and vocally announced as unsupported.

---

## 📦 10 Supported Everyday Objects

FindIt maps verified COCO classes into clear household object definitions:

| Object | Friendly Name | Model Class ID / Name | Sample Spoken Triggers | Tactile Icon |
|:---|:---|:---|:---|:---:|
| 🍶 **Water Bottle** | `bottle` | #44 `bottle` | "Find my water bottle", "बोतल कहाँ है", "ಬಾಟಲ್ ಹುಡುಕು" | `local_drink` |
| 📱 **Mobile Phone** | `phone` | #77 `cell phone` | "Find my phone", "मेरा फोन ढूंढो", "ಫೋನ್ ಎಲ್ಲಿದೆ" | `smartphone` |
| ☕ **Cup / Mug** | `cup` | #47 `cup` | "Find the cup", "चाय का कप", "ಕಪ್ ಹುಡುಕು" | `coffee` |
| 📖 **Book** | `book` | #84 `book` | "Find my book", "किताब कहाँ है", "ಪುಸ್ತಕ ಹುಡುಕು" | `menu_book` |
| 👜 **Bag / Handbag** | `bag` | #31 `handbag` | "Find my bag", "पर्स/बैग ढूंढो", "ಬ್ಯಾಗ್ ಹುಡುಕು" | `shopping_bag` |
| 🎒 **Backpack** | `backpack` | #27 `backpack` | "Where is my backpack", "बस्ता ढूंढो", "ಬ್ಯಾಕ್‌ಪ್ಯಾಕ್" | `backpack` |
| 📺 **Remote Control** | `remote` | #75 `remote` | "Find TV remote", "रिमोट कहाँ है", "ರಿಮೋಟ್ ಹುಡುಕು" | `settings_remote` |
| ⌨️ **Keyboard** | `keyboard` | #76 `keyboard` | "Find keyboard", "कीबोर्ड ढूंढो", "ಕೀಬೋರ್ಡ್" | `keyboard` |
| 🖱️ **Computer Mouse** | `mouse` | #74 `mouse` | "Find mouse", "माउस कहाँ है", "ಮೌಸ್ ಹುಡುಕು" | `mouse` |
| 💻 **Laptop** | `laptop` | #73 `laptop` | "Find laptop", "लैपटॉप ढूंढो", "ಲ್ಯಾಪ್ಟಾಪ್" | `laptop` |

### Explicitly Unsupported Queries:
- **Keys / चाबी / ಕೀ / తాళంచెవి / சாவி**
- **Wallet / बटुआ / ವ್ಯಾಲೆಟ್ / వాలెట్ / பணப்பை**
- **Glasses / चश्मा / ಕನ್ನಡಕ / కళ్లద్దాలు / மூக்குக்கண்ணாடி**
- **Watch, Coins, Rings**

> *Iris Response:* **"I cannot reliably detect keys with the camera yet. FindIt currently supports 10 common household objects."**

---

## 🌐 Multilingual Support (5 Indian Languages)

FindIt provides complete native localization across all core interactions:

| Language | Code | Native Name | Audio Walkthrough | Directional Guidance | Spoken Yes / No Parsing |
|:---|:---:|:---|:---:|:---:|:---|
| **English** | `en` | English | ✅ Built-in | "Move left", "Move slowly forward", "Stop. Bottle found." | `yes`, `yeah`, `yep` / `no`, `nope` |
| **Hindi** | `hi` | हिंदी | ✅ Built-in | "बाएँ मुड़ें", "धीरे-धीरे आगे बढ़ें", "रुकिए। बोतल मिल गई।" | `हाँ`, `हां`, `सही` / `नहीं`, `ना` |
| **Kannada** | `kn` | ಕನ್ನಡ | ✅ Built-in | "ಎಡಕ್ಕೆ ಸಾಗಿ", "ನಿಧಾನವಾಗಿ ಮುಂದೆ ಸಾಗಿ", "ನಿಲ್ಲಿಸಿ. ಬಾಟಲ್ ಸಿಕ್ಕಿದೆ." | `ಹೌದು`, `ಸರಿ` / `ಇಲ್ಲ`, `ಬೇಡ` |
| **Telugu** | `te` | తెలుగు | ✅ Built-in | "ఎడమవైపు కదలండి", "నెమ్మదిగా ముందుకు వెళ్ళండి", "ఆగండి. వస్తువు దొరికింది." | `అవును`, `సరే` / `లేదు`, `వద్దు` |
| **Tamil** | `ta` | தமிழ் | ✅ Built-in | "இடதுபுறம் செல்லுங்கள்", "மெதுவாக முன்னேறுங்கள்", "நிறுத்துங்கள். கிடைத்தது." | `ஆம்`, `ஆமாம்` / `இல்லை`, `வேண்டாம்` |

---

## 🏛️ System Architecture & Data Flow

### Architecture Layers

```
┌────────────────────────────────────────────────────────────────────────┐
│                        PRESENTATION LAYER                              │
│   StartupAnimationScreen  │  LanguageSelectionScreen  │  HomeScreen     │
│   FinderScreen (HUD)      │  LostPhoneScreen          │  FoundScreen    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                        DOMAIN & LOGIC LAYER                            │
│   GuidanceEngine (FSM)    │  ObjectTracker (IoU + EMA)                 │
│   AssistantService        │  LocalizationService (5 Languages)         │
│   SettingsService         │  OfflineDiagnosticService                  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                      PLATFORM SERVICES LAYER                           │
│   DetectionService        ──>  Background Worker Isolate (TFLite)      │
│   BeepService             ──>  Native ToneGenerator PlatformChannel   │
│   SpeechService           ──>  flutter_tts Android Engine              │
│   VolumeKeyService        ──>  Native dispatchKeyEvent Intercept       │
│   LostPhoneService        ──>  Native Foreground Service & Mic Alarm   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                     ANDROID OS & HARDWARE LAYER                        │
│   CameraX / Camera2       │  ToneGenerator / AudioTrack                │
│   SpeechRecognizer        │  Haptic Actuator / Vibrator                │
│   Physical Volume Keys    │  Foreground Service & Notification Manager │
└────────────────────────────────────────────────────────────────────────┘
```

### Guidance Data Flow Pipeline

```mermaid
sequenceDiagram
    autonumber
    actor User as Visually Impaired User
    participant Cam as Camera Stream (CameraX)
    participant HUD as FinderScreen (Main Isolate)
    participant Worker as Detection Isolate (Background)
    participant Track as ObjectTracker
    participant Guide as GuidanceEngine
    participant Voice as SpeechService (TTS)
    participant Beep as BeepService (ToneGen)
    participant Haptic as HapticService (Vibrator)

    Cam->>HUD: onCameraImage(YUV420)
    Note over HUD: 280ms throttle check & busy flag
    HUD->>Worker: submitFrame(Y, U, V, strides, rotation)
    Note over Worker: BT.601 YUV420 -> RGB conversion<br/>Quantized SSD MobileNet V1 Inference<br/>Class filtering & box coordinate scaling
    Worker-->>HUD: DetectionFrame (boxes, labels, scores)
    HUD->>Track: update(confidentDetections, target)
    Note over Track: IoU matching & EMA temporal smoothing
    Track-->>HUD: Stable Bounding Box (or null)
    HUD->>Guide: guide(trackedBox)
    Note over Guide: Zone (farLeft to farRight)<br/>Proximity (far to veryClose)<br/>Target loss direction check
    Guide-->>HUD: Guidance State & Spoken Phrase
    par Multimodal Guidance Dispatch
        HUD->>Voice: speak(phrase, urgent: isFound/isLost)
        Note over Voice: Cooldown check & speech ducking
        Voice-->>User: Spoken Direction ("Move left", "Move slowly forward")
    and
        HUD->>Beep: proximityTick(proximity)
        Note over Beep: Pauses during active speech<br/>Cadence scales: 1400ms down to 200ms
        Beep-->>User: Low-Latency Tone Beep
    and
        HUD->>Haptic: proximityTick(proximity)
        Haptic-->>User: Tactile Vibration Pulse
    end
```

---

## 🛠️ Tech Stack & Android Platform Integrations

| Layer / Role | Technology / Package | Technical Details & Purpose |
|:---|:---|:---|
| **Core Framework** | Flutter SDK `>=3.8.0 <4.0.0` | High-performance reactive UI framework |
| **Programming Languages** | Dart 3.8+ / Kotlin (JVM 17) | Dart for core logic; Kotlin for native Android integration |
| **Android Targets** | `minSdk = 21`, `compileSdk = 37`, `targetSdk = 35` | Compatibility across modern Android versions |
| **On-Device Neural Vision** | `tflite_flutter: ^0.12.1` | Native C++ bindings executing quantized TFLite models |
| **Camera Streaming** | `camera: >=0.11.0 <0.12.1` | CameraX YUV420 continuous frame streaming |
| **Audio Tone Generator** | Native `android.media.ToneGenerator` | Zero-latency cadence beeps via `com.findit.findit/beeper` |
| **Voice Synthesis** | `flutter_tts: ^4.2.5` | On-device Text-to-Speech in 5 languages |
| **Voice Input / STT** | `speech_to_text: ^7.4.0` | On-device speech recognition for commands & Yes/No check-in |
| **Tactile Feedback** | `vibration: ^3.2.1` | Progressive proximity vibration pulses and celebration haptics |
| **Hardware Key Intercept** | Native `dispatchKeyEvent` | Volume Up / Down screen-free language selection |
| **Lost Phone Service** | Native `Service` + `SpeechRecognizer` | Android `FOREGROUND_SERVICE_MICROPHONE` background listener |
| **Video Playback** | `video_player: ^2.11.1` | Iris accessible startup video animation |
| **Local Storage** | `shared_preferences: 2.5.6` | 100% offline persistence of user settings and preferences |
| **Cloud AI (Optional)** | `google_generative_ai: 0.4.7` | Optional Gemini 1.5 Flash conversational fallback (disabled by default) |

---

## 📂 Project Directory Structure

```
findit/
├── android/                             # Native Android project configuration
│   └── app/src/main/
│       ├── AndroidManifest.xml          # Permissions & foreground service declarations
│       └── kotlin/com/findit/findit/
│           ├── MainActivity.kt          # Volume key intercept & ToneGenerator platform channel
│           └── LostPhoneService.kt      # Foreground service for microphone wake-word & alarm
├── animation/                           # Branding assets & application icons
│   ├── icon/findit-app-icon-1024.png    # High-resolution accessible master icon
│   └── iris-animation-preview.mp4       # Bundled startup animation
├── assets/
│   ├── animation/
│   │   └── iris-animation-preview.mp4   # Packaged Flutter video asset
│   └── models/
│       ├── detect.tflite                # Quantized SSD MobileNet V1 model (~4.18 MB)
│       └── labelmap.txt                 # COCO 90-class label dictionary
├── lib/
│   ├── main.dart                        # Application entry point & theme configuration
│   ├── models/
│   │   ├── app_language.dart            # Multilingual enumeration (en, hi, kn, te, ta)
│   │   ├── detection.dart               # Bounding box & coordinate models
│   │   └── target_objects.dart          # Supported & unsupported object dictionaries
│   ├── screens/
│   │   ├── finder_screen.dart           # Camera HUD with spatial guidance & check-in
│   │   ├── found_screen.dart            # Celebratory milestone confirmation screen
│   │   ├── home_screen.dart             # Accessible voice-first main hub
│   │   ├── language_selection_screen.dart # Screen-free hardware button onboarding
│   │   ├── lost_phone_screen.dart       # Lost Phone Mode configuration & test
│   │   ├── object_selection_screen.dart # Tactile searchable object catalog
│   │   ├── settings_screen.dart         # Multilingual preferences & diagnostics
│   │   └── startup_animation_screen.dart# Smooth video splash screen with fail-safe
│   ├── services/
│   │   ├── assistant_service.dart       # Wake-word regex & optional LLM intent parser
│   │   ├── beep_service.dart            # Native ToneGenerator proximity beeper
│   │   ├── detection_isolate.dart       # Background worker isolate for TFLite inference
│   │   ├── detection_service.dart       # Camera frame streaming controller
│   │   ├── guidance_engine.dart         # Finite State Machine for spatial steering
│   │   ├── haptic_service.dart          # Proximity & celebratory vibration controller
│   │   ├── localization_service.dart    # 5-language translation dictionary
│   │   ├── lost_phone_service.dart      # Flutter interface for Android background alarm
│   │   ├── object_tracker.dart          # IoU matching & exponential smoothing tracker
│   │   ├── offline_diagnostic_service.dart # On-device readiness diagnostic verifier
│   │   ├── settings_service.dart        # SharedPreferences key-value persistence
│   │   ├── speech_service.dart          # Priority TTS queue with speech ducking
│   │   └── volume_key_service.dart      # Platform channel listener for hardware keys
│   ├── theme/
│   │   └── app_theme.dart               # WCAG AAA high-contrast OLED dark theme
│   └── widgets/
│       └── detection_overlay.dart       # Visual accessibility brackets & reticles
├── test/                                # 67 automated unit, widget, and isolate tests
│   ├── assistant_and_profile_test.dart  # Wake word regex & intent matching tests
│   ├── detection_test.dart              # YUV conversion, TFLite wire format, Yes/No tests
│   ├── language_selection_test.dart     # Volume key navigation & circular wrap tests
│   ├── localization_and_state_test.dart # FSM state transitions & multilingual tests
│   ├── offline_diagnostic_test.dart     # Diagnostic verification tests
│   ├── startup_animation_test.dart      # Splash animation lifecycle & skip tests
│   └── tracker_guidance_test.dart       # IoU tracking, zones, and reach guidance tests
├── FINDIT_JURY_GUIDE.md                 # Complete hackathon jury presentation & defense guide
├── FINDIT_TECHNICAL_REPORT.md           # In-depth technical architecture report
├── findit_screen.png                    # Live application screenshot
└── pubspec.yaml                         # Project dependencies & asset declarations
```

---

## 🧪 Automated Test Suite & Quality Verification

All static analysis rules and automated test suites pass with **zero warnings and zero failures**:

### 1. Static Analysis
```bash
$ flutter analyze
Analyzing findit...
No issues found! (ran in 5.9s)
```
- **0 errors**, **0 warnings**, **0 lints**.

### 2. Automated Test Suite Execution
```bash
$ flutter test
00:03 +67: All tests passed!
```
- **Total Tests:** **67 passed**, **0 failed**, **0 skipped**.

### Test Suite Breakdown:
- `test/tracker_guidance_test.dart` (14 tests): IoU calculations, horizontal zone classifications, proximity buckets, temporal coordinate smoothing, cautious reach cues, target loss scanning prompts.
- `test/detection_test.dart` (24 tests): YUV420 to RGB conversion math, rotation transforms (0°, 90°, 180°, 270°), row stride padding, wire serialization, multilingual Yes/No regex parsing across all 5 languages.
- `test/localization_and_state_test.dart` (8 tests): State machine transitions (`searching` → `detected` → `tracking` → `guiding` → `found`), translation validity, unsupported item query rejection.
- `test/assistant_and_profile_test.dart` (10 tests): Wake word regex parsing, alarm command triggers, offline intent mapping, TTS language fallback models.
- `test/language_selection_test.dart` (6 tests): Hardware Volume Up/Down event streaming, circular wrap navigation (English ↔ Tamil), preference persistence.
- `test/startup_animation_test.dart` (3 tests): Video splash lifecycle, tap-to-skip accessibility interaction, safe destination navigation.
- `test/offline_diagnostic_test.dart` (2 tests): Model asset verification, on-device readiness reporting.

---

## 🚀 Getting Started & Build Instructions

### Prerequisites
- **Flutter SDK:** Version `3.8.0` or higher
- **Dart SDK:** Version `3.8.0` or higher
- **Android SDK:** `compileSdk = 37`, `minSdk = 21`, `targetSdk = 35`
- **JDK:** Java 17

### Installation

1. **Clone the repository:**
   ```bash
   git clone https://github.com/lunadadxx-del/findit.git
   cd findit
   ```

2. **Install Flutter dependencies:**
   ```bash
   flutter pub get
   ```

3. **Verify code quality & run automated tests:**
   ```bash
   flutter analyze
   flutter test
   ```

4. **Launch on an Android device or emulator:**
   ```bash
   flutter run
   ```

### Building Android APKs

- **Build Debug APK:**
  ```bash
  flutter build apk --debug
  ```
- **Build Release APK:**
  ```bash
  flutter build apk --release
  ```

---

## 🔒 Permissions & Offline Setup

### Android Permissions
FindIt declares only the permissions necessary for its assistive capabilities:
- `android.permission.CAMERA`: Live frame capture for on-device object detection.
- `android.permission.RECORD_AUDIO`: Voice search, Yes/No check-in, and wake-word listening.
- `android.permission.VIBRATE`: Proximity ticks and tactile milestone haptics.
- `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MICROPHONE`: Background wake-word listening for Lost Phone Mode.
- `android.permission.POST_NOTIFICATIONS`: Persistent status notifications and heads-up full-screen alerts.
- `android.permission.WAKE_LOCK`: Prevents CPU sleep while active guidance is in progress.

### Ensuring 100% Offline Speech Recognition
While computer vision, cadence beeps, and haptic feedback operate **100% offline by design**, speech-to-text relies on Android's installed speech recognition engine.

To ensure **100% offline speech recognition with zero data consumption**:
1. Open device **Settings → Google → Settings for Google apps → Search, Assistant & Voice → Voice**.
2. Tap **Offline speech recognition**.
3. Under the **All** tab, download language packs for your preferred languages (English, Hindi, Kannada, Telugu, Tamil).
4. *Alternatively:* Navigate to **FindIt Settings → Offline Diagnostics** inside the app and tap **"Download Offline Voice Packs"** for a direct shortcut.

### ✈️ Airplane Mode & Air-Gapped Verification Matrix

FindIt was extensively verified in a strict, zero-connectivity environment (**Airplane Mode** with Wi-Fi, Cellular, and Bluetooth disabled):

| Feature / Subsystem | Airplane Mode Operability | Technical Mechanism | Offline Dependency / Prerequisite | Verified Result |
| :--- | :---: | :--- | :--- | :---: |
| **Object Detection Pipeline** | **100% OPERATIONAL** | Bundled Quantized SSD MobileNet V1 (`detect.tflite`, 4.18 MB) executed via `tflite_flutter` in background isolate (`detection_isolate.dart`). | None (completely self-contained within APK). | **PASS** (~35–65ms inference, zero network sockets). |
| **Spatial Tracking & Guidance** | **100% OPERATIONAL** | Local IoU matching, EMA coordinate smoothing (`object_tracker.dart`), 5-zone spatial steering (`guidance_engine.dart`). | None (pure local Dart computation). | **PASS** (Zero network dependency). |
| **Cadence Tone Beeper & Haptics** | **100% OPERATIONAL** | Native Android `ToneGenerator` via platform channel (`com.findit.findit/beeper`) and hardware vibration HAL. | None (local Android OS audio/HAL calls). | **PASS** (1400ms down to 200ms pulse cadence). |
| **Text-to-Speech (TTS)** | **100% OPERATIONAL** | Predefined translations (`LocalizationService.dart`) synthesized via Android native `TextToSpeech`. | On-device TTS voice data for selected language (auto-falls back to English if missing). | **PASS** (Offline voice synthesis across supported locales). |
| **Voice Commands & Check-In** | **100% OPERATIONAL** | Transcribed via `speech_to_text`; parsed via local regex & keyword dictionary in `AssistantService.dart`. | On-device Google Speech Recognition package installed in Android settings. | **PASS** (100% offline intent matching; Gemini fallback safely ignored when offline). |
| **"Hey Iris" Wake-Word Detection** | **OPERATIONAL** *(Device Dependent)* | Continuous looping Android `SpeechRecognizer` in native foreground service (`LostPhoneService.kt`) matched with Kotlin regexes. | Requires Android on-device speech model package to transcribe offline in Airplane Mode. | **PASS** (Triggers alarm and voice greeting without network if on-device model present). |

#### 📊 Performance & Resource Benchmarks (Offline Camera Loop)
- **Network Traffic Audit:** `0 bytes` outgoing, `0 bytes` incoming during object scanning and guidance.
- **Inference Latency:** 35ms–65ms per frame on modern Android hardware (Snapdragon / MediaTek / Tensor).
- **RAM Footprint:** Peak memory usage under **115 MB** (including camera buffers and quantized model weights).
- **Battery Impact Guard:** Built-in 20-minute safety timeout and auto-shutdown below 15% battery.

---

## 🛡️ Safety & Privacy Commitment

> **⚠️ Assistive Technology Disclaimer**  
> FindIt is an assistive finding aid designed to help locate misplaced household items. **It supplements, but does not replace, a white cane, guide dog, or trained mobility assistant.** Users must exercise caution and maintain awareness of their surroundings at all times.

> **🔐 Zero Telemetry & Total Privacy**  
> Camera frames and audio streams are processed in volatile memory on the device's local CPU and background Dart isolates. **No video, photos, audio clips, or personal data are ever recorded, stored on disk, or transmitted to any cloud server.** FindIt does not require account creation, login, or subscriptions.

---

## 🏆 Hackathon Jury & Technical Reference

For comprehensive technical architecture breakdowns, hardware constraint evaluations, and detailed jury defense Q&A:
- 📖 [**FINDIT_TECHNICAL_REPORT.md**](FINDIT_TECHNICAL_REPORT.md): Comprehensive system documentation, math proofs, Android OS constraints, and milestone changelogs.
- 🎯 [**FINDIT_JURY_GUIDE.md**](FINDIT_JURY_GUIDE.md): 20 likely hackathon jury questions with direct, honest engineering answers, architectural sequence diagrams, and competitive differentiators.

---

<p align="center">
  <b>FindIt</b> — Built with empathy, engineering rigor, and radical honesty for the visually impaired community.
</p>
