package com.example.upscaler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the upscaling job alive while the app is in the background, split-screen or the
 * screen is off. The job itself still runs in MainActivity's worker thread; this service
 * makes Android treat the process as "foreground work" (notification + wake lock) so it is
 * not frozen or killed.
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL = "sukko_progress"
        private const val NOTE_ID = 1001
        private var lastShown = 0L

        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) { }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } catch (_: Throwable) { }
        }

        /** Updates the notification text (rate-limited to about 2 per second). */
        fun progress(ctx: Context, done: Int, total: Int, name: String) {
            val now = System.currentTimeMillis()
            if (now - lastShown < 500 && done < total) return
            lastShown = now
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTE_ID, build(ctx, "Upscaling $done / $total", name, done, total))
            } catch (_: Throwable) { }
        }

        private fun build(ctx: Context, title: String, text: String, done: Int, total: Int): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
            b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
            if (total > 0) b.setProgress(total, done.coerceIn(0, total), false)
            return b.build()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Upscaling progress", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val note = build(this, "Sukko is upscaling", "Working in the background\u2026", 0, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTE_ID, note)
        }
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sukko:upscale").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)   // safety timeout: 6 hours
            }
        } catch (_: Throwable) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) { }
        wakeLock = null
        super.onDestroy()
    }
}
