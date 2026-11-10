package dev.jarvis.companion

import java.util.Locale

/** English and Hinglish wake variants. No hidden listening state lives here. */
object WakePhrase {
    const val LISTENING_REPLY = "Yes, I'm listening"

    private val patterns = listOf(
        Regex("\\b(?:hey|hello|ok|okay|arey|arre)\\s+jarvis\\b", RegexOption.IGNORE_CASE),
        Regex("\\bjarvis\\s+(?:activate|wake|start)\\b", RegexOption.IGNORE_CASE),
        Regex("^\\s*jarvis\\s*[.!?]*\\s*$", RegexOption.IGNORE_CASE),
    )

    fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()

    fun matches(text: String): Boolean {
        val value = normalize(text)
        if (value.isBlank()) return false
        return patterns.any { it.containsMatchIn(value) }
    }

    /** Text spoken after the wake phrase, or null when only the wake phrase was said. */
    fun trailingCommand(text: String): String? {
        val original = text.trim()
        val greeting = Regex(
            "^\\s*(?:hey|hello|ok|okay|arey|arre)\\s+jarvis\\b\\s*,?\\s*",
            RegexOption.IGNORE_CASE,
        ).replaceFirst(original, "")
        val direct = if (greeting != original) greeting else Regex(
            "^\\s*jarvis\\b\\s*,?\\s*",
            RegexOption.IGNORE_CASE,
        ).replaceFirst(original, "")
        val remainder = direct.trim().trim('.', '!', '?', ',')
        if (remainder.isBlank()) return null
        val value = normalize(remainder)
        if (value in setOf("activate", "wake", "wake up", "start", "listen", "are you there")) return null
        return remainder
    }

    fun isStopCommand(text: String): Boolean {
        val value = normalize(text)
        return value in setOf("stop listening", "stop jarvis", "jarvis stop", "disable jarvis", "stop")
    }

    private val confirmPatterns = listOf(
        Regex("\\b(?:yes|yeah|yep|yup|confirm|confirmed|go ahead|proceed|do it|sure|please do)\\b"),
        Regex("^\\s*(?:ok|okay)\\s*$"),
    )

    private val declinePatterns = listOf(
        Regex("\\b(?:no|nope|cancel|never mind|nevermind|dont|do not|abort)\\b"),
    )

    /** True when the user agreed to carry out a sensitive action. */
    fun isConfirmation(text: String): Boolean {
        val value = normalize(text)
        if (value.isBlank()) return false
        return confirmPatterns.any { it.containsMatchIn(value) }
    }

    /** True when the user refused a sensitive action. */
    fun isDecline(text: String): Boolean {
        val value = normalize(text)
        if (value.isBlank()) return false
        return declinePatterns.any { it.containsMatchIn(value) }
    }
}
