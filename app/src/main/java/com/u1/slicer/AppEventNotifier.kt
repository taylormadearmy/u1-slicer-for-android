package com.u1.slicer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object AppEventNotifier {

    private const val CHANNEL_SLICE = "slice_events"
    private const val CHANNEL_PRINTER = "printer_events"
    private const val CHANNEL_LOAD = "load_events"
    private const val ID_SLICE = 10
    private const val ID_PRINTER = 11
    private const val ID_LOAD = 12
    const val EXTRA_NAVIGATE_TO = "navigate_to"

    sealed class Event {
        data class ModelLoaded(val filename: String) : Event()
        // F81 (GitHub #120): long-running load stages.
        data class BambuPipelineReady(val filename: String) : Event()
        data class PreparePreviewReady(val filename: String) : Event()
        data class SliceComplete(val filename: String) : Event()
        data class SliceFailed(val error: String) : Event()
        data class UploadComplete(val filename: String) : Event()
        data class PrintStarted(val filename: String) : Event()
        data class PrintPaused(val filename: String, val progress: Int) : Event()
        data class PrintComplete(val filename: String) : Event()
        data class PrintFailed(val filename: String) : Event()
        object PrinterOffline : Event()
    }

    fun notify(context: Context, event: Event, nickname: String = "", printerCount: Int = 1) {
        if (AppForegroundTracker.isInForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return

        val channelId = channelFor(event)
        createChannels(context)
        val notifId = when (event) {
            is Event.ModelLoaded, is Event.BambuPipelineReady, is Event.PreparePreviewReady -> ID_LOAD
            is Event.SliceComplete, is Event.SliceFailed -> ID_SLICE
            else -> ID_PRINTER
        }
        val navigateTo = navigateTargetFor(event)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (navigateTo != null) putExtra(EXTRA_NAVIGATE_TO, navigateTo)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationManagerCompat.from(context).notify(
            notifId,
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(buildTitle(event, nickname, printerCount))
                .setContentText(bodyFor(event))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
        )
    }

    fun buildTitle(event: Event, nickname: String = "", printerCount: Int = 1): String {
        val base = titleFor(event)
        return if (nickname.isNotBlank() && printerCount > 1) "$nickname — $base" else base
    }

    internal fun titleFor(event: Event): String = when (event) {
        is Event.ModelLoaded -> "Model ready"
        is Event.BambuPipelineReady -> "3MF processed"
        is Event.PreparePreviewReady -> "Preview ready"
        is Event.SliceComplete -> "Slice complete"
        is Event.SliceFailed -> "Slice failed"
        is Event.UploadComplete -> "Upload complete"
        is Event.PrintStarted -> "Print started"
        is Event.PrintPaused -> "Print paused"
        is Event.PrintComplete -> "Print complete"
        is Event.PrintFailed -> "Print stopped"
        is Event.PrinterOffline -> "Monitoring paused"
    }

    internal fun bodyFor(event: Event): String = when (event) {
        is Event.ModelLoaded -> "${event.filename} loaded and ready to slice"
        is Event.BambuPipelineReady -> "${event.filename} sanitised and embedded"
        is Event.PreparePreviewReady -> "${event.filename} preview rendered"
        is Event.SliceComplete -> "${event.filename} is ready to send to printer"
        is Event.SliceFailed -> event.error.take(100)
        is Event.UploadComplete -> "${event.filename} sent to printer"
        is Event.PrintStarted -> event.filename
        is Event.PrintPaused -> "${event.filename} paused at ${event.progress}%"
        is Event.PrintComplete -> "${event.filename} finished"
        is Event.PrintFailed -> "${event.filename} was cancelled or failed"
        is Event.PrinterOffline -> "Tap to reconnect and see printer status"
    }

    internal fun navigateTargetFor(event: Event): String? = when (event) {
        is Event.SliceComplete -> "preview"
        is Event.PrintComplete -> "preview"
        is Event.PrintStarted -> "printer"
        is Event.PrintPaused -> "printer"
        is Event.PrintFailed -> "printer"
        is Event.PrinterOffline -> "printer"
        else -> null
    }

    private fun channelFor(event: Event): String = when (event) {
        is Event.ModelLoaded, is Event.BambuPipelineReady, is Event.PreparePreviewReady -> CHANNEL_LOAD
        is Event.SliceComplete, is Event.SliceFailed -> CHANNEL_SLICE
        else -> CHANNEL_PRINTER
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CHANNEL_SLICE, "Slice events", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Notifications for slicing completion and failure" },
            NotificationChannel(CHANNEL_PRINTER, "Printer events", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Notifications for printer upload, print progress, and completion" },
            NotificationChannel(CHANNEL_LOAD, "Load events", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Notifications for long-running model load stages" }
        ).forEach { channel ->
            if (manager.getNotificationChannel(channel.id) == null) manager.createNotificationChannel(channel)
        }
    }
}
