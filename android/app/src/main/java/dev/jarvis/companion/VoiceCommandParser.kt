package dev.jarvis.companion

/** Deterministic local actions; AI output is never parsed or executed as an action. */
object VoiceCommandParser {
    data class Action(val kind: String, val value: String = "", val message: String = "")

    private val openPattern = Regex("^\\s*open\\s+(.+?)\\s*[.!?]*\\s*$", RegexOption.IGNORE_CASE)
    private val whatsappPattern = Regex(
        "^\\s*(?:prepare|send)\\s+(?:a\\s+)?whatsapp\\s+message\\s+to\\s+([+0-9][+0-9 ().-]{4,24})\\s+(?:saying|that\\s+says)\\s+(.+?)\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val callPattern = Regex(
        "^\\s*(?:call|dial)\\s+([+0-9][+0-9 ().-]{4,24})\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val smsPattern = Regex(
        "^\\s*(?:prepare|draft|send)\\s+(?:an?\\s+)?(?:sms|text(?:\\s+message)?)\\s+to\\s+([+0-9][+0-9 ().-]{4,24})\\s+(?:saying|that\\s+says)\\s+(.+?)\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): Action? {
        whatsappPattern.matchEntire(text)?.let { match ->
            val digits = match.groupValues[1].filter(Char::isDigit)
            val body = match.groupValues[2].trim()
            if (digits.length in 7..15 && body.isNotBlank()) {
                return Action("whatsapp_draft", digits, body)
            }
        }
        callPattern.matchEntire(text)?.let { match ->
            val number = match.groupValues[1].filter(Char::isDigit)
            if (number.length in 7..15) return Action("call_confirmation", number)
        }
        smsPattern.matchEntire(text)?.let { match ->
            val number = match.groupValues[1].filter(Char::isDigit)
            val body = match.groupValues[2].trim()
            if (number.length in 7..15 && body.isNotBlank()) return Action("sms_draft", number, body)
        }
        openPattern.matchEntire(text)?.let { match ->
            val app = match.groupValues[1].trim().lowercase().trimEnd('.', '!', '?')
            if (app.isNotBlank() && app.length <= 80) return Action("open_app", app)
        }
        return null
    }
}
