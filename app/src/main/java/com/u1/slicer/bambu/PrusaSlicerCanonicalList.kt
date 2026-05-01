package com.u1.slicer.bambu

import android.util.Log
import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentSource
import java.io.File
import java.util.zip.ZipFile

/**
 * Phase 2.1 — load-time normalize for PrusaSlicer 3MF files.
 *
 * PrusaSlicer 3MFs store config in `Metadata/Slic3r_PE.config` as INI lines
 * commented with `; ` (so opening the 3MF in another slicer doesn't apply
 * unintended settings). The colour palette is on:
 *
 *   ; extruder_colour = #000000;#DB5182;#008000;#00FF00;#FBEB7D
 *   ; filament_colour = #FF8000;#FF8000;#FF8000;#FF8000;#FF8000
 *
 * For PrusaSlicer MMU files, `extruder_colour` represents the loaded
 * filament-per-slot palette and is the canonical "what filaments this print
 * uses" answer; `filament_colour` is sometimes all-same (a single "current
 * filament" colour from the slicer's filament-profile pane). We prefer
 * `extruder_colour` and fall back to `filament_colour` only when
 * `extruder_colour` is missing.
 *
 * Paint segmentation (multi-material painting) is folded in identically to
 * Bambu via the shared `mergePaintStates` path — `.model` XML walks match
 * both formats.
 */
private const val TAG = "PrusaSlicerCanonical"
private const val PRUSA_CONFIG_PATH = "Metadata/Slic3r_PE.config"
private val PRUSA_MODEL_REGEX = Regex(""".*\.model$""")

fun prusaSlicerCanonicalList(file: File): CanonicalFilamentList? {
    if (!file.exists() || !file.isFile) return null
    return try {
        ZipFile(file).use { zip ->
            val configEntry = zip.getEntry(PRUSA_CONFIG_PATH) ?: return null
            val content = zip.getInputStream(configEntry)
                .bufferedReader().use { it.readText() }
            val base = buildFromPrusaConfig(content) ?: return null

            // Paint segmentation: walk every .model, decode every
            // paint_color / mmu_segmentation attribute. Same logic as Bambu.
            val paintStates = mutableSetOf<Int>()
            zip.entries().asSequence()
                .filter { e -> !e.isDirectory && PRUSA_MODEL_REGEX.matches(e.name) }
                .forEach { e ->
                    zip.getInputStream(e).bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            extractPaintSpecs(line).forEach { spec ->
                                paintStates += PaintColorDecoder.decodeStates(spec)
                            }
                        }
                    }
                }
            mergePaintStates(base, paintStates)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to build canonical list for ${file.name}: ${e.message}")
        null
    }
}

/**
 * Visible for unit tests — builds the canonical list from the INI text of
 * `Slic3r_PE.config`. Returns `null` if neither `extruder_colour` nor
 * `filament_colour` is present.
 */
internal fun buildFromPrusaConfig(iniText: String): CanonicalFilamentList? {
    val keyValue = parsePrusaIni(iniText)
    val rawColours = keyValue["extruder_colour"] ?: keyValue["filament_colour"] ?: return null
    val parts = rawColours.split(";").mapNotNull { p ->
        Regex("#[0-9A-Fa-f]{6,8}").find(p.trim())?.value?.take(7)
    }
    if (parts.isEmpty()) return null

    val typesRaw = keyValue["filament_type"]
    // PrusaSlicer uses semicolons for filament_type (Korok:
    // `filament_type = PLA;PLA;PLA;PLA;PLA`). Some forks emit commas,
    // so split on either delimiter to be defensive.
    val types = typesRaw?.split(';', ',')?.map { it.trim() }

    val entries = parts.mapIndexed { i, colour ->
        FilamentEntry(
            fileIndex = i,
            color = colour,
            materialType = types?.getOrNull(i)?.takeIf { it.isNotBlank() },
            source = FilamentSource.FILE_COLOUR,
        )
    }
    return CanonicalFilamentList(filaments = entries)
}

/**
 * Visible for unit tests — parses PrusaSlicer's commented-INI format into a
 * key/value map. Lines look like `; key = value` (Prusa) or `key = value`
 * (some forks). Leading `;` and surrounding whitespace are trimmed.
 */
internal fun parsePrusaIni(iniText: String): Map<String, String> {
    val out = linkedMapOf<String, String>()
    iniText.lines().forEach { raw ->
        val stripped = raw.trimStart(';', ' ', '\t').trimEnd()
        if (stripped.isEmpty()) return@forEach
        val eq = stripped.indexOf('=')
        if (eq <= 0) return@forEach
        val key = stripped.substring(0, eq).trim()
        val value = stripped.substring(eq + 1).trim()
        if (key.isNotEmpty()) out[key] = value
    }
    return out
}
