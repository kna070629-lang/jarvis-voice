package dev.jarvis.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Visible foreground wake-phrase listener. It waits for "Hey Jarvis", speaks a
 * short acknowledgement, listens for exactly one command, executes it through
 * the structured dispatcher, and returns to wake mode. Errors always restart
 * with a delay and exponential backoff; there is no tight polling loop.
 */
class WakeWordService : Service(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var dispatcher: ActionDispatcher? = null
    private lateinit var engine: IntentEngine
    private var mode = MODE_WAKE
    private var stopped = false
    private var startingRecognition = false
    private var languageIndex = 0
    private var activeLanguage: String? = null
    private var conversationActive = false
    private var followUpFailures = 0
    private var pendingRequest: ActionRequest? = null
    private var pendingToken: String? = null

    /** The one place a pending action lives. */
    private val confirmations = ConfirmationManager()

    /** The community JARVIS voice, with the built in voice as the fallback. */
    private val piperVoice = PiperVoicePlayer(this)
    private var restartDelayMs = MIN_RESTART_MS
    private var afterSpeech: (() -> Unit)? = null
    private var lastResultText: String = ""
    private var lastResultAt: Long = 0L

    /** True while the assistant is speaking, so the microphone stays closed. */
    private var speaking = false
    private var notificationManager: NotificationManager? = null

    private val restartRunnable = Runnable {
        if (!stopped) {
            startListening(if (mode == MODE_COMMAND) "Listening for your command" else "Waiting for Hey Jarvis")
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        dispatcher = ActionDispatcher(this)
        engine = IntentEngine(ActionPluginRegistry(this))
        capabilities = engine.plugins.describe()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publish("Permission needed: Microphone")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            publish("Speech recognition is unavailable on this device")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startVisibleForeground()) {
            publish("Android did not allow the microphone foreground service")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        stopped = false
        restartDelayMs = MIN_RESTART_MS
        ensureRecognizer()
        mode = MODE_WAKE
        startListening("Waiting for Hey Jarvis")
        return START_STICKY
    }

    private fun startVisibleForeground(): Boolean = try {
        val notification = buildNotification("Waiting for Hey Jarvis")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "JARVIS wake phrase",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows when JARVIS is listening for the wake phrase."
            setShowBadge(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS wake phrase is on")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    private fun publish(text: String) {
        stateText = text
        listener?.invoke(text)
        try {
            notificationManager?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        // Prefer the system recognition service. The on-device recognizer only
        // supports languages whose models are installed and otherwise fails with
        // ERROR_LANGUAGE_UNAVAILABLE (13), which used to leave the wake loop stuck.
        // Some Android 16 builds ship without the on-device recognition
        // service, so the support is checked instead of assumed.
        val onDeviceSupported = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        } catch (_: Exception) {
            false
        }
        recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (_: Exception) {
            null
        } ?: try {
            if (onDeviceSupported) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        recognizer?.setRecognitionListener(recognitionListener)
    }

    private fun languageCandidates(): List<String> {
        val saved = getSharedPreferences(VoicePreferences.PREFS, MODE_PRIVATE)
            .getString("language_tag", null)
            .orEmpty()
        val system = Locale.getDefault().toLanguageTag()
        // The saved language comes first, then the device language, then the
        // full supported list. Tamil and Indian English are included because
        // they are part of the supported set.
        val preferred = listOf(saved, system) +
            VoiceCapabilities.LANGUAGES.map { it.tag }
        return preferred
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Locale.forLanguageTag(it).toLanguageTag() }
            .filter { it.isNotBlank() && it != "und" }
            .distinct()
    }

    private fun recognitionLanguage(): String {
        if (activeLanguage == null) {
            val candidates = languageCandidates()
            activeLanguage = candidates.getOrElse(languageIndex) { candidates.firstOrNull() ?: "en-US" }
        }
        return activeLanguage ?: "en-US"
    }

    /** Move to the next known language. Returns false when every candidate was tried. */
    private fun rotateRecognitionLanguage(): Boolean {
        val candidates = languageCandidates()
        if (candidates.isEmpty()) return false
        languageIndex += 1
        if (languageIndex >= candidates.size) {
            languageIndex = 0
            activeLanguage = candidates.first()
            return false
        }
        activeLanguage = candidates[languageIndex]
        return true
    }

    private fun startListening(label: String) {
        if (stopped) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publish("Permission needed: Microphone")
            stopSelf()
            return
        }
        handler.removeCallbacks(restartRunnable)
        ensureRecognizer()
        val engine = recognizer
        if (engine == null) {
            scheduleRestart(3000, "Speech recognizer is unavailable")
            return
        }
        startingRecognition = true
        try {
            engine.cancel()
        } catch (_: Exception) {
        }
        val language = recognitionLanguage()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Stop the utterance sooner after the user stops talking so replies
            // start faster, but keep the minimum length so a short word is not
            // cut off before the recogniser has heard it.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            // Bias the recogniser towards the command vocabulary. This is the
            // cheap version of a grammar constrained recogniser and it lifts
            // the accuracy of short commands without any extra compute.
            putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, COMMAND_BIASING)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY)
                putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_CONFIDENCE, true)
                putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)
            }
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                if (mode == MODE_WAKE) 1100L else 800L,
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (mode == MODE_WAKE) "Say Hey Jarvis" else "Speak your command")
        }
        publish(label)
        try {
            engine.startListening(intent)
        } catch (_: Exception) {
            scheduleRestart(1500, "Microphone is busy")
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            startingRecognition = false
            restartDelayMs = MIN_RESTART_MS
            languageIndex = languageCandidates().indexOf(recognitionLanguage()).coerceAtLeast(0)
            publish(if (mode == MODE_WAKE) "Waiting for Hey Jarvis" else "Listening for your command")
        }

        override fun onBeginningOfSpeech() = Unit

        /**
         * The recogniser reports the microphone level while it listens. It is
         * published so the interface can show the voice reacting in real time.
         */
        override fun onRmsChanged(rmsdB: Float) {
            if (rmsdB <= 0f) return
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            handler.post { micLevel = level }
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            startingRecognition = false
            publish("Thinking")
        }

        override fun onError(error: Int) {
            recordError("recognition error $error")
            if (startingRecognition && error == SpeechRecognizer.ERROR_CLIENT) return
            startingRecognition = false
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    publish("Permission needed: Microphone")
                    stopSelf()
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (mode == MODE_COMMAND) {
                        if (pendingRequest != null) {
                            pendingRequest = null
                            pendingToken = null
                            speak("Cancelled.") { continueConversation() }
                        } else if (conversationActive && followUpFailures < 1) {
                            followUpFailures += 1
                            startListening("Still listening")
                        } else {
                            endConversation()
                        }
                    } else {
                        scheduleRestart(1200, "Waiting for Hey Jarvis")
                    }
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (rotateRecognitionLanguage()) {
                        scheduleRestart(700, "Trying ${recognitionLanguage()} speech recognition")
                    } else {
                        publish("Speech recognition language unavailable; install voice data in Google speech services")
                        scheduleRestart(5000, "Speech recognition language unavailable")
                    }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(2500, "Microphone is busy")
                SpeechRecognizer.ERROR_AUDIO -> scheduleRestart(1800, "Microphone audio is unavailable")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    scheduleRestart(4000, "Speech recognition network error")
                else -> scheduleRestart(2000, speechError(error))
            }
        }

        override fun onResults(results: Bundle?) {
            startingRecognition = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f
            restartDelayMs = MIN_RESTART_MS

            // The recogniser sometimes delivers the same final result twice.
            // The same text within a short window is one utterance, so it is
            // only handled once.
            if (text.isNotBlank()) {
                val now = System.currentTimeMillis()
                if (text == lastResultText && now - lastResultAt < DUPLICATE_RESULT_WINDOW_MS) {
                    return
                }
                lastResultText = text
                lastResultAt = now
            }
            if (mode == MODE_WAKE) {
                if (WakePhrase.matches(text)) {
                    val trailing = WakePhrase.trailingCommand(text)
                    if (trailing != null) {
                        handleWakeCommand(trailing)
                    } else {
                        handleWake()
                    }
                } else {
                    scheduleRestart(800, "Waiting for Hey Jarvis")
                }
            } else {
                followUpFailures = 0
                // A low confidence result is treated as not understood, so a
                // mangled command is never executed.
                if (confidence in 0f..LOW_CONFIDENCE_LIMIT && text.isNotBlank()) {
                    publish("I did not catch that clearly")
                    speak("I did not catch that clearly. Say it again.") { continueConversation() }
                    return
                }
                val awaiting = confirmations.current()
                when {
                    WakePhrase.isStopCommand(text) -> {
                        confirmations.clear("the user stopped the assistant")
                        endConversation(spoken = true)
                    }
                    awaiting != null -> handleConfirmationReply(text)
                    text.isBlank() -> speak("I didn't catch that. Say it again.") { continueConversation() }
                    else -> processCommand(text)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handleWake() {
        if (stopped) return
        mode = MODE_COMMAND
        beginConversation()
        publish("Awake. Listening for your command")
        startingRecognition = true
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        speak(WakePhrase.LISTENING_REPLY) {
            handler.postDelayed({
                if (!stopped) startListening("Listening for your command")
            }, 250)
        }
    }

    private fun handleWakeCommand(text: String) {
        if (stopped) return
        mode = MODE_COMMAND
        beginConversation()
        publish("Awake. Processing the spoken command")
        startingRecognition = true
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        speak(WakePhrase.LISTENING_REPLY) {
            handler.postDelayed({
                if (!stopped) processCommand(text)
            }, 250)
        }
    }

    /** Marks a conversation as running so follow-up commands skip the wake phrase. */
    private fun beginConversation() {
        conversationActive = true
        followUpFailures = 0
    }

    /**
     * Keeps the conversation open and listens for the next command without
     * requiring the wake phrase again.
     */
    private fun continueConversation() {
        if (stopped) return
        mode = MODE_COMMAND
        conversationActive = true
        followUpFailures = 0
        pendingRequest = null
        pendingToken = null
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed({
            if (!stopped) startListening("Listening for your command")
        }, 250)
    }

    /**
     * Ends the conversation and returns to wake mode. When [spoken] is true the
     * stop command was spoken by the user, so a farewell is spoken first.
     */
    private fun endConversation(spoken: Boolean = false) {
        if (stopped) return
        conversationActive = false
        followUpFailures = 0
        pendingRequest = null
        pendingToken = null
        confirmations.clear("the conversation ended")
        mode = MODE_WAKE
        handler.removeCallbacks(restartRunnable)
        if (spoken) {
            speak("Goodbye.") { returnToWake() }
        } else {
            handler.postDelayed({
                if (!stopped) startListening("Waiting for Hey Jarvis")
            }, 250)
        }
    }

    /**
     * Asks the user to confirm a sensitive action by voice instead of sending
     * them to the app, so a spoken command can be completed in one go.
     */
    private fun requestConfirmation(request: ActionRequest, question: String) {
        val result = dispatcher?.execute(request)
            ?: ActionResult(request.id, ActionStatus.FAILED, "The action dispatcher is unavailable.")
        if (result.status != ActionStatus.NEEDS_CONFIRMATION) {
            // The action did not need a confirmation after all, so it is
            // reported and the conversation continues.
            confirmations.complete(request.id, result.message)
            speak(result.message) { continueConversation() }
            return
        }
        // The manager refuses to ask twice for the same action.
        if (!confirmations.begin(request, result.confirmationToken.orEmpty(), question)) {
            return
        }
        speak("$question Say yes to continue, or no to cancel.") { continueConversation() }
    }

    /**
     * Runs or drops the action that was waiting for a spoken yes or no.
     *
     * The manager consumes the action, so a repeated yes cannot run it twice
     * and a yes with nothing pending cannot replay an old action.
     */
    private fun handleConfirmationReply(text: String) {
        when (confirmations.decide(text)) {
            ConfirmationManager.Decision.DECLINED -> {
                speak("Cancelled.") { continueConversation() }
            }
            ConfirmationManager.Decision.CONFIRMED -> {
                val pending = confirmations.current() ?: confirmations.consumeConfirmed()
                if (pending == null) {
                    speak("There is nothing waiting for confirmation.") { continueConversation() }
                    return
                }
                val result = dispatcher?.execute(pending.action, pending.token)
                    ?: ActionResult(
                        pending.action.id,
                        ActionStatus.FAILED,
                        "The action dispatcher is unavailable.",
                    )
                if (result.status == ActionStatus.SUCCEEDED) {
                    confirmations.complete(pending.action.id, result.message)
                } else {
                    confirmations.fail(pending.action.id, result.message)
                }
                speak(result.message) { continueConversation() }
            }
            ConfirmationManager.Decision.EXPIRED -> {
                speak("That confirmation timed out.") { continueConversation() }
            }
            ConfirmationManager.Decision.UNKNOWN -> {
                speak("Say yes to continue, or no to cancel.") { continueConversation() }
            }
            ConfirmationManager.Decision.NONE -> {
                speak("There is nothing waiting for confirmation.") { continueConversation() }
            }
        }
    }

    /**
     * Runs a device action.
     *
     * The assistant says what it is about to do first, and only then performs
     * the action. That is how a caller expects a call to feel: the name is
     * heard before the phone starts ringing.
     */
    private fun runAction(request: ActionRequest) {
        val action = dispatcher
            ?: run {
                speak("The action dispatcher is unavailable.") { continueConversation() }
                return
            }
        val announcement = action.announce(request)
        if (announcement == null) {
            val result = action.execute(request)
            reportResult(request, result)
            return
        }
        speak(announcement) {
            val result = action.execute(request)
            // A successful action was already announced, so only a failure is
            // spoken again.
            if (!result.succeeded) {
                reportResult(request, result)
            } else {
                continueConversation()
            }
        }
    }

    /** Speaks the result of an action. */
    private fun reportResult(request: ActionRequest, result: ActionResult) {
        when (result.status) {
            ActionStatus.AMBIGUOUS -> speak("I found more than one app matching that name. Open JARVIS to choose one.") { returnToWake() }
            ActionStatus.NEEDS_CONFIRMATION -> speak("Confirmation is needed. Open JARVIS to review it.") { returnToWake() }
            else -> speak(result.message) { continueConversation() }
        }
    }

    private fun confirmationQuestion(request: ActionRequest): String = when (request.type) {
        "call" -> "Shall I call ${request.args["number"].orEmpty()}?"
        "prepare_sms" -> "Shall I prepare the message for ${request.args["number"].orEmpty()}?"
        "prepare_whatsapp" -> "Shall I open WhatsApp for ${request.args["number"].orEmpty()}?"
        "read_notifications" -> "Shall I read your notifications?"
        else -> "Shall I do that?"
    }

    private fun processCommand(text: String) {
        // The time and the date are answered on the phone, so they never cost
        // a backend round trip.
        localTimeOrDate(text)?.let { reply ->
            recordRoute("local-time", 0L)
            speak(reply) { continueConversation() }
            return
        }
        // The local-first engine decides whether this can be answered on the
        // phone. Only when no local tier understands it does the model get
        // involved, which keeps simple commands fast and works offline.
        when (val decision = engine.route(text)) {
            is IntentEngine.Result.Cancelled -> {
                confirmations.clear("the user stopped the assistant")
                speak("Stopped.") { returnToWake() }
            }
            is IntentEngine.Result.Handled -> {
                recordRoute(decision.tier, decision.latencyMs)
                publish("Handled locally in ${decision.latencyMs} ms")
                val request = decision.request
                if (request != null) {
                    // A device action still goes through the dispatcher, so
                    // permission checks and confirmations are applied.
                    if (request.requiresConfirmation) {
                        requestConfirmation(request, confirmationQuestion(request))
                        return
                    }
                    runAction(request)
                    return
                }
                val plugin = decision.plugin
                if (plugin != null) {
                    val missing = engine.plugins.missingPermissions(plugin)
                    if (missing.isNotEmpty()) {
                        speak("Permission needed for ${plugin.description.lowercase()}.") { continueConversation() }
                        return
                    }
                    val outcome = plugin.execute(this, decision.arguments)
                    speak(outcome.message) { continueConversation() }
                    return
                }
                speak("Done.") { continueConversation() }
            }
            is IntentEngine.Result.Escalate -> {
                recordRoute("escalate", decision.latencyMs)
                chatWithBackend(text)
            }
        }
    }

    /** Answers a time or date question without asking the backend. */
    private fun localTimeOrDate(text: String): String? {
        val value = text.trim().lowercase()
        val wantsTime = TIME_PATTERN.matches(value)
        val wantsDate = DATE_PATTERN.matches(value)
        if (!wantsTime && !wantsDate) return null
        val now = java.time.ZonedDateTime.now()
        return if (wantsTime) {
            "It is " + now.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")) + "."
        } else {
            "Today is " + now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM")) + "."
        }
    }

    private fun chatWithBackend(text: String) {
        val token = TokenStore.load(this)
        if (token.isNullOrBlank()) {
            speak("Pairing required. Open JARVIS and save the local bridge token.") { returnToWake() }
            return
        }
        // The answer is streamed, so each sentence is spoken the moment it
        // arrives instead of waiting for the whole reply. The request has to
        // run off the main thread or Android refuses the network call.
        Thread {
            publish("Understanding the request")
            BackendClient.chatStream(
                context = this,
                token = token,
                text = text,
                apps = dispatcher?.launchableApps().orEmpty(),
                onSpeech = { piece ->
                    handler.post { speakQueued(piece) }
                },
                onAction = { action ->
                    handler.post {
                        if (action.requiresConfirmation) {
                            requestConfirmation(action, confirmationQuestion(action))
                        } else {
                            // The action is announced first, then it runs.
                            val announcement = dispatcher?.announce(action)
                            val perform = {
                                val result = dispatcher?.execute(action)
                                    ?: ActionResult(action.id, ActionStatus.FAILED, "The action dispatcher is unavailable.")
                                if (result.succeeded) {
                                    io.execute {
                                        try {
                                            BackendClient.sendActionResult(this, token, result)
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                                if (announcement == null || !result.succeeded) {
                                    speak(result.message) { continueConversation() }
                                } else {
                                    continueConversation()
                                }
                            }
                            if (announcement == null) {
                                perform()
                            } else {
                                speak(announcement) { perform() }
                            }
                        }
                    }
                },
                onFinal = { finalText ->
                    handler.post {
                        if (finalText.isBlank()) {
                            speak("The backend returned an empty response.") { continueConversation() }
                        } else {
                            continueConversation()
                        }
                    }
                },
                onError = { message ->
                    handler.post {
                        speak("Backend unavailable: $message") { continueConversation() }
                    }
                },
            )
        }.start()
    }

    /** Speaks a streamed sentence without cutting off the previous one. */
    private fun speakQueued(text: String) {
        if (stopped) return
        val engine = tts
        if (!ttsReady || engine == null) {
            speak(text)
            return
        }
        publish("Speaking")
        engine.speak(text.take(1200), TextToSpeech.QUEUE_ADD, null, "jarvis-stream-${UUID.randomUUID()}")
    }

    private fun confirmationLabel(type: String): String = when (type) {
        "call" -> "A phone call needs confirmation"
        "prepare_sms" -> "An SMS draft needs confirmation"
        "prepare_whatsapp" -> "A WhatsApp draft needs confirmation"
        "read_notifications" -> "Reading notifications needs confirmation"
        else -> "That action needs confirmation"
    }

    /**
     * Speaks and keeps the microphone closed until the utterance is over.
     *
     * The recogniser listening while JARVIS talks is how it hears itself and
     * starts a loop.
     */
    private fun speak(text: String, next: (() -> Unit)? = null) {
        if (stopped) return
        afterSpeech = next
        speaking = true
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        // A custom voice recording replaces text-to-speech when the user picked one.
        val customVoice = VoicePreferences.customVoiceUri(this)
        if (customVoice != null) {
            publish("Speaking")
            CustomVoicePlayer.play(
                this,
                customVoice,
                onComplete = {
                    val action = afterSpeech
                    afterSpeech = null
                    action?.invoke()
                },
                onFailed = {
                    // The recording could not be played, so use the engine instead.
                    handler.post { speakWithEngine(text, next) }
                },
            )
            return
        }
        speakWithEngine(text, next)
    }

    private fun speakWithEngine(text: String, next: (() -> Unit)?) {
        // The community JARVIS voice is produced by the backend, which keeps
        // the model warm. The built in engine is the fallback.
        val token = TokenStore.load(this)
        if (!token.isNullOrBlank() && piperVoice.isAvailable(this, token)) {
            publish("Speaking")
            piperVoice.speak(this, token, text) { ok ->
                handler.post {
                    if (!ok) {
                        speakWithEngine(text)
                        return@post
                    }
                    val action = afterSpeech
                    afterSpeech = null
                    action?.invoke()
                }
            }
            return
        }
        speakWithEngine(text)
    }

    /** Speaks with the built in Android engine. */
    private fun speakWithEngine(text: String) {
        val engine = tts
        if (!ttsReady || engine == null) {
            publish("Speaking (TTS unavailable)")
            handler.postDelayed({
                val action = afterSpeech
                afterSpeech = null
                action?.invoke()
            }, 350)
            return
        }
        publish("Speaking")
        val result = engine.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, null, "jarvis-wake-${UUID.randomUUID()}")
        if (result == TextToSpeech.ERROR) {
            handler.postDelayed({
                val action = afterSpeech
                afterSpeech = null
                action?.invoke()
            }, 250)
        }
    }

    private fun returnToWake() {
        if (stopped) return
        mode = MODE_WAKE
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed({
            if (!stopped) startListening("Waiting for Hey Jarvis")
        }, 250)
    }

    private fun scheduleRestart(delayMs: Long, reason: String) {
        if (stopped) return
        publish(reason)
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs.coerceIn(MIN_RESTART_MS, MAX_RESTART_MS))
        restartDelayMs = (restartDelayMs * 2).coerceAtMost(MAX_RESTART_MS)
    }

    private fun speechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client stopped; retrying"
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition service failed; retrying"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "Speech recognition has no model for ${recognitionLanguage()}; trying another language"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Speech recognition language unavailable; trying another language"
        else -> "Speech recognition error $code; retrying"
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        val engine = tts
        if (!ttsReady || engine == null) {
            publish("Android TextToSpeech is unavailable")
            return
        }
        VoicePreferences.apply(engine, this)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                publish("Speaking")
            }

            override fun onDone(utteranceId: String?) {
                speaking = false
                val action = afterSpeech
                afterSpeech = null
                action?.invoke()
            }

            @Deprecated("Deprecated by Android")
            override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

            override fun onError(utteranceId: String?, errorCode: Int) {
                speaking = false
                val action = afterSpeech
                afterSpeech = null
                publish("TextToSpeech error $errorCode")
                action?.invoke()
            }
        })
    }

    override fun onDestroy() {
        stopped = true
        running = false
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        startingRecognition = false
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        stateText = "Off"
        listener?.invoke("Off")
        try {
            stopForeground(true)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.jarvis.companion.action.WAKE_START"
        const val ACTION_STOP = "dev.jarvis.companion.action.WAKE_STOP"
        private const val CHANNEL_ID = "jarvis_wake_phrase"
        private const val NOTIFICATION_ID = 4101
        /**
         * The vocabulary the recogniser should expect. Biasing the recogniser
         * towards it makes short commands far more accurate.
         */
        private val COMMAND_BIASING = arrayOf(
            "hey jarvis",
            "turn on the flashlight",
            "turn off the flashlight",
            "turn on bluetooth",
            "turn off bluetooth",
            "turn on wifi",
            "turn off wifi",
            "turn on do not disturb",
            "lock the screen",
            "open the app",
            "open settings",
            "open camera",
            "open youtube",
            "open chrome",
            "open spotify",
            "open whatsapp",
            "set an alarm",
            "set a timer",
            "volume up",
            "volume down",
            "battery level",
            "play the music",
            "pause the music",
            "call",
            "send a whatsapp message",
        )

        /** Questions about the clock that are answered without the backend. */
        private val TIME_PATTERN = Regex(
            "^\\s*(?:what(?:'s| is)?\\s+(?:the\\s+)?)?(?:current\\s+)?time(?:\\s+is\\s+it)?[?.! ]*$",
        )

        private val DATE_PATTERN = Regex(
            "^\\s*(?:what(?:'s| is)?\\s+(?:the\\s+)?)?(?:current\\s+)?(?:date|today(?:'s)?\\s+date)(?:\\s+is\\s+it)?[?.! ]*$",
        )

        /** The same final result inside this window is one utterance. */
        private const val DUPLICATE_RESULT_WINDOW_MS = 1200L

        /** Results below this confidence are treated as not understood. */
        private const val LOW_CONFIDENCE_LIMIT = 0.45f

        private const val MODE_WAKE = 0
        private const val MODE_COMMAND = 1
        private const val MIN_RESTART_MS = 800L
        private const val MAX_RESTART_MS = 30_000L

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var stateText: String = "Off"
            private set

        @Volatile
        var lastProvider: String = ""
            private set

        /**
         * Live microphone level from the recogniser, 0 to 1. The interface
         * reads it so the core can react to the voice while it listens.
         */
        /** The last recognition error, so the diagnostics screen can show it. */
        @Volatile
        var lastError: String = ""
            private set

        /** Records the last recognition error. */
        internal fun recordError(message: String) {
            lastError = message
        }

        @Volatile
        var micLevel: Float = 0f

        /** Which tier handled the last request: stop, structured, local or escalate. */
        /** The local capability descriptions, captured when the service starts. */
        @Volatile
        var capabilities: List<String> = emptyList()
            private set

        @Volatile
        var lastRouteTier: String = ""
            private set

        /** How long the local routing took for the last request, in milliseconds. */
        @Volatile
        var lastRouteLatencyMs: Long = 0L
            private set

        /** How many requests the local tiers handled without the model. */
        @Volatile
        var localHandledCount: Int = 0
            private set

        /** How many requests had to be escalated to the model. */
        @Volatile
        var escalatedCount: Int = 0
            private set

        /** Records the outcome of a routing decision for the diagnostics screen. */
        internal fun recordRoute(tier: String, latencyMs: Long) {
            lastRouteTier = tier
            lastRouteLatencyMs = latencyMs
            if (tier == "escalate") escalatedCount++ else localHandledCount++
        }

        /** The local capabilities that can run on this phone. */
        fun localCapabilities(): List<String> = capabilities

        @Volatile
        var listener: ((String) -> Unit)? = null
    }
}
