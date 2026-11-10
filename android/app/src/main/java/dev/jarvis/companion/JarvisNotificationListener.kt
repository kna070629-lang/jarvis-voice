package dev.jarvis.companion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.ArrayDeque

data class PermittedNotification(val packageName: String, val title: String, val text: String)

/** Stores a small, in-memory notification window. Nothing is uploaded automatically. */
class JarvisNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(160)
        val text = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        ).filterNotNull().firstOrNull { it.isNotBlank() }.orEmpty().take(600)
        if (title.isBlank() && text.isBlank()) return
        synchronized(items) {
            items.addLast(PermittedNotification(sbn.packageName.take(120), title, text))
            while (items.size > 20) items.removeFirst()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit

    companion object {
        private val items = ArrayDeque<PermittedNotification>()
        fun snapshot(): List<PermittedNotification> = synchronized(items) { items.toList() }
        fun clear() = synchronized(items) { items.clear() }
    }
}
