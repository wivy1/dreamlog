package com.wivy.dreamlog.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.wivy.dreamlog.MainActivity
import com.wivy.dreamlog.R

object NightNotification {
    const val NOTIFICATION_ID = 20_001
    const val CHANNEL_ID = "night_capture"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active night",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Silent status while DreamLog is listening for recollections."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        snapshot: CaptureRuntimeSnapshot,
    ): Notification {
        val openPendingIntent = PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val endPendingIntent = PendingIntent.getService(
            context,
            END_REQUEST_CODE,
            Intent(context, NightListeningService::class.java)
                .setAction(NightListeningService.ACTION_END_NIGHT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = notificationText(snapshot)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dreamlog)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(snapshot.displayDate?.let { "Night $it" })
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(null)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "End night",
                    endPendingIntent,
                ).build(),
            )
            .build()
    }

    private fun notificationText(snapshot: CaptureRuntimeSnapshot): Pair<String, String> =
        when {
            snapshot.microphoneSilenced ->
                "DreamLog is not listening" to "Android is silencing the microphone input"

            snapshot.phase == CapturePhase.STARTING ->
                "DreamLog is starting" to "Verifying the microphone and local wake model"

            snapshot.phase == CapturePhase.ACKNOWLEDGING ||
                snapshot.phase == CapturePhase.RECORDING ->
                "DreamLog is recording" to "Capturing a wake-triggered recollection"

            snapshot.phase == CapturePhase.FINALIZING ->
                "DreamLog is saving" to "Finalizing the current recollection"

            snapshot.visibleOtherRecorderCount > 0 ->
                "Another recorder is active" to "Android may silence one of the microphone clients"

            else ->
                "DreamLog is listening" to
                    "Say “DreamLog” or “Hey DreamLog”"
        }

    private const val OPEN_REQUEST_CODE = 20_002
    private const val END_REQUEST_CODE = 20_003
}
