package dev.jarvis.companion

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plays the community JARVIS voice.
 *
 * The voice is produced by the backend, which keeps the model warm, so the
 * phone never loads a hundred megabytes of model itself. The sound is played
 * with the Android media player, so it works with no extra permission.
 *
 * When the backend cannot speak, the caller falls back to the built in voice.
 */
class PiperVoicePlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var cacheFile: File? = null

    /** True when the backend has the voice ready. */
    fun isAvailable(context: Context, token: String): Boolean {
        if (token.isBlank()) return false
        return try {
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 8000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write("{\"text\":\"ready\"}".toByteArray()) }
            val code = connection.responseCode
            connection.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fetches the speech for the text and plays it.
     *
     * @param onDone called when the playback has finished or failed.
     */
    fun speak(
        context: Context,
        token: String,
        text: String,
        onDone: (Boolean) -> Unit = {},
    ) {
        if (text.isBlank() || token.isBlank()) {
            onDone(false)
            return
        }
        Thread {
            val audio = fetch(text, token)
            if (audio == null) {
                onDone(false)
                return@Thread
            }
            play(audio, onDone)
        }.start()
    }

    /** Fetches the wave file for the text. */
    fun fetch(text: String, token: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 120000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use {
                it.write("{\"text\":${quote(text)}}".toByteArray(Charsets.UTF_8))
            }
            if (connection.responseCode != 200) {
                Log.w(TAG, "the backend returned HTTP ${connection.responseCode} for the speech")
                return null
            }
            connection.inputStream.use { it.readBytes() }
        } catch (exception: Exception) {
            Log.w(TAG, "the speech could not be fetched", exception)
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** Plays the wave file that was fetched. */
    private fun play(audio: ByteArray, onDone: (Boolean) -> Unit) {
        stop()
        try {
            val file = File(context.cacheDir, "jarvis-voice.wav")
            file.writeBytes(audio)
            cacheFile = file
            val created = MediaPlayer()
            player = created
            created.setDataSource(file.absolutePath)
            created.setOnCompletionListener {
                onDone(true)
                stop()
            }
            created.setOnErrorListener { _, _, _ ->
                onDone(false)
                stop()
                true
            }
            created.prepare()
            created.start()
        } catch (exception: Exception) {
            Log.w(TAG, "the speech could not be played", exception)
            onDone(false)
            stop()
        }
    }

    /** Stops the playback and clears the queue. */
    fun stop() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        try {
            cacheFile?.delete()
        } catch (_: Exception) {
        }
        cacheFile = null
    }

    /** True when something is playing. */
    val isPlaying: Boolean
        get() = try {
            player?.isPlaying == true
        } catch (_: Exception) {
            false
        }

    private fun quote(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
        return "\"$escaped\""
    }

    companion object {
        private const val TAG = "JarvisVoice"
        private const val ENDPOINT = "http://127.0.0.1:8765/v1/tts"
    }
}
