package com.jarvis.assistant

import android.service.notification.NotificationListenerService

class JarvisNotificationListener : NotificationListenerService() {
    companion object { @Volatile var instance: JarvisNotificationListener? = null }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    fun recent(): List<String> = try {
        activeNotifications.filter { !it.isOngoing }.take(10).map { sbn ->
            val extras = sbn.notification.extras
            val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            catch (e: Exception) { sbn.packageName }
            "$app: ${extras.getCharSequence("android.title") ?: ""} - ${extras.getCharSequence("android.text") ?: ""}"
        }
    } catch (e: Exception) { emptyList() }
}
