package com.auto.app

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

/**
 * Priority 2 & Performance Directive Phase 4:
 * Background Export & Recovery Foreground Service (`targetSdk = 34`).
 * - Owns the user-initiated long-running export/upscale notification so job lifetime is independent of
 *   `MainActivity`, `EditorScreen`, or screen navigation.
 * - Uses `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` (`0x00000200`) on Android 14 (API 34+).
 * - Provides a user-visible ongoing notification with stage, progress percentage, and an explicit "Cancel" action button.
 * - Handles Android 14's `onTimeout(startId)` safely by marking the active job `INTERRUPTED` in `ExportJobStore`
 *   ("Export interrupted by Android OS time limit — Resume") and stopping cleanly without corrupting project data.
 * - Reports any foreground-service promotion or startup failure visibly via `DiagnosticsLog.recordWarning` and
 *   `lastStartupWarning` rather than swallowing exceptions silently.
 */
class ExportForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                Exporter.cancel(applicationContext)
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val pct = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
                val statusText = intent?.getStringExtra(EXTRA_STATUS) ?: "Preparing export..."
                ensureChannel()
                val notification = buildNotification(pct, statusText)
                startForegroundSafe(notification)
                return START_NOT_STICKY
            }
        }
    }

    /** Android 14+ (API 34) mediaProcessing foreground service timeout callback. */
    override fun onTimeout(startId: Int) {
        val msg = "Android system background time limit reached — Resume export when ready."
        DiagnosticsLog.recordWarning(msg)
        Exporter.markInterruptedBySystem(applicationContext, msg)
        stopForegroundCompat()
        stopSelf(startId)
    }

    private fun startForegroundSafe(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            lastStartupWarning = null
        } catch (t: Throwable) {
            // Surface foreground-service promotion restriction visibly in diagnostics & status without crashing the running export
            val warn = "Foreground notification unavailable (${t.javaClass.simpleName}); keep app open until export finishes."
            lastStartupWarning = warn
            DiagnosticsLog.recordWarning(warn)
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            DiagnosticsLog.recordWarning("stopForeground warning: ${t.javaClass.simpleName}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Export Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress and Cancel control while exporting MP4 videos"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progressPct: Int, statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentPi = PendingIntent.getActivity(this, 1, openIntent, piFlags)

        val cancelIntent = Intent(this, ExportForegroundService::class.java).apply { action = ACTION_CANCEL }
        val cancelPi = PendingIntent.getService(this, 2, cancelIntent, piFlags)

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Manva Video Export")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPct.coerceIn(0, 100), false)
            .setContentIntent(contentPi)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cancel",
                    cancelPi
                ).build()
            )

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "manva_export_channel"
        const val NOTIFICATION_ID = 4201
        const val ACTION_START_OR_UPDATE = "com.auto.app.action.EXPORT_UPDATE"
        const val ACTION_CANCEL = "com.auto.app.action.EXPORT_CANCEL"
        const val ACTION_STOP = "com.auto.app.action.EXPORT_STOP"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_STATUS = "extra_status"

        @Volatile
        var lastStartupWarning: String? = null
            private set

        /** Starts or updates the foreground export notification, returning any startup warning message visibly. */
        fun notifyProgress(ctx: Context, progressPct: Int, statusText: String): String? {
            val appCtx = ctx.applicationContext
            val i = Intent(appCtx, ExportForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_PROGRESS, progressPct)
                putExtra(EXTRA_STATUS, statusText)
            }
            return try {
                if (Build.VERSION.SDK_INT >= 26) {
                    appCtx.startForegroundService(i)
                } else {
                    appCtx.startService(i)
                }
                lastStartupWarning
            } catch (t: Throwable) {
                val warn = "Background service start restricted by OS (${t.javaClass.simpleName}); keep screen active during export."
                lastStartupWarning = warn
                DiagnosticsLog.recordWarning(warn)
                warn
            }
        }

        fun stop(ctx: Context) {
            val appCtx = ctx.applicationContext
            try {
                appCtx.stopService(Intent(appCtx, ExportForegroundService::class.java))
            } catch (t: Throwable) {
                DiagnosticsLog.recordWarning("Service stop warning: ${t.javaClass.simpleName}")
            }
        }
    }
}
