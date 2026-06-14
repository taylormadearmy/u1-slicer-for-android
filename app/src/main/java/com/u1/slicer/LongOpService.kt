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
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat

/**
 * Generalised foreground service that keeps the process alive during any long-running operation:
 * loading a model, the Bambu sanitise+embed pipeline, plate selection, add-to-bed, slicing.
 *
 * Replaces the original `SlicingService` (which only covered slicing). The stack-based stage
 * label means nested operations show the most-specific in-flight stage in the notification,
 * and a paired `start`/`stop` (driven by `SlicerViewModel.beginLongOp` / `endLongOp`)
 * guarantees the service does not leak on cancellation or exception.
 *
 * Usage (always paired via try/finally):
 *   LongOpService.start(context, "Loading model")
 *   try { ... } finally { LongOpService.stop(context) }
 *
 * Slicing-style progress updates rename the top of the stack rather than pushing a new frame:
 *   LongOpService.update(context, percent, stageLabel)
 */
class LongOpService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val stage = intent?.getStringExtra(EXTRA_STAGE)
        // B130 (#158): every onStartCommand reached via startForegroundService()
        // MUST call startForeground() within Android's 5s watchdog — even when we
        // immediately want to stop. The ACTION_STOP and empty-stage (post-kill
        // restart) paths previously returned without promoting to foreground, so
        // a stop / empty command arriving as the start crashed the app with
        // ForegroundServiceDidNotStartInTimeException under rapid start/stop churn
        // (more likely on a hot / busy phone). Promote first on every path, then stop.
        if (action == ACTION_STOP || stage.isNullOrBlank()) {
            startForeground(NOTIFICATION_ID, buildNotification(stage ?: "Working", 0))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
        startForeground(NOTIFICATION_ID, buildNotification(stage, progress))
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background work",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while preparing, loading or slicing models"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(stage: String, progress: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (progress > 0) "$stage... ($progress%)" else "$stage..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("U1 Slicer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setProgress(100, progress.coerceIn(0, 100), progress == 0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "LongOpService"
        private const val CHANNEL_ID = "long_op_progress"
        private const val NOTIFICATION_ID = 1

        @VisibleForTesting internal const val EXTRA_STAGE = "stage"
        @VisibleForTesting internal const val EXTRA_PROGRESS = "progress"
        @VisibleForTesting internal const val ACTION_STOP = "com.u1.slicer.STOP_LONG_OP"
        @VisibleForTesting internal const val ACTION_UPDATE = "com.u1.slicer.UPDATE_LONG_OP"

        private val stackLock = Any()
        private val stageStack = ArrayDeque<String>()

        /** Stack-mutation result: what the Service should now render. */
        internal data class DispatchPayload(val action: String, val stage: String?, val progress: Int)

        @VisibleForTesting
        internal enum class ServiceStartMode {
            START_SERVICE,
            START_FOREGROUND_SERVICE
        }

        /**
         * Optional Android-side dispatch sink for instrumented tests. When non-null, the
         * companion records the [DispatchPayload] here instead of starting the actual Service.
         * Unit tests use the pure `*Pure` functions directly and ignore this.
         */
        @VisibleForTesting
        @JvmStatic
        internal var dispatchSink: ((DispatchPayload) -> Unit)? = null

        /**
         * Pure stack-mutation: push [stage], return the payload the Service should render.
         * No Android dependency — safe to call from JVM unit tests.
         */
        @VisibleForTesting
        internal fun startPure(stage: String): DispatchPayload {
            synchronized(stackLock) { stageStack.addLast(stage) }
            return DispatchPayload(ACTION_UPDATE, currentStageOrNull(), 0)
        }

        /**
         * Pure stack-mutation: replace the top frame (if any) with [stage] when non-null,
         * carry [progress] to the Service.
         */
        @VisibleForTesting
        internal fun updatePure(progress: Int, stage: String?): DispatchPayload {
            if (stage != null) {
                synchronized(stackLock) {
                    if (stageStack.isNotEmpty()) {
                        stageStack.removeLast()
                    }
                    stageStack.addLast(stage)
                }
            }
            return DispatchPayload(ACTION_UPDATE, currentStageOrNull(), progress)
        }

        /**
         * Pure stack-mutation: pop the top frame. If the stack becomes empty, the payload
         * carries [ACTION_STOP] so the Service tears down.
         */
        @VisibleForTesting
        internal fun stopPure(): DispatchPayload {
            val (becameEmpty, newTop) = synchronized(stackLock) {
                if (stageStack.isNotEmpty()) stageStack.removeLast()
                stageStack.isEmpty() to stageStack.lastOrNull()
            }
            return if (becameEmpty) {
                DispatchPayload(ACTION_STOP, null, 0)
            } else {
                DispatchPayload(ACTION_UPDATE, newTop, 0)
            }
        }

        @VisibleForTesting
        internal fun currentStageOrNull(): String? =
            synchronized(stackLock) { stageStack.lastOrNull() }

        @VisibleForTesting
        internal fun resetForTest() {
            synchronized(stackLock) { stageStack.clear() }
            dispatchSink = null
        }

        /** Push a new stage frame and update the notification. */
        fun start(context: Context, stage: String) {
            val payload = startPure(stage)
            dispatch(context, payload)
        }

        /**
         * Update progress and optionally rename the top frame. Rename semantics match the
         * native slicer progress listener: each tick carries the latest stage string and
         * replaces (not pushes) the current top.
         */
        fun update(context: Context, progress: Int, stage: String? = null) {
            val payload = updatePure(progress, stage)
            dispatch(context, payload)
        }

        /** Pop the top frame. When the stack becomes empty, stop the foreground service. */
        fun stop(context: Context) {
            val payload = stopPure()
            dispatch(context, payload)
        }

        @VisibleForTesting
        internal fun startModeFor(
            payload: DispatchPayload,
            sdkInt: Int = Build.VERSION.SDK_INT,
            appInForeground: Boolean = AppForegroundTracker.isInForeground
        ): ServiceStartMode =
            when {
                payload.action == ACTION_STOP -> ServiceStartMode.START_SERVICE
                sdkInt < Build.VERSION_CODES.O -> ServiceStartMode.START_SERVICE
                // Try a normal service start first. When it is legal, Android does not arm
                // startForegroundService()'s 5s watchdog while the UI thread is busy entering
                // native work. If the app is truly backgrounded Android throws immediately;
                // dispatch() catches that and falls back to startForegroundService().
                else -> ServiceStartMode.START_SERVICE
            }

        private fun dispatch(context: Context, payload: DispatchPayload) {
            dispatchSink?.let {
                it(payload)
                return
            }
            val intent = Intent(context, LongOpService::class.java).apply {
                this.action = payload.action
                if (payload.stage != null) putExtra(EXTRA_STAGE, payload.stage)
                putExtra(EXTRA_PROGRESS, payload.progress)
            }
            try {
                when (startModeFor(payload)) {
                    ServiceStartMode.START_SERVICE -> context.startService(intent)
                    ServiceStartMode.START_FOREGROUND_SERVICE -> context.startForegroundService(intent)
                }
            } catch (state: IllegalStateException) {
                if (payload.action != ACTION_STOP && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        context.startForegroundService(intent)
                    } catch (t: Throwable) {
                        Log.w(TAG, "LongOpService foreground fallback failed for action=${payload.action} stage=${payload.stage}: ${t.message}")
                    }
                } else {
                    Log.w(TAG, "LongOpService dispatch failed for action=${payload.action} stage=${payload.stage}: ${state.message}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "LongOpService dispatch failed for action=${payload.action} stage=${payload.stage}: ${t.message}")
            }
        }
    }
}
