package com.findit.findit

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class LostPhoneService : Service() {

    companion object {
        private const val TAG = "LostPhoneService"
        const val CHANNEL_ID = "findit_lost_phone_channel"
        const val WAKEUP_CHANNEL_ID = "findit_wakeup_channel"
        const val NOTIFICATION_ID = 9021

        const val ACTION_START = "com.findit.findit.ACTION_START_LOST_PHONE"
        const val ACTION_STOP = "com.findit.findit.ACTION_STOP_LOST_PHONE"
        const val ACTION_STOP_ALARM = "com.findit.findit.ACTION_STOP_ALARM"
        const val ACTION_TEST_ALARM = "com.findit.findit.ACTION_TEST_ALARM"

        const val EXTRA_TIMEOUT_MINUTES = "extra_timeout_minutes"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isAlarmRinging: Boolean = false
            private set

        @Volatile
        var isPausedByApp: Boolean = false
            private set

        @Volatile
        var instanceRef: LostPhoneService? = null
            private set

        var eventListener: LostPhoneListener? = null

        fun pauseListening() {
            instanceRef?.pauseInternal()
        }

        fun resumeListening() {
            instanceRef?.resumeInternal()
        }

        fun getStatusMap(): Map<String, Any> {
            val inst = instanceRef
            val onDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && inst != null) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(inst)
            } else false
            return mapOf(
                "isRunning" to isRunning,
                "isAlarmRinging" to isAlarmRinging,
                "isListening" to (inst?.isRecognizing ?: false),
                "isAwaitingCommand" to (inst?.isAwaitingUserCommand ?: false),
                "isPausedByApp" to isPausedByApp,
                "isOnDeviceRecognitionAvailable" to onDevice,
                "state" to (inst?.getCurrentStateName() ?: if (isRunning) "RUNNING" else "STOPPED")
            )
        }
    }

    interface LostPhoneListener {
        fun onServiceStateChanged(running: Boolean)
        fun onAlarmTriggered(phrase: String)
        fun onAlarmStopped()
        fun onIrisWakeup(phrase: String)
        fun onVoiceCommand(command: String)
        fun onServiceError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var batteryReceiver: BroadcastReceiver? = null

    private var timeoutMinutes: Int = 20
    private var isRecognizing: Boolean = false
    private var consecutiveErrors: Int = 0
    private var fallbackToEnUs: Boolean = false
    private var fallbackToStandardRecognizer: Boolean = false
    private var currentListeningLang: String = "en-US"

    fun getCurrentStateName(): String {
        return when {
            !isRunning -> "STOPPED"
            isAlarmRinging -> "ALARM_RINGING"
            isSpeakingPrompt -> "SPEAKING_PROMPT"
            isPausedByApp -> "PAUSED_BY_APP"
            isAwaitingUserCommand -> "AWAITING_COMMAND"
            isRecognizing -> "LISTENING_HOTWORD"
            else -> "IDLE"
        }
    }

    fun pauseInternal() {
        Log.i(TAG, "[AudioFlow] pauseInternal: Pausing speech recognition for app foreground task.")
        isPausedByApp = true
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacks(commandTimeoutRunnable)
        isAwaitingUserCommand = false
        try {
            speechRecognizer?.cancel()
            isRecognizing = false
        } catch (_: Exception) {}
        eventListener?.onServiceStateChanged(isRunning)
    }

    fun resumeInternal() {
        Log.i(TAG, "[AudioFlow] resumeInternal: Resuming speech recognition.")
        isPausedByApp = false
        mainHandler.removeCallbacks(safetyResumeRunnable)
        if (isRunning && !isSpeakingPrompt && !isAlarmRinging) {
            scheduleRestart(350)
        }
        eventListener?.onServiceStateChanged(isRunning)
    }

    private val safetyResumeRunnable = Runnable {
        Log.i(TAG, "[AudioFlow] Safety watchdog expiring (20s). Checking if listening should resume.")
        if (isRunning && !isAlarmRinging && isPausedByApp) {
            resumeInternal()
        }
    }

    // 1. Alarm Trigger: ONLY when the user explicitly asks where the phone is or to ring the phone
    private val alarmTriggerRegex = Regex(
        """(?i)\bwhere\s+are\s+you\b|""" +
        """(?i)\b(find|ring|locate|where\s+is)\s*(my\s+|the\s+)?phone\b|""" +
        """(?i)\bphone\s*(ring|where)\b|""" +
        """\b(कहाँ\s+हो|कहा\s+हो|फोन\s+कहाँ\s+है|फोन\s+ढूंढो|फोन\s+बजाओ|खोया\s+फोन)\b|""" +
        """\b(ಫೋನ್\s+ಎಲ್ಲಿದೆ|ಎಲ್ಲಿದ್ದೀಯಾ?|ಫೋನ್\s+ರಿಂಗ್\s+ಮಾಡು|ಕಳೆದುಹೋದ\s+ಫೋನ್)\b|""" +
        """\b(ఫోన్\s*ఎక్కడుంది|ఎక్కడున్నావు|ఫోన్\s*రింగ్\s*చేయి|పోయిన\s*ఫోన్)\b|""" +
        """\b(போன்\s*எங்கே|எங்கே\s*இருக்கிறாய்|போன்\s*ரிங்\s*செய்|தொலைந்த\s*போன்)\b"""
    )

    // 2. Assistant Wakeup: "Hey Iris", "Iris", "Hi Iris", "Ok Iris", phonetic matches like "Here it is" / "Here is", "Harris", "Harry", "हे आइरिस", "ಐರಿಸ್", "ఐరిస్", "ஐரிஸ்"
    private val irisWakeupRegex = Regex(
        """(?i)\b(hey|hi|ok|okay|hei|hai|ay|a|here|hay)?\s*(iris|irish|ayres|aires|ayris|airis|eris|irus|iras|ires|aris|iriss|it\s+is)\b|""" +
        """(?i)\bhere\s+is\b|\bharris\b|\bharry\b|\bheiress\b|\bhigh\s*risk\b|""" +
        """(हे|हाय|हेलो\s+)?(आइरिस|आयरिस|आईरिस|इरिस)|(ಹೇ|ಹಾಯ್\s+)?(ಐರಿಸ್)|""" +
        """(హే|ಹಾಯ್\s+)?(ఐరిస్|ఐరీస్)|(ஹே|ஹாய்\s+)?(ஐரிஸ்)"""
    )

    // 3. Stop Alarm: Voice commands to silence ringing alarm
    private val stopAlarmRegex = Regex(
        """(?i)\b(stop(\s+(the|this))?\s*alarm|stop\s*(it|this|please|now)?|alarm\s*stop|silence(\s*alarm)?|quiet|shut\s*up|cancel|mute(\s*alarm)?|turn\s*off(\s+(the|this))?\s*alarm|off(\s+(the|this))?\s*alarm|found(\s*(it|my\s*phone|the\s*phone))?|i\s*found\s*(it|my\s*phone)|got\s*it|i\s*got\s*it|phone\s*found)\b|""" +
        """(?i)\b(hey\s+|hi\s+|ok\s+|okay\s+)?(iris|irish|ayres|aires|eris|here\s+is)\s+(stop|top|talk|quiet|silence|cancel|off|close)\b|""" +
        """\b(अलार्म\s*(बंद\s*(करो|कर\s*दो)?|रोको?)|बंद\s*(करो|कर\s*दो)|फोन\s*मिल\s*गया|मिल\s*गया|चुप\s*(हो\s*जाओ|रहो)?|रुको|आवाज\s*बंद\s*(करो)?)\b|""" +
        """\b(ಅಲಾರಂ\s*ನಿಲ್ಲಿಸು|ನಿಲ್ಲಿಸು|ಆಫ್\s*ಮಾಡು|ಫೋನ್\s*ಸಿಕ್ತು|ಸಾಕು)\b|""" +
        """\b(అలారం\s*ఆపు|ఆపు|సరిపోతుంది|ఫోన్\s*దొరికింది)\b|""" +
        """\b(அலாரத்தை\s*நிறுத்து|நிறுத்து|அமைதி|போன்\s*கிடைத்தது)\b"""
    )

    private var isAwaitingUserCommand: Boolean = false
    private var isSpeakingPrompt: Boolean = false
    private var pendingWakeupPhrase: String? = null
    private var pendingWakeupTimestamp: Long = 0L

    private val partialWakeupDebounceRunnable = Runnable {
        val phrase = pendingWakeupPhrase
        if (phrase != null && isRunning && !isSpeakingPrompt && !isAlarmRinging && !isPausedByApp) {
            Log.i(TAG, "[AudioFlow] Triggering Iris wakeup from sustained partial result: '$phrase'")
            pendingWakeupPhrase = null
            handleIrisWakeup(phrase)
        }
    }

    private val commandTimeoutRunnable = Runnable {
        Log.i(TAG, "[AudioFlow] Command response window timed out (10s) with no input. Speaking feedback.")
        isAwaitingUserCommand = false
        speakTimeoutFeedbackAndResume()
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun isFuzzyIrisWord(word: String): Boolean {
        val w = word.lowercase()
        val known = listOf(
            "iris", "irish", "ayres", "aires", "ayris", "airis", "eris", "irus",
            "iras", "ires", "aris", "iriss", "paris", "parris", "paradis", "pares",
            "harris", "haris", "harry", "ferris", "ayush", "aayush", "arish", "ayrish",
            "heiress", "heirs", "alice", "ellis"
        )
        if (w in known) return true
        if (w.length in 3..6) {
            if (levenshteinDistance(w, "iris") <= 1 ||
                levenshteinDistance(w, "ayris") <= 1 ||
                levenshteinDistance(w, "paris") <= 1) {
                return true
            }
        }
        return false
    }

    private fun isFuzzyIrisBigram(w1: String, w2: String): Boolean {
        val prefixes = listOf("hey", "hi", "hello", "ok", "okay", "here", "where", "there", "ay", "aye", "a", "hai", "hei", "yeah", "yea", "yah", "ya")
        if (w1 in prefixes && isFuzzyIrisWord(w2)) return true
        if ((w1 == "i" || w1 == "hi" || w1 == "high" || w1 == "hey" || w1 == "a") &&
            (w2 in listOf("risk", "wrist", "rest", "res", "race", "rhys", "russ", "reese", "miss", "mrs", "is", "artist"))) return true
        if ((w1 == "here" || w1 == "hair" || w1 == "air") && (w2 == "is" || w2 == "it")) return true
        return false
    }

    private fun matchesIrisWakeWord(clean: String): Boolean {
        val lower = clean.trim().lowercase()
        if (lower.isEmpty()) return false

        // 1. Core regex match
        if (irisWakeupRegex.containsMatchIn(lower)) return true

        // 2. Far-field acoustic phonetic variations:
        // Distant / room reverberated speech is frequently transcribed by Google as:
        // "paris", "paradis", "harris", "here it is", "i risk", "high risk", "i rest", "hi res", "air is", "ayush", etc.
        val farFieldRegex = Regex(
            """(?i)\b(hey|hi|hello|ok|okay|here|where|there|the|a|ay|aye|hai|hei|yeah|yea|yah|ya)?\s*""" +
            """(paris|parris|paradis|pares|harris|harry|haris|ferris|iris|irish|ayres|aires|ayris|airis|eris|irus|iras|ires|aris|iriss|alice|ellis|ayush|aayush|arish|ayrish|virus|heiress|heirs)\b|""" +
            """(?i)\b(here|there|where|yeah|yea|yah)\s+(it\s+)?is\b|""" +
            """(?i)\b(i|hi|hey|high|a)\s+(risk|wrist|rest|res|race|rhys|russ|reese|miss|mrs|is|it\s+is|artist)\b|""" +
            """(?i)\b(hair|air)\s+is\b|""" +
            """(?i)\bparadis\b|\bparis\b|\bparris\b"""
        )
        if (farFieldRegex.containsMatchIn(lower)) return true

        // 3. Multilingual wake words
        val multilingualRegex = Regex(
            """(हे|हाय|हेलो|अरे|सुनो\s+)?(आइरिस|आयरिस|आईरिस|इरिस)|""" +
            """(ಹೇ|ಹಾಯ್|ಕೇಳು\s+)?(ಐರಿಸ್|ಐರೀಸ್)|""" +
            """(హే|హాయ్|వినండి\s+)?(ఐరిస్|ఐరీస్)|""" +
            """(ஹே|ஹாய்|கேளுங்கள்\s+)?(ஐரிஸ்)"""
        )
        if (multilingualRegex.containsMatchIn(clean)) return true

        // 4. Token-level fuzzy phonetic distance check
        val tokens = lower.split(Regex("""[\s,.:;!?\-]+""")).filter { it.isNotEmpty() }
        for (i in tokens.indices) {
            val word = tokens[i]
            if (isFuzzyIrisWord(word)) return true
            if (i < tokens.size - 1) {
                if (isFuzzyIrisBigram(tokens[i], tokens[i + 1])) return true
            }
        }

        return false
    }

    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady: Boolean = false

    private fun initTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    Log.i(TAG, "Native TextToSpeech initialized successfully.")
                    val locale = getPreferredLocale()
                    try {
                        val setLangRes = textToSpeech?.setLanguage(locale)
                        Log.i(TAG, "TTS setLanguage($locale) = $setLangRes")
                    } catch (e: Exception) {
                        Log.w(TAG, "TTS setLanguage error: ${e.message}")
                    }
                    textToSpeech?.setSpeechRate(0.95f)
                    textToSpeech?.setPitch(1.0f)
                    try {
                        val audioAttrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        textToSpeech?.setAudioAttributes(audioAttrs)
                    } catch (_: Exception) {}

                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.i(TAG, "[AudioFlow] TTS onStart: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            Log.i(TAG, "[AudioFlow] TTS onDone: $utteranceId")
                            if (utteranceId == "iris_wakeup_prompt") {
                                // 350ms pause allows speaker acoustic reverberation to clear before mic opens
                                mainHandler.postDelayed({
                                    isSpeakingPrompt = false
                                    isAwaitingUserCommand = true
                                    mainHandler.removeCallbacks(commandTimeoutRunnable)
                                    mainHandler.postDelayed(commandTimeoutRunnable, 10000L)
                                    startSpeechRecognizer()
                                }, 350L)
                            } else if (utteranceId == "iris_timeout_prompt") {
                                mainHandler.postDelayed({
                                    isSpeakingPrompt = false
                                    startSpeechRecognizer()
                                }, 350L)
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            Log.w(TAG, "[AudioFlow] TTS onError: $utteranceId")
                            if (utteranceId == "iris_wakeup_prompt") {
                                mainHandler.postDelayed({
                                    isSpeakingPrompt = false
                                    isAwaitingUserCommand = true
                                    mainHandler.removeCallbacks(commandTimeoutRunnable)
                                    mainHandler.postDelayed(commandTimeoutRunnable, 10000L)
                                    startSpeechRecognizer()
                                }, 350L)
                            } else if (utteranceId == "iris_timeout_prompt") {
                                mainHandler.postDelayed({
                                    isSpeakingPrompt = false
                                    startSpeechRecognizer()
                                }, 350L)
                            }
                        }
                    })
                } else {
                    Log.e(TAG, "Native TTS initialization failed with status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initTextToSpeech error: ${e.message}")
        }
    }

    private fun getPreferredLocale(): Locale {
        try {
            val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val langCode = prefs.getString("flutter.findit_preferred_language", "en") ?: "en"
            return when (langCode.lowercase()) {
                "hi", "hin", "hindi" -> Locale("hi", "IN")
                "kn", "kan", "kannada" -> Locale("kn", "IN")
                "te", "tel", "telugu" -> Locale("te", "IN")
                "ta", "tam", "tamil" -> Locale("ta", "IN")
                else -> Locale.ENGLISH
            }
        } catch (_: Exception) {
            return Locale.ENGLISH
        }
    }

    private fun getHowCanIHelpPrompt(): Pair<String, Locale> {
        val locale = getPreferredLocale()
        val text = when (locale.language) {
            "hi" -> "हाँ, मैं सुन रही हूँ। मैं आपकी क्या मदद करूँ?"
            "kn" -> "ಹೌದು, ನಾನು ಕೇಳಿಸಿಕೊಳ್ಳುತ್ತಿದ್ದೇನೆ. ನಾನು ನಿಮಗೆ ಹೇಗೆ ಸಹಾಯ ಮಾಡಲಿ?"
            "te" -> "చెప్పండి, నేను వింటున్నాను. నేను మీకు ఎలా సహాయం చేయగలను?"
            "ta" -> "சொல்லுங்கள், நான் கேட்கிறேன். நான் உங்களுக்கு எவ்வாறு உதவ முடியும்?"
            else -> "I'm here. How can I help you?"
        }
        return Pair(text, locale)
    }

    private fun speakHowCanIHelpAndListen() {
        val (prompt, locale) = getHowCanIHelpPrompt()
        Log.i(TAG, "Speaking prompt: '$prompt' in locale: $locale")

        // Unmute and elevate media stream volume so blind user clearly hears it from across the room
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            if (currentVol < (maxVol * 0.85).toInt()) {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.85).toInt(), 0)
            }
        } catch (_: Exception) {}

        isSpeakingPrompt = true
        mainHandler.removeCallbacks(restartRunnable)

        if (textToSpeech != null && isTtsReady) {
            try {
                textToSpeech?.language = locale
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }
                val res = textToSpeech?.speak(prompt, TextToSpeech.QUEUE_FLUSH, params, "iris_wakeup_prompt")
                Log.i(TAG, "TTS speak result: $res")
                if (res == TextToSpeech.SUCCESS) {
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS speak failed: ${e.message}")
            }
        }

        // Fallback if TTS engine wasn't ready: pause 1.8s then start listening for reply
        mainHandler.postDelayed({
            isSpeakingPrompt = false
            isAwaitingUserCommand = true
            mainHandler.removeCallbacks(commandTimeoutRunnable)
            mainHandler.postDelayed(commandTimeoutRunnable, 10000L)
            startSpeechRecognizer()
        }, 1800L)
    }

    private fun speakPromptAgain() {
        val locale = getPreferredLocale()
        val text = when (locale.language) {
            "hi" -> "मैं सुन रही हूँ। आपको क्या ढूंढना है?"
            "kn" -> "ನಾನು ಆಲಿಸುತ್ತಿದ್ದೇನೆ. ನೀವು ಏನನ್ನು ಹುಡುಕಬೇಕು?"
            "te" -> "నేను వింటున్నాను. మీరు ఏమి వెతకాలి?"
            "ta" -> "நான் கேட்கிறேன். நீங்கள் எதைத் தேட வேண்டும்?"
            else -> "I'm listening. What would you like to find?"
        }
        Log.i(TAG, "[AudioFlow] Re-prompting user: '$text' in locale: $locale")
        isSpeakingPrompt = true
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacks(commandTimeoutRunnable)
        try {
            speechRecognizer?.cancel()
            isRecognizing = false
        } catch (_: Exception) {}

        if (textToSpeech != null && isTtsReady) {
            try {
                textToSpeech?.language = locale
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }
                val res = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "iris_wakeup_prompt")
                if (res == TextToSpeech.SUCCESS) {
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS speakPromptAgain failed: ${e.message}")
            }
        }

        mainHandler.postDelayed({
            isSpeakingPrompt = false
            isAwaitingUserCommand = true
            mainHandler.removeCallbacks(commandTimeoutRunnable)
            mainHandler.postDelayed(commandTimeoutRunnable, 10000L)
            startSpeechRecognizer()
        }, 1500L)
    }

    private fun speakTimeoutFeedbackAndResume() {
        val locale = getPreferredLocale()
        val text = when (locale.language) {
            "hi" -> "मुझे कोई कमांड नहीं सुनाई दी। फिर से सुन रहा हूँ।"
            "kn" -> "ಯಾವುದೇ ಆದೇಶ ಕೇಳಿಸಲಿಲ್ಲ. ಮತ್ತೆ ಆಲಿಸುತ್ತಿದ್ದೇನೆ."
            "te" -> "ఏ ఆదేశమూ వినపడలేదు. మళ్లీ వింటున్నాను."
            "ta" -> "எந்த கட்டளையும் கேட்கவில்லை. மீண்டும் கேட்கிறேன்."
            else -> "I did not hear a command. Resuming listening."
        }
        Log.i(TAG, "[AudioFlow] Speaking timeout prompt: '$text' in locale: $locale")

        isSpeakingPrompt = true
        mainHandler.removeCallbacks(restartRunnable)

        if (textToSpeech != null && isTtsReady) {
            try {
                textToSpeech?.language = locale
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }
                val res = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "iris_timeout_prompt")
                if (res == TextToSpeech.SUCCESS) {
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "[AudioFlow] TTS timeout speak error: ${e.message}")
            }
        }

        mainHandler.postDelayed({
            isSpeakingPrompt = false
            startSpeechRecognizer()
        }, 1500L)
    }

    private val restartRunnable = Runnable {
        if (isRunning) {
            startSpeechRecognizer()
        }
    }

    private val serviceAutoStopRunnable = Runnable {
        Log.i(TAG, "Lost Phone Mode reached auto-timeout ($timeoutMinutes min) to conserve battery.")
        eventListener?.onServiceError("Lost Phone Mode auto-stopped after $timeoutMinutes min to save battery.")
        stopSelf()
    }

    private val alarmAutoStopRunnable = Runnable {
        Log.i(TAG, "Alarm reached 3-minute auto-silence safety limit.")
        stopAlarm()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerBatteryMonitor()
        initTextToSpeech()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                stopAlarm()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_ALARM -> {
                stopAlarm()
                return START_STICKY
            }
            ACTION_TEST_ALARM -> {
                triggerAlarm("Test Alarm")
                return START_STICKY
            }
            ACTION_START -> {
                timeoutMinutes = intent?.getIntExtra(EXTRA_TIMEOUT_MINUTES, 20)?.coerceIn(5, 60) ?: 20
                startForegroundServiceMode()
                return START_STICKY
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundServiceMode() {
        if (isRunning) {
            return
        }
        isRunning = true
        eventListener?.onServiceStateChanged(true)

        // 1. Acquire partial wake lock to keep CPU active during detection window
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FindIt:LostPhoneWakeLock"
            ).apply {
                acquire(timeoutMinutes * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock acquire failed: ${e.message}")
        }

        // 2. Start Foreground Service with microphone type on Android 10+
        val notification = buildListeningNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
            eventListener?.onServiceError("Could not start foreground microphone service: ${e.message}")
            stopSelf()
            return
        }

        // 3. Schedule auto-timeout to prevent indefinite battery drain
        mainHandler.removeCallbacks(serviceAutoStopRunnable)
        mainHandler.postDelayed(serviceAutoStopRunnable, timeoutMinutes * 60 * 1000L)

        // 4. Start hotword recognition
        startSpeechRecognizer()
    }

    private fun resetSpeechRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isRecognizing = false
    }

    private fun startSpeechRecognizer() {
        mainHandler.post {
            if (!isRunning || isSpeakingPrompt || isPausedByApp) return@post
            try {
                if (speechRecognizer == null) {
                    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                        Log.e(TAG, "Android SpeechRecognizer is unavailable on this device.")
                        eventListener?.onServiceError("Android SpeechRecognizer is unavailable on this device.")
                        return@post
                    }
                    val onDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                    } else false

                    speechRecognizer = if (!fallbackToStandardRecognizer && onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Log.i(TAG, "[AudioFlow] Creating 100% on-device SpeechRecognizer (offline).")
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    } else {
                        Log.i(TAG, "[AudioFlow] Creating SpeechRecognizer with EXTRA_PREFER_OFFLINE.")
                        SpeechRecognizer.createSpeechRecognizer(this)
                    }.apply {
                        setRecognitionListener(speechListener)
                    }
                }

                // Always cancel previous session before starting a new listening pass
                try {
                    speechRecognizer?.cancel()
                } catch (_: Exception) {}

                val locale = getPreferredLocale()
                val baseTag = when (locale.language) {
                    "hi" -> "hi-IN"
                    "kn" -> "kn-IN"
                    "te" -> "te-IN"
                    "ta" -> "ta-IN"
                    else -> "en-US"
                }
                val langTag = if (fallbackToEnUs) "en-US" else baseTag
                currentListeningLang = langTag

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 20)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "en-IN", "hi-IN", "kn-IN"))
                    putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                    if (isAlarmRinging) {
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
                    } else if (isAwaitingUserCommand) {
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    } else {
                        // Far-field idle mode: allow short/quick syllables (100ms) and longer gap (1800ms)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                    }
                }
                isRecognizing = true
                speechRecognizer?.startListening(intent)
                Log.i(TAG, "SpeechRecognizer started listening (lang=$langTag, awaitingReply=$isAwaitingUserCommand, isPaused=$isPausedByApp, offline=true)")
            } catch (e: Exception) {
                Log.e(TAG, "startSpeechRecognizer error: ${e.message}")
                resetSpeechRecognizer()
                scheduleRestart(1000)
            }
        }
    }

    private val speechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isRecognizing = true
            consecutiveErrors = 0
            Log.i(TAG, "Ready for speech (awaitingCommand=$isAwaitingUserCommand, lang=$currentListeningLang)")
        }

        override fun onBeginningOfSpeech() {
            Log.i(TAG, "Beginning of speech detected")
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isRecognizing = false
            Log.i(TAG, "End of speech detected")
            if (!isAwaitingUserCommand && pendingWakeupPhrase != null) {
                mainHandler.removeCallbacks(partialWakeupDebounceRunnable)
                val phrase = pendingWakeupPhrase!!
                pendingWakeupPhrase = null
                Log.i(TAG, "[AudioFlow] onEndOfSpeech triggering Iris wakeup from partial candidate: '$phrase'")
                handleIrisWakeup(phrase)
            }
        }

        override fun onError(error: Int) {
            isRecognizing = false
            Log.w(TAG, "SpeechRecognizer onError: $error (awaitingCommand=$isAwaitingUserCommand, isPaused=$isPausedByApp)")
            if (!isRunning || isSpeakingPrompt || isPausedByApp) return

            // If distant speech timed out or had no match, but we captured the wake word in partial results:
            if (!isAwaitingUserCommand && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                if (pendingWakeupPhrase != null && (System.currentTimeMillis() - pendingWakeupTimestamp) < 3500L) {
                    val phrase = pendingWakeupPhrase!!
                    pendingWakeupPhrase = null
                    mainHandler.removeCallbacks(partialWakeupDebounceRunnable)
                    Log.i(TAG, "[AudioFlow] Recovered Iris wakeup from partial result despite timeout ($error): '$phrase'")
                    handleIrisWakeup(phrase)
                    return
                }
            }
            pendingWakeupPhrase = null
            mainHandler.removeCallbacks(partialWakeupDebounceRunnable)

            // Errors 6 (SPEECH_TIMEOUT) and 7 (NO_MATCH) are expected in silence
            val delayMs = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (isAwaitingUserCommand) 200L else 250L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    consecutiveErrors++
                    resetSpeechRecognizer()
                    500L
                }
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_NETWORK -> {
                    consecutiveErrors++
                    if (consecutiveErrors > 2) {
                        resetSpeechRecognizer()
                        1500L
                    } else {
                        500L
                    }
                }
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, // 13
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> { // 12
                    Log.w(TAG, "SpeechRecognizer language unavailable/not supported ($error) for lang=$currentListeningLang")
                    if (!fallbackToEnUs && currentListeningLang != "en-US") {
                        Log.i(TAG, "Falling back to en-US on-device model.")
                        fallbackToEnUs = true
                        resetSpeechRecognizer()
                        400L
                    } else if (!fallbackToStandardRecognizer) {
                        Log.i(TAG, "Falling back to standard SpeechRecognizer with EXTRA_PREFER_OFFLINE.")
                        fallbackToStandardRecognizer = true
                        resetSpeechRecognizer()
                        400L
                    } else {
                        consecutiveErrors++
                        resetSpeechRecognizer()
                        1500L
                    }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    eventListener?.onServiceError("Microphone permission missing for Lost Phone Mode.")
                    stopSelf()
                    return
                }
                else -> {
                    resetSpeechRecognizer()
                    500L
                }
            }
            scheduleRestart(delayMs)
        }

        override fun onResults(results: Bundle?) {
            isRecognizing = false
            if (!isRunning || isSpeakingPrompt || isPausedByApp) return
            mainHandler.removeCallbacks(partialWakeupDebounceRunnable)
            pendingWakeupPhrase = null
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!checkMatches(matches, isPartial = false)) {
                scheduleRestart(250)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isRunning || isSpeakingPrompt || isPausedByApp) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            checkMatches(matches, isPartial = true)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun stripIrisHotword(phrase: String): String {
        var res = phrase.trim()
        res = res.replace(Regex("""(?i)^\s*(hey|hi|hello|ok|okay|hei|hai|ay|aye|a|yeah|yea|yah|ya|ha|yo|here|where|there|hay)?\s*(iris|irish|ayres|aires|ayris|airis|eris|irus|iras|ires|aris|iriss|paris|parris|paradis|pares|ferris|alice|ellis|ayush|aayush|arish|haris|harris|harry|heiress|virus|it\s+is)\b[:,\s]*"""), "")
        res = res.replace(Regex("""(?i)^\s*(here|there|where|yeah|yea|yah)\s+(it\s+)?is\b[:,\s]*"""), "")
        res = res.replace(Regex("""(?i)^\s*(i|hi|hey|high|a)\s+(risk|wrist|rest|res|race|rhys|russ|reese|miss|mrs|is|artist)\b[:,\s]*"""), "")
        res = res.replace(Regex("""(?i)^\s*(hair|air)\s+is\b[:,\s]*"""), "")
        res = res.replace(Regex("""(?i)^\s*(high\s*risk|hi\s*res|here\s*is)\b[:,\s]*"""), "")
        res = res.replace(Regex("""^\s*(हे|हाय|हेलो|अरे|सुनो\s+)?(आइरिस|आयरिस|आईरिस|इरिस)\b[:,\s]*"""), "")
        res = res.replace(Regex("""^\s*(ಹೇ|ಹಾಯ್|ಕೇಳು\s+)?(ಐರಿಸ್|ಐರೀಸ್)\b[:,\s]*"""), "")
        res = res.replace(Regex("""^\s*(హే|హాయ్|వినండి\s+)?(ఐరిస్|ఐరీస్)\b[:,\s]*"""), "")
        res = res.replace(Regex("""^\s*(ஹே|ஹாய்|கேளுங்கள்\s+)?(ஐரிஸ்)\b[:,\s]*"""), "")

        val lowerRes = res.trim().lowercase()
        if (lowerRes in listOf("it is", "is", "yeah", "hey", "hi", "here", "there", "a", "i", "risk", "paris", "iris")) {
            return ""
        }
        return res.trim()
    }

    private fun isDirectFindingCommand(phrase: String): Boolean {
        val lower = phrase.lowercase()
        // English finding action verbs or question words + object
        val englishPattern = Regex("""(?i)\b(find|search|look\s+for|where\s+is|locate|show\s+me)\b.*\b(bottle|cup|book|bag|backpack|remote|keyboard|mouse|laptop|glasses|wallet|keys?|phone)\b""")
        if (englishPattern.containsMatchIn(lower)) return true

        // Multilingual: finding verbs or object keywords in 5 languages:
        // Hindi: ढूंढो, खोजो, दिखाओ, बताओ, कहाँ है, बोतल, किताब, बैग, रिमोट, कीबोर्ड, माउस, लैपटॉप
        val hindiPattern = Regex("""\b(ढूंढो|खोजो|दिखाओ|बताओ|कहाँ\s+है)\b|बोतल|किताब|बैग|रिमोट|कीबोर्ड|माउस|लैपटॉप""")
        if (hindiPattern.containsMatchIn(phrase)) return true

        // Kannada: ಹುಡುಕು, ತೋರಿಸು, ಎಲ್ಲಿದೆ, ಬಾಟಲ್, ಕಪ್, ಪುಸ್ತಕ, ಬ್ಯಾಗ್, ರಿಮೋಟ್, ಲ್ಯಾಪ್‌ಟಾಪ್
        val kannadaPattern = Regex("""\b(ಹುಡುಕು|ತೋರಿಸು|ಎಲ್ಲಿದೆ)\b|ಬಾಟಲ್|ಪುಸ್ತಕ|ಬ್ಯಾಗ್|ರಿಮೋಟ್|ಲ್ಯಾಪ್‌ಟಾಪ್""")
        if (kannadaPattern.containsMatchIn(phrase)) return true

        // Telugu: వెతుకు, చూపించు, ఎక్కడుంది, బాటిల్, కప్పు, పుస్తకం, బ్యాగ్, రిమోట్, ల్యాప్‌టాప్
        val teluguPattern = Regex("""\b(వెతుకు|చూపించు|ఎక్కడుంది)\b|బాటిల్|ಕప్పు|పుస్తకం|బ్యాగ్|రిమోట్|ల్యాప్‌టాప్""")
        if (teluguPattern.containsMatchIn(phrase)) return true

        // Tamil: கண்டுபிடி, தேடு, காட்டு, எங்கே, பாட்டில், கப், புத்தகம், பை, ரிமோட், லேப்டாப்
        val tamilPattern = Regex("""\b(கண்டுபிடி|தேடு|காட்டு|எங்கே)\b|பாட்டில்|புத்தகம்|ரிமோட்|லேப்டாப்""")
        if (tamilPattern.containsMatchIn(phrase)) return true

        return false
    }

    private fun bringActivityToFront(
        command: String? = null,
        phrase: String? = null,
        fromVoiceCommand: Boolean = false,
        fromIrisWakeup: Boolean = false
    ) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (fromVoiceCommand && command != null) {
                    putExtra("from_voice_command", true)
                    putExtra("command", command)
                }
                if (fromIrisWakeup) {
                    putExtra("from_iris_wakeup", true)
                    if (phrase != null) putExtra("phrase", phrase)
                    if (command != null) putExtra("command", command)
                }
            }
            if (launchIntent != null) {
                val requestCode = if (fromVoiceCommand) 101 else 102
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    requestCode,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val sendOptions = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    sendOptions.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }

                try {
                    pendingIntent.send(this, 0, null, null, null, null, sendOptions.toBundle())
                } catch (e: Exception) {
                    Log.w(TAG, "pendingIntent.send failed: ${e.message}")
                    startActivity(launchIntent, sendOptions.toBundle())
                }

                if (fromVoiceCommand && command != null) {
                    updateFindingNotification(command, pendingIntent)
                } else if (fromIrisWakeup) {
                    updateWakeupNotification(phrase ?: "Hey Iris", pendingIntent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[AudioFlow] Launch activity error: ${e.message}")
        }
    }

    private fun updateWakeupNotification(phrase: String, pendingOpen: PendingIntent) {
        try {
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, WAKEUP_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            builder.setContentTitle("Iris: \"How can I help you?\"")
                .setContentText("Listening for command (e.g. \"Find bottle\"). Tap to open FindIt.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingOpen)
                .setFullScreenIntent(pendingOpen, true)
                .setAutoCancel(true)
                .setOngoing(false)
                .addAction(android.R.drawable.ic_menu_view, "OPEN FINDIT", pendingOpen)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setCategory(Notification.CATEGORY_CALL)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "updateWakeupNotification error: ${e.message}")
        }
    }

    private fun updateFindingNotification(command: String, pendingOpen: PendingIntent) {
        try {
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, WAKEUP_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            builder.setContentTitle("FindIt: Finding ${command.replaceFirstChar { it.uppercase() }}")
                .setContentText("Tap to open camera guidance for $command")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingOpen)
                .setFullScreenIntent(pendingOpen, true)
                .setAutoCancel(true)
                .setOngoing(false)
                .addAction(android.R.drawable.ic_menu_view, "OPEN CAMERA", pendingOpen)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setCategory(Notification.CATEGORY_CALL)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "updateFindingNotification error: ${e.message}")
        }
    }

    private fun dispatchVoiceCommand(command: String) {
        Log.i(TAG, "[AudioFlow] Dispatching voice command: '$command'")
        isSpeakingPrompt = false
        isAwaitingUserCommand = false
        mainHandler.removeCallbacks(commandTimeoutRunnable)

        // Cancel current recognizer pass cleanly before notifying app
        try {
            speechRecognizer?.cancel()
            isRecognizing = false
        } catch (_: Exception) {}

        bringActivityToFront(command = command, fromVoiceCommand = true)
        eventListener?.onVoiceCommand(command)
        scheduleRestart(1500)
    }

    private fun handleIrisWakeup(phrase: String) {
        Log.i(TAG, "[AudioFlow] Iris wakeup detected: '$phrase'")

        val cleanedCommand = stripIrisHotword(phrase)
        val hasDirectCommand = cleanedCommand.isNotEmpty() && cleanedCommand.length >= 3 && !isFuzzyIrisWord(cleanedCommand)

        if (hasDirectCommand) {
            // Check if the command is asking for the phone / alarm trigger
            if (alarmTriggerRegex.containsMatchIn(cleanedCommand)) {
                Log.i(TAG, "[AudioFlow] One-breath alarm trigger: '$cleanedCommand'")
                triggerAlarm(cleanedCommand)
                return
            }
            Log.i(TAG, "[AudioFlow] One-breath direct command with hotword: '$cleanedCommand'")
            dispatchVoiceCommand(cleanedCommand)
        } else {
            // User said "Hey Iris" only -> bring activity to front & speak "How can I help you?" via TTS
            bringActivityToFront(phrase = phrase, fromIrisWakeup = true)

            // Cancel current speech recognizer pass and mark prompt speaking
            isSpeakingPrompt = true
            mainHandler.removeCallbacks(restartRunnable)
            try {
                speechRecognizer?.cancel()
                isRecognizing = false
            } catch (_: Exception) {}

            eventListener?.onIrisWakeup(phrase)
            speakHowCanIHelpAndListen()
        }
    }

    private fun checkMatches(matches: ArrayList<String>?, isPartial: Boolean): Boolean {
        if (matches == null || matches.isEmpty()) return false
        for (phrase in matches) {
            val clean = phrase.trim()
            if (clean.isEmpty()) continue
            Log.i(TAG, "[AudioFlow] Speech candidate (partial=$isPartial, awaitingReply=$isAwaitingUserCommand): '$clean'")

            // 1. Alarm is ringing -> Check for STOP alarm commands (works on partial or final)
            if (isAlarmRinging) {
                if (stopAlarmRegex.containsMatchIn(clean)) {
                    Log.i(TAG, "[AudioFlow] Stop alarm command matched: '$clean'")
                    stopAlarm()
                    return true
                }
                continue
            }

            // 2. Currently awaiting user command (after "I'm here. How can I help you?")
            if (isAwaitingUserCommand) {
                if (alarmTriggerRegex.containsMatchIn(clean)) {
                    isAwaitingUserCommand = false
                    mainHandler.removeCallbacks(commandTimeoutRunnable)
                    Log.i(TAG, "[AudioFlow] Alarm trigger matched in awaiting reply: '$clean'")
                    triggerAlarm(clean)
                    return true
                }

                if (matchesIrisWakeWord(clean)) {
                    val cmd = stripIrisHotword(clean)
                    if (cmd.isEmpty()) {
                        Log.i(TAG, "[AudioFlow] Repeated hotword in awaiting reply: '$clean'")
                        speakPromptAgain()
                        return true
                    } else if (!isPartial) {
                        Log.i(TAG, "[AudioFlow] Voice command with wake word received: '$cmd'")
                        dispatchVoiceCommand(cmd)
                        return true
                    }
                } else if (!isPartial) {
                    val cmd = stripIrisHotword(clean)
                    if (cmd.isNotEmpty()) {
                        Log.i(TAG, "[AudioFlow] Voice command received in awaiting reply: '$cmd'")
                        dispatchVoiceCommand(cmd)
                        return true
                    }
                }
                continue
            }

            // 3. Idle listening mode (hotword listening)
            // 3a. Explicit lost phone / alarm trigger ("Where are you?", "Find my phone", etc.)
            if (alarmTriggerRegex.containsMatchIn(clean)) {
                mainHandler.removeCallbacks(partialWakeupDebounceRunnable)
                pendingWakeupPhrase = null
                Log.i(TAG, "[AudioFlow] Alarm trigger matched: '$clean'")
                triggerAlarm(clean)
                return true
            }

            // 3b. "Hey Iris" or far-field phonetic variant ("Paris", "Paradis", "Irish", "Here it is", etc.)
            if (matchesIrisWakeWord(clean)) {
                val cleanedCommand = stripIrisHotword(clean)
                val hasDirectCommand = cleanedCommand.isNotEmpty() && cleanedCommand.length >= 3 && !isFuzzyIrisWord(cleanedCommand)

                if (hasDirectCommand) {
                    // One-breath wake word + command: wait for final result so command isn't cut off
                    if (!isPartial) {
                        mainHandler.removeCallbacks(partialWakeupDebounceRunnable)
                        pendingWakeupPhrase = null
                        Log.i(TAG, "[AudioFlow] Iris hotword + command detected: '$clean'")
                        handleIrisWakeup(clean)
                        return true
                    }
                } else {
                    // Pure wake word heard: "Hey Iris", "Iris", "Paris", "Paradis", "Here it is"
                    if (!isPartial) {
                        mainHandler.removeCallbacks(partialWakeupDebounceRunnable)
                        pendingWakeupPhrase = null
                        Log.i(TAG, "[AudioFlow] Iris hotword detected in final result: '$clean'")
                        handleIrisWakeup(clean)
                        return true
                    } else {
                        // Partial result: save candidate and start short debounce so speech from distance responds immediately
                        pendingWakeupPhrase = clean
                        pendingWakeupTimestamp = System.currentTimeMillis()
                        mainHandler.removeCallbacks(partialWakeupDebounceRunnable)
                        mainHandler.postDelayed(partialWakeupDebounceRunnable, 450L)
                        Log.i(TAG, "[AudioFlow] Potential Iris wake word recorded from partial: '$clean'")
                    }
                }
            }

            // 3c. Direct finding command without wake-word ("Find my bottle", "बोतल ढूंढो", etc.)
            if (!isPartial && isDirectFindingCommand(clean)) {
                mainHandler.removeCallbacks(partialWakeupDebounceRunnable)
                pendingWakeupPhrase = null
                Log.i(TAG, "[AudioFlow] Direct finding command matched without hotword: '$clean'")
                dispatchVoiceCommand(clean)
                return true
            }
        }
        return false
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.removeCallbacks(restartRunnable)
        if (isRunning && !isSpeakingPrompt && !isPausedByApp) {
            mainHandler.postDelayed(restartRunnable, delayMs)
        }
    }

    private fun triggerAlarm(phrase: String) {
        if (isAlarmRinging) return
        isAlarmRinging = true

        // 1. Stop speech listener briefly for audio initialization
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            isRecognizing = false
        } catch (_: Exception) {}

        // 2. Maximize alarm stream volume
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Volume set error: ${e.message}")
        }

        // 3. Play loud alarm ringtone
        try {
            val alertUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alertUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer error: ${e.message}")
        }

        // 4. Vibrate in heavy SOS cadence
        try {
            val vibrator = getVibrator()
            val pattern = longArrayOf(0, 600, 200, 600, 200, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrator error: ${e.message}")
        }

        // 5. Update notification to high priority alarm banner
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildAlarmNotification(phrase))
        } catch (e: Exception) {
            Log.e(TAG, "Notification update error: ${e.message}")
        }

        // 6. Bring MainActivity to foreground
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("from_lost_phone_alarm", true)
                putExtra("triggered_phrase", phrase)
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Launch activity error: ${e.message}")
        }

        // 7. Notify listeners
        eventListener?.onAlarmTriggered(phrase)

        // 8. 3-minute safety timeout to auto-stop ringing
        mainHandler.removeCallbacks(alarmAutoStopRunnable)
        mainHandler.postDelayed(alarmAutoStopRunnable, 3 * 60 * 1000L)

        // 9. Schedule speech listener to listen for "Stop" command after audio initializes
        scheduleRestart(600)
    }

    fun stopAlarm() {
        if (!isAlarmRinging) return
        isAlarmRinging = false
        mainHandler.removeCallbacks(alarmAutoStopRunnable)

        // Cancel active recognizer pass so "stop" is not interpreted as next hotword
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            isRecognizing = false
        } catch (_: Exception) {}

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}

        try {
            getVibrator()?.cancel()
        } catch (_: Exception) {}

        if (isRunning) {
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, buildListeningNotification())
            } catch (_: Exception) {}
            scheduleRestart(500)
        }

        eventListener?.onAlarmStopped()
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lost Phone Mode",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "FindIt microphone service listening for 'Hey Iris'"
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)

            val wakeupChannel = NotificationChannel(
                WAKEUP_CHANNEL_ID,
                "Iris Wakeup & Guidance",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority alerts and heads-up voice assistant interactions"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(wakeupChannel)
        }
    }

    private fun buildListeningNotification(): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LostPhoneService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("FindIt: Lost Phone Mode Active")
            .setContentText("Listening for \"Hey Iris\" (auto-off in $timeoutMinutes min)...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", pendingStop)

        return builder.build()
    }

    private fun buildAlarmNotification(phrase: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("from_lost_phone_alarm", true)
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val silenceIntent = Intent(this, LostPhoneService::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        val pendingSilence = PendingIntent.getService(
            this,
            2,
            silenceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("🚨 PHONE FOUND! ALARM RINGING 🚨")
            .setContentText("Heard: \"$phrase\". Tap below to silence.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_lock_silent_mode_off, "SILENCE ALARM", pendingSilence)

        return builder.build()
    }

    private fun registerBatteryMonitor() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (Intent.ACTION_BATTERY_LOW == intent?.action) {
                    Log.w(TAG, "Battery low detected (<15%). Auto-stopping Lost Phone Mode.")
                    eventListener?.onServiceError("Lost Phone Mode auto-stopped: Device battery is low (<15%).")
                    stopSelf()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_LOW)
        registerReceiver(batteryReceiver, filter)
    }

    override fun onDestroy() {
        isRunning = false
        isAlarmRinging = false
        isSpeakingPrompt = false
        isAwaitingUserCommand = false
        mainHandler.removeCallbacksAndMessages(null)

        stopAlarm()

        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}

        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            isTtsReady = false
        } catch (_: Exception) {}

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}

        try {
            if (batteryReceiver != null) {
                unregisterReceiver(batteryReceiver)
                batteryReceiver = null
            }
        } catch (_: Exception) {}

        if (instanceRef == this) {
            instanceRef = null
        }
        isPausedByApp = false

        eventListener?.onServiceStateChanged(false)
        super.onDestroy()
    }
}
