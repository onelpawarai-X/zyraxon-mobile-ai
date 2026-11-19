package ai.zyraxon.assistant.auto

import ai.zyraxon.assistant.autopilot.Kind
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Notifications from other apps become autopilot events (title as the sender, text as the message). */
class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName || sbn.isOngoing) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val ex = n.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return
            Thread { runCatching { AutoRuntime.event(applicationContext, Kind.NOTIFICATION, from = title, text = text, pkg = sbn.packageName) } }.start()
        } catch (_: Exception) {}
    }
}
