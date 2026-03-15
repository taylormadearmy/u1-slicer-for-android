package com.u1.slicer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the process alive during slicing.
 *
 * Android may kill background processes during long slices (30-120s for complex
 * Bambu 3MF, multi-colour models). This service displays a persistent notification
 * and prevents the OS from killing the process.
 *
 * Usage from SlicerViewModel:
 *   SlicingService.start(context)   // call before native.slice()
 *   SlicingService.updateProgress(context, progress, stage)  // during slicing
 *   SlicingService.stop(context)    // after slicing completes or fails
 */
class SlicingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
        val stage = intent?.getStringExtra(EXTRA_STAGE) ?: "Slicing..."
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(progress, stage)
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Slicing Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while slicing 3D models"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: Int, stage: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Slicing...")
            .setContentText("$stage ($progress%)")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "SlicingService"
        private const val CHANNEL_ID = "slicing_progress"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_PROGRESS = "progress"
        private const val EXTRA_STAGE = "stage"
        private const val ACTION_STOP = "com.u1.slicer.STOP_SLICING"

        fun start(context: Context) {
            Log.d(TAG, "Starting foreground slicing service")
            val intent = Intent(context, SlicingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(context: Context, progress: Int, stage: String) {
            val intent = Intent(context, SlicingService::class.java).apply {
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_STAGE, stage)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            Log.d(TAG, "Stopping foreground slicing service")
            val intent = Intent(context, SlicingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
