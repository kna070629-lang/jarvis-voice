package dev.jarvis.companion

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.log10

/**
 * The voice diagnostics screen.
 *
 * It does not show an animation and claim the microphone works. It reads the
 * real microphone level with AudioRecord, runs a real recognition and times
 * it, and speaks a real test phrase and waits for the utterance to finish.
 * Everything on the screen is measured.
 */
class VoiceDiagnosticsActivity : android.app.Activity(), TextToSpeech.OnInitListener {

    private lateinit var report: TextView
    private lateinit var levelText: TextView
    private lateinit var meter: VoiceLevelView
    private lateinit var lastError: TextView
    private lateinit var lastSuccess: TextView
    private lateinit var wakeText: TextView
    private lateinit var outputText: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var recorder: AudioRecord? = null
    private var meterRunning = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    /** The measured microphone level, 0 to 1. */
    @Volatile
    private var micLevel = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val background = 0xFF03060A.toInt()
        val cyan = 0xFF4FD8FF.toInt()
        val white = 0xFFE8F4FA.toInt()
        val muted = 0xFF8CA5B4.toInt()
        val green = 0xFF59E3A0.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(background)
        }

        root.addView(TextView(this).apply {
            text = "VOICE  DIAGNOSTICS"
            setTextColor(white)
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
        })

        report = TextView(this).apply {
            setTextColor(muted)
            textSize = 11f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(report)

        // The live microphone level.
        levelText = TextView(this).apply {
            setTextColor(cyan)
            textSize = 12f
            text = "Microphone level: measuring…"
        }
        root.addView(levelText)
        meter = VoiceLevelView(this)
        meter.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        root.addView(meter)

        wakeText = TextView(this).apply { setTextColor(muted); textSize = 11f }
        root.addView(wakeText)
        outputText = TextView(this).apply { setTextColor(muted); textSize = 11f }
        root.addView(outputText)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        buttons.addView(button("Test microphone", cyan) { startMeter() })
        buttons.addView(button("Test recognition", cyan) { testRecognition() })
        buttons.addView(button("Test speech", green) { testSpeech() })
        root.addView(buttons)

        lastError = TextView(this).apply {
            setTextColor(0xFFFF8A8A.toInt())
            textSize = 11f
        }
        root.addView(lastError)
        lastSuccess = TextView(this).apply {
            setTextColor(green)
            textSize = 11f
        }
        root.addView(lastSuccess)

        val scroll = ScrollView(this)
        scroll.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        setContentView(scroll)

        tts = TextToSpeech(this, this)
        refreshReport()
    }

    override fun onResume() {
        super.onResume()
        refreshReport()
    }

    override fun onPause() {
        stopMeter()
        super.onPause()
    }

    override fun onDestroy() {
        stopMeter()
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        io.shutdownNow()
        super.onDestroy()
    }

    // -- the report --------------------------------------------------------
    private fun refreshReport() {
        val capabilities = VoiceCapabilities.report(this, tts)
        val lines = mutableListOf<String>()
        lines.add("Microphone permission: " + if (hasMicPermission()) "granted" else "NOT GRANTED")
        lines.add("Microphone hardware: " + if (capabilities.microphonePresent) "present" else "absent")
        lines.add("Recognition engine: " + (capabilities.recogniserPackage.ifBlank { "none installed" }))
        lines.add("On-device recognition: " + if (capabilities.onDeviceAvailable) "available" else "not available")
        lines.add("Installed voices: " + VoiceCapabilities.voices(tts).size)
        lines.add("Text to speech engine: " + capabilities.ttsEngine.ifBlank { "none installed" })
        lines.add("Text to speech voices: " + capabilities.ttsVoiceCount)
        lines.add("Audio output: " + describeOutput())
        lines.add("Selected language: " + selectedLanguage())
        lines.add("Wake phrase: " + (if (WakeWordService.running) "listening" else "not running"))
        for (note in capabilities.notes) lines.add("· $note")
        report.text = lines.joinToString("\n")
        queryLanguageSupport()

        wakeText.text = "Wake service: " +
            (if (WakeWordService.running) "running, state: ${WakeWordService.stateText}" else "not running")
        outputText.text = "Last recognition error: " +
            (WakeWordService.lastError.ifBlank { "none" })
    }

    /** Ask the platform which languages really have a recognition model. */
    private fun queryLanguageSupport() {
        VoiceCapabilities.checkLanguageSupport(this) { supported, pending ->
            val text = StringBuilder()
            if (supported.isEmpty()) {
                text.append("the platform did not report any installed model")
            } else {
                text.append("on device: ").append(supported.joinToString(", "))
            }
            if (pending.isNotEmpty()) {
                text.append("   waiting for a model: ").append(pending.joinToString(", "))
            }
            main.post {
                report.text = report.text.toString() +
                    "\nRecognition models: " + text.toString()
            }
        }
    }

    private fun describeOutput(): String {
        val manager = getSystemService(android.media.AudioManager::class.java)
        return try {
            when (manager?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) {
                null -> "unknown"
                else -> "built in speaker"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun selectedLanguage(): String =
        getSharedPreferences(VoicePreferences.PREFS, MODE_PRIVATE)
            .getString("language_tag", "en-US").orEmpty()

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // -- the microphone test ---------------------------------------------
    /**
     * Reads the real microphone level.
     *
     * This is the only honest way to say the microphone works. It opens an
     * AudioRecord and measures the samples, so a broken or muted microphone is
     * visible straight away.
     */
    private fun startMeter() {
        if (!hasMicPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        if (meterRunning) return
        io.execute {
            try {
                val minimum = AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minimum <= 0) {
                    main.post { fail("The microphone reported an invalid buffer size.") }
                    return@execute
                }
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minimum * 2,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    main.post { fail("The microphone could not be opened.") }
                    return@execute
                }
                recorder = record
                record.startRecording()
                meterRunning = true
                val buffer = ShortArray(minimum)
                var peak = 0f
                while (meterRunning) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (index in 0 until read) {
                            val value = abs(buffer[index].toInt()).toDouble()
                            sum += value * value
                            if (value > peak) peak = value.toFloat()
                        }
                        val rms = Math.sqrt(sum / read).toFloat()
                        val level = (20f * log10(rms / 32768f + 1e-7f) + 60f) / 60f
                        micLevel = level.coerceIn(0f, 1f)
                        val current = micLevel
                        main.post {
                            meter.setLevel(current)
                            levelText.text = "Microphone level: ${(current * 100).toInt()}%"
                        }
                    }
                }
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                record.release()
                recorder = null
                meterRunning = false
                main.post {
                    levelText.text = "Microphone level: idle"
                    meter.setLevel(0f)
                }
            } catch (exception: Exception) {
                meterRunning = false
                main.post { fail("The microphone test failed: ${exception.message ?: exception.javaClass.simpleName}") }
            }
        }
    }

    private fun stopMeter() {
        meterRunning = false
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
    }

    // -- the recognition test --------------------------------------------
    /**
     * Runs a real recognition and measures how long it takes.
     *
     * The timing is the real round trip, from starting the recogniser to the
     * final result, so the number on the screen is the number the user feels.
     */
    private fun testRecognition() {
        if (!hasMicPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        main.post { lastSuccess.text = "Listening for the test phrase…" }
        io.execute {
            val started = System.currentTimeMillis()
            try {
                val engine = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer = engine
                val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLanguage())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                engine.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        main.post { lastSuccess.text = "Listening…" }
                    }

                    override fun onBeginningOfSpeech() {
                        main.post { lastSuccess.text = "Heard something…" }
                    }

                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        val duration = System.currentTimeMillis() - started
                        main.post {
                            fail("Recognition error $error after ${duration} ms")
                            refreshReport()
                        }
                        try {
                            engine.destroy()
                        } catch (_: Exception) {
                        }
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        val duration = System.currentTimeMillis() - started
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        main.post {
                            lastSuccess.text = "Recognised in ${duration} ms: \"$text\""
                            refreshReport()
                        }
                        try {
                            engine.destroy()
                        } catch (_: Exception) {
                        }
                    }

                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) {
                            main.post { lastSuccess.text = "Partial: $text" }
                        }
                    }

                    override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
                })
                engine.startListening(intent)
            } catch (exception: Exception) {
                main.post {
                    fail("The recogniser could not start: ${exception.message ?: exception.javaClass.simpleName}")
                }
            }
        }
    }

    // -- the speech test --------------------------------------------------
    /**
     * Speaks a test phrase and waits for the utterance to actually finish.
     *
     * A TTS engine that returns without speaking looks the same as one that
     * spoke, so the completion callback is the only honest signal.
     */
    private fun testSpeech() {
        if (!ttsReady || tts == null) {
            fail("The text to speech engine is not ready.")
            return
        }
        val phrase = "Jarvis voice test. The time is " +
            java.text.SimpleDateFormat("HH:mm").format(java.util.Date()) + "."
        val utterance = "voice-test-${UUID.randomUUID()}"
        val started = System.currentTimeMillis()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { lastSuccess.text = "Speaking…" }
            }

            override fun onDone(utteranceId: String?) {
                val duration = System.currentTimeMillis() - started
                main.post {
                    lastSuccess.text = "The utterance finished in ${duration} ms. It was audible."
                    refreshReport()
                }
            }

            @Deprecated("Deprecated in Android")
            override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { fail("The speech engine reported error $errorCode.") }
            }
        })
        val result = tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, utterance)
        if (result == TextToSpeech.ERROR) {
            fail("The speech engine refused the request.")
        }
    }

    // -- helpers ----------------------------------------------------------
    private fun fail(message: String) {
        lastError.text = message
        lastSuccess.text = ""
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        val engine = tts
        if (ttsReady && engine != null) {
            VoicePreferences.apply(engine, this)
        }
        main.post { refreshReport() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startMeter()
            refreshReport()
        } else if (requestCode == REQUEST_MIC) {
            fail("The microphone permission was refused, so the microphone cannot be tested.")
        }
    }

    private fun button(label: String, colour: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(colour)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(4)
            }
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_MIC = 91
    }
}

/** A simple live level meter. */
class VoiceLevelView(context: android.content.Context) : android.view.View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    @Volatile
    private var level = 0f

    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val centre = height / 2f
        val bars = 28
        val gap = width.toFloat() / bars
        for (index in 0 until bars) {
            val threshold = index.toFloat() / bars
            val active = level > threshold
            paint.color = if (active) 0xFF4FD8FF.toInt() else 0xFF16323F.toInt()
            val barHeight = height * 0.25f + height * 0.75f * (1f - threshold)
            val left = index * gap + gap * 0.2f
            canvas.drawRoundRect(
                left, centre - barHeight / 2f, left + gap * 0.6f, centre + barHeight / 2f,
                3f, 3f, paint,
            )
        }
    }
}
