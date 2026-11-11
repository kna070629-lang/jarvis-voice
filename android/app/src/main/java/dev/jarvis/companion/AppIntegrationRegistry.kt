package dev.jarvis.companion

/**
 * What JARVIS knows about one application.
 *
 * The registry is a description of what each application supports, so the
 * assistant never claims to do something it cannot do. A level is only raised
 * when the behaviour was actually verified on the device.
 */
data class AppCapability(
    val packageName: String,
    val label: String,
    /** The names people actually say for this application. */
    val aliases: List<String> = emptyList(),
    /** What the application can be used for. */
    val level: SupportLevel = SupportLevel.LAUNCH_SUPPORTED,
    /** The search action, when the application has a supported one. */
    val search: Boolean = false,
    /** The text entry action, when the application has a supported one. */
    val textInput: Boolean = false,
    /** The official API, when the application offers one that is used. */
    val officialApi: String = "",
    /** Why the application is restricted, when it is. */
    val restriction: String = "",
)

/**
 * The registry of the applications JARVIS knows about.
 *
 * The list is not the whole set of installed applications. Anything that is
 * installed but not listed is still opened by its name through the discovery
 * manager, it is simply reported as `NOT_TESTED`.
 */
object AppIntegrationRegistry {

    /** The applications that were checked on the device, with what they support. */
    val CAPABILITIES: List<AppCapability> = listOf(
        // Messaging and social
        AppCapability(
            "com.whatsapp",
            "WhatsApp",
            listOf("whats app", "whatsup", "wa"),
            SupportLevel.WORKFLOW_SUPPORTED,
            search = true,
            textInput = true,
        ),
        AppCapability(
            "com.whatsapp.w4b",
            "WhatsApp Business",
            listOf("whats app business", "wa business"),
            SupportLevel.WORKFLOW_SUPPORTED,
            search = true,
            textInput = true,
        ),
        AppCapability(
            "org.telegram.messenger",
            "Telegram",
            listOf("tele gram"),
            SupportLevel.WORKFLOW_SUPPORTED,
            search = true,
            textInput = true,
        ),
        AppCapability(
            "com.instagram.android",
            "Instagram",
            listOf("insta gram", "ig", "insta"),
            SupportLevel.SEARCH_SUPPORTED,
            search = true,
            textInput = true,
        ),
        AppCapability(
            "com.google.android.apps.messaging",
            "Messages",
            listOf("sms", "text message"),
            SupportLevel.WORKFLOW_SUPPORTED,
            textInput = true,
        ),

        // Media and design
        AppCapability(
            "com.spotify.music",
            "Spotify",
            listOf("spot ify"),
            SupportLevel.LAUNCH_SUPPORTED,
            search = true,
        ),
        AppCapability(
            "com.google.android.youtube",
            "YouTube",
            listOf("you tube", "yt"),
            SupportLevel.LAUNCH_SUPPORTED,
            search = true,
        ),
        AppCapability(
            "com.canva.editor",
            "Canva",
            listOf("can va"),
            SupportLevel.RESTRICTED,
            restriction = "The editor and the export are driven by the accessibility service, " +
                "and JARVIS only reports a step that it can see succeed.",
        ),
        AppCapability(
            "com.capcut.app",
            "CapCut",
            listOf("cap cut"),
            SupportLevel.RESTRICTED,
            restriction = "Editing is only automated where the current version exposes " +
                "the control, and the export is never claimed without evidence.",
        ),

        // Shopping
        AppCapability(
            "com.amazon.mShop.android.shopping",
            "Amazon",
            listOf("amazon shopping", "amazon app"),
            SupportLevel.SEARCH_SUPPORTED,
            search = true,
            restriction = "An order is never placed and a payment is never made.",
        ),
        AppCapability(
            "com.flipkart.android",
            "Flipkart",
            listOf("flip kart"),
            SupportLevel.SEARCH_SUPPORTED,
            search = true,
            restriction = "An order is never placed and a payment is never made.",
        ),
        AppCapability(
            "com.myntra.android",
            "Myntra",
            listOf("my ntra"),
            SupportLevel.SEARCH_SUPPORTED,
            search = true,
            restriction = "An order is never placed and a payment is never made.",
        ),

        // Payments, read only
        AppCapability(
            "org.phonepe.phonepe",
            "PhonePe",
            listOf("phone pe", "phonepay"),
            SupportLevel.RESTRICTED,
            restriction = "Money is never transferred and a collect request is never approved.",
        ),
        AppCapability(
            "com.paytm.android",
            "Paytm",
            listOf("pay tm"),
            SupportLevel.RESTRICTED,
            restriction = "Money is never transferred and a collect request is never approved.",
        ),

        // Development
        AppCapability(
            "com.github.android",
            "GitHub",
            listOf("git hub"),
            SupportLevel.API_INTEGRATED,
            officialApi = "the GitHub API",
        ),
        AppCapability(
            "com.foxdebug.acode",
            "Acode",
            listOf("a code"),
            SupportLevel.LAUNCH_SUPPORTED,
        ),
        AppCapability(
            "com.termux",
            "Termux",
            listOf("term ux"),
            SupportLevel.LAUNCH_SUPPORTED,
        ),

        // Assistants
        AppCapability(
            "com.openai.chatgpt",
            "ChatGPT",
            listOf("chat gpt", "open ai"),
            SupportLevel.LAUNCH_SUPPORTED,
        ),
        AppCapability(
            "com.anthropic.claude",
            "Claude",
            listOf("clode"),
            SupportLevel.LAUNCH_SUPPORTED,
        ),

        // Utilities
        AppCapability(
            "com.truecaller",
            "Truecaller",
            listOf("true caller"),
            SupportLevel.LAUNCH_SUPPORTED,
        ),
        AppCapability(
            "com.digilocker.android",
            "DigiLocker",
            listOf("digi locker"),
            SupportLevel.LAUNCH_SUPPORTED,
        ),
        AppCapability(
            "com.google.android.apps.authenticator2",
            "Authenticator",
            listOf("google authenticator"),
            SupportLevel.RESTRICTED,
            restriction = "A one time code is never read out and never stored.",
        ),
        AppCapability(
            "com.google.android.apps.drive",
            "Google Drive",
            listOf("drive"),
            SupportLevel.LAUNCH_SUPPORTED,
            search = true,
        ),
        AppCapability(
            "com.google.android.apps.docs",
            "Google Docs",
            listOf("docs", "google docs"),
            SupportLevel.LAUNCH_SUPPORTED,
            search = true,
            textInput = true,
        ),
        AppCapability(
            "com.google.android.apps.classroom",
            "Google Classroom",
            listOf("class room"),
            SupportLevel.LAUNCH_SUPPORTED,
        ),

        // Trading, read only
        AppCapability(
            "net.metaquotes.metatrader5",
            "MetaTrader 5",
            listOf("meta trader", "mt5"),
            SupportLevel.RESTRICTED,
            restriction = "A trade is never placed, modified or closed without a confirmation " +
                "of the instrument, the direction, the quantity and the order.",
        ),
        AppCapability(
            "com.tradingview",
            "TradingView",
            listOf("trading view"),
            SupportLevel.RESTRICTED,
            restriction = "A trade is never placed, modified or closed without a confirmation " +
                "of the instrument, the direction, the quantity and the order.",
        ),
    )

    private val byPackage: Map<String, AppCapability> = CAPABILITIES.associateBy { it.packageName }

    /** What is known about an application. */
    fun capability(packageName: String): AppCapability? = byPackage[packageName]

    /** What is known about an application, or a description of what is unknown. */
    fun capabilityOrUnknown(packageName: String, label: String): AppCapability =
        byPackage[packageName] ?: AppCapability(
            packageName = packageName,
            label = label,
            level = SupportLevel.NOT_TESTED,
            restriction = "It is installed, so it can be opened, but nothing else is known about it.",
        )

    /** The names an application answers to, including its own label. */
    fun namesFor(packageName: String): List<String> {
        val capability = byPackage[packageName] ?: return emptyList()
        return (listOf(capability.label) + capability.aliases).distinct()
    }
}
