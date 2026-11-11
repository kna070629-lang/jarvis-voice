package dev.jarvis.companion

/**
 * What JARVIS says before an action happens.
 *
 * The assistant announces the action first and only then performs it, so the
 * user hears what is about to happen before the phone starts ringing. A
 * contact is announced by name and never by the whole number, which is what
 * makes it sound natural rather than like a machine reading a string.
 */
object ActionAnnouncer {

    fun announce(request: ActionRequest, resolvedName: String? = null): String? {
        return when (request.type) {
            "call" -> {
                val number = request.args["number"].orEmpty().trim()
                if (number.isBlank()) null else "Calling $number."
            }
            "call_contact" -> {
                val spoken = request.args["name"].orEmpty().trim()
                if (spoken.isBlank()) {
                    null
                } else {
                    // The resolved name is used, so an alias such as "home" is
                    // announced as the person it points at.
                    "Calling ${resolvedName ?: spoken}."
                }
            }
            "open_app" -> {
                val name = request.args["name"].orEmpty().trim()
                if (name.isBlank()) null else "Opening $name."
            }
            "open_package" -> "Opening ${request.args["package"].orEmpty().trim()}."
            "open_dialer" -> "Opening the dialer."
            "open_settings" -> "Opening settings."
            "toggle_flashlight" -> {
                val state = request.args["state"].orEmpty().lowercase()
                if (state == "on") "Turning the flashlight on." else "Turning the flashlight off."
            }
            "set_volume" -> "Changing the volume."
            "media_control" -> "Playing."
            "set_timer" -> "Setting the timer."
            "set_alarm" -> "Setting the alarm."
            "prepare_whatsapp", "prepare_sms" -> {
                val target = request.args["name"]?.takeIf { it.isNotBlank() }
                    ?: request.args["number"].orEmpty().trim()
                if (target.isBlank()) null else "Preparing the message for $target."
            }
            "open_chat" -> {
                val target = request.args["name"]?.takeIf { it.isNotBlank() }
                    ?: request.args["number"].orEmpty().trim()
                if (target.isBlank()) null else "Opening the chat with $target."
            }
            else -> null
        }
    }
}
