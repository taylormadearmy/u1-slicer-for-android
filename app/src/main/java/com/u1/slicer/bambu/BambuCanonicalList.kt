package com.u1.slicer.bambu

import android.util.Log
import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Phase 2.1 — load-time normalize for Bambu / OrcaSlicer 3MF files.
 *
 * Reads `Metadata/project_settings.config` (JSON) and produces a
 * [CanonicalFilamentList] with one [FilamentEntry] per declared filament,
 * tagged [FilamentSource.FILE_COLOUR].
 *
 * SEMM (paint segmentation) entries with [FilamentSource.PAINT_DERIVED] are
 * added in a later slice that integrates `PaintColorDecoder`. Object-default
 * extruders (compound objects with `<part>` extruder metadata) are added
 * via [FilamentSource.OBJECT_DEFAULT] in a subsequent slice.
 *
 * Returns `null` if the file is not a valid 3MF or has no
 * `project_settings.config` (callers fall back to other format normalizers
 * — STL synthetic, PrusaSlicer Slic3r_PE.config, etc.).
 */
private const val TAG = "BambuCanonicalList"
private const val PROJECT_SETTINGS_PATH = "Metadata/project_settings.config"
private val MODEL_ENTRY_REGEX = Regex(""".*\.model$""")

/**
 * Full canonical list — combines FILE_COLOUR entries from
 * `Metadata/project_settings.config` with PAINT_DERIVED entries and
 * `paintStateMap` from any `paint_color` / `mmu_segmentation` triangle
 * attributes in the file's `.model` XML entries.
 *
 * Returns `null` for invalid / non-Bambu 3MF files. Callers should fall
 * through to other format normalizers (PrusaSlicer, STL synthetic) when
 * this returns `null`.
 */
fun bambuCanonicalList(file: File): CanonicalFilamentList? {
    if (!file.exists() || !file.isFile) return null
    return try {
        ZipFile(file).use { zip ->
            val settingsEntry = zip.getEntry(PROJECT_SETTINGS_PATH) ?: return null
            val settingsText = zip.getInputStream(settingsEntry)
                .bufferedReader().use { it.readText() }
            val base = buildFromProjectSettings(settingsText) ?: return null

            // Walk every .model entry, decode every paint_color / mmu_segmentation
            // attribute via PaintColorDecoder, aggregate the set of leaf paint
            // states the slicer will see.
            val paintStates = mutableSetOf<Int>()
            zip.entries().asSequence()
                .filter { e -> !e.isDirectory && MODEL_ENTRY_REGEX.matches(e.name) }
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
 * @deprecated since slice 4 — use [bambuCanonicalList] which also folds in
 *   paint segmentation. Kept temporarily for the slice-2 instrumented test
 *   which asserts the FILE_COLOUR-only contract on the Die fixture.
 */
fun bambuFileColourList(file: File): CanonicalFilamentList? {
    if (!file.exists() || !file.isFile) return null
    return try {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(PROJECT_SETTINGS_PATH) ?: return null
            val content = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            buildFromProjectSettings(content)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read $PROJECT_SETTINGS_PATH from ${file.name}: ${e.message}")
        null
    }
}

/** Visible for unit tests — builds the canonical list from a JSON string. */
internal fun buildFromProjectSettings(jsonText: String): CanonicalFilamentList? {
    val json = try {
        JSONObject(jsonText.trim())
    } catch (_: JSONException) {
        return null
    }
    val colours = json.optJSONArray("filament_colour") ?: return null
    if (colours.length() == 0) return null

    val types = json.optJSONArray("filament_type")
    val colourRegex = Regex("#[0-9A-Fa-f]{6,8}")

    val entries = (0 until colours.length()).map { i ->
        val rawColour = colours.optString(i, "")
        val cleaned = colourRegex.find(rawColour)?.value?.take(7) ?: rawColour.take(7)
        FilamentEntry(
            fileIndex = i,
            color = cleaned,
            materialType = types?.optStringOrNull(i),
            source = FilamentSource.FILE_COLOUR,
        )
    }
    return CanonicalFilamentList(filaments = entries)
}

private fun JSONArray.optStringOrNull(i: Int): String? {
    if (i < 0 || i >= length()) return null
    val v = optString(i, "")
    return v.takeIf { it.isNotBlank() }
}

/**
 * Visible for unit tests — extracts every `paint_color` / `mmu_segmentation` /
 * `slic3rpe:mmu_segmentation` attribute value from a single line of XML.
 *
 * Multiple matches per line are supported (compact 3MFs sometimes pack many
 * triangles onto one line). Empty values are filtered out.
 */
internal fun extractPaintSpecs(line: String): List<String> =
    PAINT_SPEC_REGEX.findAll(line)
        .mapNotNull { it.groupValues[1].takeIf { v -> v.isNotBlank() } }
        .toList()

private val PAINT_SPEC_REGEX =
    Regex("""(?:paint_color|mmu_segmentation|slic3rpe:mmu_segmentation)="([^"]+)"""")

/**
 * Visible for unit tests — given a base canonical list (typically from
 * [buildFromProjectSettings]) and a set of decoded paint states, returns a
 * new list that:
 *   - Contains every base entry untouched.
 *   - Adds [FilamentSource.PAINT_DERIVED] entries for any paint state index
 *     that addresses beyond the base list's bounds (B95 high-index shape:
 *     Buzz plate 9 paint state 11 against a 4-entry `filament_colour`).
 *   - Builds [CanonicalFilamentList.paintStateMap] mapping each decoded
 *     state → the `fileIndex` it addresses.
 *
 * Paint states are 1-based as emitted by [PaintColorDecoder]; state N
 * addresses `fileIndex = N - 1`.
 */
internal fun mergePaintStates(
    base: CanonicalFilamentList,
    paintStates: Set<Int>,
): CanonicalFilamentList {
    if (paintStates.isEmpty()) return base
    val maxAddressed = paintStates.maxOrNull() ?: return base
    val targetSize = maxOf(base.size, maxAddressed)

    val expanded = base.filaments.toMutableList()
    while (expanded.size < targetSize) {
        // Synthetic placeholder — colour and material come from the file's
        // declared list when present; out-of-bounds addressed states get a
        // neutral grey + null material so the UI can still render them.
        expanded.add(
            FilamentEntry(
                fileIndex = expanded.size,
                color = "#808080",
                materialType = null,
                source = FilamentSource.PAINT_DERIVED,
            )
        )
    }
    val paintStateMap = paintStates.associateWith { state -> state - 1 }
    return CanonicalFilamentList(filaments = expanded, paintStateMap = paintStateMap)
}
