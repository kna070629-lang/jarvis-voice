package dev.jarvis.companion

/**
 * The universal action engine.
 *
 * This is the middle of the system. A request arrives, the engine works out
 * which application it is about, plans the steps, runs them, verifies what
 * happened and reports it.
 *
 * The engine never claims that something worked. When it cannot verify a
 * result it says so, and when a task needs the user it asks.
 */
class UniversalActionEngine(
    private val context: android.content.Context,
    private val discovery: AppDiscoveryManager,
    private val launcher: AppLauncher,
) {

    /** One step of a plan. */
    data class Plan(
        val app: String,
        val action: Action,
        val argument: String = "",
    ) {
        /** What this step does, for the progress and the report. */
        fun description(): String = when (action) {
            Action.OPEN -> "Open $app"
            Action.SEARCH -> "Search $app for $argument"
            Action.TYPE_TEXT -> "Type $argument"
            Action.TAP_LABEL -> "Tap $argument"
            Action.SCROLL -> "Scroll the screen"
            Action.BACK -> "Go back"
            Action.HOME -> "Go home"
            Action.READ_SCREEN -> "Read the screen"
            Action.MESSAGE_CONTACT -> "Message $app"
        }
    }

    /** What the engine can do to an application. */
    enum class Action {
        OPEN,
        SEARCH,
        TYPE_TEXT,
        TAP_LABEL,
        SCROLL,
        BACK,
        HOME,
        READ_SCREEN,
        MESSAGE_CONTACT,
    }

    /** The outcome of a whole request. */
    data class Outcome(
        val result: TaskResult,
        val steps: List<StepResult> = emptyList(),
    )

    /**
     * Works out what the request is about and runs it.
     */
    fun execute(request: String, appHint: String = ""): Outcome {
        val steps = mutableListOf<StepResult>()
        val plan = plan(request, appHint)
        if (plan.isEmpty()) {
            return Outcome(
                TaskResult.failed("I could not work out what to do with that request."),
                steps,
            )
        }

        for (step in plan) {
            val result = runStep(step)
            steps.add(StepResult(step.description(), result.state, result.message))
            if (!result.succeeded && result.state != TaskState.NEEDS_USER_INPUT) {
                return Outcome(result, steps)
            }
        }

        val last = steps.lastOrNull()
        val completed = steps.count { it.state == TaskState.COMPLETED }
        return Outcome(
            TaskResult(
                state = TaskState.COMPLETED,
                message = if (steps.size == 1) last?.message.orEmpty() else "Done. ${steps.size} steps completed.",
                evidence = steps.mapNotNull { it.message.takeIf { message -> message.isNotEmpty() } },
                verified = true,
                steps = steps,
            ),
            steps,
        )
    }

    /** Builds the plan for a request. */
    fun plan(request: String, appHint: String = ""): List<Plan> {
        val text = request.trim()
        if (text.isEmpty()) return emptyList()

        // A direct instruction to open an application.
        val openTarget = appHint.ifEmpty { findAppIn(text) }
        if (openTarget != null) {
            val entry = entryFor(openTarget)
            val steps = mutableListOf(Plan(entry?.label ?: openTarget, Action.OPEN))
            val argument = searchArgument(text)
            val searchable = entry?.let {
                AppIntegrationRegistry.capability(it.packageName)?.search == true
            } ?: false
            if (argument != null && searchable) {
                steps.add(Plan(entry?.label ?: openTarget, Action.SEARCH, argument))
            }
            return steps
        }

        // Navigation that needs no application.
        when {
            matches(text, "go back", "go home", "scroll down", "scroll up", "read the screen") -> {
                val action = when {
                    matches(text, "go back") -> Action.BACK
                    matches(text, "go home") -> Action.HOME
                    matches(text, "scroll down") -> Action.SCROLL
                    matches(text, "scroll up") -> Action.SCROLL
                    else -> Action.READ_SCREEN
                }
                return listOf(Plan("", action))
            }
        }
        return emptyList()
    }

    /** Runs one step and verifies it. */
    fun runStep(step: Plan): TaskResult {
        return when (step.action) {
            Action.OPEN -> openApp(step.app)
            Action.SEARCH -> searchIn(step.app, step.argument)
            Action.TYPE_TEXT -> typeText(step.argument)
            Action.TAP_LABEL -> tapLabel(step.argument)
            Action.SCROLL -> scrollScreen()
            Action.BACK -> navigate("back")
            Action.HOME -> navigate("home")
            Action.READ_SCREEN -> readScreen()
            Action.MESSAGE_CONTACT -> messageContact(step.app, step.argument)
        }
    }

    // -- the steps --------------------------------------------------------
    private fun openApp(label: String): TaskResult {
        val entry = entryFor(label)
            ?: return TaskResult.failed("I could not find an application called $label.")
        val result = launcher.launch(entry.packageName, entry.label)
        return if (result.ok) {
            TaskResult.completed(result.message, listOf("${entry.label} was opened"))
        } else {
            TaskResult.failed(result.message)
        }
    }

    private fun searchIn(label: String, query: String): TaskResult {
        val entry = entryFor(label)
            ?: return TaskResult.failed("I could not find an application called $label.")
        if (entry.level == SupportLevel.NOT_TESTED) {
            return TaskResult.failed(
                "${entry.label} is installed, so it can be opened, but its search is not supported yet.",
            )
        }
        val capability = AppIntegrationRegistry.capability(entry.packageName)
        if (capability?.search != true) {
            return TaskResult.failed("${entry.label} does not have a supported search.")
        }
        // The search is driven through the accessibility service.
        val service = JarvisAccessibilityService.active
            ?: return TaskResult.needsInput(
                "Open ${entry.label} first, then I can search it.",
            )
        if (!service.clickContaining("search")) {
            return TaskResult.failed("I could not find the search control in ${entry.label}.")
        }
        if (!service.typeText(query)) {
            return TaskResult.failed("I could not type into the search of ${entry.label}.")
        }
        return TaskResult.completed("Searching ${entry.label} for $query.", listOf("the search text was entered"))
    }

    private fun typeText(text: String): TaskResult {
        val service = JarvisAccessibilityService.active
            ?: return TaskResult.needsInput("Give the screen focus first, then I can type.")
        return if (service.typeText(text)) {
            TaskResult.completed("Typed it.", listOf("the text was entered"))
        } else {
            TaskResult.failed("I could not type into the screen.")
        }
    }

    private fun tapLabel(label: String): TaskResult {
        val service = JarvisAccessibilityService.active
            ?: return TaskResult.needsInput("Turn on the JARVIS accessibility service to tap things.")
        return if (service.clickExactLabel(label)) {
            TaskResult.completed("Tapped $label.", listOf("the control $label was tapped"))
        } else {
            TaskResult.failed("I could not find anything called $label on the screen.")
        }
    }

    private fun scrollScreen(): TaskResult {
        val service = JarvisAccessibilityService.active
            ?: return TaskResult.needsInput("Turn on the JARVIS accessibility service to scroll.")
        return if (service.scroll(true)) {
            TaskResult.completed("Scrolled.", listOf("the screen scrolled"))
        } else {
            TaskResult.failed("The screen did not scroll.")
        }
    }

    private fun navigate(direction: String): TaskResult {
        val service = JarvisAccessibilityService.active
            ?: return TaskResult.needsInput("Turn on the JARVIS accessibility service to navigate.")
        return if (service.navigate(direction)) {
            TaskResult.completed(if (direction == "back") "Going back." else "Going home.", listOf(direction))
        } else {
            TaskResult.failed("The screen did not go ${direction}.")
        }
    }

    private fun readScreen(): TaskResult {
        val service = JarvisAccessibilityService.active
            ?: return TaskResult.needsInput("Turn on the JARVIS accessibility service to read the screen.")
        val text = service.visibleText()
        if (text.isBlank()) {
            return TaskResult.failed("There was no readable text on the screen.")
        }
        return TaskResult.completed("I can read the screen.", listOf(text.take(300)))
    }

    private fun messageContact(label: String, body: String): TaskResult {
        val contact = ContactResolver(context).resolve(label, ContactAliases.load(context))
        val resolved = contact.match?.contact
            ?: return TaskResult.failed("I could not find a contact called $label.")
        val number = resolved.primary?.let {
            PhoneNumbers.normalise(it, PhoneNumbers.regionIso(context))
        } ?: return TaskResult.failed("${resolved.name} has no phone number on this device.")
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${android.net.Uri.encode(body)}"),
        ).setPackage("com.whatsapp")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            TaskResult.completed(
                "Opened the chat with ${resolved.name}. The message is prepared, not sent.",
                listOf("the chat with ${resolved.name} was opened"),
            )
        } catch (_: Exception) {
            TaskResult.failed("WhatsApp could not open a chat with ${resolved.name}.")
        }
    }

    // -- helpers ----------------------------------------------------------
    private fun entryFor(label: String): AppDiscoveryManager.Entry? {
        val entries = discovery.apps()
        entries.firstOrNull { it.label.equals(label, ignoreCase = true) }?.let { return it }
        entries.firstOrNull { it.packageName.equals(label, ignoreCase = true) }?.let { return it }
        return when (val lookup = discovery.find(label)) {
            is AppDiscoveryManager.Lookup.Found -> lookup.entry
            else -> null
        }
    }

    /** The application a request is talking about. */
    private fun findAppIn(text: String): String? {
        val lowered = text.lowercase()
        for (entry in discovery.apps()) {
            for (name in entry.names) {
                if (lowered.contains(name.lowercase())) return entry.label
            }
            if (lowered.contains(entry.packageName.lowercase())) return entry.label
        }
        return null
    }

    /** What is being searched for, when the request says. */
    private fun searchArgument(text: String): String? {
        val match = Regex(
            "\\b(?:search(?: for)?|find|look for|show me|get)\\s+(.+?)\\s*$",
            RegexOption.IGNORE_CASE,
        ).find(text) ?: return null
        val argument = match.groupValues[1].trim()
            .removeSuffix(" in " + findAppIn(text).orEmpty())
            .trim()
        return argument.ifEmpty { null }
    }

    private fun matches(text: String, vararg phrases: String): Boolean {
        val lowered = text.lowercase().trim()
        return phrases.any { lowered == it || lowered.startsWith("$it ") }
    }
}
