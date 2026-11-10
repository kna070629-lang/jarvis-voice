package dev.jarvis.companion

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Shared TTS configuration used by both the activity and the wake service. */
object VoicePreferences {
    const val PREFS = "jarvis_voice_settings"
    const val DEFAULT_RATE = 0.95f
    const val DEFAULT_PITCH = 0.85f
    const val MASCULINE = "masculine tag"
    const val KEY_CUSTOM_VOICE = "custom_voice_uri"

    data class Applied(
        val selectedName: String?,
        val masculine: Boolean,
        val anyVoiceInstalled: Boolean,
        val languageSupported: Boolean,
    )

    fun apply(tts: TextToSpeech, context: Context): Applied {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString("language_tag", "en-US") ?: "en-US"
        val locale = Locale.forLanguageTag(tag)
        val languageResult = tts.setLanguage(locale)
        val languageSupported = languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        val voices = try {
            tts.voices?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val matching = voices.filter { it.locale.language.equals(locale.language, ignoreCase = true) }
        val savedName = prefs.getString("voice_name", null)
        val saved = matching.firstOrNull { it.name == savedName }
        val masculine = matching.firstOrNull { isMasculine(it) }
        val chosen = saved ?: masculine ?: matching.firstOrNull()
        if (chosen != null) {
            if (tts.setVoice(chosen) == TextToSpeech.ERROR) tts.setLanguage(locale)
        }
        tts.setSpeechRate(prefs.getFloat("speech_rate", DEFAULT_RATE).coerceIn(0.5f, 1.5f))
        tts.setPitch(prefs.getFloat("speech_pitch", DEFAULT_PITCH).coerceIn(0.5f, 1.5f))
        return Applied(chosen?.name, chosen != null && isMasculine(chosen), matching.isNotEmpty(), languageSupported)
    }

    fun isMasculine(voice: Voice): Boolean {
        val text = try {
            voice.name + " " + voice.features.joinToString(" ")
        } catch (_: Exception) {
            voice.name
        }
        val words = text.lowercase(Locale.ROOT).split(Regex("[^a-z]+")).filter { it.isNotBlank() }.toSet()
        if (words.any { it in setOf("female", "feminine", "woman", "women", "girl") }) return false
        return words.any { it in setOf("male", "masculine", "man", "men", "deep", "low") }
    }

    fun maleInstallHint(): String =
        "No masculine-tagged voice is installed for this language. JARVIS uses a lower pitch and slower rate as a fallback. " +
            "To install voice data: Settings > Text-to-speech output > Google engine > Install voice data."

    /**
     * Custom voice recording selected by the user. When present JARVIS plays this
     * recording instead of the Android text-to-speech voice.
     */
    fun customVoiceUri(context: Context): Uri? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_VOICE, null)
        if (stored.isNullOrBlank()) return null
        return try {
            Uri.parse(stored)
        } catch (_: Exception) {
            null
        }
    }

    fun setCustomVoiceUri(context: Context, uri: Uri?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (uri == null) {
            editor.remove(KEY_CUSTOM_VOICE)
        } else {
            editor.putString(KEY_CUSTOM_VOICE, uri.toString())
        }
        editor.apply()
    }

    fun customVoiceLabel(context: Context): String {
        val uri = customVoiceUri(context) ?: return "No custom voice file"
        val name = displayName(context, uri)
        return name ?: uri.lastPathSegment ?: uri.toString()
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}
