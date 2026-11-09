package dev.jarvis.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val bg = Color.rgb(5, 13, 17)
    private val panel = Color.rgb(11, 27, 32)
    private val panel2 = Color.rgb(16, 39, 44)
    private val cyan = Color.rgb(65, 246, 210)
    private val green = Color.rgb(120, 255, 170)
    private val white = Color.rgb(230, 247, 245)
    private val muted = Color.rgb(142, 176, 177)
    private val red = Color.rgb(255, 122, 122)
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var appsStatus: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var voiceState: TextView
    private lateinit var partialText: TextView
    private lateinit var history: TextView
    private lateinit var tokenInput: EditText
    private lateinit var micButton: Button
    private lateinit var languageSpinner: Spinner
    private lateinit var appSpinner: Spinner
    private lateinit var conversationCheck: CheckBox
    private lateinit var waveform: VoiceWaveformView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var suppressResume = false
    private var languageTag = "en-US"
    private var speechRate = 1.0f
    private var speechPitch = 1.0f
    private var discoveredApps: List<LaunchableApp> = emptyList()

    private data class LaunchableApp(val label: String, val packageName: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
        tts = TextToSpeech(this, this)
        refreshApps()
        refreshPermissionStatus()
        checkBackend()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(34))
        }
        scroll.addView(page)
        setContentView(scroll)

        text(page, "J A R V I S   /   C O M P A N I O N", 12, cyan, true, 12)
        text(page, "Voice interface", 30, white, true, 2)
        text(page, "ANDROID  •  LOCAL BRIDGE  •  OPENCLAW", 11, muted, true, 18)

        status = cardLabel(page, "Backend  ·  checking loopback…", cyan)
        voiceState = cardLabel(page, "VOICE IDLE  ·  microphone is off", muted)
        permissionStatus = cardLabel(page, "Permissions  ·  checking…", muted)
        waveform = VoiceWaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
        }
        page.addView(waveform)
        partialText = text(page, "Tap the microphone when you want to speak.", 14, muted, false, 12)

        val micRow = row(page)
        micButton = button(micRow, "🎙  Start listening", true) {
            if (listening) stopListening("Listening stopped") else startListening()
        }.also { it.contentDescription = "Start or stop voice recognition" }
        button(micRow, "Stop voice", false) { stopSpeaking() }

        conversationCheck = CheckBox(this).apply {
            text = "Conversation mode  ·  listen again after each spoken reply"
            setTextColor(white)
            buttonTintList = android.content.res.ColorStateList.valueOf(cyan)
            textSize = 13f
        }
        page.addView(conversationCheck)
        text(page, "This mode reopens Android recognition after each reply while this screen stays open. It does not use a wake word or run in the background. Android's selected recognition provider may process audio outside this app.", 12, muted, false, 12)

        section(page, "VOICE")
        languageSpinner = spinner(page, listOf("English (United States)", "English (India)", "Hindi (India)"))
        languageSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            languageTag = listOf("en-US", "en-IN", "hi-IN").getOrElse(position) { "en-US" }
            if (ttsReady) applyTtsLanguage()
        }
        slider(page, "Speech speed", 50) { speechRate = 0.5f + it / 100f; applyTtsSettings() }
        slider(page, "Voice pitch", 50) { speechPitch = 0.5f + it / 100f; applyTtsSettings() }

        section(page, "SECURE PYTHON CONNECTION")
        text(page, "The bridge accepts chat requests on 127.0.0.1 only. Paste its private pairing token; Android stores it encrypted with Android Keystore.", 12, muted, false, 8)
        tokenInput = EditText(this).apply {
            hint = "Pairing token from: python3 mobile_bridge.py --show-token"
            setHintTextColor(muted)
            setTextColor(white)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(panel2, 12)
            TokenStore.load(this@MainActivity)?.let { setText(it) }
        }
        page.addView(tokenInput, matchWrap(8))
        button(page, "Save token and connect", true) { saveTokenAndConnect() }

        section(page, "APPS")
        text(page, "Launchable apps visible to Android are listed here. Voice shortcuts and this picker open apps only; they do not send messages or place calls.", 12, muted, false, 8)
        appSpinner = spinner(page, listOf("Loading installed apps…"))
        appsStatus = text(page, "Launchable apps have not been loaded.", 12, muted, false, 6)
        button(page, "Open selected app", false) { openSelectedApp() }
        button(page, "Refresh launchable apps", false) { refreshApps() }

        section(page, "ACCESSIBILITY  ·  OPTIONAL")
        text(page, "When you enable this service in Android Settings, JARVIS can perform Back/Home and, only after you press the control below, click one exact visible label. It ignores password fields and does not save screen text.", 12, muted, false, 8)
        button(page, "Explain and open Accessibility settings", false) { requestAccessibilityEnable() }
        val navRow = row(page)
        button(navRow, "Navigate Back", false) { runAccessibilityNavigation("back") }
        button(navRow, "Go Home", false) { runAccessibilityNavigation("home") }
        button(page, "Tap an exact visible label…", false) { requestExactLabelTap() }

        section(page, "NOTIFICATIONS  ·  OPTIONAL")
        text(page, "Notification access is opt-in. JARVIS keeps at most 20 recent text notifications in memory. They are never sent to OpenClaw unless you review and confirm a summary.", 12, muted, false, 8)
        button(page, "Explain and open notification access", false) { requestNotificationAccess() }
        button(page, "Review and summarize recent notifications", false) { confirmNotificationSummary() }
        button(page, "Clear in-memory notifications", false) {
            JarvisNotificationListener.clear()
            toast("Recent notification text cleared")
        }

        section(page, "CONVERSATION")
        history = text(page, "Conversation is kept on this screen until the app closes.", 13, white, false, 12).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(panel, 12)
            minHeight = dp(70)
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            voiceState.text = "VOICE PERMISSION REQUIRED  ·  allow microphone to start"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceState.text = "VOICE ERROR  ·  no Android recognition service is available"
            voiceState.setTextColor(red)
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(recognitionListener()) }
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS")
            }
            listening = true
            micButton.text = "Stop listening"
            waveform.active = true
            voiceState.text = "LISTENING  ·  microphone active for this request"
            voiceState.setTextColor(green)
            partialText.text = "Listening…"
            recognizer?.startListening(request)
        } catch (exception: Exception) {
            listening = false
            micButton.text = "🎙  Start listening"
            waveform.active = false
            voiceState.text = "VOICE ERROR  ·  ${exception.message ?: "recognition could not start"}"
            voiceState.setTextColor(red)
        }
    }

    private fun recognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { runOnUiThread { voiceState.text = "LISTENING  ·  speak now" } }
        override fun onBeginningOfSpeech() { runOnUiThread { voiceState.text = "LISTENING  ·  speech detected" } }
        override fun onRmsChanged(rmsdB: Float) { waveform.level = rmsdB }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { runOnUiThread { voiceState.text = "THINKING  ·  recognition complete" } }
        override fun onError(error: Int) {
            runOnUiThread {
                listening = false
                micButton.text = "🎙  Start listening"
                waveform.active = false
                voiceState.setTextColor(red)
                voiceState.text = "VOICE ERROR  ·  ${speechError(error)}"
                partialText.text = "Tap the microphone to try again."
            }
        }
        override fun onResults(results: Bundle?) {
            val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            runOnUiThread {
                listening = false
                micButton.text = "🎙  Start listening"
                waveform.active = false
                if (phrase.isBlank()) {
                    voiceState.text = "VOICE ERROR  ·  no words were recognized"
                    voiceState.setTextColor(red)
                } else handleRecognizedText(phrase)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val phrase = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (phrase.isNotBlank()) runOnUiThread { partialText.text = phrase }
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handleRecognizedText(phrase: String) {
        addTurn("YOU", phrase)
        partialText.text = phrase
        val action = VoiceCommandParser.parse(phrase)
        when (action?.kind) {
            "open_app" -> {
                if (!isKnownLaunchTarget(action.value)) {
                    sendChat(phrase)
                    return
                }
                val opened = launchNamedApp(action.value)
                val response = if (opened) "Opening ${action.value}." else "I could not find a launchable app named ${action.value}."
                addTurn("JARVIS", response)
                speakResponse(response)
            }
            "whatsapp_draft" -> confirmWhatsAppDraft(action.value, action.message)
            else -> sendChat(phrase)
        }
    }

    private fun sendChat(text: String) {
        val token = TokenStore.load(this)
        if (token.isNullOrBlank()) {
            voiceState.text = "PAIRING REQUIRED  ·  save the local bridge token first"
            voiceState.setTextColor(red)
            return
        }
        voiceState.text = "THINKING  ·  contacting the local JARVIS backend"
        voiceState.setTextColor(cyan)
        waveform.active = false
        io.execute {
            try {
                val response = postCommand(token, text)
                runOnUiThread {
                    voiceState.text = "SPEAKING  ·  response from the JARVIS backend"
                    voiceState.setTextColor(green)
                    partialText.text = ""
                    addTurn("JARVIS", response)
                    speakResponse(response)
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    voiceState.text = "CONNECTION ERROR  ·  ${exception.message ?: "backend unavailable"}"
                    voiceState.setTextColor(red)
                }
            }
        }
    }

    private fun postCommand(token: String, text: String): String {
        require(text.length in 1..4000) { "Please keep each request under 4,000 characters." }
        val connection = URL("http://127.0.0.1:8765/v1/command").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 105000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $token")
            val body = JSONObject().put("action", "chat")
                .put("request_id", UUID.randomUUID().toString())
                .put("text", text).toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                throw IllegalStateException(when (statusCode) {
                    401 -> "token rejected; check pairing"
                    409 -> "duplicate or conflicting request; try again"
                    else -> "local backend returned HTTP $statusCode"
                })
            }
            return JSONObject(payload).optString("text").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("backend returned an empty response")
        } finally {
            connection.disconnect()
        }
    }

    private fun saveTokenAndConnect() {
        try {
            TokenStore.save(this, tokenInput.text.toString())
            tokenInput.setText("")
            tokenInput.hint = "Token saved securely  ·  enter a new token to replace"
            checkBackend()
        } catch (exception: Exception) {
            status.text = "Backend  ·  ${exception.message ?: "token could not be saved"}"
            status.setTextColor(red)
        }
    }

    private fun checkBackend() {
        io.execute {
            try {
                val connection = URL("http://127.0.0.1:8765/health").openConnection() as HttpURLConnection
                connection.connectTimeout = 2500
                connection.readTimeout = 2500
                TokenStore.load(this)?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val payload = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                connection.disconnect()
                runOnUiThread {
                    val paired = code == 200 && try { JSONObject(payload).optBoolean("authenticated") } catch (_: Exception) { false }
                    status.text = when {
                        paired -> "Backend  ·  paired and connected on loopback"
                        code == 200 -> "Backend  ·  online  ·  save pairing token"
                        code == 401 -> "Backend  ·  token rejected  ·  check pairing"
                        else -> "Backend  ·  HTTP $code"
                    }
                    status.setTextColor(if (paired) green else if (code == 200) cyan else red)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    status.text = "Backend  ·  offline  ·  start the existing JARVIS TUI in Termux"
                    status.setTextColor(red)
                }
            }
        }
    }

    private fun confirmWhatsAppDraft(number: String, body: String) {
        AlertDialog.Builder(this)
            .setTitle("Review WhatsApp draft")
            .setMessage("Recipient: +$number\n\nMessage:\n$body\n\nContinue to WhatsApp to review it? JARVIS will not press Send.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open WhatsApp") { _, _ ->
                val uri = Uri.parse("https://wa.me/$number?text=${Uri.encode(body)}")
                val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")
                try {
                    startActivity(intent)
                    speakResponse("WhatsApp is open with a draft. Review it and press Send there if you choose.")
                } catch (_: Exception) {
                    val businessIntent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp.w4b")
                    try { startActivity(businessIntent) } catch (_: Exception) { toast("WhatsApp is not installed or could not open this number") }
                }
            }.show()
    }

    private fun refreshApps() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        discoveredApps = packageManager.queryIntentActivities(launcher, 0)
            .mapNotNull { info ->
                val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                if (label.isBlank()) null else LaunchableApp(label, info.activityInfo.packageName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        val labels = discoveredApps.map { it.label }.ifEmpty { listOf("No launchable apps visible") }
        appSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        appsStatus.text = "${discoveredApps.size} launchable apps visible to Android"
    }

    private fun openSelectedApp() {
        val app = discoveredApps.getOrNull(appSpinner.selectedItemPosition) ?: return toast("No app selected")
        if (!launchPackage(app.packageName)) toast("Could not open ${app.label}")
        else announce("Opening ${app.label}.")
    }

    private fun launchNamedApp(requested: String): Boolean {
        val name = requested.lowercase().trim()
        val actions = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "spotify" to listOf("com.spotify.music"),
            "youtube" to listOf("com.google.android.youtube"),
            "chrome" to listOf("com.android.chrome"),
            "phone" to listOf("com.google.android.dialer", "com.android.dialer"),
            "messages" to listOf("com.google.android.apps.messaging", "com.android.messaging"),
            "camera" to listOf("com.android.camera2", "com.google.android.GoogleCamera"),
            "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
        )
        when (name) {
            "settings" -> return safeStart(Intent(Settings.ACTION_SETTINGS))
            "phone", "dialer" -> return safeStart(Intent(Intent.ACTION_DIAL))
            "camera" -> {
                val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (capture.resolveActivity(packageManager) != null) return safeStart(capture)
            }
            "clock", "alarms" -> {
                val alarm = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                if (alarm.resolveActivity(packageManager) != null && safeStart(alarm)) return true
            }
        }
        val packages = actions[name].orEmpty()
        packages.firstOrNull { launchPackage(it) }?.let { return true }
        val matched = discoveredApps.firstOrNull { it.label.equals(requested, ignoreCase = true) }
        return matched?.let { launchPackage(it.packageName) } ?: false
    }

    private fun isKnownLaunchTarget(requested: String): Boolean {
        val aliases = setOf("whatsapp", "spotify", "youtube", "chrome", "phone", "dialer", "messages", "camera", "clock", "alarms", "settings")
        return requested.lowercase().trim() in aliases || discoveredApps.any { it.label.equals(requested, ignoreCase = true) }
    }

    private fun launchPackage(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return safeStart(intent)
    }

    private fun safeStart(intent: Intent): Boolean = try {
        if (intent.resolveActivity(packageManager) == null) false else {
            startActivity(intent)
            true
        }
    } catch (_: Exception) { false }

    private fun requestAccessibilityEnable() {
        AlertDialog.Builder(this)
            .setTitle("Optional Accessibility access")
            .setMessage("Android will let JARVIS inspect visible UI nodes for an exact label only after you request a tap, plus perform Back/Home. The service skips password fields, does not store screen contents, and never taps from AI output. You must enable it yourself in Android Settings.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open Settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .show()
    }

    private fun runAccessibilityNavigation(action: String) {
        val service = JarvisAccessibilityService.active
        if (service == null) {
            toast("Enable JARVIS supported navigation in Android Accessibility settings first")
            return
        }
        val ok = service.navigate(action)
        if (!ok) toast("Android did not perform that navigation action")
    }

    private fun requestExactLabelTap() {
        val input = EditText(this).apply { hint = "Exact visible label"; setTextColor(white); setHintTextColor(muted) }
        AlertDialog.Builder(this)
            .setTitle("Tap one exact visible label")
            .setMessage("JARVIS searches visible, non-password nodes only after confirmation. It does not read or save surrounding text.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Confirm tap") { _, _ ->
                val label = input.text.toString().trim()
                val service = JarvisAccessibilityService.active
                if (service == null) toast("Enable the optional Accessibility service first")
                else if (service.clickExactLabel(label)) toast("Tapped the matching visible control")
                else toast("No exact clickable label was found")
            }.show()
    }

    private fun requestNotificationAccess() {
        AlertDialog.Builder(this)
            .setTitle("Optional notification access")
            .setMessage("If enabled, Android grants JARVIS access to notification titles and text. They stay in memory on this device and are not uploaded automatically. A separate confirmation is required before sending a summary to your configured OpenClaw backend.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open Settings") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                catch (_: Exception) { toast("Open Android Settings → Notification access") }
            }.show()
    }

    private fun confirmNotificationSummary() {
        val items = JarvisNotificationListener.snapshot()
        if (items.isEmpty()) return toast("No permitted notification text is available")
        val preview = items.takeLast(8).joinToString("\n\n") { item ->
            val app = appLabel(item.packageName)
            val content = listOf(item.title, item.text).filter { it.isNotBlank() }.joinToString(" — ")
            "$app: $content"
        }.take(3000)
        AlertDialog.Builder(this)
            .setTitle("Share notification summary?")
            .setMessage("This notification text will be sent to your configured JARVIS/OpenClaw backend. Review the preview:\n\n$preview")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send summary") { _, _ -> sendChat("Summarize these recent Android notifications:\n$preview") }
            .show()
    }

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) { packageName }

    private fun refreshPermissionStatus() {
        val mic = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "mic granted" else "mic not granted"
        val accessibility = if (isAccessibilityEnabled()) "accessibility enabled" else "accessibility off"
        val notifications = if (isNotificationAccessEnabled()) "notification access enabled" else "notification access off"
        permissionStatus.text = "Permissions  ·  $mic  ·  $accessibility  ·  $notifications"
        permissionStatus.setTextColor(if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) green else muted)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(this, JarvisAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val component = ComponentName(this, JarvisNotificationListener::class.java).flattenToString()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun speakResponse(text: String) {
        suppressResume = false
        val engine = tts
        if (!ttsReady || engine == null) {
            voiceState.text = "TTS ERROR  ·  Android speech engine is not ready"
            voiceState.setTextColor(red)
            return
        }
        applyTtsSettings()
        val result = engine.speak(text.take(12000), TextToSpeech.QUEUE_FLUSH, null, "jarvis-${UUID.randomUUID()}")
        if (result == TextToSpeech.ERROR) {
            voiceState.text = "TTS ERROR  ·  Android rejected the speech request"
            voiceState.setTextColor(red)
        }
    }

    private fun announce(text: String) {
        addTurn("JARVIS", text)
        speakResponse(text)
    }

    private fun stopSpeaking() {
        suppressResume = true
        tts?.stop()
        stopListening("Voice output stopped")
        voiceState.text = "VOICE IDLE  ·  speech stopped"
        voiceState.setTextColor(muted)
    }

    private fun applyTtsLanguage() {
        val result = tts?.setLanguage(Locale.forLanguageTag(languageTag)) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            voiceState.text = "TTS LANGUAGE ERROR  ·  selected voice is unavailable on this device"
            voiceState.setTextColor(red)
        }
    }

    private fun applyTtsSettings() {
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(speechPitch)
    }

    override fun onInit(result: Int) {
        ttsReady = result == TextToSpeech.SUCCESS
        if (!ttsReady) {
            voiceState.text = "TTS ERROR  ·  Android could not initialize a speech engine"
            voiceState.setTextColor(red)
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = runOnUiThread {
                voiceState.text = "SPEAKING  ·  Android TextToSpeech"
                voiceState.setTextColor(green)
            }
            override fun onDone(utteranceId: String?) = runOnUiThread {
                if (!suppressResume && conversationCheck.isChecked && !isFinishing) startListening()
                else {
                    voiceState.text = "VOICE IDLE  ·  microphone is off"
                    voiceState.setTextColor(muted)
                }
            }
            @Deprecated("Deprecated by Android")
            override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)
            override fun onError(utteranceId: String?, errorCode: Int) = runOnUiThread {
                voiceState.text = "TTS ERROR  ·  Android speech engine failed ($errorCode)"
                voiceState.setTextColor(red)
            }
        })
        applyTtsLanguage()
        applyTtsSettings()
        voiceState.text = "VOICE READY  ·  tap the microphone when you want to speak"
        voiceState.setTextColor(muted)
    }

    private fun stopListening(message: String) {
        if (!listening) return
        listening = false
        micButton.text = "🎙  Start listening"
        waveform.active = false
        try { recognizer?.cancel() } catch (_: Exception) { }
        voiceState.text = "VOICE IDLE  ·  $message"
        voiceState.setTextColor(muted)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                refreshPermissionStatus()
                startListening()
            }
            else {
                voiceState.text = "VOICE PERMISSION DENIED  ·  allow microphone to use SpeechRecognizer"
                voiceState.setTextColor(red)
            }
        }
    }

    override fun onPause() {
        stopListening("screen is not active")
        suppressResume = true
        tts?.stop()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::permissionStatus.isInitialized) refreshPermissionStatus()
    }

    override fun onDestroy() {
        stopListening("app closed")
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun addTurn(speaker: String, value: String) {
        val prior = history.text.toString().takeIf { it != "Conversation is kept on this screen until the app closes." }.orEmpty()
        history.text = (prior + "\n\n$speaker\n" + value).trim().takeLast(10000)
    }

    private fun speechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio capture failed"
        SpeechRecognizer.ERROR_CLIENT -> "recognition client stopped"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission is missing"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "recognition service network error"
        SpeechRecognizer.ERROR_NO_MATCH -> "speech was not recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer is busy; try again shortly"
        SpeechRecognizer.ERROR_SERVER -> "recognition service failed"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech detected"
        else -> "recognition error $code"
    }

    private fun section(parent: LinearLayout, title: String) {
        text(parent, title, 12, cyan, true, 10).setPadding(0, dp(18), 0, dp(8))
    }

    private fun cardLabel(parent: LinearLayout, label: String, color: Int): TextView = text(parent, label, 13, color, true, 7).apply {
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(panel, 12)
    }

    private fun text(parent: LinearLayout, value: String, size: Int, color: Int, bold: Boolean, bottom: Int): TextView {
        val view = TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1.05f)
        }
        parent.addView(view, matchWrap(bottom))
        return view
    }

    private fun row(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        parent.addView(this, matchWrap(4))
    }

    private fun button(parent: ViewGroup, label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(if (primary) bg else cyan)
        background = rounded(if (primary) cyan else panel2, 14)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        setOnClickListener { action() }
        val params = if (parent is LinearLayout && parent.orientation == LinearLayout.HORIZONTAL) {
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6); topMargin = dp(3); bottomMargin = dp(3) }
        } else matchWrap(5, 48)
        parent.addView(this, params)
    }

    private fun spinner(parent: LinearLayout, options: List<String>): Spinner = Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    textSize = 14f
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    setBackgroundColor(panel2)
                    textSize = 14f
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        background = rounded(panel2, 12)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        parent.addView(this, matchWrap(8, 48))
    }

    private fun slider(parent: LinearLayout, label: String, initial: Int, changed: (Int) -> Unit) {
        text(parent, "$label  ·  1.0", 12, muted, false, 0)
        SeekBar(this).apply {
            max = 100
            progress = initial
            progressTintList = android.content.res.ColorStateList.valueOf(cyan)
            thumbTintList = android.content.res.ColorStateList.valueOf(green)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = 0.5f + progress / 100f
                    changed(value)
                    (parent.getChildAt(parent.childCount - 2) as? TextView)?.text = "$label  ·  ${"%.2f".format(Locale.US, value)}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            parent.addView(this, matchWrap(4, 38))
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun matchWrap(bottom: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (height == 0) ViewGroup.LayoutParams.WRAP_CONTENT else height).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object { private const val REQUEST_MIC = 41 }
}

private class SimpleItemSelectedListener(private val selected: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = selected(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}

private class VoiceWaveformView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(65, 246, 210); strokeWidth = 5f; strokeCap = Paint.Cap.ROUND }
    private var phase = 0f
    var level: Float = 0f
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (active) phase += 0.10f
        val bars = 37
        val gap = width.toFloat() / bars
        val center = height / 2f
        for (i in 0 until bars) {
            val wave = kotlin.math.abs(kotlin.math.sin((i * 0.42f) + phase))
            val envelope = kotlin.math.max(0.16f, 1f - kotlin.math.abs(i - bars / 2f) / (bars / 2f))
            val mic = if (active) (kotlin.math.max(0f, level + 3f) / 14f).coerceIn(0.15f, 1f) else 0.24f
            val amplitude = (5f + wave * 25f * envelope * mic).coerceAtMost(height * 0.44f)
            paint.alpha = (95 + 160 * envelope).toInt().coerceAtMost(255)
            val x = gap * i + gap / 2
            canvas.drawLine(x, center - amplitude, x, center + amplitude, paint)
        }
        if (active) postInvalidateDelayed(45)
    }
}
