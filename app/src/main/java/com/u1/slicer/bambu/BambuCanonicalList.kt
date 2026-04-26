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
