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
 * Keeps the app running while the learner is away mid-turn (Gemma writing, Piper speaking, an
 * attempt being scored), so Android doesn't freeze it or kill it to free memory. It shows a
 * notification while it runs, and stops as soon as the turn is done.
 */
class KeepWorkingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Finishing a turn", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Pedant finishes a reply after you leave the app"
            },
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Pedant is finishing a reply")
            .setContentText("Tap to go back to your conversation")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        // A "short service" is meant for exactly this: finishing something the user started.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    /** Android allows a short service about three minutes; a turn that takes longer is left to finish if it can. */
    override fun onTimeout(startId: Int) = stopSelf()

    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    companion object {
        private const val CHANNEL = "keep-working"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, KeepWorkingService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepWorkingService::class.java))
        }
    }
}
