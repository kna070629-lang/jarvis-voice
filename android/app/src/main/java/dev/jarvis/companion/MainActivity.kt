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
import android.widget.FrameLayout
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
    private val bg = Color.rgb(5, 31, 32)
    private val panel = Color.rgb(11, 43, 38)
    private val panel2 = Color.rgb(22, 56, 50)
    private val cyan = Color.rgb(142, 182, 155)
    private val green = Color.rgb(218, 241, 222)
    private val white = Color.rgb(218, 241, 222)
    private val muted = Color.rgb(142, 182, 155)
    private val red = Color.rgb(230, 137, 128)
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(22), dp(26), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(5, 31, 32), Color.rgb(11, 43, 38), Color.rgb(5, 31, 32)),
            )
        }
        setContentView(root)

        text(root, "J A R V I S", 16, green, true, 2).gravity = Gravity.CENTER
        text(root, "PERSONAL VOICE ASSISTANT", 10, muted, true, 16).gravity = Gravity.CENTER
        status = text(root, "Python bridge  ·  checking loopback…", 12, muted, false, 4).apply { gravity = Gravity.CENTER }
        voiceState = text(root, "Ready when you are", 14, white, false, 0).apply { gravity = Gravity.CENTER }

        val center = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            contentDescription = "JARVIS voice orb; animation reflects listening, thinking, speaking, and error states"
        }
        waveform = VoiceWaveformView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(292), dp(292), Gravity.CENTER)
        }
        center.addView(waveform)
        root.addView(center)
        partialText = text(root, "Tap below to begin a conversation", 14, muted, false, 8).apply { gravity = Gravity.CENTER }

        history = text(root, "", 12, white, false, 10).apply {
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        permissionStatus = text(root, "Microphone and optional phone permissions are shown in Controls", 10, muted, false, 6).apply { gravity = Gravity.CENTER }

        micButton = button(root, "◉     Start a conversation", true) {
            if (listening) stopListening("Listening stopped") else startListening()
        }.also {
            it.contentDescription = "Start or stop voice recognition"
            it.background = glassSurface()
            it.setTextColor(green)
            it.textSize = 15f
            it.minimumHeight = dp(58)
        }
        val actions = row(root)
        button(actions, "Stop speaking", false) { stopSpeaking() }
        button(actions, "Controls", false) { showControls() }

        buildControls()
    }

    private fun buildControls() {
        val page = controlsPage
        if (page.childCount > 0) return
        conversationCheck = CheckBox(this).apply {
            text = "Conversation mode  ·  listen again after each spoken reply"
            setTextColor(white)
            buttonTintList = android.content.res.ColorStateList.valueOf(cyan)
            textSize = 13f
        }
        page.addView(conversationCheck)
        text(page, "Recognition starts only after you tap the microphone and stops with the request. There is no background wake word. Android's selected recognition provider may process audio outside this app.", 12, muted, false, 12)

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
            hint = "Local pairing token (show only when pairing)"
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
        text(page, "Notification access is opt-in. JARVIS keeps at most 20 recent text notifications in memory. They are never sent unless you review and confirm a summary.", 12, muted, false, 8)
        button(page, "Explain and open notification access", false) { requestNotificationAccess() }
        button(page, "Review and summarize recent notifications", false) { confirmNotificationSummary() }
        button(page, "Clear in-memory notifications", false) {
            JarvisNotificationListener.clear()
            toast("Recent notification text cleared")
        }

        section(page, "CONVERSATION")
        text(page, "The recent transcript is shown on the main screen and remains in memory until the app closes.", 12, muted, false, 12)
    }

    private val controlsPage: LinearLayout by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
    }

    private fun showControls() {
        val scroll = ScrollView(this).apply { addView(controlsPage) }
        AlertDialog.Builder(this)
            .setTitle("JARVIS controls and permissions")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            voiceState.text = "VOICE PERMISSION REQUIRED  ·  allow microphone to start"
            waveform.setState(VoiceWaveformView.State.ERROR)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceState.text = "VOICE ERROR  ·  no Android recognition service is available"
            voiceState.setTextColor(red)
            waveform.setState(VoiceWaveformView.State.ERROR)
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
            waveform.setState(VoiceWaveformView.State.LISTENING)
            voiceState.text = "LISTENING  ·  microphone active for this request"
            voiceState.setTextColor(green)
            partialText.text = "Listening…"
            recognizer?.startListening(request)
        } catch (exception: Exception) {
            listening = false
            micButton.text = "🎙  Start listening"
            waveform.setState(VoiceWaveformView.State.ERROR)
            voiceState.text = "VOICE ERROR  ·  ${exception.message ?: "recognition could not start"}"
            voiceState.setTextColor(red)
        }
    }

    private fun recognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { runOnUiThread { voiceState.text = "LISTENING  ·  speak now"; waveform.setState(VoiceWaveformView.State.LISTENING) } }
        override fun onBeginningOfSpeech() { runOnUiThread { voiceState.text = "LISTENING  ·  speech detected"; waveform.setState(VoiceWaveformView.State.LISTENING) } }
        override fun onRmsChanged(rmsdB: Float) { waveform.level = rmsdB }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { runOnUiThread { voiceState.text = "THINKING  ·  recognition complete"; waveform.setState(VoiceWaveformView.State.THINKING) } }
        override fun onError(error: Int) {
            runOnUiThread {
                listening = false
                micButton.text = "🎙  Start listening"
                waveform.setState(VoiceWaveformView.State.ERROR)
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
                waveform.setState(VoiceWaveformView.State.THINKING)
                if (phrase.isBlank()) {
                    voiceState.text = "VOICE ERROR  ·  no words were recognized"
                    voiceState.setTextColor(red)
                    waveform.setState(VoiceWaveformView.State.ERROR)
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
            "whatsapp_draft" -> { waveform.setState(VoiceWaveformView.State.IDLE); confirmWhatsAppDraft(action.value, action.message) }
            "call_confirmation" -> { waveform.setState(VoiceWaveformView.State.IDLE); confirmCall(action.value) }
            "sms_draft" -> { waveform.setState(VoiceWaveformView.State.IDLE); confirmSmsDraft(action.value, action.message) }
            else -> sendChat(phrase)
        }
    }

    private fun confirmCall(number: String) {
        AlertDialog.Builder(this)
            .setTitle("Review phone call")
            .setMessage("Open the system dialer for +$number? JARVIS will not place the call. You can review and cancel in the dialer.")
            .setNegativeButton("Cancel") { _, _ -> voiceState.text = "VOICE IDLE  ·  call cancelled" }
            .setPositiveButton("Open dialer") { _, _ ->
                safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$number")))
            }
            .show()
    }

    private fun confirmSmsDraft(number: String, body: String) {
        AlertDialog.Builder(this)
            .setTitle("Review SMS draft")
            .setMessage("Recipient: +$number\n\nMessage:\n$body\n\nOpen the messaging app to review this draft? JARVIS will not send it.")
            .setNegativeButton("Cancel") { _, _ -> voiceState.text = "VOICE IDLE  ·  SMS draft cancelled" }
            .setPositiveButton("Open SMS draft") { _, _ ->
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+$number")).putExtra("sms_body", body)
                if (!safeStart(intent)) toast("No messaging app can open this draft")
            }
            .show()
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
        waveform.setState(VoiceWaveformView.State.THINKING)
        io.execute {
            try {
                val response = postCommand(token, text)
                runOnUiThread {
                    voiceState.text = "SPEAKING  ·  response from the JARVIS backend"
                    voiceState.setTextColor(green)
                    waveform.setState(VoiceWaveformView.State.SPEAKING)
                    partialText.text = ""
                    addTurn("JARVIS", response)
                    speakResponse(response)
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    voiceState.text = "CONNECTION ERROR  ·  ${exception.message ?: "backend unavailable"}"
                    voiceState.setTextColor(red)
                waveform.setState(VoiceWaveformView.State.OFFLINE)
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
                    val runtime = try { JSONObject(payload).optJSONObject("runtime") } catch (_: Exception) { null }
                    val mode = runtime?.optString("mode", "")?.takeIf { it.isNotBlank() }
                    val ai = runtime?.optString("openrouter", "")?.takeIf { it.isNotBlank() }
                    status.text = when {
                        paired -> "Python bridge  ·  ${mode ?: "connected"}  ·  AI ${ai ?: "status unavailable"}"
                        code == 200 -> "Backend  ·  online  ·  save pairing token"
                        code == 401 -> "Backend  ·  token rejected  ·  check pairing"
                        else -> "Backend  ·  HTTP $code"
                    }
                    status.setTextColor(if (paired) green else if (code == 200) cyan else red)
                    if (paired && (mode == "offline" || ai == "not_configured")) {
                        waveform.setState(VoiceWaveformView.State.OFFLINE)
                    } else if (paired) {
                        waveform.setState(VoiceWaveformView.State.IDLE)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    status.text = "Backend  ·  offline  ·  start ./jarvis-mobile.sh in Termux"
                    status.setTextColor(red)
                    waveform.setState(VoiceWaveformView.State.OFFLINE)
                }
            }
        }
    }

    private fun confirmWhatsAppDraft(number: String, body: String) {
        AlertDialog.Builder(this)
            .setTitle("Review WhatsApp draft")
            .setMessage("Recipient: +$number\n\nMessage:\n$body\n\nContinue to WhatsApp to review it? JARVIS will not press Send.")
            .setNegativeButton("Cancel") { _, _ -> voiceState.text = "VOICE IDLE  ·  WhatsApp draft cancelled" }
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
            .setMessage("If enabled, Android grants JARVIS access to notification titles and text. They stay in memory on this device and are not uploaded automatically. A separate confirmation is required before sending a summary to your configured Python assistant.")
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
            .setMessage("This notification text will be sent to your configured JARVIS backend, which may use your configured cloud provider. Review the preview:\n\n$preview")
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
            waveform.setState(VoiceWaveformView.State.ERROR)
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
        waveform.setState(VoiceWaveformView.State.IDLE)
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
            waveform.setState(VoiceWaveformView.State.ERROR)
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = runOnUiThread {
                voiceState.text = "SPEAKING  ·  Android TextToSpeech"
                voiceState.setTextColor(green)
                waveform.setState(VoiceWaveformView.State.SPEAKING)
            }
            override fun onDone(utteranceId: String?) = runOnUiThread {
                if (!suppressResume && conversationCheck.isChecked && !isFinishing) startListening()
                else {
                    voiceState.text = "VOICE IDLE  ·  microphone is off"
                    voiceState.setTextColor(muted)
                    waveform.setState(VoiceWaveformView.State.IDLE)
                }
            }
            @Deprecated("Deprecated by Android")
            override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)
            override fun onError(utteranceId: String?, errorCode: Int) = runOnUiThread {
                voiceState.text = "TTS ERROR  ·  Android speech engine failed ($errorCode)"
                voiceState.setTextColor(red)
                waveform.setState(VoiceWaveformView.State.ERROR)
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
        waveform.setState(VoiceWaveformView.State.IDLE)
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
        if (::status.isInitialized) checkBackend()
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

    private fun glassSurface() = GradientDrawable().apply {
        setColor(Color.argb(72, 35, 83, 71))
        cornerRadius = dp(32).toFloat()
        setStroke(dp(1), Color.argb(145, 142, 182, 155))
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
    enum class State { IDLE, LISTENING, THINKING, SPEAKING, ERROR, OFFLINE }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contour = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private var phase = 0f
    private var state = State.IDLE
    private val reducedMotion = try {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) { false }
    var level: Float = 0f
        set(value) { field = value; invalidate() }

    fun setState(next: State) {
        state = next
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val moving = state != State.ERROR && state != State.OFFLINE
        if (!reducedMotion && moving) phase += if (state == State.IDLE) 0.009f else if (state == State.THINKING) 0.022f else 0.045f
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) * 0.34f
        val amplitude = if (state == State.LISTENING) (level + 2f).coerceIn(0f, 14f) / 14f else if (state == State.SPEAKING) 0.32f else 0.12f
        val pulse = if (reducedMotion) 0f else kotlin.math.sin(phase).toFloat() * 0.035f
        val tint = when (state) {
            State.ERROR, State.OFFLINE -> Color.rgb(113, 135, 119)
            State.LISTENING -> Color.rgb(142, 182, 155)
            State.SPEAKING -> Color.rgb(218, 241, 222)
            else -> Color.rgb(92, 145, 119)
        }
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(18, 218, 241, 222)
        canvas.drawCircle(cx, cy, base * 1.42f, fill)
        fill.color = Color.argb(26, 142, 182, 155)
        canvas.drawCircle(cx, cy, base * 1.22f, fill)

        val path = android.graphics.Path()
        val points = 72
        for (index in 0..points) {
            val angle = (index.toFloat() / points) * (Math.PI * 2).toFloat()
            val organic = kotlin.math.sin(angle * 3f + phase) * 0.035f + kotlin.math.sin(angle * 5f - phase * 0.7f) * 0.018f
            val voice = if (state == State.LISTENING || state == State.SPEAKING) kotlin.math.sin(angle * 2f + phase * 1.4f) * amplitude * 0.075f else 0f
            val radius = base * (1f + pulse + organic + voice)
            val x = cx + kotlin.math.cos(angle) * radius
            val y = cy + kotlin.math.sin(angle) * radius
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        fill.color = Color.argb(72, Color.red(tint), Color.green(tint), Color.blue(tint))
        canvas.drawPath(path, fill)
        contour.color = Color.argb(170, Color.red(tint), Color.green(tint), Color.blue(tint))
        canvas.drawPath(path, contour)
        fill.color = Color.argb(115, 218, 241, 222)
        canvas.drawCircle(cx, cy, base * 0.53f, fill)
        fill.color = Color.argb(34, 218, 241, 222)
        canvas.drawCircle(cx - base * 0.18f, cy - base * 0.2f, base * 0.31f, fill)

        if (!reducedMotion && moving) postInvalidateDelayed(if (state == State.IDLE) 120 else 45)
    }
}
