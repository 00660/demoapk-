package com.codex.lanremote.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.codex.lanremote.MainActivity
import com.codex.lanremote.R
import com.codex.lanremote.control.ControlCenter

class WebControlService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在启动网页远程控制服务..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = ControlCenter.startServer(applicationContext)
        val text = if (result.success) {
            "服务已就绪：${ControlCenter.baseUrl(applicationContext)}"
        } else {
            result.message
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        return START_STICKY
    }

    override fun onDestroy() {
        ControlCenter.stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "lan-remote-control"
        private const val NOTIFICATION_ID = 12001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WebControlService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebControlService::class.java))
        }
    }
}
