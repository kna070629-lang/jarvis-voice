package dev.jarvis.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * The live topology of the JARVIS network.
 *
 * The positions and the connections come from the backend, which is the
 * single definition of the architecture. The interface therefore draws the
 * real network rather than a picture of one.
 */
data class NetworkNode(
    val nodeId: String,
    val kind: String,
    val name: String,
    val status: String,
    val detail: String,
    val capabilities: List<String>,
    val tasksCompleted: Int,
    val tasksFailed: Int,
    val lastTaskId: String,
    val lastError: String,
    val recentTasks: List<NetworkTask>,
    val x: Float,
    val y: Float,
) {
    val isCore: Boolean get() = kind == "core"
    val isEngine: Boolean get() = kind == "engine"
    val isCluster: Boolean get() = kind == "cluster"

    companion object {
        fun from(json: JSONObject): NetworkNode {
            val position = json.optJSONArray("position") ?: JSONArray()
            return NetworkNode(
                nodeId = json.optString("node_id"),
                kind = json.optString("kind"),
                name = json.optString("name"),
                status = json.optString("status", "offline"),
                detail = json.optString("detail"),
                capabilities = json.optJSONArray("capabilities").toStringList(),
                tasksCompleted = json.optInt("tasks_completed"),
                tasksFailed = json.optInt("tasks_failed"),
                lastTaskId = json.optString("last_task_id"),
                lastError = json.optString("last_error"),
                recentTasks = json.optJSONArray("recent_tasks").map { task ->
                    NetworkTask(
                        taskId = task.optString("task_id"),
                        ok = task.optBoolean("ok"),
                        summary = task.optString("summary"),
                    )
                },
                x = position.optDouble(0, 0.5).toFloat(),
                y = position.optDouble(1, 0.5).toFloat(),
            )
        }
    }
}

/** One task the node has run. */
data class NetworkTask(
    val taskId: String,
    val ok: Boolean,
    val summary: String,
)

/** One connection between two nodes. */
data class NetworkLink(
    val source: String,
    val destination: String,
    val status: String,
    val transmissions: Int,
    val failures: Int,
) {
    val isActive: Boolean
        get() = status == "active" || status == "transmitting"
    val isFailed: Boolean get() = status == "failed"
    val isSuccessful: Boolean get() = status == "successful"
}

/** The whole network at one moment. */
data class NetworkSnapshot(
    val nodes: List<NetworkNode>,
    val links: List<NetworkLink>,
    val stats: Map<String, Any>,
    val receivedAt: Long = System.currentTimeMillis(),
) {
    fun node(nodeId: String): NetworkNode? = nodes.firstOrNull { it.nodeId == nodeId }

    companion object {
        fun from(json: JSONObject): NetworkSnapshot {
            val nodes = json.optJSONArray("nodes").map { NetworkNode.from(it) }
            val links = json.optJSONArray("links").map { link ->
                NetworkLink(
                    source = link.optString("source"),
                    destination = link.optString("destination"),
                    status = link.optString("status", "inactive"),
                    transmissions = link.optInt("transmissions"),
                    failures = link.optInt("failures"),
                )
            }
            val stats = mutableMapOf<String, Any>()
            json.optJSONObject("stats")?.let { statsObject ->
                val keys = statsObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    stats[key] = statsObject.opt(key)
                }
            }
            return NetworkSnapshot(nodes, links, stats)
        }
    }
}

/** The colours used for each node state. */
object NetworkPalette {
    fun forStatus(status: String): Int = when (status) {
        "listening" -> 0xFF4FD8FF.toInt()
        "thinking" -> 0xFF7AA2FF.toInt()
        "processing" -> 0xFF9B6BFF.toInt()
        "executing" -> 0xFF59E3A0.toInt()
        "waiting_approval" -> 0xFFFFC24B.toInt()
        "completed" -> 0xFF2FB98A.toInt()
        "failed" -> 0xFFFF6B6B.toInt()
        "cancelled" -> 0xFFFFC24B.toInt()
        "idle" -> 0xFF2E6E8E.toInt()
        "offline" -> 0xFF46545E.toInt()
        else -> 0xFF2E6E8E.toInt()
    }

    fun forLink(status: String): Int = when (status) {
        "transmitting" -> 0xFF8FF6FF.toInt()
        "active" -> 0xFF4FD8FF.toInt()
        "successful" -> 0xFF59E3A0.toInt()
        "failed" -> 0xFFFF6B6B.toInt()
        else -> 0xFF1B3D52.toInt()
    }
}

/** Small helpers for reading a JSON array as a list of strings or objects. */
private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        val value = optString(index)
        if (value.isNotBlank()) values.add(value)
    }
    return values
}

private fun <T> JSONArray?.map(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val values = mutableListOf<T>()
    for (index in 0 until length()) {
        optJSONObject(index)?.let { values.add(transform(it)) }
    }
    return values
}
