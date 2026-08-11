package com.u1.slicer.network

/** Physical nozzle side on printers whose filament inputs have side-aware routing. */
enum class NozzleSide {
    LEFT,
    RIGHT,
    UNKNOWN,
}

/** How strongly a reported filament slot is tied to [FilamentSlot.nozzleSide]. */
enum class FilamentRouting {
    /** The printer did not report enough topology information to decide. */
    UNKNOWN,

    /** The slot feeds one physical nozzle. */
    FIXED,

    /** An installed filament switch can route the slot to either nozzle. */
    SWITCHABLE,
}

/**
 * Bambu Filament Track Switch (FTS) state.
 *
 * Presence of `device.fila_switch` is the authoritative installed signal. Older
 * firmware omits that object, so every field deliberately has a neutral default.
 */
data class FilamentTrackSwitchStatus(
    val installed: Boolean = false,
    val inputSlots: List<Int> = emptyList(),
    val outputExtruderIds: List<Int> = emptyList(),
    val outputNozzleSides: List<NozzleSide> = emptyList(),
    val statusFlags: Int = 0,
    val infoFlags: Int = 0,
)

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
    val manufacturer: String = "",
    val nozzleSide: NozzleSide = NozzleSide.UNKNOWN,
    val routing: FilamentRouting = FilamentRouting.UNKNOWN,
)

/** Installed Bambu nozzle hardware reported by MQTT. Empty/unknown fields never block a print. */
data class NozzleHardwareStatus(
    val index: Int,
    val diameter: Float? = null,
    val type: String = "",
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
    val extruders: List<ExtruderStatus> = emptyList(),
    val nozzles: List<NozzleHardwareStatus> = emptyList(),
    val filamentTrackSwitch: FilamentTrackSwitchStatus = FilamentTrackSwitchStatus(),
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
