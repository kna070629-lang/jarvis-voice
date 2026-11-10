package dev.jarvis.companion

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

/**
 * Plays the user-selected custom voice recording.
 *
 * When a custom voice file is configured, JARVIS speaks by playing that file
 * instead of using the Android text-to-speech engine. Playback always reports
 * completion so the caller can continue the conversation flow, and reports
 * failure separately so the caller can fall back to text-to-speech.
 */
object CustomVoicePlayer {
    private var player: MediaPlayer? = null

    /**
     * Plays [uri]. [onComplete] runs after successful playback and [onFailed]
     * runs when the file cannot be prepared or decoded.
     */
    fun play(context: Context, uri: Uri, onComplete: () -> Unit, onFailed: () -> Unit) {
        stop()
        val created = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(context, uri)
                setOnCompletionListener {
                    release()
                    onComplete()
                }
                setOnErrorListener { _, _, _ ->
                    release()
                    onFailed()
                    true
                }
                prepare()
            }
        } catch (_: Exception) {
            null
        }
        if (created == null) {
            onFailed()
            return
        }
        player = created
        try {
            created.start()
        } catch (_: Exception) {
            release()
            onFailed()
        }
    }

    fun stop() {
        val active = player ?: return
        try {
            active.stop()
        } catch (_: Exception) {
        }
        release()
    }

    private fun release() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }
}
