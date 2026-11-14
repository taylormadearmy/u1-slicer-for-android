package com.u1.slicer.gcode

import java.io.File

/** Adds the U1 firmware's automatic purifier policy to newly sliced U1 jobs. */
object U1TopCover {
    fun command(materials: List<String>): String {
        // Unknown materials use cooling, avoiding a keep-warm policy for a soft filament.
        val minimum = materials.minOfOrNull { material ->
            when (material.uppercase().trim()) {
                "PETG", "PETG-CF", "PETG-GF", "PET" -> 70
                "ABS", "ASA", "PCTG", "PC", "PA", "PA-CF", "PA-GF" -> 100
                else -> 45
            }
        } ?: 45
        return when {
            minimum <= 50 -> "SET_PURIFIER_MODE MODE=1 DESIRE_TEMP=42 ALARM_TEMP=48 FAN_SPEED=0.6 DELAY_OFF=0"
            minimum <= 70 -> "SET_PURIFIER_MODE MODE=1 DESIRE_TEMP=45 ALARM_TEMP=51 FAN_SPEED=0.6 DELAY_OFF=0"
            else -> "SET_PURIFIER_MODE MODE=3 DESIRE_TEMP=45 FAN_SPEED=0.6 DELAY_OFF=600"
        }
    }

    /** Existing profile commands win. Usage and materials are in the generated file's tool space. */
    fun apply(file: File, materials: List<String>): Boolean {
        var hasStart = false
        var hasPurifier = false
        var usage = emptyList<Double>()
        file.forEachLine { line ->
            val code = line.substringBefore(';').trim()
            if (code == "PRINT_START" || code.startsWith("PRINT_START ")) hasStart = true
            if (code == "SET_PURIFIER_MODE" || code.startsWith("SET_PURIFIER_MODE ")) hasPurifier = true
            if (line.trimStart().startsWith("; filament used [mm] =")) {
                usage = line.substringAfter('=').split(',').map { it.trim().toDoubleOrNull() ?: 0.0 }
            }
        }
        if (!hasStart || hasPurifier) return false
        val active = if (usage.isEmpty()) materials else usage.mapIndexedNotNull { index, mm ->
            if (mm > 0) materials.getOrElse(index) { "UNKNOWN" } else null
        }
        val temporary = File.createTempFile("u1-purifier-", ".gcode", file.parentFile)
        try {
            temporary.bufferedWriter().use { writer ->
                var inserted = false
                file.forEachLine { line ->
                    writer.appendLine(line)
                    val code = line.substringBefore(';').trim()
                    if (!inserted && (code == "PRINT_START" || code.startsWith("PRINT_START "))) {
                        writer.appendLine(command(active))
                        inserted = true
                    }
                }
            }
            temporary.copyTo(file, overwrite = true)
        } finally {
            temporary.delete()
        }
        return true
    }
}
