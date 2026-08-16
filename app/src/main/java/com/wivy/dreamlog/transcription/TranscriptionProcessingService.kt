package com.wivy.dreamlog.transcription

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.wivy.dreamlog.MainActivity
import com.wivy.dreamlog.R

/**
 * Finite foreground lifetime for owner-visible local ASR.
 *
 * `mediaProcessing` is the Android foreground-service type intended for time-consuming work on
 * local media assets. The service does not own another queue or retry loop: the runtime store owns
 * exactly one operation, and this component only prevents that operation from becoming ordinary
 * cached-process work after the visible app starts it.
 */
class TranscriptionProcessingService : Service() {
    private var intentionalStop = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                intentionalStop = false
                TranscriptionNotification.ensureChannel(applicationContext)
                startForeground(
                    TranscriptionNotification.NOTIFICATION_ID,
                    TranscriptionNotification.build(
                        context = applicationContext,
                        completedCount = intent.getIntExtra(EXTRA_COMPLETED_COUNT, 0),
                        eligibleCount = intent.getIntExtra(EXTRA_ELIGIBLE_COUNT, 0),
                    ),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
                )
            }

            ACTION_STOP -> {
                intentionalStop = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }

            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!intentionalStop) {
            TranscriptionRuntimeStore.onForegroundServiceDestroyed()
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        intentionalStop = true
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING != 0) {
            TranscriptionRuntimeStore.onForegroundServiceTimeout()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    companion object {
        private const val ACTION_START =
            "com.wivy.dreamlog.transcription.START_MEDIA_PROCESSING"
        private const val ACTION_STOP =
            "com.wivy.dreamlog.transcription.STOP_MEDIA_PROCESSING"
        private const val EXTRA_COMPLETED_COUNT = "completed_count"
        private const val EXTRA_ELIGIBLE_COUNT = "eligible_count"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, TranscriptionProcessingService::class.java)
                    .setAction(ACTION_START),
            )
        }

        fun updateProgress(
            context: Context,
            completedCount: Int,
            eligibleCount: Int,
        ) {
            TranscriptionNotification.ensureChannel(context)
            context.getSystemService(NotificationManager::class.java).notify(
                TranscriptionNotification.NOTIFICATION_ID,
                TranscriptionNotification.build(context, completedCount, eligibleCount),
            )
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, TranscriptionProcessingService::class.java)
                        .setAction(ACTION_STOP),
                )
            }.onFailure {
                // If Android has already removed the finite service, there is nothing left to stop.
                context.stopService(Intent(context, TranscriptionProcessingService::class.java))
            }
        }
    }
}

internal object TranscriptionNotification {
    const val NOTIFICATION_ID = 20_101
    private const val CHANNEL_ID = "local_transcription"
    private const val OPEN_REQUEST_CODE = 20_102

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Local transcription",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress for finite on-device transcription of retained audio."
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
        completedCount: Int,
        eligibleCount: Int,
    ): Notification {
        val openPendingIntent = PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val progress = progressText(completedCount, eligibleCount)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dreamlog)
            .setContentTitle("DreamLog is transcribing")
            .setContentText(progress)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (eligibleCount > 0) {
                    setProgress(eligibleCount, completedCount.coerceIn(0, eligibleCount), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    internal fun progressText(completedCount: Int, eligibleCount: Int): String =
        if (eligibleCount > 0) {
            "${completedCount.coerceIn(0, eligibleCount)} of $eligibleCount sessions complete"
        } else {
            "Preparing the verified local model"
        }
}
