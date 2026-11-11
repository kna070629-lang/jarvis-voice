package dev.jarvis.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The accessibility service that drives the applications.
 *
 * It can read what is on the screen, find the visible text, tap a control,
 * type into a field and scroll. Everything it does is limited to what Android
 * exposes to an accessibility service, it never reads a password or a one
 * time code, and it never automates a captcha.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        onDisconnected()
        super.onDestroy()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        onConnected(this)
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            serviceInfo = info
        } catch (_: Exception) {
        }
    }

    /** True when the service is connected and can be used. */
    val isReady: Boolean get() = active != null

    /** The application that is in the foreground. */
    fun currentPackage(): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    /**
     * The text that is on the screen.
     *
     * This is how JARVIS reads a search result or checks that a message was
     * prepared. Only the visible text is returned.
     */
    fun visibleText(limit: Int = 4000): String {
        val root = rootInActiveWindow ?: return ""
        return try {
            val parts = mutableListOf<String>()
            collectText(root, parts, 400)
            parts.joinToString("\n").take(limit)
        } finally {
            root.recycle()
        }
    }

    /** The clickable controls that are on the screen, by their label. */
    fun clickableLabels(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        return try {
            val labels = mutableListOf<String>()
            collectClickable(root, labels, 300)
            labels.distinct()
        } finally {
            root.recycle()
        }
    }

    /** Taps a control by its exact label. */
    fun clickExactLabel(requested: String): Boolean {
        val wanted = requested.trim().lowercase()
        if (wanted.isEmpty() || wanted.length > 80) return false
        if (sensitiveAction.containsMatchIn(wanted)) return false
        val root = rootInActiveWindow ?: return false
        return try {
            findAndClick(root, wanted)
        } finally {
            root.recycle()
        }
    }

    /** Taps a control whose label contains the given text. */
    fun clickContaining(requested: String): Boolean {
        val wanted = requested.trim().lowercase()
        if (wanted.isEmpty() || wanted.length > 80) return false
        if (sensitiveAction.containsMatchIn(wanted)) return false
        val root = rootInActiveWindow ?: return false
        return try {
            findAndClickContaining(root, wanted)
        } finally {
            root.recycle()
        }
    }

    /** Types text into the field that has the focus. */
    fun typeText(text: String): Boolean {
        if (text.isEmpty() || text.length > 500) return false
        val root = rootInActiveWindow ?: return false
        return try {
            val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditable(root)
                ?: return false
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val done = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            field.recycle()
            done
        } catch (_: Exception) {
            false
        } finally {
            root.recycle()
        }
    }

    /** Scrolls the screen in a direction. */
    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            root.findAccessibilityNodeInfosByViewId("android:id/content")
                .firstOrNull()
                ?.performAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                ) ?: root.performAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
        } catch (_: Exception) {
            false
        } finally {
            root.recycle()
        }
    }

    /** The navigation moves the accessibility service can perform. */
    fun navigate(action: String): Boolean = when (action) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents", "recent apps" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        else -> false
    }

    /** The bounds of a control, so the interface can point at it. */
    fun boundsOfLabel(requested: String): Rect? {
        val wanted = requested.trim().lowercase()
        val root = rootInActiveWindow ?: return null
        return try {
            val node = findNode(root, wanted) ?: return null
            val rect = Rect()
            try {
                node.getBoundsInScreen(rect)
                if (rect.isEmpty) null else rect
            } finally {
                node.recycle()
            }
        } catch (_: Exception) {
            null
        } finally {
            root.recycle()
        }
    }

    // -- internals --------------------------------------------------------
    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 40 || out.size > 400) return
        if (node.isPassword) return
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() && text != description) out.add(text)
        else if (description.isNotEmpty()) out.add(description)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectText(child, out, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun collectClickable(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 40 || out.size > 300) return
        if (node.isClickable && !node.isPassword) {
            val label = node.text?.toString()?.trim().orEmpty()
                .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
            if (label.isNotEmpty()) out.add(label)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectClickable(child, out, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun findNode(node: AccessibilityNodeInfo, wanted: String): AccessibilityNodeInfo? {
        if (node.isPassword) return null
        val label = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val description = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if (label == wanted || description == wanted) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = try {
                findNode(child, wanted)
            } finally {
                child.recycle()
            }
            if (found != null) return found
        }
        return null
    }

    private fun findAndClick(node: AccessibilityNodeInfo, wanted: String): Boolean {
        if (node.isPassword || !node.isVisibleToUser) return false
        val label = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val description = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if (label == wanted || description == wanted) {
            val candidate = AccessibilityNodeInfo.obtain(node)
            return try {
                var clicked = false
                repeat(4) {
                    if (clicked) return@repeat
                    val current = candidate ?: return@repeat
                    if (current.isPassword) return@repeat
                    if (current.isClickable) {
                        clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        val parent = current.parent
                        if (parent != null) {
                            clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            parent.recycle()
                        }
                    }
                }
                clicked
            } finally {
                candidate.recycle()
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val clicked = try {
                findAndClick(child, wanted)
            } finally {
                child.recycle()
            }
            if (clicked) return true
        }
        return false
    }

    private fun findAndClickContaining(node: AccessibilityNodeInfo, wanted: String): Boolean {
        if (node.isPassword || !node.isVisibleToUser) return false
        val label = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val description = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if (label.contains(wanted) || description.contains(wanted)) {
            if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val parent = node.parent
            if (parent != null) {
                val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return clicked
            }
            return false
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val clicked = try {
                findAndClickContaining(child, wanted)
            } finally {
                child.recycle()
            }
            if (clicked) return true
        }
        return false
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && !node.isPassword) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            var found: AccessibilityNodeInfo? = null
            try {
                found = findFirstEditable(child)
            } catch (_: Exception) {
                found = null
            } finally {
                if (found == null) child.recycle()
            }
            if (found != null) return found
        }
        return null
    }

    companion object {
        /** The live instance, when the user has enabled the service. */
        @Volatile
        var active: JarvisAccessibilityService? = null
            private set

        /**
         * Words that are never acted on. A password, a one time code and a
         * captcha are not read and not tapped.
         */
        private val sensitiveAction = Regex(
            "\\b(?:password|passcode|pin\\b|otp|one time code|verification code|captcha|otp " +
                "authentication code|security code|bank account|cvv)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** Called by the system when the service is connected. */
        internal fun onConnected(service: JarvisAccessibilityService) {
            active = service
        }

        /** Called by the system when the service is disconnected. */
        internal fun onDisconnected() {
            active = null
        }
    }
}
