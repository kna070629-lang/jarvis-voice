package dev.jarvis.companion

import org.json.JSONObject

enum class ActionStatus {
    UNDERSTOOD,
    AUTHORIZED,
    ATTEMPTED,
    SUCCEEDED,
    FAILED,
    PERMISSION_REQUIRED,
    NEEDS_CONFIRMATION,
    AMBIGUOUS,
    DUPLICATE,
    ;

    fun wire(): String = name.lowercase()

    companion object {
        fun fromWire(value: String?): ActionStatus = entries.firstOrNull { it.wire() == value?.lowercase() } ?: FAILED
    }
}

data class ActionRequest(
    val id: String,
    val type: String,
    val args: Map<String, String> = emptyMap(),
    val requiresConfirmation: Boolean = false,
    val sensitive: Boolean = false,
    /** The contract fields the backend sends, so the result can be reported. */
    val requestId: String = "",
    val target: String = "",
    val permissionStatus: String = "unknown",
    val executionStatus: String = "pending",
) {
    companion object {
        private val idPattern = Regex("^[A-Za-z0-9_-]{8,64}$")
        private val typePattern = Regex("^[a-z_]{3,40}$")

        fun create(
            type: String,
            args: Map<String, String> = emptyMap(),
            requiresConfirmation: Boolean = false,
            sensitive: Boolean = false,
            id: String = java.util.UUID.randomUUID().toString(),
        ): ActionRequest = ActionRequest(id, type, args, requiresConfirmation, sensitive)

        fun fromJson(value: JSONObject?): ActionRequest? {
            if (value == null) return null
            val id = value.optString("action_id", "")
            val type = value.optString("action", "")
            if (!idPattern.matches(id) || !typePattern.matches(type)) return null
            val rawArgs = value.optJSONObject("args") ?: JSONObject()
            val args = LinkedHashMap<String, String>()
            val keys = rawArgs.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = rawArgs.opt(key)
                if (key.length !in 1..32 || item !is String || item.length > 240) return null
                args[key] = item
            }
            return ActionRequest(
                id = id,
                type = type,
                args = args,
                requiresConfirmation = value.optBoolean("requires_confirmation", false),
                sensitive = value.optBoolean("sensitive", false),
                requestId = value.optString("request_id", id),
                target = value.optString("target"),
                permissionStatus = value.optString("permission_status", "unknown"),
                executionStatus = value.optString("execution_status", "pending"),
            )
        }
    }
}

data class ActionResult(
    val requestId: String,
    val status: ActionStatus,
    val message: String,
    val options: List<AppMatch> = emptyList(),
    val confirmationToken: String? = null,
) {
    val succeeded: Boolean get() = status == ActionStatus.SUCCEEDED
}

data class AppMatch(val label: String, val packageName: String)
