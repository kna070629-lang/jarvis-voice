package dev.jarvis.companion

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import java.util.Locale

/**
 * Validates and executes structured phone actions. Every branch reports a
 * specific result; success is only returned after Android accepted the intent.
 */
class ActionDispatcher(private val context: Context) {
    private val resolver = AppResolver(context)
    private val contacts = ContactResolver(context)
    private val ledger = ActionLedger()

    /** Whether the flashlight is currently lit, so it can be toggled. */
    private var flashlightOn = false

    fun refreshApps() = resolver.refresh()

    /** Apps installed on this device, reported to the backend so it can plan actions. */
    fun launchableApps(): List<AppMatch> = resolver.launchableApps()

    fun execute(request: ActionRequest, confirmationToken: String? = null): ActionResult {
        if (!validRequest(request)) {
            return ActionResult(request.id, ActionStatus.FAILED, "That action request was not valid.")
        }
        if (!ledger.begin(request.id)) {
            return ActionResult(request.id, ActionStatus.DUPLICATE, "That action was already handled.")
        }
        if (request.requiresConfirmation && !ledger.consumeConfirmation(request.id, confirmationToken)) {
            val token = ledger.awaitConfirmation(request.id)
            return ActionResult(
                request.id,
                ActionStatus.NEEDS_CONFIRMATION,
                "Confirmation required before this action is executed.",
                confirmationToken = token,
            )
        }
        if (request.type in ACTIVITY_ACTIONS && !canLaunchFromBackground()) {
            ledger.complete(request.id)
            return ActionResult(
                request.id,
                ActionStatus.PERMISSION_REQUIRED,
                "Permission needed: allow Display over other apps so wake mode can launch it.",
            )
        }
        val result = try {
            dispatch(request)
        } catch (_: SecurityException) {
            ActionResult(request.id, ActionStatus.PERMISSION_REQUIRED, "Permission needed for that action.")
        } catch (_: Exception) {
            ActionResult(request.id, ActionStatus.FAILED, "Android could not complete that action.")
        }
        ledger.complete(request.id)
        return result
    }

    private fun validRequest(request: ActionRequest): Boolean {
        if (request.type.length !in 3..40 || !Regex("^[a-z_]+$").matches(request.type)) return false
        if (request.args.size > 8) return false
        return request.args.all { (key, value) ->
            key.length in 1..32 && !key.contains(Regex("[^a-z_]")) && value.length <= 240
        }
    }

    private fun dispatch(request: ActionRequest): ActionResult {
        return when (request.type) {
            "open_app" -> openApp(request)
            "open_package" -> openPackage(request)
            "open_settings" -> if (safeStart(Intent(Settings.ACTION_SETTINGS))) {
                success(request, "Settings opened.")
            } else {
                failure(request, "Android did not open Settings.")
            }
            "set_alarm" -> setAlarm(request)
            "open_dialer" -> openDialer(request)
            "call" -> placeCall(request)
            "prepare_sms" -> prepareSms(request)
            "prepare_whatsapp" -> prepareWhatsApp(request)
            "read_notifications" -> readNotifications(request)
            "accessibility" -> accessibility(request)
            "call_contact" -> callContact(request)
            "open_chat" -> openChat(request)
            "click_text" -> clickText(request)
            "toggle_flashlight" -> toggleFlashlight(request)
            "set_volume" -> setVolume(request)
            "media_control" -> mediaControl(request)
            "set_timer" -> setTimer(request)
            "set_calendar" -> setCalendar(request)
            else -> failure(request, "Unsupported Android action: ${request.type}.")
        }
    }

    private fun canLaunchFromBackground(): Boolean {
        if (context is Activity) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return Settings.canDrawOverlays(context)
    }

    private fun openApp(request: ActionRequest): ActionResult {
        val name = request.args["name"].orEmpty().trim()
        if (name.isBlank()) return failure(request, "Which app should I open?")
        return when (val resolution = resolver.resolve(name)) {
            is AppResolution.Found -> {
                if (launchPackage(resolution.app.packageName)) {
                    success(request, "Opening ${resolution.app.label}.")
                } else {
                    failure(request, "App not installed: $name.")
                }
            }
            is AppResolution.Ambiguous -> ActionResult(
                request.id,
                ActionStatus.AMBIGUOUS,
                "I found more than one app matching $name. Which one?",
                options = resolution.options,
            )
            AppResolution.NotFound -> failure(request, "App not installed: $name.")
        }
    }

    private fun openPackage(request: ActionRequest): ActionResult {
        val packageName = request.args["package"].orEmpty().trim()
        if (packageName.isBlank()) return failure(request, "No app package was selected.")
        if (!launchPackage(packageName)) return failure(request, "That app could not be opened.")
        return success(request, "Opening ${resolver.labelFor(packageName)}.")
    }

    private fun setAlarm(request: ActionRequest): ActionResult {
        val raw = request.args["time"].orEmpty()
        val parsed = parseTime(raw) ?: return failure(request, "I couldn't understand the alarm time: $raw.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, parsed.first)
            putExtra(AlarmClock.EXTRA_MINUTES, parsed.second)
            putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        if (!safeStart(intent)) return failure(request, "Android did not open the alarm screen.")
        return success(request, "Alarm for ${formatTime(parsed.first, parsed.second)} sent to the Clock app.")
    }

    private fun openDialer(request: ActionRequest): ActionResult {
        val number = request.args["number"].orEmpty()
        val intent = if (number.isNotBlank()) Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        else Intent(Intent.ACTION_DIAL)
        if (!safeStart(intent)) return failure(request, "Android did not open the dialer.")
        return success(request, "Dialer opened.")
    }

    private fun placeCall(request: ActionRequest): ActionResult {
        val number = request.args["number"].orEmpty()
        if (number.length !in 7..15) return failure(request, "I need a valid phone number before calling.")
        val canCallDirectly = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (canCallDirectly && safeStart(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))) {
            return success(request, "Calling $number.")
        }
        // Without the Phone permission the dialer is opened with the number
        // filled in, so the call still starts with one tap.
        if (safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))) {
            return success(request, "Dialer opened with $number. Press the call button to connect.")
        }
        return ActionResult(
            request.id,
            ActionStatus.PERMISSION_REQUIRED,
            "Permission needed: Phone. Grant the Phone permission to place calls.",
        )
    }

    private fun prepareSms(request: ActionRequest): ActionResult {
        val number = request.args["number"].orEmpty()
        val body = request.args["text"].orEmpty()
        if (number.length !in 7..15) return failure(request, "I need a valid phone number for the SMS draft.")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", body)
        if (!safeStart(intent)) return failure(request, "No messaging app can open that SMS draft.")
        return success(request, "SMS draft opened. JARVIS did not send it.")
    }

    /**
     * Calls a person by the name that was spoken. The number comes from the
     * contacts on the device, so the user never has to say a number.
     */
    private fun callContact(request: ActionRequest): ActionResult {
        val name = request.args["name"].orEmpty().trim()
        if (name.isEmpty()) return failure(request, "I need a name to call.")
        if (!contacts.hasPermission()) {
            return ActionResult(
                request.id,
                ActionStatus.PERMISSION_REQUIRED,
                "Permission needed: Contacts. Allow JARVIS to read contacts so I can call people by name.",
            )
        }
        val contact = contacts.resolve(name)
            ?: return failure(request, "I could not find a contact called $name.")
        val number = contact.primary
            ?: return failure(request, "$name has no phone number on this device.")
        val canCallDirectly = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (canCallDirectly && safeStart(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))) {
            return success(request, "Calling ${contact.name}.")
        }
        if (safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))) {
            return success(request, "Dialer opened with ${contact.name}. Press the call button to connect.")
        }
        return failure(request, "Android did not open the dialer.")
    }

    /**
     * Opens an existing conversation with a person, optionally with a message
     * already written in the composer.
     */
    private fun openChat(request: ActionRequest): ActionResult {
        val name = request.args["name"].orEmpty().trim()
        if (name.isEmpty()) return failure(request, "I need a name to open a chat with.")
        val body = request.args["text"].orEmpty()
        val app = request.args["app"].orEmpty().trim().lowercase()
        if (!contacts.hasPermission()) {
            return ActionResult(
                request.id,
                ActionStatus.PERMISSION_REQUIRED,
                "Permission needed: Contacts. Allow JARVIS to read contacts so I can open chats by name.",
            )
        }
        val contact = contacts.resolve(name)
            ?: return failure(request, "I could not find a contact called $name.")
        val number = contact.primary
            ?: return failure(request, "${contact.name} has no phone number on this device.")
        val encoded = Uri.encode(body)
        val whatsapp = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=$encoded")
        val sms = Uri.parse("smsto:$number").buildUpon().apply {
            if (body.isNotBlank()) appendQueryParameter("sms_body", body)
        }.build()
        val wantsSms = app == "sms" || app == "message" || app == "messages"
        val intents = if (wantsSms) {
            listOf(
                Intent(Intent.ACTION_VIEW, sms).setPackage("com.google.android.apps.messaging"),
                Intent(Intent.ACTION_VIEW, sms).setPackage("com.android.messaging"),
                Intent(Intent.ACTION_SENDTO, sms),
            )
        } else {
            listOf(
                Intent(Intent.ACTION_VIEW, whatsapp).setPackage("com.whatsapp"),
                Intent(Intent.ACTION_VIEW, whatsapp).setPackage("com.whatsapp.w4b"),
                Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$number&text=$encoded")).setPackage("com.whatsapp"),
                Intent(Intent.ACTION_SENDTO, sms),
            )
        }
        for (intent in intents) {
            if (safeStart(intent)) {
                val target = if (wantsSms) "the message app" else "WhatsApp"
                return success(request, "Opened the $target chat with ${contact.name}.")
            }
        }
        return failure(request, "No messaging app could open a chat with ${contact.name}.")
    }

    /**
     * Taps a control on the current screen by its visible text. This is what
     * lets JARVIS drive any app, not just open it.
     */
    private fun clickText(request: ActionRequest): ActionResult {
        val label = request.args["text"].orEmpty().trim()
        if (label.isEmpty()) return failure(request, "I need the text of the control to tap.")
        val service = JarvisAccessibilityService.active
            ?: return ActionResult(
                request.id,
                ActionStatus.PERMISSION_REQUIRED,
                "Permission needed: Accessibility. Enable the JARVIS accessibility service to tap on screen.",
            )
        return if (service.clickExactLabel(label)) {
            success(request, "Tapped $label.")
        } else {
            failure(request, "I could not find anything labelled $label on this screen.")
        }
    }

    /** Turns the camera torch on or off. */
    private fun toggleFlashlight(request: ActionRequest): ActionResult {
        val wanted = request.args["state"].orEmpty().trim().lowercase()
        val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
            ?: return failure(request, "This device has no camera control.")
        val hasTorch = try {
            manager.cameraIdList.any { id -> manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (_: Exception) {
            false
        }
        if (!hasTorch) return failure(request, "This device has no flashlight.")
        val turnOn = when (wanted) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            else -> !flashlightOn
        }
        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        } ?: return failure(request, "The flashlight could not be found.")
        return try {
            manager.setTorchMode(cameraId, turnOn)
            flashlightOn = turnOn
            success(request, if (turnOn) "Flashlight on." else "Flashlight off.")
        } catch (_: Exception) {
            failure(request, "The flashlight could not be switched.")
        }
    }

    /** Changes the media volume, or mutes it. */
    private fun setVolume(request: ActionRequest): ActionResult {
        val level = request.args["level"].orEmpty().trim().lowercase()
        val audio = context.getSystemService(android.media.AudioManager::class.java)
            ?: return failure(request, "This device has no audio control.")
        val stream = android.media.AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        return try {
            when (level) {
                "up", "louder" -> audio.setStreamVolume(stream, (audio.getStreamVolume(stream) + max / 4).coerceAtMost(max), 0)
                "down", "quieter" -> audio.setStreamVolume(stream, (audio.getStreamVolume(stream) - max / 4).coerceAtLeast(0), 0)
                "mute", "silent" -> audio.setStreamVolume(stream, 0, 0)
                "max", "full", "loud" -> audio.setStreamVolume(stream, max, 0)
                else -> {
                    val value = level.removePrefix("level").trim().toIntOrNull()
                        ?: return failure(request, "I could not work out the volume level.")
                    audio.setStreamVolume(stream, ((max * value) / 100).coerceIn(0, max), 0)
                }
            }
            success(request, "Volume set to $level.")
        } catch (_: Exception) {
            failure(request, "The volume could not be changed.")
        }
    }

    /** Sends a media key, such as play or pause. */
    private fun mediaControl(request: ActionRequest): ActionResult {
        val action = request.args["action"].orEmpty().trim().lowercase()
        val key = when (action) {
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            else -> return failure(request, "I do not know that media control.")
        }
        val audio = context.getSystemService(android.media.AudioManager::class.java)
            ?: return failure(request, "This device has no audio control.")
        return try {
            val now = android.os.SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(
                android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, key, 0),
            )
            success(request, "Media $action.")
        } catch (_: Exception) {
            failure(request, "The media control could not be sent.")
        }
    }

    /** Opens the clock app timer screen. */
    private fun setTimer(request: ActionRequest): ActionResult {
        val duration = request.args["duration"].orEmpty().trim()
        val minutes = duration.toIntOrNull()
            ?: return failure(request, "I could not work out how long the timer should be.")
        if (minutes !in 1..600) return failure(request, "A timer must be between 1 and 600 minutes.")
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, minutes)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, request.args["message"].orEmpty())
        }
        if (!safeStart(intent)) return failure(request, "No clock app could set the timer.")
        return success(request, "Timer set for $minutes minutes.")
    }

    /** Opens the calendar composer with a new event. */
    private fun setCalendar(request: ActionRequest): ActionResult {
        val title = request.args["title"].orEmpty().trim()
        if (title.isEmpty()) return failure(request, "I need a title for the event.")
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = android.provider.CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            request.args["time"]?.takeIf { it.isNotBlank() }?.let {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it)
            }
        }
        if (!safeStart(intent)) return failure(request, "No calendar app could open the event.")
        return success(request, "Opening a new event called $title.")
    }

    private fun prepareWhatsApp(request: ActionRequest): ActionResult {
        val number = request.args["number"].orEmpty()
        val body = request.args["text"].orEmpty()
        if (number.length !in 7..15) return failure(request, "I need a valid phone number for the WhatsApp draft.")
        val encoded = Uri.encode(body)
        // Talk straight to the app. The wa.me web link needs a browser and a
        // network round trip, so it silently fails to open WhatsApp.
        val candidates = listOf(
            Triple("https://api.whatsapp.com/send?phone=$number&text=$encoded", "com.whatsapp", "WhatsApp"),
            Triple("whatsapp://send?phone=$number&text=$encoded", "com.whatsapp", "WhatsApp"),
            Triple("https://api.whatsapp.com/send?phone=$number&text=$encoded", "com.whatsapp.w4b", "WhatsApp Business"),
            Triple("whatsapp://send?phone=$number&text=$encoded", "com.whatsapp.w4b", "WhatsApp Business"),
        )
        for ((uriText, pkg, label) in candidates) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriText)).setPackage(pkg)
            if (safeStart(intent)) {
                return success(request, "$label draft opened for $number. Press Send there if you choose.")
            }
        }
        // Fall back to the web link in a browser when WhatsApp is not installed.
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=$encoded"))
        if (safeStart(web)) {
            return success(request, "WhatsApp is not installed, so the message was opened in a browser.")
        }
        return failure(request, "WhatsApp is not installed and no browser could open the message.")
    }

    private fun readNotifications(request: ActionRequest): ActionResult {
        if (!notificationAccessEnabled()) {
            return ActionResult(
                request.id,
                ActionStatus.PERMISSION_REQUIRED,
                "Permission needed: Notification access. Enable JARVIS notification access in Android Settings.",
            )
        }
        val items = JarvisNotificationListener.snapshot()
        if (items.isEmpty()) return success(request, "There are no recent notifications.")
        val text = items.takeLast(6).joinToString("\n") { item ->
            val title = item.title.take(120)
            val body = item.text.take(240)
            val app = resolver.labelFor(item.packageName)
            "$app: " + listOf(title, body).filter { it.isNotBlank() }.joinToString(" - ")
        }.take(1200)
        return success(request, text)
    }

    private fun accessibility(request: ActionRequest): ActionResult {
        val service = JarvisAccessibilityService.active
        if (service == null) {
            return ActionResult(
                request.id,
                ActionStatus.PERMISSION_REQUIRED,
                "Permission needed: Accessibility. Enable JARVIS supported navigation in Android Settings.",
            )
        }
        val navigation = request.args["navigation"].orEmpty()
        if (!service.navigate(navigation)) {
            return failure(request, "Android did not perform the $navigation navigation action.")
        }
        return success(request, "Navigation action $navigation completed.")
    }

    private fun launchPackage(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return safeStart(intent)
    }

    private fun safeStart(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }

    private fun notificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val component = ComponentName(context, JarvisNotificationListener::class.java).flattenToString()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun success(request: ActionRequest, message: String) =
        ActionResult(request.id, ActionStatus.SUCCEEDED, message.take(1200))

    private fun failure(request: ActionRequest, message: String) =
        ActionResult(request.id, ActionStatus.FAILED, message.take(1200))

    companion object {
        private val timePattern = Regex("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$", RegexOption.IGNORE_CASE)

        fun parseTime(raw: String): Pair<Int, Int>? {
            val match = timePattern.matchEntire(raw.trim()) ?: return null
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
            val suffix = match.groupValues[3].lowercase(Locale.ROOT)
            if (minute !in 0..59) return null
            if (suffix.isEmpty()) {
                if (hour !in 0..23) return null
            } else {
                if (hour !in 1..12) return null
                hour %= 12
                if (suffix == "pm") hour += 12
            }
            return hour to minute
        }

        fun formatTime(hour: Int, minute: Int): String {
            val suffix = if (hour >= 12) "PM" else "AM"
            val display = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            return "%d:%02d %s".format(Locale.US, display, minute, suffix)
        }

        private val ACTIVITY_ACTIONS = setOf(
            "open_app",
            "open_package",
            "open_settings",
            "set_alarm",
            "open_dialer",
            "call",
            "prepare_sms",
            "prepare_whatsapp",
        )
    }
}
