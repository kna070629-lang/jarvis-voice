package com.strognoff.jarvis

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Minimal, user-enabled service. It observes supported UI changes and exposes no credential scraping. */
class JarvisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { active = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // UI inspection and actions are deliberately opt-in and not driven by generated shell text.
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }

    /** Clicks only a currently visible, enabled, clickable node with the exact user-entered label. */
    fun clickExactVisibleLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(label)
        val exact = matches.firstOrNull { node ->
            val visibleText = node.text?.toString() ?: node.contentDescription?.toString()
            node.isVisibleToUser && node.isEnabled && visibleText.equals(label, ignoreCase = true)
        } ?: return false
        var target: AccessibilityNodeInfo? = exact
        repeat(5) {
            if (target == null || target!!.isClickable) return@repeat
            target = target!!.parent
        }
        return target?.takeIf { it.isVisibleToUser && it.isEnabled && it.isClickable }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    companion object { @Volatile var active: JarvisAccessibilityService? = null }
}
