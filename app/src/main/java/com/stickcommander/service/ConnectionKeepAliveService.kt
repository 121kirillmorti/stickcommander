package com.stickcommander.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.stickcommander.MainActivity
import com.stickcommander.R

/**
 * Keeps the process in the foreground while a serial/BLE link is active so the OS
 * does not reclaim the connection during screen-off or heavy memory pressure.
 *
 * The service does not own the transport; it only hosts a connectedDevice
 * foreground notification. The repository starts it after a successful connect.
 */
class ConnectionKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stick)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.isSuccess

        if (!started) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_connection),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_connection_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "stickcommander_connection"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ConnectionKeepAliveService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionKeepAliveService::class.java))
        }
    }
}
