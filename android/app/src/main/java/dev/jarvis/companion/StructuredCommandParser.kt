package dev.jarvis.companion

import java.util.Locale

/** Deterministic command interpretation. AI output is never parsed as an action. */
object StructuredCommandParser {
    data class Parsed(
        val type: String,
        val args: Map<String, String> = emptyMap(),
        val requiresConfirmation: Boolean = false,
        val sensitive: Boolean = false,
    )

    private val wakePrefix = Regex(
        "^\\s*(?:hey\\s+|ok(?:ay)?\\s+|hello\\s+|arey\\s+|arre\\s+)?jarvis\\s*,?\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val openPattern = Regex(
        "^\\s*(?:open|launch|start|activate|run)\\s+(.+?)\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val appStopwords = setOf(
        "the", "a", "an", "my", "your", "our", "their", "his", "her", "this",
        "that", "these", "those", "is", "are", "was", "were", "be", "been",
        "being", "am", "do", "does", "did", "can", "could", "should", "would",
        "will", "shall", "may", "might", "must", "have", "has", "had", "to",
        "of", "in", "on", "at", "by", "for", "with", "from", "about", "into",
        "over", "under", "and", "or", "but", "if", "then", "than", "so",
        "because", "while", "when", "where", "what", "which", "who", "whom",
        "whose", "why", "how", "quick", "quickly", "meeting", "notes",
        "calculation", "source", "important", "file", "files", "folder",
        "document", "documents", "website", "site", "link", "terminal", "shell",
        "script", "code", "project", "app", "apps",
    )

    private fun plausibleAppName(name: String): Boolean {
        val words = name.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size in 1..3 && name.length <= 60 && words.none { it in appStopwords }
    }

    private val appPrefix = setOf("the", "a", "an", "my", "our", "up")
    private val appSuffix = setOf("please", "now", "app", "apps", "application", "for", "me")
    private val whatsappAliases = setOf("whatsapp", "whatsappbusiness", "whatsappmessenger")
    private val appAliases = mapOf(
        "whatsapp" to "whatsapp",
        "whatsappbusiness" to "whatsapp business",
        "whatsappmessenger" to "whatsapp",
        "instagram" to "instagram",
        "insta" to "instagram",
        "youtube" to "youtube",
        "playstore" to "play store",
    )

    /** Normalize spoken app names: filler words, possessives, common ASR splits. */
    private fun cleanAppName(value: String): String {
        val name = value.trim()
            .replace(Regex("['\u2019]s\\b", RegexOption.IGNORE_CASE), "s")
            .replace(Regex("['\u2019]"), "")
        val words = name.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        while (words.isNotEmpty() && words.first().lowercase(Locale.ROOT) in appPrefix) words.removeAt(0)
        var compact = words.joinToString("").lowercase(Locale.ROOT)
        if (compact in whatsappAliases) return withOriginalCasing(words, "whatsapp")
        appAliases[compact]?.let { return withOriginalCasing(words, it) }
        while (words.isNotEmpty() && words.last().lowercase(Locale.ROOT) in appSuffix) words.removeAt(words.lastIndex)
        val cleaned = words.joinToString(" ")
        compact = cleaned.replace(" ", "").lowercase(Locale.ROOT)
        if (compact in whatsappAliases) return withOriginalCasing(words, "whatsapp")
        return appAliases[compact]?.let { withOriginalCasing(words, it) } ?: cleaned
    }

    /**
     * Keeps the casing the user spoke when normalization only rewrote the same words.
     * "WhatsApp" stays "WhatsApp" while "insta" still expands to "instagram".
     */
    private fun withOriginalCasing(words: List<String>, canonical: String): String {
        val canonicalWords = canonical.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (canonicalWords.size != words.size) return canonical
        val rebuilt = words.mapIndexed { index, word ->
            if (word.lowercase(Locale.ROOT) == canonicalWords[index].lowercase(Locale.ROOT)) word else canonicalWords[index]
        }
        return rebuilt.joinToString(" ")
    }
    private val whatsappPattern = Regex(
        "^\\s*(?:prepare|draft|send)\\s+(?:a\\s+)?whatsapp\\s+message\\s+to\\s+([+0-9][+0-9 ().-]{4,24})\\s+(?:saying|that\\s+says)\\s+(.+?)\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val callPattern = Regex(
        "^\\s*(?:call|phone)\\s+([+0-9][+0-9 ().-]{4,24})\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val smsPattern = Regex(
        "^\\s*(?:prepare|draft|send)\\s+(?:an?\\s+)?(?:sms|text(?:\\s+message)?)\\s+to\\s+([+0-9][+0-9 ().-]{4,24})\\s+(?:saying|that\\s+says)\\s+(.+?)\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val dialPattern = Regex(
        "^\\s*(?:dial|open\\s+(?:the\\s+)?dialer)\\s*([+0-9][+0-9 ().-]{4,24})?\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val alarmPattern = Regex(
        "^\\s*(?:set|create)\\s+(?:an?\\s+)?alarm(?:\\s+for)?\\s+(.+?)\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val notificationPattern = Regex(
        "^\\s*(?:read|show|check|summari[sz]e)\\s+(?:my\\s+|the\\s+)?notifications?\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val navigationPattern = Regex(
        "^\\s*(?:(?:go|navigate|take\\s+me|show|open)(?:\\s+to)?\\s+)?(back|home|recents?|recent\\s+apps?)\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE,
    )

    fun stripWake(text: String): String {
        val original = text.trim()
        val stripped = wakePrefix.replaceFirst(original, "").trim()
        return stripped.ifBlank { original }
    }

    fun parse(raw: String): Parsed? {
        val text = stripWake(raw)
        if (text.isBlank() || text.length > 500) return null
        val lowered = text.lowercase().trim()

        if (notificationPattern.matches(text)) {
            return Parsed("read_notifications", requiresConfirmation = true, sensitive = true)
        }

        navigationPattern.matchEntire(text)?.let { match ->
            val target = match.groupValues[1].lowercase().trim()
            val navigation = if (target.startsWith("recent")) "recents" else target
            return Parsed("accessibility", mapOf("navigation" to navigation))
        }

        whatsappPattern.matchEntire(text)?.let { match ->
            val digits = match.groupValues[1].filter(Char::isDigit)
            val body = match.groupValues[2].trim().take(240)
            if (digits.length in 7..15 && body.isNotBlank()) {
                return Parsed(
                    "prepare_whatsapp",
                    mapOf("number" to digits, "text" to body),
                    requiresConfirmation = true,
                    sensitive = true,
                )
            }
            return null
        }

        smsPattern.matchEntire(text)?.let { match ->
            val digits = match.groupValues[1].filter(Char::isDigit)
            val body = match.groupValues[2].trim().take(240)
            if (digits.length in 7..15 && body.isNotBlank()) {
                return Parsed(
                    "prepare_sms",
                    mapOf("number" to digits, "text" to body),
                    requiresConfirmation = true,
                    sensitive = true,
                )
            }
            return null
        }

        callPattern.matchEntire(text)?.let { match ->
            val number = match.groupValues[1].filter(Char::isDigit)
            if (number.length in 7..15) {
                return Parsed(
                    "call",
                    mapOf("number" to number),
                    requiresConfirmation = true,
                    sensitive = true,
                )
            }
            return null
        }

        dialPattern.matchEntire(text)?.let { match ->
            val number = match.groupValues[1].filter(Char::isDigit)
            return if (number.length in 7..15) {
                Parsed("open_dialer", mapOf("number" to number))
            } else {
                Parsed("open_dialer")
            }
        }

        alarmPattern.matchEntire(text)?.let { match ->
            val time = match.groupValues[1].trim().take(40)
            if (time.isNotBlank()) return Parsed("set_alarm", mapOf("time" to time))
            return null
        }

        if (lowered in setOf("settings", "android settings", "system settings")) {
            return Parsed("open_settings")
        }

        openPattern.matchEntire(text)?.let { match ->
            val name = cleanAppName(match.groupValues[1].trim().take(80))
            if (name.isBlank() || !plausibleAppName(name)) return null
            return when (name.lowercase(Locale.ROOT)) {
                "settings", "android settings", "system settings" -> Parsed("open_settings")
                "dialer", "phone dialer" -> Parsed("open_dialer")
                else -> Parsed("open_app", mapOf("name" to name))
            }
        }
        return null
    }
}
