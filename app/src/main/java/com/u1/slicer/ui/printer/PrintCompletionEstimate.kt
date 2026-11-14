package com.u1.slicer.ui.printer

import com.u1.slicer.network.PrinterStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong

internal fun estimatePrintCompletion(status: PrinterStatus, now: Instant): Instant? {
    val progress = status.progress.toDouble()
    val elapsed = status.printDuration.toDouble()
    // File progress is only an approximation of print time. Wait for a useful
    // sample so startup/heating does not immediately produce a wild estimate.
    if (!status.isPrinting || !progress.isFinite() || status.progress < 0.01f || progress >= 1.0 ||
        !elapsed.isFinite() || elapsed < 60.0) return null

    val remainingSeconds = (elapsed * (1.0 - progress) / progress).roundToLong()
    return runCatching { now.plusSeconds(remainingSeconds) }.getOrNull()
}

internal fun formatPrintCompletion(
    completion: Instant,
    now: Instant,
    zone: ZoneId,
    use24Hour: Boolean,
    locale: Locale,
): String {
    val localCompletion = completion.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val time = localCompletion.format(DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale))
    return when (localCompletion.toLocalDate()) {
        today -> time
        today.plusDays(1) -> "Tomorrow, $time"
        else -> "${localCompletion.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))}, $time"
    }
}
