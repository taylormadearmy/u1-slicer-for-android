package com.u1.slicer.gcode

import com.u1.slicer.machine.GcodeFlavor
import com.u1.slicer.machine.MachineFamily
import com.u1.slicer.machine.MachineProfile

enum class ThumbnailFormat {
    KLIPPER,
    PRUSA,
}

data class PauseInjection(
    val marker: String,
    val recognizedMarkers: Set<String> = setOf(marker),
    val fallbackCommand: String,
    val useProfileCommand: Boolean,
    val emitToolChange: Boolean,
    val emitToolTemperature: Boolean,
)

data class ValidatorRules(
    val requiredCommandPrefixes: Set<String> = emptySet(),
    val forbiddenCommandPrefixes: Set<String> = emptySet(),
    val rejectBowdenUnloadSequence: Boolean = false,
)

interface GcodeDialect {
    fun remapToolLine(line: String, toolMap: Map<Int, Int>): String

    val pauseInjection: PauseInjection
    val thumbnailFormat: ThumbnailFormat
    val supportsExcludeObject: Boolean
    val validatorRules: ValidatorRules
}

object KlipperSnapmakerDialect : GcodeDialect {
    private val smParamRegex = Regex("""((?:EXTRUDER|INDEX)=)(\d+)""")

    override fun remapToolLine(line: String, toolMap: Map<Int, Int>): String {
        if (!line.startsWith("SM_") || !smParamRegex.containsMatchIn(line)) return line
        return smParamRegex.replace(line) { match ->
            val compact = match.groupValues[2].toIntOrNull() ?: return@replace match.value
            val actual = toolMap[compact] ?: compact
            "${match.groupValues[1]}$actual"
        }
    }

    override val pauseInjection = PauseInjection(
        marker = "; PAUSE_PRINT",
        recognizedMarkers = setOf("; PAUSE_PRINT", ";PAUSE_PRINT"),
        fallbackCommand = "M400 U1",
        useProfileCommand = true,
        emitToolChange = true,
        emitToolTemperature = true,
    )
    override val thumbnailFormat = ThumbnailFormat.KLIPPER
    override val supportsExcludeObject = true
    override val validatorRules = ValidatorRules(rejectBowdenUnloadSequence = true)
}

object KlipperGenericDialect : GcodeDialect {
    override fun remapToolLine(line: String, toolMap: Map<Int, Int>): String = line

    override val pauseInjection = PauseInjection(
        marker = "; PAUSE_PRINT",
        fallbackCommand = "PAUSE",
        useProfileCommand = false,
        emitToolChange = false,
        emitToolTemperature = false,
    )
    override val thumbnailFormat = ThumbnailFormat.KLIPPER
    override val supportsExcludeObject = true
    override val validatorRules = ValidatorRules()
}

object MarlinDialect : GcodeDialect {
    override fun remapToolLine(line: String, toolMap: Map<Int, Int>): String = line

    override val pauseInjection = PauseInjection(
        marker = "; PAUSE_PRINT",
        fallbackCommand = "M600",
        useProfileCommand = false,
        emitToolChange = false,
        emitToolTemperature = false,
    )
    override val thumbnailFormat = ThumbnailFormat.PRUSA
    override val supportsExcludeObject = false
    override val validatorRules = ValidatorRules(
        requiredCommandPrefixes = setOf("M104", "M109"),
        forbiddenCommandPrefixes = setOf("EXCLUDE_OBJECT_", "SET_HEATER_TEMPERATURE"),
    )
}

fun MachineProfile.gcodeDialect(): GcodeDialect = when {
    family == MachineFamily.SNAPMAKER -> KlipperSnapmakerDialect
    gcodeFlavor == GcodeFlavor.MARLIN -> MarlinDialect
    else -> KlipperGenericDialect
}

internal fun formatThumbnailBlock(
    width: Int,
    height: Int,
    encoded: String,
    format: ThumbnailFormat,
): String = buildString {
    val rowLength = when (format) {
        ThumbnailFormat.KLIPPER -> 76
        ThumbnailFormat.PRUSA -> {
            append("; THUMBNAIL_BLOCK_START\n\n;\n")
            78
        }
    }
    append("; thumbnail begin ${width}x${height} ${encoded.length}\n")
    var offset = 0
    while (offset < encoded.length) {
        val end = minOf(offset + rowLength, encoded.length)
        append("; ${encoded.substring(offset, end)}\n")
        offset = end
    }
    append("; thumbnail end\n")
    when (format) {
        ThumbnailFormat.KLIPPER -> append(";\n")
        ThumbnailFormat.PRUSA -> append("; THUMBNAIL_BLOCK_END\n\n")
    }
}
