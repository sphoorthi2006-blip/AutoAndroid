package com.suko.app.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Step 30: keeps the app alive (foreground service + partial wake lock) while a long export runs in the web screen, so turning the screen off or switching
 * apps does not stop it. Started and stopped by the page through the bridge method "keepalive" (see [PhoneEngine]). The wake lock times out after 3 hours as a safety net.
 */
class ExportKeepAliveService : Service() {
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Export", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setContentTitle("Bezalel")
            .setContentText("Making your video. Keep the app installed; you can lock the screen.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        if (lock == null) {
            lock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bezalel:export").apply { acquire(3 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "export"
        private const val ID = 4242

        fun set(ctx: Context, on: Boolean) {
            val i = Intent(ctx, ExportKeepAliveService::class.java)
            if (on) { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } else ctx.stopService(i)
        }
    }
}
