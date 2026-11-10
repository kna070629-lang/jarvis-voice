package dev.jarvis.companion

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class BackendReply(
    val text: String,
    val provider: String = "",
    val actionRequest: ActionRequest? = null,
    val duplicate: Boolean = false,
)

/** Authenticated loopback bridge client. Tokens never appear in logs. */
object BackendClient {
    private const val ENDPOINT = "http://127.0.0.1:8765/v1/command"
    private const val MAX_APPS_REPORTED = 120

    fun chat(
        context: Context,
        token: String,
        text: String,
        requestId: String = UUID.randomUUID().toString(),
        apps: List<AppMatch> = emptyList(),
    ): BackendReply {
        require(text.length in 1..4000) { "Please keep each request under 4,000 characters." }
        val body = JSONObject()
            .put("action", "chat")
            .put("request_id", requestId)
            .put("text", text)
        if (apps.isNotEmpty()) {
            // The installed app list lets the backend plan actions for any app
            // on this device instead of guessing names.
            val list = org.json.JSONArray()
            for (app in apps.take(MAX_APPS_REPORTED)) {
                list.put(
                    JSONObject()
                        .put("label", app.label.take(60))
                        .put("package", app.packageName.take(120)),
                )
            }
            body.put("apps", list)
        }
        return post(token, body)
    }

    /**
     * Sends a chat request and reads the streamed answer.
     *
     * [onSpeech] is called with each sentence as soon as the backend produces
     * it, so the voice can start talking before the whole answer is ready.
     */
    fun chatStream(
        context: Context,
        token: String,
        text: String,
        apps: List<AppMatch> = emptyList(),
        onSpeech: (String) -> Unit = {},
        onAction: (ActionRequest) -> Unit = {},
        onFinal: (String) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        require(text.length in 1..4000) { "Please keep each request under 4,000 characters." }
        val body = JSONObject()
            .put("action", "chat")
            .put("request_id", UUID.randomUUID().toString())
            .put("text", text)
            .put("stream", true)
        if (apps.isNotEmpty()) {
            val list = org.json.JSONArray()
            for (app in apps.take(MAX_APPS_REPORTED)) {
                list.put(
                    JSONObject()
                        .put("label", app.label.take(60))
                        .put("package", app.packageName.take(120)),
                )
            }
            body.put("apps", list)
        }
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 150000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $token")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                onError(
                    when (statusCode) {
                        401 -> "token rejected; check pairing"
                        409 -> "duplicate or conflicting request; try again"
                        else -> "local backend returned HTTP $statusCode"
                    },
                )
                return
            }
            val stream = connection.inputStream.bufferedReader(Charsets.UTF_8)
            var builder = StringBuilder()
            stream.use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val chunk = try {
                        JSONObject(line)
                    } catch (_: Exception) {
                        continue
                    }
                    when (chunk.optString("type")) {
                        "speech" -> {
                            val piece = chunk.optString("text", "")
                            if (piece.isNotBlank()) onSpeech(piece)
                        }
                        "action" -> {
                            ActionRequest.fromJson(chunk.optJSONObject("action_request"))?.let(onAction)
                        }
                        "final" -> {
                            builder.append(chunk.optString("text", ""))
                            onFinal(builder.toString())
                        }
                        "error" -> onError(chunk.optString("text", "The request failed."))
                    }
                }
            }
        } catch (exception: Exception) {
            onError(exception.message ?: "connection failed")
        } finally {
            connection.disconnect()
        }
    }

    fun sendActionResult(context: Context, token: String, result: ActionResult): BackendReply {
        val body = JSONObject()
            .put("action", "action_result")
            .put("request_id", UUID.randomUUID().toString())
            .put("action_id", result.requestId)
            .put("status", result.status.wire())
            .put("message", result.message.take(2000))
        return post(token, body)
    }

    private fun post(token: String, body: JSONObject): BackendReply {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 105000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $token")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                throw IllegalStateException(
                    when (statusCode) {
                        401 -> "token rejected; check pairing"
                        409 -> "duplicate or conflicting request; try again"
                        else -> "local backend returned HTTP $statusCode"
                    },
                )
            }
            val json = JSONObject(payload)
            return BackendReply(
                text = json.optString("text", ""),
                provider = json.optString("provider", ""),
                actionRequest = ActionRequest.fromJson(json.optJSONObject("action_request")),
                duplicate = json.optBoolean("duplicate", false),
            )
        } finally {
            connection.disconnect()
        }
    }
}
