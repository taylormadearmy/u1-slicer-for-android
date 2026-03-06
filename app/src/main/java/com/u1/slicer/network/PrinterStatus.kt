package com.u1.slicer.network

/**
 * Per-extruder filament slot as reported by Moonraker (print_task_config or AFC).
 */
data class FilamentSlot(
    val index: Int,            // 0-based extruder index
    val label: String,         // "E1", "E2", …
    val color: String,         // "#RRGGBB"
    val loaded: Boolean,
    val materialType: String,  // "PLA", "PETG", "ABS", …
    val subType: String = "",
    val manufacturer: String = ""
)

data class PrinterStatus(
    val state: String,           // "standby", "printing", "paused", "complete", "error"
    val progress: Float,         // 0.0 - 1.0
    val filename: String = "",
    val printDuration: Float = 0f,  // seconds
    val filamentUsed: Float = 0f,   // mm
    val nozzleTemp: Float = 0f,
    val nozzleTarget: Float = 0f,
    val bedTemp: Float = 0f,
    val bedTarget: Float = 0f,
    val extruders: List<ExtruderStatus> = emptyList()
) {
    val isConnected: Boolean get() = state != "disconnected"
    val isPrinting: Boolean get() = state == "printing"
    val isPaused: Boolean get() = state == "paused"
    val isIdle: Boolean get() = state == "standby" || state == "complete"

    val progressPercent: Int get() = (progress * 100).toInt()

    val printTimeFormatted: String get() {
        val totalMin = (printDuration / 60).toInt()
        val hours = totalMin / 60
        val mins = totalMin % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }
}

data class ExtruderStatus(
    val index: Int,
    val temp: Float,
    val target: Float,
    val active: Boolean = false
)
