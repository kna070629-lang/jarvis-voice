package dev.jarvis.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * A single local capability that JARVIS can run on the phone.
 *
 * Every plugin declares its identity, what it does, the permissions it needs
 * and how to execute it. The intent engine consults the registry before any
 * cloud model is asked, so a supported command never leaves the device.
 */
interface JarvisAction {
    /** Stable identifier, for example "device.battery". */
    val id: String

    /** One line description shown in the diagnostics screen. */
    val description: String

    /** Permissions this action needs before it can run. */
    val requiredPermissions: List<String>
        get() = emptyList()

    /** True when the action needs network access. */
    val requiresNetwork: Boolean
        get() = false

    /**
     * Runs the action.
     *
     * @return a result that says what actually happened. The engine never
     * reports success for an action that did not run.
     */
    fun execute(context: Context?, arguments: Map<String, String>): ActionOutcome
}

/** The honest result of running an action. */
data class ActionOutcome(
    val status: ActionStatus,
    val message: String,
    val detail: Map<String, String> = emptyMap(),
) {
    val succeeded: Boolean get() = status == ActionStatus.SUCCEEDED

    companion object {
        fun ok(message: String, detail: Map<String, String> = emptyMap()) =
            ActionOutcome(ActionStatus.SUCCEEDED, message, detail)

        fun failed(message: String) = ActionOutcome(ActionStatus.FAILED, message)

        fun notPermitted(message: String) = ActionOutcome(ActionStatus.PERMISSION_REQUIRED, message)
    }
}

/**
 * The registry of local action plugins.
 *
 * The registry is the single place that knows what the phone can do. The
 * intent engine routes to it first, so the local tier stays fast and keeps
 * working when the network is gone.
 */
class ActionPluginRegistry(private val context: Context?) {

    private val plugins = LinkedHashMap<String, JarvisAction>()

    init {
        registerAll()
    }

    fun register(action: JarvisAction) {
        plugins[action.id] = action
    }

    fun all(): List<JarvisAction> = plugins.values.toList()

    fun find(id: String): JarvisAction? = plugins[id]

    /** True when every permission the action needs has been granted. */
    fun hasPermissions(action: JarvisAction): Boolean = missingPermissions(action).isEmpty()

    /**
     * The permissions that are still missing for an action.
     *
     * Without a context there is nothing to check against, so nothing is
     * reported as missing. The routing logic stays usable in unit tests.
     */
    fun missingPermissions(action: JarvisAction): List<String> {
        val owner = context ?: return emptyList()
        return action.requiredPermissions.filter { permission ->
            owner.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    /** A short list of the registered capabilities for the diagnostics screen. */
    fun describe(): List<String> = plugins.values.map { action ->
        val state = if (hasPermissions(action)) "ready" else "needs permission"
        "${action.id}  ${action.description}  [$state]"
    }

    private fun registerAll() {
        register(BatteryAction())
        register(DeviceInfoAction())
        register(CalculationAction())
        register(WifiAction())
        register(BluetoothAction())
        register(DndAction())
        register(ScreenAction())
    }
}

/** Shared permission names for the plugin registry. */
object JarvisPermissions {
    val CONTACTS = Manifest.permission.READ_CONTACTS
    val PHONE = Manifest.permission.CALL_PHONE
    val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS
    val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO
}
