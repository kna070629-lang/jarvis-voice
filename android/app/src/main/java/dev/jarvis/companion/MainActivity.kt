package dev.jarvis.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.InputType
import android.text.TextUtils
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
    private val bg = Color.rgb(5, 8, 12)
    private val bgDeep = Color.rgb(9, 16, 23)
    private val panel = Color.rgb(12, 20, 27)
    private val panel2 = Color.rgb(18, 32, 43)
    private val cyan = Color.rgb(79, 216, 255)
    private val green = Color.rgb(89, 227, 160)
    private val white = Color.rgb(232, 244, 250)
    private val muted = Color.rgb(155, 176, 192)
    private val red = Color.rgb(255, 107, 107)
    private val io = Executors.newSingleThreadExecutor()
    private val healthIo = Executors.newSingleThreadExecutor()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

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
    private var wakeStatus: TextView? = null
    private lateinit var waveform: VoiceWaveformView
    private var waveformHost: FrameLayout? = null
    private var mainScroll: ScrollView? = null
    private var voiceSpinner: Spinner? = null
    private var voiceInfo: TextView? = null
    private var customVoiceStatus: TextView? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var suppressResume = false
    private var languageTag = "en-US"
    private var speechRate = 1.0f
    private var speechPitch = 0.85f
    private var discoveredApps: List<LaunchableApp> = emptyList()
    private var voiceChoices: List<Voice> = emptyList()
    private var selectedVoiceName: String? = null
    private var voiceBusy = false
    private var controlsDialog: Dialog? = null
    private val openDialogs = mutableSetOf<Dialog>()
    private var pendingStartWake = false
    private var wakeRequested = false
    private var updatingWakeToggle = false
    private val actionDispatcher by lazy { ActionDispatcher(this) }

    /** Feeds the live microphone level into the core display. */
    private val waveformTick = object : android.os.Handler(android.os.Looper.getMainLooper()) {
        private val runnable = object : Runnable {
            override fun run() {
                if (::waveform.isInitialized) {
                    waveform.level = WakeWordService.micLevel
                }
                postDelayed(this, 60)
            }
        }

        fun start() {
            removeCallbacks(runnable)
            postDelayed(runnable, 60)
        }

        fun stop() {
            removeCallbacks(runnable)
        }
    }

    private val prefs by lazy { getSharedPreferences("jarvis_voice_settings", MODE_PRIVATE) }

    private data class LaunchableApp(val label: String, val packageName: String)
    private data class SliderRow(val seekBar: SeekBar, val heading: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }
        loadVoicePreferences()
        buildUi()
        buildControls()
        tts = TextToSpeech(this, this)
        refreshApps()
        refreshPermissionStatus()
        checkBackend()
    }

    private fun loadVoicePreferences() {
        languageTag = prefs.getString("language_tag", "en-US") ?: "en-US"
        speechRate = prefs.getFloat("speech_rate", VoicePreferences.DEFAULT_RATE)
        speechPitch = prefs.getFloat("speech_pitch", VoicePreferences.DEFAULT_PITCH)
        selectedVoiceName = prefs.getString("voice_name", null)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(18), dp(14), dp(18), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.rgb(4, 10, 20),
                    Color.rgb(6, 14, 26),
                    bgDeep,
                    Color.rgb(3, 7, 13),
                ),
            )
        }
        setContentView(root)

        text(root, "J A R V I S", 18, white, true, 2).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        text(root, "PERSONAL VOICE ASSISTANT", 10, cyan, true, 6).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
        }
        status = text(root, "Python bridge  ·  checking loopback…", 12, muted, false, 4).apply {
            gravity = Gravity.CENTER
        }

        mainScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mainScroll?.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(mainScroll)

        waveformHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, waveformHeight())
        }
        waveform = VoiceWaveformView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            contentDescription = "JARVIS voice orb; animation reflects listening, thinking, speaking, and error states"
        }
        waveformHost?.addView(waveform)
        content.addView(waveformHost)

        voiceState = text(content, "Ready when you are", 14, white, false, 6).apply { gravity = Gravity.CENTER }
        partialText = text(content, "Tap to enable the wake phrase, then say Hey Jarvis", 13, muted, false, 8).apply {
            gravity = Gravity.CENTER
        }

        text(content, "TRANSCRIPT", 10, cyan, true, 4).setPadding(0, dp(6), 0, dp(4))
        history = text(content, "", 13, white, false, 8).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(panel, 16)
            setTextIsSelectable(true)
            visibility = View.GONE
        }

        permissionStatus = text(
            content,
            "Permissions  ·  microphone, accessibility and notifications are shown in Controls",
            10,
            muted,
            false,
            10,
        ).apply { gravity = Gravity.CENTER }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(bottom)

        micButton = button(bottom, "◉  Enable \"Hey Jarvis\"", true) {
            toggleWakeListening()
        }.also {
            it.contentDescription = "Enable or disable the visible Hey Jarvis wake phrase service"
            it.background = glassSurface()
            it.setTextColor(green)
            it.textSize = 15f
        }
        setMicIdleLabel()

        val actions = row(bottom)
        button(actions, "Stop speaking", false) { stopSpeaking() }
        button(actions, "Controls", false) { showControls() }
    }

    private fun buildControls() {
        val page = controlsPage
        if (page.childCount > 0) return

        conversationCheck = CheckBox(this).apply {
            text = "Wake phrase  ·  listen for \"Hey Jarvis, activate\""
            setTextColor(white)
            buttonTintList = android.content.res.ColorStateList.valueOf(cyan)
            textSize = 13f
            isChecked = WakeWordService.running
            minimumHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                if (updatingWakeToggle) return@setOnCheckedChangeListener
                if (checked) startWakeListening() else stopWakeListening("Wake phrase listening disabled")
            }
        }
        page.addView(conversationCheck, matchWrap(6))
        wakeStatus = text(
            page,
            "Wake service is off. When enabled, a visible foreground notification shows while the microphone listens for the wake phrase. The service listens for exactly one command, speaks the result, then returns to wake mode. Nothing listens silently.",
            12,
            muted,
            false,
            8,
        )
        button(page, "Explain microphone and notification permissions", false) {
            AlertDialog.Builder(this)
                .setTitle("Wake phrase permissions")
                .setMessage("JARVIS needs the microphone to hear \"Hey Jarvis, activate\" and a visible foreground notification while it listens. Android may process audio with its selected speech recognition provider. You can turn the wake phrase off at any time with this toggle or the notification action.")
                .setNegativeButton("Close", null)
                .show()
                .also { track(it) }
        }

        section(page, "WAKE MODE APP LAUNCHES")
        text(
            page,
            "Android blocks background apps from opening other screens. To let the wake service launch apps, Settings, the dialer, or composers while JARVIS is in the background, allow Display over other apps. Without it, wake mode reports a specific permission message instead of pretending the action failed.",
            12,
            muted,
            false,
            8,
        )
        button(page, "Explain and allow Display over other apps", false) { requestOverlayPermission() }

        section(page, "VOICE")
        val languageTags = VoiceCapabilities.LANGUAGES.map { it.tag }
        languageSpinner = spinner(page, VoiceCapabilities.LANGUAGES.map { it.label })
        languageSpinner.setSelection(languageTags.indexOf(languageTag).coerceAtLeast(0), false)
        languageSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            val next = languageTags.getOrElse(position) { "en-US" }
            if (next == languageTag) return@SimpleItemSelectedListener
            languageTag = next
            prefs.edit().putString("language_tag", languageTag).apply()
            if (ttsReady) {
                applyTtsLanguage()
                refreshVoiceChoices(preferSaved = false)
            }
        }

        voiceSpinner = spinner(page, listOf("Loading installed voices…"))
        button(page, "Preview selected voice", false) { previewVoice() }
        voiceInfo = text(
            page,
            "Installed voices load after Android TextToSpeech initializes. JARVIS prefers a masculine-tagged voice when the engine reports one.",
            11,
            muted,
            false,
            10,
        )

        val rateRow = slider(page, "Speech speed", progressFor(speechRate)) { value ->
            speechRate = value
            prefs.edit().putFloat("speech_rate", value).apply()
            applyTtsSettings()
        }
        val pitchRow = slider(page, "Voice pitch", progressFor(speechPitch)) { value ->
            speechPitch = value
            prefs.edit().putFloat("speech_pitch", value).apply()
            applyTtsSettings()
        }
        rateRow.seekBar.contentDescription = "Speech speed"
        pitchRow.seekBar.contentDescription = "Voice pitch"

        section(page, "CUSTOM VOICE FILE")
        text(
            page,
            "Pick an audio recording and JARVIS speaks by playing it instead of using Android text-to-speech. " +
                "The same recording is used by wake mode. Clear it to return to the built-in voice.",
            12,
            muted,
            false,
            8,
        )
        customVoiceStatus = text(page, VoicePreferences.customVoiceLabel(this), 12, white, false, 6)
        button(page, "Choose custom voice file", false) { pickCustomVoice() }
        button(page, "Clear custom voice file", false) { clearCustomVoice() }

        section(page, "NEURAL NETWORK")
        text(
            page,
            "The live view of the JARVIS architecture: the central core, the six inner engines " +
                "and the six outer clusters, with the connections that carried real traffic.",
            12,
            muted,
            false,
            8,
        )
        button(page, "Open the system diagnostics", false) {
            try {
                startActivity(Intent(this, DiagnosticsActivity::class.java))
            } catch (_: Exception) {
                Toast.makeText(this, "The diagnostics could not be opened", Toast.LENGTH_SHORT).show()
            }
        }
        button(page, "Open the voice diagnostics", false) {
            try {
                startActivity(Intent(this, VoiceDiagnosticsActivity::class.java))
            } catch (_: Exception) {
                Toast.makeText(this, "The voice diagnostics could not be opened", Toast.LENGTH_SHORT).show()
            }
        }
        button(page, "Open the live network", false) {
            try {
                startActivity(Intent(this, NetworkActivity::class.java))
            } catch (_: Exception) {
                Toast.makeText(this, "The network view could not be opened", Toast.LENGTH_SHORT).show()
            }
        }

        section(page, "LOCAL CAPABILITIES")
        text(
            page,
            "These run on the phone without the internet or a model. A supported command is answered " +
                "by the local brain, which is why simple requests are instant.",
            12,
            muted,
            false,
            8,
        )
        text(page, "Local routing: ${lastRouteLabel()}", 12, white, false, 4)
        text(page, "Local plugin list", 12, white, true, 4)
        for (capability in WakeWordService.localCapabilities()) {
            text(page, capability, 11, muted, false, 2)
        }

        section(page, "SECURE PYTHON CONNECTION")
        text(
            page,
            "The bridge accepts chat requests on 127.0.0.1 only. Paste its private pairing token; Android stores it encrypted with Android Keystore.",
            12,
            muted,
            false,
            8,
        )
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
            minimumHeight = dp(48)
            TokenStore.load(this@MainActivity)?.let { setText(it) }
        }
        page.addView(tokenInput, matchWrap(8))
        button(page, "Save token and connect", true) { saveTokenAndConnect() }

        section(page, "APPS")
        text(
            page,
            "Launchable apps visible to Android are listed here. Voice shortcuts and this picker open apps only; they do not send messages or place calls.",
            12,
            muted,
            false,
            8,
        )
        appSpinner = spinner(page, listOf("Loading installed apps…"))
        appsStatus = text(page, "Launchable apps have not been loaded.", 12, muted, false, 6)
        button(page, "Open selected app", false) { openSelectedApp() }
        button(page, "Refresh launchable apps", false) { refreshApps() }

        section(page, "ACCESSIBILITY  ·  OPTIONAL")
        text(
            page,
            "When you enable this service in Android Settings, JARVIS can perform Back/Home and, only after you press the control below, click one exact visible label. It ignores password fields and does not save screen text.",
            12,
            muted,
            false,
            8,
        )
        button(page, "Explain and open Accessibility settings", false) { requestAccessibilityEnable() }
        val navRow = row(page)
        button(navRow, "Navigate Back", false) { runAccessibilityNavigation("back") }
        button(navRow, "Go Home", false) { runAccessibilityNavigation("home") }
        button(page, "Tap an exact visible label…", false) { requestExactLabelTap() }

        section(page, "NOTIFICATIONS  ·  OPTIONAL")
        text(
            page,
            "Notification access is opt-in. JARVIS keeps at most 20 recent text notifications in memory. They are never sent unless you review and confirm a summary.",
            12,
            muted,
            false,
            8,
        )
        button(page, "Explain and open notification access", false) { requestNotificationAccess() }
        button(page, "Review and summarize recent notifications", false) { confirmNotificationSummary() }
        button(page, "Clear in-memory notifications", false) {
            JarvisNotificationListener.clear()
            toast("Recent notification text cleared")
        }

        section(page, "CONVERSATION")
        text(
            page,
            "The recent transcript stays on the main screen and remains in memory until the app closes.",
            12,
            muted,
            false,
            8,
        )
        button(page, "Clear on-screen transcript", false) {
            history.text = ""
            history.visibility = View.GONE
            toast("On-screen transcript cleared")
        }
    }

    private val controlsPage: LinearLayout by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
    }

    private fun showControls() {
        if (controlsDialog?.isShowing == true) return
        val dialog = Dialog(this)
        controlsDialog = dialog
        track(dialog)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(20), dp(16), dp(20), dp(10))
            background = rounded(bg, 20)
        }
        container.addView(TextView(this).apply {
            text = "Controls and permissions"
            textSize = 18f
            setTextColor(white)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        })

        (controlsPage.parent as? ViewGroup)?.removeView(controlsPage)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        }
        scroll.addView(
            controlsPage,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        button(container, "Done", true) { dialog.dismiss() }

        dialog.setContentView(container)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val width = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(280))
                val height = (resources.displayMetrics.heightPixels - dp(48)).coerceAtLeast(dp(280))
                setLayout(width, height)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        dialog.show()
    }

    private fun performAction(
        request: ActionRequest,
        confirmationToken: String? = null,
        reportToBackend: Boolean = false,
    ) {
        voiceBusy = true
        waveform.setState(VoiceWaveformView.State.THINKING)

        // The action is announced before it runs, so the user hears what is
        // about to happen first.
        val announcement = actionDispatcher.announce(request)
        if (announcement != null && confirmationToken == null) {
            addTurn("JARVIS", announcement)
            speakResponse(announcement)
            handler.postDelayed({
                val result = actionDispatcher.execute(request, confirmationToken)
                finishAction(result, request, reportToBackend)
            }, 700)
            return
        }
        val result = actionDispatcher.execute(request, confirmationToken)
        finishAction(result, request, reportToBackend)
    }

    private fun finishAction(
        result: ActionResult,
        request: ActionRequest,
        reportToBackend: Boolean,
    ) {
        when (result.status) {
            ActionStatus.NEEDS_CONFIRMATION -> {
                voiceBusy = false
                waveform.setState(VoiceWaveformView.State.IDLE)
                showActionConfirmation(request, result, reportToBackend)
            }
            ActionStatus.AMBIGUOUS -> {
                voiceBusy = false
                waveform.setState(VoiceWaveformView.State.IDLE)
                showAppChoice(request, result, reportToBackend)
            }
            ActionStatus.PERMISSION_REQUIRED -> {
                voiceBusy = false
                waveform.setState(VoiceWaveformView.State.ERROR)
                addTurn("JARVIS", result.message)
                showPermissionHelp(result.message)
                speakResponse(result.message)
            }
            else -> {
                if (result.succeeded && reportToBackend) {
                    val token = TokenStore.load(this)
                    if (!token.isNullOrBlank()) {
                        io.execute {
                            try {
                                BackendClient.sendActionResult(this, token, result)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                addTurn("JARVIS", result.message)
                speakResponse(result.message)
            }
        }
    }

    private fun showActionConfirmation(
        request: ActionRequest,
        result: ActionResult,
        reportToBackend: Boolean,
    ) {
        AlertDialog.Builder(this)
            .setTitle("Confirm device action")
            .setMessage("${result.message}\n\nJARVIS will perform this action only after you confirm.")
            .setNegativeButton("Cancel") { _, _ ->
                finishVoiceTurn()
                voiceState.text = "VOICE IDLE  ·  action cancelled"
                voiceState.setTextColor(muted)
                waveform.setState(VoiceWaveformView.State.IDLE)
            }
            .setPositiveButton("Confirm") { _, _ ->
                performAction(request, result.confirmationToken, reportToBackend)
            }
            .show()
            .also { track(it) }
    }

    private fun showAppChoice(
        request: ActionRequest,
        result: ActionResult,
        reportToBackend: Boolean,
    ) {
        val options = result.options
        if (options.isEmpty()) {
            finishVoiceTurn()
            speakResponse(result.message)
            return
        }
        val labels = options.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which app?")
            .setItems(labels) { _, index ->
                val option = options.getOrNull(index) ?: return@setItems
                val selected = ActionRequest.create(
                    "open_package",
                    mapOf("package" to option.packageName),
                )
                performAction(selected, reportToBackend = reportToBackend)
            }
            .setNegativeButton("Cancel") { _, _ ->
                finishVoiceTurn()
                voiceState.text = "VOICE IDLE  ·  app choice cancelled"
            }
            .show()
            .also { track(it) }
    }

    private fun showPermissionHelp(message: String) {
        val intent = when {
            message.contains("Notification access", ignoreCase = true) ->
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            message.contains("Accessibility", ignoreCase = true) ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            message.contains("Display over other apps", ignoreCase = true) ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            message.contains("Phone", ignoreCase = true) ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            message.contains("Microphone", ignoreCase = true) ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            else -> null
        }
        if (intent == null) return
        AlertDialog.Builder(this)
            .setTitle("Permission needed")
            .setMessage(message)
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open settings") { _, _ -> safeStart(intent) }
            .show()
            .also { track(it) }
    }

    private fun sendChat(text: String) {
        val token = TokenStore.load(this)
        if (token.isNullOrBlank()) {
            finishVoiceTurn()
            voiceState.text = "PAIRING REQUIRED  ·  save the local bridge token first"
            voiceState.setTextColor(red)
            return
        }
        voiceBusy = true
        voiceState.text = "THINKING  ·  contacting the local JARVIS backend"
        voiceState.setTextColor(cyan)
        waveform.setState(VoiceWaveformView.State.THINKING)
        io.execute {
            // The answer is streamed, so each sentence is spoken and shown as
            // soon as the backend produces it.
            BackendClient.chatStream(
                context = this,
                token = token,
                text = text,
                apps = actionDispatcher.launchableApps(),
                onSpeech = { piece ->
                    onUi {
                        waveform.setState(VoiceWaveformView.State.SPEAKING)
                        voiceState.text = "SPEAKING  ·  streaming response"
                        voiceState.setTextColor(green)
                        speakResponse(piece)
                    }
                },
                onAction = { action ->
                    onUi {
                        partialText.text = ""
                        voiceState.text = "ACTION  ·  the backend requested a device action"
                        voiceState.setTextColor(cyan)
                        performAction(action, reportToBackend = true)
                    }
                },
                onFinal = { finalText ->
                    onUi {
                        partialText.text = ""
                        if (finalText.isNotBlank()) {
                            addTurn("JARVIS", finalText)
                        }
                        finishVoiceTurn()
                    }
                },
                onError = { message ->
                    onUi {
                        voiceBusy = false
                        voiceState.text = "CONNECTION ERROR  ·  $message"
                        voiceState.setTextColor(red)
                        waveform.setState(VoiceWaveformView.State.OFFLINE)
                    }
                },
            )
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
        healthIo.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("http://127.0.0.1:8765/health").openConnection() as HttpURLConnection
                connection.connectTimeout = 2500
                connection.readTimeout = 2500
                TokenStore.load(this)?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val payload = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                onUi {
                    val paired = code == 200 && try {
                        JSONObject(payload).optBoolean("authenticated")
                    } catch (_: Exception) {
                        false
                    }
                    val runtime = try {
                        JSONObject(payload).optJSONObject("runtime")
                    } catch (_: Exception) {
                        null
                    }
                    val mode = runtime?.optString("mode", "")?.takeIf { it.isNotBlank() }
                    val ai = runtime?.optString("openrouter", "")?.takeIf { it.isNotBlank() }
                    status.text = when {
                        paired -> "Python bridge  ·  ${mode ?: "connected"}  ·  AI ${ai ?: "status unavailable"}"
                        code == 200 -> "Backend  ·  online  ·  save pairing token"
                        code == 401 -> "Backend  ·  token rejected  ·  check pairing"
                        else -> "Backend  ·  HTTP $code"
                    }
                    status.setTextColor(if (paired) green else if (code == 200) cyan else red)
                    if (!voiceBusy) {
                        if (paired && (mode == "offline" || ai == "not_configured")) {
                            waveform.setState(VoiceWaveformView.State.OFFLINE)
                        } else if (paired) {
                            waveform.setState(VoiceWaveformView.State.IDLE)
                        }
                    }
                }
            } catch (_: Exception) {
                onUi {
                    status.text = "Backend  ·  offline  ·  start ./jarvis-mobile.sh in Termux"
                    status.setTextColor(red)
                    if (!voiceBusy) waveform.setState(VoiceWaveformView.State.OFFLINE)
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun confirmWhatsAppDraft(number: String, body: String) {
        AlertDialog.Builder(this)
            .setTitle("Review WhatsApp draft")
            .setMessage("Recipient: +$number\n\nMessage:\n$body\n\nContinue to WhatsApp to review it? JARVIS will not press Send.")
            .setNegativeButton("Cancel") { _, _ ->
                finishVoiceTurn()
                voiceState.text = "VOICE IDLE  ·  WhatsApp draft cancelled"
            }
            .setPositiveButton("Open WhatsApp") { _, _ ->
                finishVoiceTurn()
                val uri = Uri.parse("https://wa.me/$number?text=${Uri.encode(body)}")
                val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")
                try {
                    startActivity(intent)
                    speakResponse("WhatsApp is open with a draft. Review it and press Send there if you choose.")
                } catch (_: Exception) {
                    val businessIntent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp.w4b")
                    try {
                        startActivity(businessIntent)
                    } catch (_: Exception) {
                        toast("WhatsApp is not installed or could not open this number")
                    }
                }
            }.show().also { track(it) }
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
        appSpinner.adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            labels,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    textSize = 13f
                    setSingleLine(false)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    setBackgroundColor(panel2)
                    textSize = 13f
                    setSingleLine(false)
                    maxLines = 3
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
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
        val aliases = setOf(
            "whatsapp",
            "spotify",
            "youtube",
            "chrome",
            "phone",
            "dialer",
            "messages",
            "camera",
            "clock",
            "alarms",
            "settings",
        )
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
    } catch (_: Exception) {
        false
    }

    private fun requestAccessibilityEnable() {
        AlertDialog.Builder(this)
            .setTitle("Optional Accessibility access")
            .setMessage("Android will let JARVIS inspect visible UI nodes for an exact label only after you request a tap, plus perform Back/Home. The service skips password fields, does not store screen contents, and never taps from AI output. You must enable it yourself in Android Settings.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open Settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .show()
            .also { track(it) }
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
        val input = EditText(this).apply {
            hint = "Exact visible label"
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
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
            }.show().also { track(it) }
    }

    private fun requestOverlayPermission() {
        val granted = Settings.canDrawOverlays(this)
        AlertDialog.Builder(this)
            .setTitle("Allow wake mode to launch apps")
            .setMessage(
                if (granted) {
                    "Display over other apps is already allowed. Wake mode can launch apps and system screens."
                } else {
                    "Android normally blocks a background app from opening other apps. Granting Display over other apps allows the visible Hey Jarvis service to launch the requested app or system screen. It does not read screen content and you can revoke it in Settings."
                },
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton(if (granted) "Open settings" else "Continue") { _, _ ->
                safeStart(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
            .show()
            .also { track(it) }
    }

    private fun requestNotificationAccess() {
        AlertDialog.Builder(this)
            .setTitle("Optional notification access")
            .setMessage("If enabled, Android grants JARVIS access to notification titles and text. They stay in memory on this device and are not uploaded automatically. A separate confirmation is required before sending a summary to your configured Python assistant.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                    toast("Open Android Settings → Notification access")
                }
            }.show().also { track(it) }
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
            .also { track(it) }
    }

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    /** A short description of how the last request was routed. */
    private fun lastRouteLabel(): String {
        val tier = WakeWordService.lastRouteTier.ifBlank { "none yet" }
        val latency = WakeWordService.lastRouteLatencyMs
        val handled = WakeWordService.localHandledCount
        val escalated = WakeWordService.escalatedCount
        return "$tier in ${latency} ms  (local $handled, model $escalated)"
    }

    private fun refreshPermissionStatus() {
        val mic = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "mic granted" else "mic not granted"
        val accessibility = if (isAccessibilityEnabled()) "accessibility enabled" else "accessibility off"
        val notifications = if (isNotificationAccessEnabled()) "notification access enabled" else "notification access off"
        val launches = if (Settings.canDrawOverlays(this)) "background launches allowed" else "background launches off"
        permissionStatus.text = "Permissions  ·  $mic  ·  $accessibility  ·  $notifications  ·  $launches"
        permissionStatus.setTextColor(
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) green else muted,
        )
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

    private fun pickCustomVoice() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("audio/*", "application/ogg", "audio/mpeg", "audio/wav", "audio/x-wav", "audio/mp4"),
            )
        }
        try {
            startActivityForResult(intent, REQUEST_CUSTOM_VOICE)
        } catch (_: Exception) {
            Toast.makeText(this, "No file picker is available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearCustomVoice() {
        VoicePreferences.setCustomVoiceUri(this, null)
        refreshCustomVoiceStatus()
        Toast.makeText(this, "Custom voice cleared", Toast.LENGTH_SHORT).show()
    }

    private fun refreshCustomVoiceStatus() {
        customVoiceStatus?.text = VoicePreferences.customVoiceLabel(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CUSTOM_VOICE) return
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        VoicePreferences.setCustomVoiceUri(this, uri)
        refreshCustomVoiceStatus()
        Toast.makeText(this, "Custom voice selected", Toast.LENGTH_SHORT).show()
    }

    private fun speakResponse(text: String) {
        suppressResume = false
        // A custom voice recording replaces text-to-speech when the user picked one.
        val customVoice = VoicePreferences.customVoiceUri(this)
        if (customVoice != null) {
            voiceBusy = true
            waveform.setState(VoiceWaveformView.State.SPEAKING)
            CustomVoicePlayer.play(
                this,
                customVoice,
                onComplete = {
                    runOnUiThread {
                        if (!voiceBusy) return@runOnUiThread
                        finishVoiceTurn()
                        voiceState.text = "VOICE IDLE  ·  custom voice"
                        voiceState.setTextColor(muted)
                        waveform.setState(VoiceWaveformView.State.IDLE)
                    }
                },
                onFailed = {
                    // The recording could not be played, so fall back to the engine.
                    runOnUiThread { speakWithEngine(text) }
                },
            )
            return
        }
        speakWithEngine(text)
    }

    private fun speakWithEngine(text: String) {
        val engine = tts
        if (!ttsReady || engine == null) {
            finishVoiceTurn()
            voiceState.text = "TTS ERROR  ·  Android speech engine is not ready"
            voiceState.setTextColor(red)
            return
        }
        applyTtsSettings()
        voiceBusy = true
        val result = engine.speak(text.take(12000), TextToSpeech.QUEUE_FLUSH, null, "jarvis-${UUID.randomUUID()}")
        if (result == TextToSpeech.ERROR) {
            finishVoiceTurn()
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
        voiceBusy = false
        tts?.stop()
        CustomVoicePlayer.stop()
        voiceState.text = "VOICE IDLE  ·  speech stopped"
        voiceState.setTextColor(muted)
        waveform.setState(VoiceWaveformView.State.IDLE)
    }

    private fun currentLocale() = Locale.forLanguageTag(languageTag)

    private fun applyTtsLanguage(): Int {
        val result = tts?.setLanguage(currentLocale()) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            voiceState.text = "TTS LANGUAGE ERROR  ·  selected voice is unavailable on this device"
            voiceState.setTextColor(red)
        }
        return result
    }

    private fun applyTtsSettings() {
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(speechPitch)
    }

    private fun refreshVoiceChoices(preferSaved: Boolean) {
        val engine = tts ?: return
        if (!ttsReady) return
        val all = try {
            engine.voices?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val language = currentLocale()
        voiceChoices = all
            .filter { it.locale.language.equals(language.language, ignoreCase = true) }
            .sortedWith(compareByDescending<Voice> { voiceScore(it, language) }.thenBy { it.name })

        val spinner = voiceSpinner
        if (voiceChoices.isEmpty()) {
            selectedVoiceName = null
            spinner?.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                listOf("No installed voice reported for ${language.displayName}"),
            )
            engine.setLanguage(language)
            updateVoiceInfo()
            return
        }

        val labels = voiceChoices.map { describeVoice(it) }
        spinner?.adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            labels,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    textSize = 13f
                    setSingleLine(false)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    setBackgroundColor(panel2)
                    textSize = 13f
                    setSingleLine(false)
                    maxLines = 3
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
        }

        val savedIndex = voiceChoices.indexOfFirst { it.name == selectedVoiceName }
        val masculineIndex = voiceChoices.indexOfFirst { voiceGenderTag(it) == GENDER_MASCULINE }
        val targetIndex = when {
            preferSaved && savedIndex >= 0 -> savedIndex
            masculineIndex >= 0 -> masculineIndex
            savedIndex >= 0 -> savedIndex
            else -> 0
        }
        spinner?.setSelection(targetIndex, false)
        spinner?.onItemSelectedListener = SimpleItemSelectedListener { position ->
            if (position in voiceChoices.indices) selectVoice(position)
        }
        selectVoice(targetIndex)
    }

    private fun selectVoice(position: Int) {
        val voice = voiceChoices.getOrNull(position) ?: return
        selectedVoiceName = voice.name
        prefs.edit().putString("voice_name", voice.name).apply()
        if (ttsReady) {
            val result = tts?.setVoice(voice) ?: TextToSpeech.ERROR
            if (result == TextToSpeech.ERROR) tts?.setLanguage(currentLocale())
        }
        updateVoiceInfo()
    }

    private fun updateVoiceInfo() {
        val info = voiceInfo ?: return
        if (voiceChoices.isEmpty()) {
            info.text = "No installed voices were reported for ${currentLocale().displayName}. JARVIS falls back to the engine default language and the configured pitch (${formatPitch(speechPitch)})."
            return
        }
        val voice = voiceChoices.firstOrNull { it.name == selectedVoiceName } ?: voiceChoices.firstOrNull()
        if (voice == null) {
            info.text = "No installed voice matched the selected language."
            return
        }
        val tag = voiceGenderTag(voice)
        val source = if (voice.isNetworkConnectionRequired) "requires network" else "on-device"
        val lines = mutableListOf(
            "Selected: ${voice.name}",
            "${voice.locale.displayName}  ·  $source  ·  $tag",
        )
        if (tag != GENDER_MASCULINE) {
            lines.add(VoicePreferences.maleInstallHint())
        }
        info.text = lines.joinToString("\n")
    }

    private fun previewVoice() {
        val engine = tts
        if (!ttsReady || engine == null) {
            finishVoiceTurn()
            toast("Android TextToSpeech is not ready yet")
            return
        }
        applyTtsSettings()
        suppressResume = true
        voiceBusy = true
        voiceState.text = "PREVIEW  ·  speaking the selected voice"
        voiceState.setTextColor(cyan)
        val result = engine.speak(
            "Hello. I am JARVIS. This is the selected Android voice.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "jarvis-preview-${UUID.randomUUID()}",
        )
        if (result == TextToSpeech.ERROR) {
            suppressResume = false
            finishVoiceTurn()
            toast("Android rejected the voice preview")
        }
    }

    private fun voiceScore(voice: Voice, language: Locale): Int {
        val name = voice.name.lowercase(Locale.ROOT)
        var score = 0
        score += if (VoicePreferences.isMasculine(voice)) 100 else -100
        if (voice.locale.language.equals(language.language, ignoreCase = true)) score += 50
        if (!voice.isNetworkConnectionRequired) score += 20 else score -= 10
        if (name.contains("local")) score += 5
        return score
    }

    private fun voiceGenderTag(voice: Voice): String = when {
        VoicePreferences.isMasculine(voice) -> GENDER_MASCULINE
        voice.name.lowercase(Locale.ROOT).contains("female") -> "feminine tag"
        else -> "neutral tag"
    }

    private fun describeVoice(voice: Voice): String {
        val source = if (voice.isNetworkConnectionRequired) "network" else "on-device"
        return "${voiceGenderTag(voice)} · ${voice.locale.displayName} · $source · ${voice.name}"
    }

    override fun onInit(result: Int) {
        if (isFinishing || isDestroyed) return
        ttsReady = result == TextToSpeech.SUCCESS
        if (!ttsReady) {
            voiceState.text = "TTS ERROR  ·  Android could not initialize a speech engine"
            voiceState.setTextColor(red)
            waveform.setState(VoiceWaveformView.State.ERROR)
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onUi {
                voiceBusy = true
                voiceState.text = "SPEAKING  ·  Android TextToSpeech"
                voiceState.setTextColor(green)
                waveform.setState(VoiceWaveformView.State.SPEAKING)
            }

            override fun onDone(utteranceId: String?) = onUi {
                voiceBusy = false
                voiceState.text = if (WakeWordService.running) {
                    "VOICE IDLE  ·  wake phrase is active"
                } else {
                    "VOICE IDLE  ·  microphone is off"
                }
                voiceState.setTextColor(muted)
                waveform.setState(VoiceWaveformView.State.IDLE)
            }

            @Deprecated("Deprecated by Android")
            override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

            override fun onError(utteranceId: String?, errorCode: Int) = onUi {
                voiceBusy = false
                voiceState.text = "TTS ERROR  ·  Android speech engine failed ($errorCode)"
                voiceState.setTextColor(red)
                waveform.setState(VoiceWaveformView.State.ERROR)
            }
        })
        val languageResult = applyTtsLanguage()
        applyTtsSettings()
        refreshVoiceChoices(preferSaved = true)
        val languageUnavailable = voiceChoices.isEmpty() &&
            (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED)
        if (languageUnavailable) {
            voiceState.text = "TTS LANGUAGE ERROR  ·  no installed voice supports ${currentLocale().displayName}"
            voiceState.setTextColor(red)
        } else {
            voiceState.text = if (WakeWordService.running) {
                "VOICE READY  ·  wake phrase is active"
            } else {
                "VOICE READY  ·  enable the Hey Jarvis wake phrase to speak"
            }
            voiceState.setTextColor(muted)
        }
        updateWakeUi(WakeWordService.stateText)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                refreshPermissionStatus()
                if (pendingStartWake) {
                    pendingStartWake = false
                    startWakeListening()
                }
            } else {
                pendingStartWake = false
                finishVoiceTurn()
                setWakeToggle(false)
                voiceState.text = "VOICE PERMISSION DENIED  ·  allow microphone for the wake phrase"
                voiceState.setTextColor(red)
            }
        }
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                toast("Wake phrase notification can now be shown")
            } else {
                toast("The foreground notification may still appear in the notification shade")
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        waveformHost?.let { host ->
            host.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, waveformHeight())
            host.requestLayout()
        }
    }

    override fun onPause() {
        WakeWordService.listener = null
        finishVoiceTurn()
        suppressResume = true
        tts?.stop()
        waveformTick.stop()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::permissionStatus.isInitialized) refreshPermissionStatus()
        if (::status.isInitialized) checkBackend()
        WakeWordService.listener = { state -> onUi { updateWakeUi(state) } }
        updateWakeUi(WakeWordService.stateText)
        // Mirror the live microphone level into the core so the display reacts
        // to the voice in real time.
        waveformTick.start()
    }

    override fun onDestroy() {
        controlsDialog?.dismiss()
        openDialogs.toList().forEach { dialog ->
            try {
                dialog.dismiss()
            } catch (_: Exception) {
            }
        }
        openDialogs.clear()
        tts?.stop()
        tts?.shutdown()
        io.shutdownNow()
        healthIo.shutdownNow()
        super.onDestroy()
    }

    private fun addTurn(speaker: String, value: String) {
        val prior = history.text?.toString().orEmpty()
        val separator = if (prior.isBlank()) "" else "\n\n"
        history.text = (prior + separator + "$speaker\n" + value).trim().takeLast(12000)
        history.visibility = View.VISIBLE
        mainScroll?.post { mainScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun section(parent: LinearLayout, title: String) {
        text(parent, title, 12, cyan, true, 10).setPadding(0, dp(18), 0, dp(8))
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
        maxLines = 3
        setTextColor(if (primary) bg else cyan)
        background = rounded(if (primary) cyan else panel2, 14)
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { action() }
        val params = if (parent is LinearLayout && parent.orientation == LinearLayout.HORIZONTAL) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
        } else {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }
        parent.addView(this, params)
    }

    private fun spinner(parent: LinearLayout, options: List<String>): Spinner = Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    textSize = 13f
                    setSingleLine(false)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    setBackgroundColor(panel2)
                    textSize = 13f
                    setSingleLine(false)
                    maxLines = 3
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        background = rounded(panel2, 12)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        minimumHeight = dp(48)
        parent.addView(this, matchWrap(8))
    }

    private fun slider(parent: LinearLayout, label: String, initial: Int, changed: (Float) -> Unit): SliderRow {
        val heading = text(parent, "$label  ·  1.00", 12, muted, false, 0)
        val seekBar = SeekBar(this).apply {
            max = 100
            progress = initial.coerceIn(0, 100)
            progressTintList = android.content.res.ColorStateList.valueOf(cyan)
            thumbTintList = android.content.res.ColorStateList.valueOf(green)
            minimumHeight = dp(44)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = 0.5f + progress / 100f
                    heading.text = "$label  ·  ${formatPitch(value)}"
                    changed(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        heading.text = "$label  ·  ${formatPitch(0.5f + initial.coerceIn(0, 100) / 100f)}"
        parent.addView(seekBar, matchWrap(4, dp(44)))
        return SliderRow(seekBar, heading)
    }

    private fun formatPitch(value: Float): String = "%.2f".format(Locale.US, value)

    private fun progressFor(value: Float): Int = ((value - 0.5f) * 100f).toInt().coerceIn(0, 100)

    private fun toggleWakeListening() {
        if (WakeWordService.running || wakeRequested) {
            stopWakeListening("Wake phrase listening disabled")
        } else {
            startWakeListening()
        }
    }

    private fun startWakeListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStartWake = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        wakeRequested = true
        prefs.edit().putBoolean("wake_enabled", true).apply()
        val intent = Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            setWakeToggle(true)
            updateWakeUi("Starting wake phrase service")
            handler.postDelayed({
                if (!WakeWordService.running) {
                    wakeRequested = false
                    updateWakeUi("Wake service did not start")
                }
            }, 2500)
        } catch (exception: Exception) {
            wakeRequested = false
            setWakeToggle(false)
            updateWakeUi("Wake service could not start: ${exception.message ?: "unknown error"}")
        }
    }

    private fun stopWakeListening(message: String) {
        wakeRequested = false
        pendingStartWake = false
        prefs.edit().putBoolean("wake_enabled", false).apply()
        try {
            stopService(Intent(this, WakeWordService::class.java))
        } catch (_: Exception) {
        }
        setWakeToggle(false)
        updateWakeUi(message)
    }

    private fun setWakeToggle(checked: Boolean) {
        if (!::conversationCheck.isInitialized) return
        updatingWakeToggle = true
        conversationCheck.isChecked = checked
        updatingWakeToggle = false
    }

    private fun updateWakeUi(state: String) {
        val active = WakeWordService.running || wakeRequested
        setWakeToggle(active)
        wakeStatus?.text = if (active) {
            "Wake phrase: $state"
        } else {
            "Wake service is off. Enable it to listen for \"Hey Jarvis, activate\". A visible notification appears while it listens."
        }
        wakeStatus?.setTextColor(if (active) green else muted)
        setMicIdleLabel()
    }

    private fun setMicIdleLabel() {
        if (!::micButton.isInitialized) return
        val active = WakeWordService.running || wakeRequested
        micButton.text = if (active) "◉  Disable \"Hey Jarvis\"" else "◉  Enable \"Hey Jarvis\""
        micButton.setTextColor(if (active) red else green)
    }

    private fun finishVoiceTurn() {
        voiceBusy = false
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun glassSurface() = GradientDrawable().apply {
        setColor(Color.argb(72, 35, 83, 71))
        cornerRadius = dp(32).toFloat()
        setStroke(dp(1), Color.argb(145, 79, 216, 255))
    }

    private fun matchWrap(bottom: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (height == 0) ViewGroup.LayoutParams.WRAP_CONTENT else height,
        ).apply { bottomMargin = dp(bottom) }

    private fun waveformHeight(): Int {
        val metrics = resources.displayMetrics
        val availableWidth = metrics.widthPixels - dp(36)
        val availableHeight = (metrics.heightPixels * 0.42f).toInt()
        return minOf(availableWidth, availableHeight).coerceIn(dp(140), dp(300))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun track(dialog: Dialog): Dialog {
        openDialogs.add(dialog)
        dialog.setOnDismissListener {
            openDialogs.remove(dialog)
            if (controlsDialog === dialog) controlsDialog = null
        }
        return dialog
    }

    private fun dialogsShowing(): Boolean = openDialogs.any { it.isShowing }

    private fun onUi(block: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_MIC = 41
        private const val REQUEST_NOTIFICATIONS = 42
        private const val REQUEST_CUSTOM_VOICE = 43
        private const val GENDER_MASCULINE = "masculine tag"
    }
}

private class SimpleItemSelectedListener(private val selected: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = selected(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
