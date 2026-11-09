package dev.jarvis.companion

/** Deterministic local actions; AI output is never parsed or executed as an action. */
object VoiceCommandParser {
    data class Action(val kind: String, val value: String = "", val message: String = "")

    private val openPattern = Regex("^\\s*open\\s+(.+?)\\s*[.!?]*\\s*$", RegexOption.IGNORE_CASE)
    private val whatsappPattern = Regex(
        "^\\s*(?:prepare|send)\\s+(?:a\\s+)?whatsapp\\s+message\\s+to\\s+([+0-9][+0-9 ().-]{4,24})\\s+(?:saying|that\\s+says)\\s+(.+?)\\s*[.!?]*\\s*$",
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
        openPattern.matchEntire(text)?.let { match ->
            val app = match.groupValues[1].trim().lowercase().trimEnd('.', '!', '?')
            if (app.isNotBlank() && app.length <= 80) return Action("open_app", app)
        }
        return null
    }
}
