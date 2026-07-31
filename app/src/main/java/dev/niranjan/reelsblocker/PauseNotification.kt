package dev.niranjan.reelsblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The pause countdown in the shade. Shared because three places need it: the
 * activity posts it, the resume action cancels it, and the service re-posts it
 * on connect so a day-long pause survives a reboot.
 */
object PauseNotification {

    const val CHANNEL = "pause"
    const val ID = 2

    fun ensureChannel(context: Context) {
        manager(context).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.pause_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    /**
     * Counts down in the shade via the chronometer, and setTimeoutAfter has the
     * system clear it at expiry even if the app is never reopened. Dismissible —
     * dismissing does not affect the pause, which is purely the stored deadline.
     */
    fun show(context: Context, prefs: Prefs) {
        val remaining = prefs.pauseRemainingMs
        if (remaining == 0L) return
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(context.getString(R.string.pause_notification_title))
            .setContentText(context.getString(R.string.pause_notification_body))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(prefs.pauseUntil)
            .setShowWhen(true)
            .setTimeoutAfter(remaining)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.pause_notification_resume),
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(context, ResumeReceiver::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build()
            )
            .build()
        manager(context).notify(ID, notification)
    }

    fun cancel(context: Context) {
        manager(context).cancel(ID)
    }

    private fun manager(context: Context) =
        context.getSystemService(NotificationManager::class.java)
}
