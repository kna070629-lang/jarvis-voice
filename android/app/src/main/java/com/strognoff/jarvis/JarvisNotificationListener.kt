package com.strognoff.jarvis

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Stores the latest permitted notification locally; no notification content is sent to the backend. */
class JarvisNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty().take(300)
        val body = (extras.getCharSequence("android.text") ?: extras.getCharSequence("android.bigText"))
            ?.toString().orEmpty().take(1000)
        getSharedPreferences("jarvis", MODE_PRIVATE).edit()
            .putString("last_notification", "${sbn.packageName}: $title — $body")
            .apply()
    }
}
