package com.pedant.app

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
import androidx.core.app.ServiceCompat

/**
 * Keeps downloads (the models, a voice, a dictionary) going after the learner leaves the app, so
 * Android doesn't stop them. It shows the download's progress in a notification while it runs, and
 * stops when the last download ends. Android calls it a foreground service: it's the learner who
 * can see it, through the notification, not the app that has to be on screen.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Pedant downloads models, voices and dictionaries"
            },
        )
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Downloading"
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, title, 0, 0), type)
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    /** Android limits how long downloads may run in the background each day; past that, stop. */
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_TITLE = "title"

        @Volatile private var running = false
        @Volatile private var lastUpdate = 0L

        fun start(context: Context, title: String) {
            val intent = Intent(context, DownloadService::class.java).putExtra(EXTRA_TITLE, title)
            runCatching { context.startForegroundService(intent) }
        }

        /** Shows [done] of [total] bytes of [title] in the notification, at most once a second. */
        fun update(context: Context, title: String, done: Long, total: Long) {
            val now = System.currentTimeMillis()
            if (!running || now - lastUpdate < 1000) return
            lastUpdate = now
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(context, title, done, total))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }

        private fun notification(context: Context, title: String, done: Long, total: Long): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val percent = if (total > 0) (done * 100 / total).toInt() else 0
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(if (total > 0) "%.2f of %.2f GB".format(done / 1e9, total / 1e9) else "Starting…")
                .setProgress(100, percent, total <= 0)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}
