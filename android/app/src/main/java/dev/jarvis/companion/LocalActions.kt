package dev.jarvis.companion

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import java.text.DecimalFormat

/**
 * The local capability plugins.
 *
 * These run entirely on the phone. They never touch the network, so they keep
 * working with the airplane mode on and they answer in milliseconds.
 */

/** Reports the battery level and charging state. */
class BatteryAction : JarvisAction {
    override val id: String = "device.battery"
    override val description: String = "Report the battery level and charging state"

    override fun execute(context: Context?, arguments: Map<String, String>): ActionOutcome {
        val manager = context?.getSystemService(BatteryManager::class.java)
            ?: return ActionOutcome.failed("The battery service is unavailable.")
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 0) return ActionOutcome.failed("The battery level is not reported on this device.")
        val status = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val power = context?.getSystemService(PowerManager::class.java)
        val saving = power?.isPowerSaveMode == true
        val state = if (charging) "charging" else "on battery"
        val extra = if (saving) ", power saving is on" else ""
        return ActionOutcome.ok(
            "The battery is at $level percent, $state$extra.",
            mapOf("level" to level.toString(), "charging" to charging.toString()),
        )
    }
}

/** Reports basic information about the phone. */
class DeviceInfoAction : JarvisAction {
    override val id: String = "device.info"
    override val description: String = "Report the device model, Android version and screen size"

    override fun execute(context: Context?, arguments: Map<String, String>): ActionOutcome {
        val model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        val version = android.os.Build.VERSION.RELEASE
        val metrics = context?.resources?.displayMetrics
            ?: return ActionOutcome.failed("The screen size is not available.")
        val size = "${metrics.widthPixels} by ${metrics.heightPixels} pixels"
        return ActionOutcome.ok(
            "This is a $model running Android $version with a $size screen.",
            mapOf("model" to model, "android" to version),
        )
    }
}

/** Evaluates a simple arithmetic expression without asking any model. */
class CalculationAction : JarvisAction {
    override val id: String = "device.calculate"
    override val description: String = "Evaluate a simple arithmetic expression"

    override fun execute(context: Context?, arguments: Map<String, String>): ActionOutcome {
        val expression = arguments["expression"].orEmpty().trim()
        if (expression.isEmpty()) return ActionOutcome.failed("I need an expression to calculate.")
        if (expression.length > 120) return ActionOutcome.failed("That expression is too long to calculate.")
        if (!SAFE_EXPRESSION.matches(expression)) {
            return ActionOutcome.failed("I can only calculate with numbers and the + - * / ( ) . % operators.")
        }
        return try {
            val value = evaluate(expression)
            val formatted = DecimalFormat("0.########").format(value)
            ActionOutcome.ok("$formatted.", mapOf("value" to formatted))
        } catch (_: Exception) {
            ActionOutcome.failed("I could not work out that calculation.")
        }
    }

    private fun evaluate(expression: String): Double {
        val tokens = Tokenizer(expression).tokenize()
        val parser = Parser(tokens)
        val value = parser.parseExpression()
        if (!parser.atEnd()) throw IllegalArgumentException("trailing input")
        return value
    }

    private class Tokenizer(private val text: String) {
        private var index = 0
        fun tokenize(): List<String> {
            val tokens = mutableListOf<String>()
            while (index < text.length) {
                val ch = text[index]
                when {
                    ch.isWhitespace() -> index++
                    ch.isDigit() || ch == '.' -> {
                        val start = index
                        while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
                        tokens.add(text.substring(start, index))
                    }
                    ch in "+-*/%()" -> {
                        tokens.add(ch.toString())
                        index++
                    }
                    else -> throw IllegalArgumentException("unexpected character $ch")
                }
            }
            return tokens
        }
    }

    private class Parser(private val tokens: List<String>) {
        private var position = 0

        fun atEnd(): Boolean = position >= tokens.size

        fun parseExpression(): Double {
            var value = parseTerm()
            while (!atEnd()) {
                val op = tokens[position]
                if (op != "+" && op != "-") break
                position++
                val right = parseTerm()
                value = if (op == "+") value + right else value - right
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (!atEnd()) {
                val op = tokens[position]
                if (op != "*" && op != "/" && op != "%") break
                position++
                val right = parseFactor()
                value = when (op) {
                    "*" -> value * right
                    "/" -> value / right
                    else -> value % right
                }
            }
            return value
        }

        private fun parseFactor(): Double {
            if (atEnd()) throw IllegalArgumentException("unexpected end")
            val token = tokens[position]
            if (token == "-") {
                position++
                return -parseFactor()
            }
            if (token == "+") {
                position++
                return parseFactor()
            }
            if (token == "(") {
                position++
                val value = parseExpression()
                if (atEnd() || tokens[position] != ")") throw IllegalArgumentException("missing bracket")
                position++
                return value
            }
            position++
            return token.toDouble()
        }
    }

    companion object {
        /** Only digits and simple arithmetic are allowed, so nothing can be evaluated as code. */
        private val SAFE_EXPRESSION = Regex("^[0-9+\\-*/%().\\s]+$")
    }
}

/** Turns Wi-Fi on or off and reports the current state. */
class WifiAction : JarvisAction {
    override val id: String = "device.wifi"
    override val description: String = "Turn Wi-Fi on or off and report the connection state"

    override fun execute(context: Context?, arguments: Map<String, String>): ActionOutcome {
        val manager = context?.getSystemService(WifiManager::class.java)
            ?: return ActionOutcome.failed("Wi-Fi is unavailable on this device.")
        val state = arguments["state"].orEmpty().lowercase()
        val enabled = manager.isWifiEnabled
        return when (state) {
            "on" -> {
                // Android does not allow apps to toggle Wi-Fi directly any more, so
                // the user is taken to the settings screen instead of pretending.
                openWifiSettings(context)
                ActionOutcome.ok("Opening the Wi-Fi settings so you can turn it on.")
            }
            "off" -> {
                openWifiSettings(context)
                ActionOutcome.ok("Opening the Wi-Fi settings so you can turn it off.")
            }
            else -> ActionOutcome.ok(
                if (enabled) "Wi-Fi is on." else "Wi-Fi is off.",
                mapOf("enabled" to enabled.toString()),
            )
        }
    }

    private fun openWifiSettings(context: Context?) {
        if (context == null) return
        try {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }
}

/** Turns Bluetooth on or off and reports the current state. */
class BluetoothAction : JarvisAction {
    override val id: String = "device.bluetooth"
    override val description: String = "Turn Bluetooth on or off and report the current state"

    override fun execute(context: Context?, arguments: Map<String, String>): ActionOutcome {
        val manager = context?.getSystemService(BluetoothManager::class.java)
            ?: return ActionOutcome.failed("Bluetooth is unavailable on this device.")
        val adapter = manager.adapter ?: return ActionOutcome.failed("This device has no Bluetooth adapter.")
        val enabled = adapter.isEnabled
        val state = arguments["state"].orEmpty().lowercase()
        return when (state) {
            "on" -> {
                try {
                    if (!enabled) {
                        val result = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                            ?.enable()
                        // Enabling needs a privileged permission, so report honestly.
                        return if (result == true) {
                            ActionOutcome.ok("Bluetooth is on.")
                        } else {
                            openBluetoothSettings(context)
                            ActionOutcome.ok("Opening the Bluetooth settings so you can turn it on.")
                        }
                    }
                    ActionOutcome.ok("Bluetooth is already on.")
                } catch (_: SecurityException) {
                    openBluetoothSettings(context)
                    ActionOutcome.ok("Opening the Bluetooth settings so you can turn it on.")
                }
            }
            "off" -> {
                try {
                    if (enabled) {
                        val result = adapter.disable()
                        return if (result) {
                            ActionOutcome.ok("Bluetooth is off.")
                        } else {
                            openBluetoothSettings(context)
                            ActionOutcome.ok("Opening the Bluetooth settings so you can turn it off.")
                        }
                    }
                    ActionOutcome.ok("Bluetooth is already off.")
                } catch (_: SecurityException) {
                    openBluetoothSettings(context)
                    ActionOutcome.ok("Opening the Bluetooth settings so you can turn it off.")
                }
            }
            else -> ActionOutcome.ok(
                if (enabled) "Bluetooth is on." else "Bluetooth is off.",
                mapOf("enabled" to enabled.toString()),
            )
        }
    }

    private fun openBluetoothSettings(context: Context?) {
        if (context == null) return
        try {
            context.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }
}

/** Reports or changes the do-not-disturb setting. */
class DndAction : JarvisAction {
    override val id: String = "device.dnd"
    override val description: String = "Report or change the do not disturb setting"

    override fun execute(context: Context?, arguments: Map<String, String>): ActionOutcome {
        val manager = context?.getSystemService(NotificationManager::class.java)
            ?: return ActionOutcome.failed("Notification control is unavailable.")
        if (!manager.isNotificationPolicyAccessGranted) {
            return ActionOutcome.notPermitted(
                "Permission needed: Do Not Disturb access. Allow it in Android notification settings.",
            )
        }
        val state = arguments["state"].orEmpty().lowercase()
        return when (state) {
            "on" -> {
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                ActionOutcome.ok("Do not disturb is on.")
            }
            "off" -> {
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                ActionOutcome.ok("Do not disturb is off.")
            }
            else -> {
                val on = manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                ActionOutcome.ok(
                    if (on) "Do not disturb is on." else "Do not disturb is off.",
                    mapOf("enabled" to on.toString()),
                )
            }
        }
    }
}

/** Reports the screen state and can lock or wake the screen. */
class ScreenAction : JarvisAction {
    override val id: String = "device.screen"
    override val description: String = "Report the screen state and lock or wake the screen"

    override fun execute(context: Context?, arguments: Map<String, String>): ActionOutcome {
        val power = context?.getSystemService(PowerManager::class.java)
            ?: return ActionOutcome.failed("Screen control is unavailable.")
        val interactive = power.isInteractive
        val state = arguments["state"].orEmpty().lowercase()
        return when (state) {
            "off" -> {
                if (interactive) {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_SCREEN_OFF).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        ActionOutcome.ok("Turning the screen off.")
                    } catch (_: Exception) {
                        ActionOutcome.failed("Android did not turn the screen off.")
                    }
                } else {
                    ActionOutcome.ok("The screen is already off.")
                }
            }
            "on" -> {
                if (!interactive) {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_SCREEN_ON).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        ActionOutcome.ok("Waking the screen.")
                    } catch (_: Exception) {
                        ActionOutcome.failed("Android did not wake the screen.")
                    }
                } else {
                    ActionOutcome.ok("The screen is already on.")
                }
            }
            else -> ActionOutcome.ok(
                if (interactive) "The screen is on." else "The screen is off.",
                mapOf("interactive" to interactive.toString()),
            )
        }
    }
}
