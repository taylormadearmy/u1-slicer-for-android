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
    val requiresMarlinNozzleTemperatureCommands: Boolean = false,
    val forbiddenCommands: Set<String> = emptySet(),
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

// Task 4.2 replaces these extraction-time defaults with their firmware-specific rules.
object KlipperGenericDialect : GcodeDialect by KlipperSnapmakerDialect {
    override fun remapToolLine(line: String, toolMap: Map<Int, Int>): String = line
}

object MarlinDialect : GcodeDialect by KlipperSnapmakerDialect {
    override fun remapToolLine(line: String, toolMap: Map<Int, Int>): String = line
}

fun MachineProfile.gcodeDialect(): GcodeDialect = when {
    family == MachineFamily.SNAPMAKER -> KlipperSnapmakerDialect
    gcodeFlavor == GcodeFlavor.MARLIN -> MarlinDialect
    else -> KlipperGenericDialect
}
