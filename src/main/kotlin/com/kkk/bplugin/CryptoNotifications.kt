package com.kkk.bplugin

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType

internal object CryptoNotifications {
    fun info(title: String, content: String) = notify(title, content, NotificationType.INFORMATION)
    fun warn(title: String, content: String) = notify(title, content, NotificationType.WARNING)
    private fun notify(title: String, content: String, type: NotificationType) {
        runCatching { NotificationGroupManager.getInstance().getNotificationGroup("Quiet Crypto").createNotification(title, content, type).notify(null) }
    }
}
