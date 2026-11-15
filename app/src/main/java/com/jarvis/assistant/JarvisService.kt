package com.jarvis.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Foreground service: keeps the process (and the "Hey Jarvis" listener) alive. Shows a visible notification. */
class JarvisService : Service() {
    companion object {
        const val ACTION_STOP = "jarvis.STOP"
        const val ACTION_QUIT = "jarvis.QUIT"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as JarvisApp).engine
        when (intent?.action) {
            ACTION_STOP -> engine.emergencyStop()
            ACTION_QUIT -> { engine.setWake(false); stopSelf(); return START_NOT_STICKY }
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))

        fun pi(a: String) = PendingIntent.getService(
            this, a.hashCode(), Intent(this, JarvisService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, "jarvis")
            .setContentTitle("Jarvis is listening for \"Hey Jarvis\"")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Jarvis Stop", pi(ACTION_STOP))
            .addAction(android.R.drawable.ic_delete, "Turn off", pi(ACTION_QUIT))
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_STICKY
    }
}
