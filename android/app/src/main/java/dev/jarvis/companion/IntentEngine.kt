package dev.jarvis.companion

/**
 * The local-first intent engine.
 *
 * Every utterance is routed through an ordered set of tiers. The cheapest
 * tier that understands the request wins, and the cloud model is only asked
 * when no local tier can understand it.
 *
 *     Tier 0  STOP        exact match on cancel words, never escalates
 *     Tier 1  STRUCTURED  the structured command parser
 *     Tier 2  LOCAL       the local plugin registry
 *     Tier 3  ESCALATE    hand over to the backend model
 *
 * The ordering matters more than the size of any tier. A local tier that has
 * to search a large corpus can be slower than asking a model, so the local
 * intents here are pre-compiled and small, and the whole local path is
 * measured on every request.
 *
 * The engine only routes. It never touches the device itself, which keeps it
 * free of any Android dependency and makes it testable on its own.
 */
class IntentEngine(
    private val registry: ActionPluginRegistry = ActionPluginRegistry(null),
) {

    /** What the engine decided about one utterance. */
    sealed class Result {
        /**
         * A local tier understood the request.
         *
         * When [request] is set the caller runs it through the action
         * dispatcher, because a structured command may still need a spoken
         * confirmation before it touches the device. When [plugin] is set the
         * caller runs the plugin.
         */
        data class Handled(
            val tier: String,
            val latencyMs: Long,
            val request: ActionRequest? = null,
            val plugin: JarvisAction? = null,
            val arguments: Map<String, String> = emptyMap(),
        ) : Result()

        /** The request should be given to the backend model. */
        data class Escalate(
            val reason: String,
            val latencyMs: Long,
        ) : Result()

        /** The user cancelled or asked JARVIS to stop. */
        data class Cancelled(
            val latencyMs: Long,
        ) : Result()
    }

    /**
     * A local intent that maps straight onto a device action.
     *
     * These are handled by the action dispatcher without the model, so they
     * keep working with no network and answer immediately.
     */
    data class DeviceIntent(
        val actionType: String,
        val pattern: Regex,
        val description: String,
        val arguments: Map<String, (MatchResult) -> String?> = emptyMap(),
        val requiresConfirmation: Boolean = false,
        val sensitive: Boolean = false,
    )

    /** A natural command for one of the supported applications. */
    data class AppIntent(
        val pattern: Regex,
        val packageName: String?,
        val actionId: String,
        val arguments: Map<String, (MatchResult) -> String?> = emptyMap(),
        val packageFor: ((MatchResult) -> String?)? = null,
    )

    /** A local intent: a pre-compiled pattern that maps to a plugin. */
    data class LocalIntent(
        val pluginId: String,
        val pattern: Regex,
        val description: String,
        val arguments: Map<String, (MatchResult) -> String?> = emptyMap(),
    )

    /** Context carried between turns so a follow-up can refer to the last request. */
    class DialogueContext {
        var lastPluginId: String? = null
            private set
        var lastArguments: Map<String, String> = emptyMap()
            private set
        var lastUtterance: String = ""
            private set

        internal fun remember(pluginId: String, arguments: Map<String, String>, utterance: String) {
            lastPluginId = pluginId
            lastArguments = arguments
            lastUtterance = utterance
        }

        fun clear() {
            lastPluginId = null
            lastArguments = emptyMap()
            lastUtterance = ""
        }
    }

    /** App actions that need a spoken confirmation before they run. */
    private val SENSITIVE_APP_ACTIONS = setOf("prepare_message", "open_chat")

    /**
     * Cancel words. They are matched exactly and never escalated, so the model
     * can never talk the assistant out of stopping.
     */
    private val stopWords = setOf(
        "stop", "stop listening", "stop jarvis", "jarvis stop", "cancel",
        "never mind", "nevermind", "forget it", "that's all", "thats all",
        "not now", "quit", "disable jarvis",
    )

    /**
     * An explicit request to call a person by name.
     *
     * This tier is deliberately local. A request to call someone must never
     * wait for a model, because the model may answer with text instead of an
     * action and the call would never happen.
     */
    private val contactCallIntents: List<AppIntent> = listOf(
        AppIntent(
            pattern = Regex(
                "^\\s*(?:call|phone|dial|ring)\\s+(?:my\\s+)?(.+?)\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            packageName = null,
            actionId = "call_contact",
            arguments = mapOf("name" to { match -> match.groupValues[1].trim() }),
            packageFor = { null },
        ),
    )

    /**
     * Natural commands for the supported applications.
     *
     * Each one names the application, the action of the integration and the
     * arguments, so the dispatcher can run it without the model.
     */
    private val appIntents: List<AppIntent> = listOf(
        AppIntent(
            pattern = Regex(
                "^\\s*(?:open|launch|start)\\s+(?:my\\s+|the\\s+)?chat\\s+with\\s+(.+?)\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            packageName = "com.whatsapp",
            actionId = "open_chat",
            arguments = mapOf("name" to { match -> match.groupValues[1].trim() }),
        ),
        AppIntent(
            pattern = Regex(
                "^\\s*(?:send|write)\\s+(?:a\\s+)?(?:message\\s+)?(?:to\\s+)?(.+?)\\s+to\\s+(.+?)\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            packageName = "com.whatsapp",
            actionId = "prepare_message",
            arguments = mapOf(
                "text" to { match -> match.groupValues[1].trim() },
                "name" to { match -> match.groupValues[2].trim() },
            ),
        ),
        AppIntent(
            pattern = Regex(
                "^\\s*(?:search|look up|find)\\s+(?:for\\s+)?(.+?)\\s+(?:in|on)\\s+(chrome|youtube|maps)\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            packageName = null,
            actionId = "search",
            arguments = mapOf("query" to { match -> match.groupValues[1].trim() }),
            packageFor = { match ->
                when (match.groupValues[2].lowercase()) {
                    "chrome" -> "com.android.chrome"
                    "youtube" -> "com.google.android.youtube"
                    else -> "com.google.android.apps.maps"
                }
            },
        ),
        AppIntent(
            pattern = Regex(
                "^\\s*(?:navigate|direct)\\s+(?:me\\s+)?to\\s+(.+?)\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            packageName = "com.google.android.apps.maps",
            actionId = "navigate",
            arguments = mapOf("destination" to { match -> match.groupValues[1].trim() }),
        ),
        AppIntent(
            pattern = Regex(
                "^\\s*(?:open|launch|start)\\s+(?:my\\s+|the\\s+)?(whatsapp|telegram|gmail|maps|chrome|youtube|calendar|camera|gallery|files|settings|play store)\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            packageName = null,
            actionId = "open",
            arguments = emptyMap(),
            packageFor = { match ->
                when (match.groupValues[1].lowercase()) {
                    "whatsapp" -> "com.whatsapp"
                    "telegram" -> "org.telegram.messenger"
                    "gmail" -> "com.google.android.gm"
                    "maps" -> "com.google.android.apps.maps"
                    "chrome" -> "com.android.chrome"
                    "youtube" -> "com.google.android.youtube"
                    "calendar" -> "com.android.calendar"
                    "camera" -> "com.android.camera2"
                    "gallery" -> "com.google.android.projection.gearhead"
                    "files" -> "com.android.documentsui"
                    "settings" -> "com.android.settings"
                    else -> "com.android.vending"
                }
            },
        ),
    )

    /**
     * Device actions that are understood on the phone. The action dispatcher
     * already knows how to run them, so the model is never involved.
     */
    private val deviceIntents: List<DeviceIntent> = listOf(
        DeviceIntent(
            actionType = "toggle_flashlight",
            description = "Turn the flashlight on or off",
            pattern = Regex(
                "^\\s*(?:turn\\s+(on|off)\\s+(?:the\\s+)?)?(?:flashlight|torch|flash\\s+light)(?:\\s+(on|off))?\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            arguments = mapOf("state" to { match ->
                val first = match.groupValues[1]
                val second = match.groupValues[2]
                (if (first.isNotBlank()) first else second).lowercase()
            }),
        ),
        DeviceIntent(
            actionType = "set_volume",
            description = "Change the media volume",
            pattern = Regex(
                "^\\s*(?:(?:turn|make)\\s+(?:the\\s+)?volume\\s+(up|down)|volume\\s+(up|down|mute|unmute|max|full|[0-9]{1,3}))\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            arguments = mapOf("level" to { match ->
                val first = match.groupValues[1]
                val second = match.groupValues[2]
                (if (first.isNotBlank()) first else second).lowercase()
            }),
        ),
        DeviceIntent(
            actionType = "media_control",
            description = "Control music playback",
            pattern = Regex(
                "^\\s*(play|pause|resume|stop|skip|next|previous|prev)\\s+(?:the\\s+)?(?:music|song|media|track|video|playback)\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            arguments = mapOf("action" to { match ->
                when (match.groupValues[1].lowercase()) {
                    "resume" -> "play"
                    "skip" -> "next"
                    else -> match.groupValues[1].lowercase()
                }
            }),
        ),
        DeviceIntent(
            actionType = "set_timer",
            description = "Set a timer",
            pattern = Regex(
                "^\\s*(?:set|start)\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?([0-9]{1,3})\\s*(?:minutes?|mins?|m)\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            arguments = mapOf("duration" to { match -> match.groupValues[1] }),
        ),
        DeviceIntent(
            actionType = "open_dialer",
            description = "Open the phone dialer",
            pattern = Regex(
                "^\\s*(?:open|show)\\s+(?:the\\s+)?(?:dialer|phone\\s+dialer|keypad)\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
        ),
    )

    private val intents: List<LocalIntent> = listOf(
        LocalIntent(
            pluginId = "device.battery",
            description = "Battery level and charging state",
            pattern = Regex(
                "^\\s*(?:what(?:'s| is)?\\s+(?:the\\s+)?)?battery(?:\\s+(?:level|status))?\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
        ),
        LocalIntent(
            pluginId = "device.info",
            description = "Device model, Android version and screen",
            pattern = Regex(
                "^\\s*(?:what(?:'s| is)?\\s+(?:this\\s+|my\\s+|the\\s+)?)?(?:phone|device)(?:\\s+info(?:rmation)?)?\\s*[.!?]*$" +
                    "|^\\s*what\\s+android\\s+version\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
        ),
        LocalIntent(
            pluginId = "device.calculate",
            description = "Arithmetic",
            pattern = Regex(
                "^(?:what(?:'s| is)|calculate|compute|how\\s+much\\s+is)\\s+(.+?)\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            arguments = mapOf(
                "expression" to { match ->
                    match.groupValues[1].trim()
                        .removePrefix("plus ")
                        .removePrefix("minus ")
                        .takeIf { it.any { ch -> ch.isDigit() } }
                },
            ),
        ),
        LocalIntent(
            pluginId = "device.wifi",
            description = "Wi-Fi state and settings",
            pattern = Regex(
                "^\\s*(?:turn\\s+(on|off)\\s+(?:the\\s+)?)?wi-?fi(?:\\s+(on|off))?\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            arguments = mapOf("state" to { match ->
                val first = match.groupValues[1]
                val second = match.groupValues[2]
                (if (first.isNotBlank()) first else second).lowercase()
            }),
        ),
        LocalIntent(
            pluginId = "device.bluetooth",
            description = "Bluetooth state and settings",
            pattern = Regex(
                "^\\s*(?:turn\\s+(on|off)\\s+(?:the\\s+)?)?bluetooth(?:\\s+(on|off))?\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            arguments = mapOf("state" to { match ->
                val first = match.groupValues[1]
                val second = match.groupValues[2]
                (if (first.isNotBlank()) first else second).lowercase()
            }),
        ),
        LocalIntent(
            pluginId = "device.dnd",
            description = "Do not disturb",
            pattern = Regex(
                "^\\s*(?:turn\\s+(on|off)\\s+(?:the\\s+)?)?(?:do\\s+not\\s+disturb|disturb|silence|silent)(?:\\s+(on|off))?\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            arguments = mapOf("state" to { match ->
                val first = match.groupValues[1]
                val second = match.groupValues[2]
                (if (first.isNotBlank()) first else second).lowercase()
            }),
        ),
        LocalIntent(
            pluginId = "device.screen",
            description = "Screen state, lock and wake",
            pattern = Regex(
                "^\\s*(?:lock\\s+(?:the\\s+)?screen|turn\\s+(?:the\\s+)?screen\\s+(on|off)|is\\s+the\\s+screen\\s+on)\\s*[.!?]*$",
                RegexOption.IGNORE_CASE,
            ),
            arguments = mapOf("state" to { match ->
                val groups = match.groupValues
                when {
                    groups.size > 2 && groups[2].isNotBlank() -> groups[2].lowercase()
                    groups[1].equals("on", ignoreCase = true) -> "on"
                    else -> ""
                }
            }),
        ),
    )

    /** The dialogue context shared by the service and the activity. */
    val dialogue = DialogueContext()

    /** The registry of local plugins, exposed for the diagnostics screen. */
    val plugins: ActionPluginRegistry get() = registry

    /**
     * Routes one utterance.
     *
     * @return the decision, including how long the local routing took.
     */
    fun route(utterance: String): Result {
        val started = System.nanoTime()
        val text = utterance.trim()
        if (text.isEmpty()) {
            return Result.Escalate("empty", elapsedMs(started))
        }

        // Tier 0: stop. Exact, and never escalated.
        val normalised = normalise(text)
        if (stopWords.contains(normalised)) {
            dialogue.clear()
            return Result.Cancelled(elapsedMs(started))
        }

        // Tier 1: the supported applications. These patterns are more precise
        // than the general ones, so they are tried first.
        // Tier 1c: natural commands for the supported applications.
        for (intent in appIntents) {
            val match = intent.pattern.matchEntire(text) ?: continue
            val packageName = intent.packageFor?.invoke(match) ?: intent.packageName ?: continue
            val args = mutableMapOf<String, String>()
            for ((key, extractor) in intent.arguments) {
                extractor(match)?.let { args[key] = it }
            }
            if (args.values.any { it.isBlank() }) continue
            val request = ActionRequest.create(
                "app_action",
                args + mapOf("package" to packageName, "action" to intent.actionId),
                requiresConfirmation = intent.actionId in SENSITIVE_APP_ACTIONS,
                sensitive = intent.actionId in SENSITIVE_APP_ACTIONS,
            )
            return Result.Handled(
                tier = "app",
                latencyMs = elapsedMs(started),
                request = request,
            )
        }

        // Tier 1b: structured commands that map to device actions.
        val structured = StructuredCommandParser.parse(text)
        if (structured != null) {
            return Result.Handled(
                tier = "structured",
                latencyMs = elapsedMs(started),
                request = ActionRequest.create(
                    structured.type,
                    structured.args,
                    structured.requiresConfirmation,
                    structured.sensitive,
                ),
            )
        }

        // Tier 1d: device actions that run without the model.
        for (intent in deviceIntents) {
            val match = intent.pattern.matchEntire(text) ?: continue
            val args = mutableMapOf<String, String>()
            for ((key, extractor) in intent.arguments) {
                extractor(match)?.let { args[key] = it }
            }
            return Result.Handled(
                tier = "device",
                latencyMs = elapsedMs(started),
                request = ActionRequest.create(
                    intent.actionType,
                    args,
                    intent.requiresConfirmation,
                    intent.sensitive,
                ),
            )
        }

        // Tier 2: the local plugin registry.
        for (intent in intents) {
            val match = intent.pattern.matchEntire(text) ?: continue
            val action = registry.find(intent.pluginId) ?: continue
            val arguments = mutableMapOf<String, String>()
            for ((key, extractor) in intent.arguments) {
                extractor(match)?.let { arguments[key] = it }
            }
            dialogue.remember(intent.pluginId, arguments, text)
            return Result.Handled(
                tier = "local",
                latencyMs = elapsedMs(started),
                plugin = action,
                arguments = arguments,
            )
        }

        // Tier 2b: an explicit request to call a person by name. This runs
        // after the local plugins, so "phone info" stays a device question,
        // and it never reaches the model.
        for (intent in contactCallIntents) {
            val match = intent.pattern.matchEntire(text) ?: continue
            val name = intent.arguments["name"]?.invoke(match)?.trim().orEmpty()
            if (name.isEmpty()) continue
            if (name.all { it.isDigit() || it in "+ ()-." }) continue
            return Result.Handled(
                tier = "contact-call",
                latencyMs = elapsedMs(started),
                request = ActionRequest.create(
                    "call_contact",
                    mapOf("name" to name),
                    requiresConfirmation = false,
                    sensitive = false,
                ),
            )
        }

        return Result.Escalate("no local intent matched", elapsedMs(started))
    }

    /** True when the utterance is a cancel phrase. */
    fun isStop(utterance: String): Boolean = stopWords.contains(normalise(utterance.trim()))

    /** A short description of the registered local capabilities. */
    fun describe(): List<String> = registry.describe()

    private fun elapsedMs(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / 1_000_000L

    private fun normalise(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()
}
