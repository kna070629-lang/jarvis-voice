package dev.jarvis.companion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.util.Locale

/**
 * The app integration engine.
 *
 * Every supported application is described by one integration. An integration
 * says which package it belongs to, what it can do, what it cannot do, what
 * permissions it needs and how each action is carried out. Nothing is
 * assumed about an app that is not installed, and an action an app does not
 * support is refused rather than approximated.
 */
class AppIntegrationEngine(private val context: Context?) {

    /** One action an application supports. */
    data class Action(
        val id: String,
        val description: String,
        val requiredPermissions: List<String> = emptyList(),
        val needsAccessibility: Boolean = false,
        /**
         * Builds the intent for the action.
         *
         * The package that is really installed is passed in, because the
         * same application has a different package on different phones.
         */
        val build: (Context?, String, Map<String, String>) -> Intent?,
    )

    /** One supported application. */
    data class Integration(
        val packageName: String,
        val label: String,
        val description: String,
        val actions: List<Action>,
        val unsupportedActions: List<String> = emptyList(),
        val website: String = "",
        /**
         * The packages this application may have. The first one that is
         * installed is used, so the integration works on any phone.
         */
        val packageCandidates: List<String> = listOf(packageName),
    ) {
        fun action(id: String): Action? = actions.firstOrNull { it.id == id }

        /** The identifier used in the action request. */
        val id: String get() = packageName
    }

    /** The result of trying to run an action. */
    data class Outcome(
        val ok: Boolean,
        val message: String,
        val actionId: String = "",
        val packageName: String = "",
        val needsPermission: String = "",
        val needsAccessibility: Boolean = false,
    )

    /** The integrations, keyed by the action the user asked for. */
    private val integrations: List<Integration> = buildIntegrations()

    /** Every integration. */
    fun all(): List<Integration> = integrations

    /** The integrations whose package is installed on this device. */
    fun installed(): List<Integration> = integrations.filter { isInstalled(it.packageName) }

    fun isInstalled(packageName: String): Boolean {
        val owner = context ?: return false
        return try {
            owner.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Find the integration that handles an action, preferring an installed app. */
    fun forAction(actionId: String): Integration? {
        val candidates = integrations.filter { it.action(actionId) != null }
        return candidates.firstOrNull { isInstalled(it.packageName) } ?: candidates.firstOrNull()
    }

    /** The package of an integration that is really installed. */
    fun installedPackage(integration: Integration): String? {
        for (candidate in integration.packageCandidates) {
            if (isInstalled(candidate)) return candidate
        }
        return null
    }

    /** Run an action of an integration. */
    fun run(
        integration: Integration,
        actionId: String,
        arguments: Map<String, String> = emptyMap(),
    ): Outcome {
        val action = integration.action(actionId)
            ?: return Outcome(
                false,
                "${integration.label} does not support $actionId.",
                actionId = actionId,
                packageName = integration.packageName,
            )

        val target = installedPackage(integration)
            ?: return Outcome(
                false,
                "${integration.label} is not installed on this phone.",
                actionId = actionId,
                packageName = integration.packageName,
            )

        val owner = context
        val missing = if (owner == null) emptyList() else action.requiredPermissions.filter { permission ->
            owner.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            return Outcome(
                false,
                "Permission needed for ${integration.label}: ${missing.joinToString(", ")}.",
                actionId = actionId,
                packageName = integration.packageName,
                needsPermission = missing.first(),
            )
        }

        val intent = try {
            action.build(context, target, arguments)
        } catch (_: Exception) {
            null
        } ?: return Outcome(
            false,
            "${integration.label} could not build the $actionId action.",
            actionId = actionId,
            packageName = integration.packageName,
        )

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val starter = owner
            ?: return Outcome(false, "There is no phone to run the action on.", actionId, integration.packageName)
        return try {
            starter.startActivity(intent)
            Outcome(
                true,
                "Opened ${integration.label}.",
                actionId = actionId,
                packageName = integration.packageName,
                needsAccessibility = action.needsAccessibility,
            )
        } catch (exception: Exception) {
            Outcome(
                false,
                "${integration.label} did not accept the $actionId action.",
                actionId = actionId,
                packageName = integration.packageName,
            )
        }
    }

    // -- the integrations -------------------------------------------------
    private fun buildIntegrations(): List<Integration> = listOf(
        Integration(
            packageName = "com.whatsapp",
            label = "WhatsApp",
            description = "Open a chat, prepare a message and read a chat.",
            unsupportedActions = listOf("read received messages"),
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open WhatsApp",
                ) { _, target, _ ->
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send")).setPackage(target)
                },
                Action(
                    id = "prepare_message",
                    description = "Open a chat with a number and prepare a message",
                ) { _, target, arguments ->
                    val number = arguments["number"].orEmpty().filter { it.isDigit() }
                    val text = arguments["text"].orEmpty()
                    if (number.length !in 7..15) return@Action null
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(text)}"),
                    ).setPackage(target)
                },
                Action(
                    id = "open_chat",
                    description = "Open an existing chat with a number",
                ) { _, target, arguments ->
                    val number = arguments["number"].orEmpty().filter { it.isDigit() }
                    if (number.length !in 7..15) return@Action null
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://api.whatsapp.com/send?phone=$number"),
                    ).setPackage(target)
                },
            ),
        ),
        Integration(
            packageName = "com.whatsapp.w4b",
            label = "WhatsApp Business",
            description = "Open a chat and prepare a message in WhatsApp Business.",
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open WhatsApp Business",
                ) { _, target, _ ->
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send")).setPackage(target)
                },
                Action(
                    id = "prepare_message",
                    description = "Open a chat with a number and prepare a message",
                ) { _, target, arguments ->
                    val number = arguments["number"].orEmpty().filter { it.isDigit() }
                    val text = arguments["text"].orEmpty()
                    if (number.length !in 7..15) return@Action null
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(text)}"),
                    ).setPackage(target)
                },
            ),
        ),
        Integration(
            packageName = "com.android.phone",
            packageCandidates = listOf("com.android.phone", "com.android.dialer", "com.google.android.dialer", "com.android.incallui"),
            label = "Phone",
            description = "Open the dialer and place a call.",
            unsupportedActions = listOf("read the call log"),
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open the dialer",
                ) { _, target, _ -> Intent(Intent.ACTION_DIAL) },
                Action(
                    id = "call",
                    description = "Place a call",
                    requiredPermissions = listOf("android.permission.CALL_PHONE"),
                ) { _, target, arguments ->
                    val number = arguments["number"].orEmpty()
                    if (number.isBlank()) return@Action null
                    Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                },
            ),
        ),
        Integration(
            packageName = "com.google.android.apps.messaging",
            packageCandidates = listOf("com.google.android.apps.messaging", "com.android.messaging", "com.samsung.android.messaging"),
            label = "Messages",
            description = "Open a conversation and prepare a text message.",
            unsupportedActions = listOf("send a message without confirmation"),
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open Messages",
                ) { _, target, _ -> Intent(Intent.ACTION_MAIN).setPackage(target) },
                Action(
                    id = "prepare_message",
                    description = "Prepare a message to a number",
                ) { _, target, arguments ->
                    val number = arguments["number"].orEmpty()
                    if (number.isBlank()) return@Action null
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                        arguments["text"]?.takeIf { it.isNotBlank() }?.let {
                            putExtra("sms_body", it)
                        }
                    }
                },
            ),
        ),
        Integration(
            packageName = "org.telegram.messenger",
            label = "Telegram",
            description = "Open Telegram and prepare a message to a username.",
            unsupportedActions = listOf("read received messages"),
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open Telegram",
                ) { _, target, _ ->
                    Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve")).setPackage("org.telegram.messenger")
                },
                Action(
                    id = "prepare_message",
                    description = "Prepare a message to a username",
                ) { _, target, arguments ->
                    val user = arguments["name"].orEmpty().trim().removePrefix("@")
                    if (user.isBlank()) return@Action null
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("tg://resolve?domain=${Uri.encode(user)}"),
                    ).setPackage("org.telegram.messenger")
                },
            ),
        ),
        Integration(
            packageName = "com.google.android.gm",
            label = "Gmail",
            description = "Open Gmail and prepare a message.",
            unsupportedActions = listOf("send a message without confirmation"),
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open Gmail",
                ) { _, target, _ ->
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com")).setPackage(target)
                },
                Action(
                    id = "prepare_message",
                    description = "Prepare a message",
                ) { _, target, arguments ->
                    val subject = arguments["subject"].orEmpty()
                    val body = arguments["text"].orEmpty()
                    Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
                        if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
                        setPackage("com.google.android.gm")
                    }
                },
            ),
        ),
        Integration(
            packageName = "com.google.android.apps.maps",
            label = "Google Maps",
            description = "Open Maps and start navigation.",
            unsupportedActions = listOf("read the saved places"),
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open Maps",
                ) { _, target, _ ->
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=0,0")).setPackage(target)
                },
                Action(
                    id = "navigate",
                    description = "Navigate to a place",
                ) { _, target, arguments ->
                    val destination = arguments["destination"].orEmpty()
                    if (destination.isBlank()) return@Action null
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("google.navigation:q=${Uri.encode(destination)}"),
                    ).setPackage(target)
                },
                Action(
                    id = "search",
                    description = "Search for a place",
                ) { _, target, arguments ->
                    val query = arguments["query"].orEmpty()
                    if (query.isBlank()) return@Action null
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=${Uri.encode(query)}"),
                    ).setPackage(target)
                },
            ),
        ),
        Integration(
            packageName = "com.android.chrome",
            label = "Chrome",
            description = "Open Chrome, open a page and run a search.",
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open Chrome",
                ) { _, target, _ ->
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).setPackage(target)
                },
                Action(
                    id = "search",
                    description = "Search the web",
                ) { _, target, arguments ->
                    val query = arguments["query"].orEmpty()
                    if (query.isBlank()) return@Action null
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"),
                    ).setPackage(target)
                },
                Action(
                    id = "open_url",
                    description = "Open a page",
                ) { _, target, arguments ->
                    val url = arguments["url"].orEmpty()
                    if (url.isBlank()) return@Action null
                    val normalised = if (url.startsWith("http")) url else "https://$url"
                    Intent(Intent.ACTION_VIEW, Uri.parse(normalised)).setPackage(target)
                },
            ),
        ),
        Integration(
            packageName = "com.google.android.youtube",
            label = "YouTube",
            description = "Open YouTube and run a search.",
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open YouTube",
                ) { _, target, _ ->
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com")).setPackage(target)
                },
                Action(
                    id = "search",
                    description = "Search YouTube",
                ) { _, target, arguments ->
                    val query = arguments["query"].orEmpty()
                    if (query.isBlank()) return@Action null
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://m.youtube.com/results?search_query=${Uri.encode(query)}"),
                    ).setPackage(target)
                },
            ),
        ),
        Integration(
            packageName = "com.google.android.calendar",
            packageCandidates = listOf("com.google.android.calendar", "com.android.calendar"),
            label = "Calendar",
            description = "Open the calendar and create an event.",
            unsupportedActions = listOf("delete an event without confirmation"),
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open the calendar",
                ) { _, target, _ -> Intent(Intent.ACTION_MAIN).setPackage(target) },
                Action(
                    id = "create_event",
                    description = "Create a calendar event",
                ) { _, target, arguments ->
                    val title = arguments["title"].orEmpty()
                    if (title.isBlank()) return@Action null
                    Intent(Intent.ACTION_INSERT).apply {
                        data = android.provider.CalendarContract.Events.CONTENT_URI
                        putExtra(android.provider.CalendarContract.Events.TITLE, title)
                        arguments["time"]?.takeIf { it.isNotBlank() }?.let {
                            putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, it)
                        }
                    }
                },
            ),
        ),
        Integration(
            packageName = "com.android.camera2",
            label = "Camera",
            description = "Open the camera.",
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open the camera",
                ) { _, target, _ ->
                    Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                },
            ),
        ),
        Integration(
            packageName = "com.google.android.apps.photos",
            packageCandidates = listOf("com.google.android.apps.photos", "com.google.android.projection.gearhead", "com.vivo.gallery", "com.android.gallery3d", "com.mi.android.gallery"),
            label = "Gallery",
            description = "Open the photo gallery.",
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open the gallery",
                ) { _, target, _ ->
                    Intent(Intent.ACTION_VIEW).setType("image/*")
                },
            ),
        ),
        Integration(
            packageName = "com.android.documentsui",
            label = "Files",
            description = "Open the file manager.",
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open Files",
                ) { _, target, _ -> Intent(Intent.ACTION_MAIN).setPackage(target) },
            ),
        ),
        Integration(
            packageName = "com.android.settings",
            label = "Settings",
            description = "Open the settings and the settings pages that Android allows.",
            unsupportedActions = listOf("change a security setting without confirmation"),
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open Settings",
                ) { _, target, _ -> Intent(android.provider.Settings.ACTION_SETTINGS) },
                Action(
                    id = "open_wifi",
                    description = "Open the Wi-Fi settings",
                ) { _, target, _ -> Intent(android.provider.Settings.ACTION_WIFI_SETTINGS) },
                Action(
                    id = "open_bluetooth",
                    description = "Open the Bluetooth settings",
                ) { _, target, _ -> Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) },
                Action(
                    id = "open_display",
                    description = "Open the display settings",
                ) { _, target, _ -> Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS) },
                Action(
                    id = "open_sound",
                    description = "Open the sound settings",
                ) { _, target, _ -> Intent(android.provider.Settings.ACTION_SOUND_SETTINGS) },
                Action(
                    id = "open_battery",
                    description = "Open the battery settings",
                ) { _, target, _ -> Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS) },
            ),
        ),
        Integration(
            packageName = "com.android.vending",
            label = "Play Store",
            description = "Open the Play Store.",
            actions = listOf(
                Action(
                    id = "open",
                    description = "Open the Play Store",
                ) { _, target, _ -> Intent(Intent.ACTION_MAIN).setPackage(target) },
            ),
        ),
    )

    /** A short description of every integration, for the diagnostics screen. */
    fun describe(): List<String> = integrations.map { integration ->
        val state = if (isInstalled(integration.packageName)) "installed" else "not installed"
        val actions = integration.actions.joinToString(", ") { it.id }
        val unsupported = if (integration.unsupportedActions.isEmpty()) "" else
            "  (unsupported: " + integration.unsupportedActions.joinToString(", ") + ")"
        "${integration.label}  [$state]  actions: $actions$unsupported"
    }
}
