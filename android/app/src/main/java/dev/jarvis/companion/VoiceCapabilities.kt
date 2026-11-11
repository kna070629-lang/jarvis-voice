package dev.jarvis.companion

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Finds out what the device can actually do.
 *
 * The voice stack differs a lot between phones. A recogniser may be missing,
 * the on-device model may not be installed, and a language may be listed but
 * have no model behind it. Everything here asks the platform rather than
 * guessing, so the diagnostics screen can tell the truth.
 */
object VoiceCapabilities {

    /** The languages JARVIS can work in. */
    data class Language(
        val tag: String,
        val label: String,
        val native: String,
    ) {
        val locale: java.util.Locale get() = java.util.Locale.forLanguageTag(tag)
    }

    /** The languages offered to the user. */
    val LANGUAGES: List<Language> = listOf(
        Language("en-US", "English (United States)", "English (US)"),
        Language("en-IN", "English (India)", "English (India)"),
        Language("en-GB", "English (United Kingdom)", "English (UK)"),
        Language("ta-IN", "Tamil (India)", "தமிழ்"),
        Language("ta-LK", "Tamil (Sri Lanka)", "தமிழ்"),
        Language("hi-IN", "Hindi (India)", "हिन्दी"),
    )

    /** The result of asking the platform what it supports. */
    data class Report(
        val recogniserAvailable: Boolean,
        val recogniserPackage: String,
        val onDeviceAvailable: Boolean,
        val ttsAvailable: Boolean,
        val ttsEngine: String,
        val ttsVoiceCount: Int,
        val microphonePresent: Boolean,
        val notes: List<String> = emptyList(),
    )

    /** The recognition service that is actually installed. */
    fun recogniserPackage(context: Context): String {
        val manager = context.packageManager
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = try {
            // No filter, for the same reason as the application discovery.
            manager.queryIntentServices(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        return services.firstOrNull()?.serviceInfo?.packageName.orEmpty()
    }

    /**
     * Ask the recogniser which languages really have a model behind them.
     *
     * The check is asynchronous, so the answer arrives through the callback.
     */
    fun checkLanguageSupport(
        context: Context,
        onResult: (supported: List<String>, pending: List<String>) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult(emptyList(), emptyList())
            return
        }
        val engine = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (_: Exception) {
            null
        }
        if (engine == null) {
            onResult(emptyList(), emptyList())
            return
        }
        val executor: Executor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
        try {
            engine.checkRecognitionSupport(
                Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                executor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        val supported = try {
                            recognitionSupport.supportedOnDeviceLanguages.orEmpty()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        val pending = try {
                            recognitionSupport.pendingOnDeviceLanguages.orEmpty()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        onResult(supported, pending)
                        try {
                            engine.destroy()
                        } catch (_: Exception) {
                        }
                    }

                    override fun onError(error: Int) {
                        onResult(emptyList(), emptyList())
                        try {
                            engine.destroy()
                        } catch (_: Exception) {
                        }
                    }
                },
            )
        } catch (_: Exception) {
            onResult(emptyList(), emptyList())
            try {
                engine.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /** Everything the diagnostics screen shows, gathered from the platform. */
    fun report(context: Context, tts: TextToSpeech? = null): Report {
        val notes = mutableListOf<String>()
        val recogniser = recogniserPackage(context)
        val recogniserAvailable = recogniser.isNotEmpty()
        if (!recogniserAvailable) {
            notes.add("No speech recognition service is installed on this device.")
        }

        val onDevice = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } catch (_: Exception) {
            false
        }
        if (!onDevice && recogniserAvailable) {
            notes.add("On-device recognition is not available, so recognition needs the network.")
        }

        var ttsEngine = ""
        var ttsVoices = 0
        var ttsAvailable = false
        if (tts != null) {
            try {
                val engines = tts.engines
                ttsAvailable = engines != null && engines.isNotEmpty()
                ttsEngine = tts.defaultEngine.orEmpty()
                ttsVoices = try {
                    tts.voices?.size ?: 0
                } catch (_: Exception) {
                    0
                }
            } catch (_: Exception) {
                ttsAvailable = false
            }
        }
        if (!ttsAvailable) {
            notes.add("No text to speech engine is installed.")
        }

        return Report(
            recogniserAvailable = recogniserAvailable,
            recogniserPackage = recogniser,
            onDeviceAvailable = onDevice,
            ttsAvailable = ttsAvailable,
            ttsEngine = ttsEngine,
            ttsVoiceCount = ttsVoices,
            microphonePresent = context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_MICROPHONE,
            ),
            notes = notes,
        )
    }

    /** The voices the installed engine offers. */
    fun voices(tts: TextToSpeech?): List<android.speech.tts.Voice> {
        if (tts == null) return emptyList()
        return try {
            tts.voices?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
