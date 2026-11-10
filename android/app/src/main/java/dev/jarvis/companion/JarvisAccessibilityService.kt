package dev.jarvis.companion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Only performs navigation when the user invokes a visible in-app control. */
class JarvisAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun navigate(action: String): Boolean = when (action) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        else -> false
    }

    /** Clicks the first exact, visible, non-password text/description match. */
    fun clickExactLabel(requested: String): Boolean {
        val wanted = requested.trim().lowercase()
        if (wanted.isEmpty() || wanted.length > 80 || sensitiveAction.containsMatchIn(wanted)) return false
        val root = rootInActiveWindow ?: return false
        return try {
            findAndClick(root, wanted)
        } finally {
            root.recycle()
        }
    }

    private fun findAndClick(node: AccessibilityNodeInfo, wanted: String): Boolean {
        if (node.isPassword || !node.isVisibleToUser) return false
        val label = (node.text?.toString() ?: node.contentDescription?.toString())
            ?.trim()?.lowercase()
        if (label == wanted) {
            var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
            repeat(5) {
                val current = candidate ?: return false
                if (current.isPassword) {
                    current.recycle()
                    return false
                }
                if (current.isVisibleToUser && current.isEnabled && current.isClickable) {
                    val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    current.recycle()
                    return clicked
                }
                val parent = current.parent
                current.recycle()
                candidate = parent
            }
            candidate?.recycle()
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val clicked = try { findAndClick(child, wanted) } finally { child.recycle() }
            if (clicked) return true
        }
        return false
    }

    companion object {
        // Sensitive actions require their own preview/confirmation flow; the
        // generic visible-label tap cannot authorize them.
        private val sensitiveAction = Regex("\\b(send|delete|remove|call|pay|payment|purchase|buy|order|transfer|share|submit|confirm)\\b")

        @Volatile
        var active: JarvisAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        active = this
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }
}
