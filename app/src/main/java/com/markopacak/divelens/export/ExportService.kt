package com.markopacak.divelens.export

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.markopacak.divelens.DiveLensApp
import com.markopacak.divelens.MainActivity
import com.markopacak.divelens.R
import com.markopacak.divelens.batch.ExportController
import com.markopacak.divelens.batch.ExportProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the export batch running when the user leaves the app or the screen turns off (add-video-processing D8,
 * import-export "Export continues in the background"). A foreground service of type `mediaProcessing` (Android 15+) or
 * `dataSync` (10–14) that follows the [ExportController]: a progress notification with Cancel while it runs, a partial
 * wake lock, then a summary notification, and it stops itself. Without the notification permission the batch still runs.
 */
@UnstableApi
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val controller: ExportController get() = testController ?: (application as DiveLensApp).graph.controller

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            controller.cancel()
            return START_NOT_STICKY
        }
        val progress = controller.progress.value
        startInForeground(progressNotification(progress as? ExportProgress.Running ?: ExportProgress.Running(0, 0)))
        if (following == null) {
            acquireWakeLock()
            following = scope.launch {
                controller.progress.collect { p ->
                    when (p) {
                        is ExportProgress.Running -> notify(PROGRESS_ID, progressNotification(p))
                        is ExportProgress.Finished -> finish(p)
                        ExportProgress.Idle -> finish(null)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * `mediaProcessing` where it exists (Android 15+), else `dataSync`. Some builds refuse `mediaProcessing` although the
     * manifest declares it (HyperOS on Android 16 reports "type none"); they get `dataSync`, which the manifest also declares.
     */
    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                ServiceCompat.startForeground(this, PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                return
            } catch (e: RuntimeException) {
                if (Build.VERSION.SDK_INT < 34 || e !is android.app.ForegroundServiceTypeException) throw e
                Log.w(TAG, "mediaProcessing refused, using dataSync", e)
            }
        }
        ServiceCompat.startForeground(this, PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun finish(result: ExportProgress.Finished?) {
        following?.cancel()
        following = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        result?.let { notify(SUMMARY_ID, summaryNotification(it)) }
        releaseWakeLock()
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiveLens:export").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notify(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun progressNotification(p: ExportProgress.Running): Notification {
        ensureChannel(this)
        val cancel = PendingIntent.getService(this, 0, cancelIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_export)
            .setContentTitle(getString(R.string.notification_exporting_title))
            .setContentText(if (p.total > 0) getString(R.string.export_progress, minOf(p.done + 1, p.total), p.total) else null)
            .setProgress(p.total, p.done, p.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.cancel), cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun summaryNotification(r: ExportProgress.Finished): Notification {
        ensureChannel(this)
        val text = resources.getQuantityString(R.plurals.summary_exported, r.exported, r.exported)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_export)
            .setContentTitle(getString(if (r.cancelled) R.string.summary_cancelled_title else R.string.summary_title))
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "ExportService"
        const val CHANNEL_ID = "export"
        const val PROGRESS_ID = 1
        const val SUMMARY_ID = 2
        const val ACTION_CANCEL = "com.markopacak.divelens.action.CANCEL_EXPORT"

        /** Upper bound of the wake lock, in case the service is killed before it releases it. */
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        /** Tests run the service against their own controller. */
        @Volatile var testController: ExportController? = null

        /** Starts the service for a batch that has just started; the app is in the foreground then. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
        }

        /** The intent of the notification's Cancel action. */
        fun cancelIntent(context: Context) = Intent(context, ExportService::class.java).setAction(ACTION_CANCEL)

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_export), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
