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
private const val LAYER_TOOL_PATH = "Metadata/custom_gcode_per_layer.xml"
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

            // M3-B full-spectrum marker: a painted 3MF that uses mix slots stamps
            // `full_spectrum_physical_count` so the loader knows paint states beyond the
            // physical count are VIRTUAL mix filaments. Those states must NOT inflate the
            // canonical (= physical num_physical) filament list — doing so would push the
            // engine's num_physical past the mix's virtual id and break the blend.
            val fullSpectrumPhysicalCount = readFullSpectrumPhysicalCount(settingsText)

            // Walk every .model entry, decode every paint_color / mmu_segmentation
            // attribute via PaintColorDecoder, aggregate the set of leaf paint
            // states the slicer will see.
            val paintStates = mutableSetOf<Int>()
            zip.entries().asSequence()
                .filter { e -> !e.isDirectory && MODEL_ENTRY_REGEX.matches(e.name) }
                .forEach { e ->
                    ThreeMfParser.streamCollectPaintSpecs(zip.getInputStream(e)) { spec ->
                        paintStates += PaintColorDecoder.decodeStates(spec)
                    }
                }
            // Drop virtual-mix paint states (state > physicalCount) so mergePaintStates
            // keeps the canonical list at exactly the physical filament count.
            val physicalPaintStates = if (fullSpectrumPhysicalCount != null) {
                paintStates.filterTo(mutableSetOf()) { it <= fullSpectrumPhysicalCount }
            } else {
                paintStates
            }
            val withPaint = mergePaintStates(base, physicalPaintStates)

            // Read custom_gcode_per_layer.xml if present (Hueforge / layer-tool
            // files). Each `type=1` or `type=2` <layer> entry references a
            // 1-based extruder index plus an optional colour. Synthesise
            // LAYER_TOOL entries for any referenced extruder not already
            // covered by FILE_COLOUR / PAINT_DERIVED.
            val layerToolEntry = zip.getEntry(LAYER_TOOL_PATH)
            if (layerToolEntry != null) {
                val xml = zip.getInputStream(layerToolEntry)
                    .bufferedReader().use { it.readText() }
                val info = parseLayerToolCustomGcodeXml(xml)
                if (info.hasToolChanges && info.extruders.isNotEmpty()) {
                    val pairs = parseLayerToolColourPairs(xml)
                    return@use mergeLayerToolData(withPaint, info, pairs)
                }
            }
            withPaint
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

/**
 * Reads the M3-B `full_spectrum_physical_count` marker from a project_settings.config
 * JSON string. Returns the physical filament count when the marker is present and a
 * positive integer, else `null`. The value is stamped as a string by
 * [com.u1.slicer.aipaint.PaintedMeshWriter] (Bambu config arrays are stringly-typed),
 * so we tolerate both string and numeric JSON forms. Visible for unit tests.
 */
internal fun readFullSpectrumPhysicalCount(jsonText: String): Int? {
    val json = try {
        JSONObject(jsonText.trim())
    } catch (_: JSONException) {
        return null
    }
    if (!json.has("full_spectrum_physical_count")) return null
    val n = json.optString("full_spectrum_physical_count", "").trim().toIntOrNull()
        ?: json.optInt("full_spectrum_physical_count", 0)
    return n.takeIf { it > 0 }
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
internal fun extractPaintSpecs(line: String): List<String> {
    var list: MutableList<String>? = null
    var startIdx = 0
    while (true) {
        var idx = line.indexOf("paint_color=\"", startIdx)
        if (idx == -1) idx = line.indexOf("mmu_segmentation=\"", startIdx)
        if (idx == -1) break

        val quoteStart = line.indexOf('"', idx) + 1
        val quoteEnd = line.indexOf('"', quoteStart)
        if (quoteEnd != -1) {
            val spec = line.substring(quoteStart, quoteEnd)
            if (spec.isNotBlank()) {
                if (list == null) list = mutableListOf()
                list.add(spec)
            }
            startIdx = quoteEnd + 1
        } else {
            break
        }
    }
    return list ?: emptyList()
}

/**
 * Visible for unit tests — extracts extruder-index → colour pairs from the
 * layer-tool XML, deduplicating by first-seen extruder. The existing
 * [parseLayerToolCustomGcodeXml] returns extruders and colours as separate
 * sets; this helper preserves the (extruder, colour) pairing the canonical
 * list needs to fill LAYER_TOOL entries with the right colour.
 */
internal fun parseLayerToolColourPairs(xml: String): Map<Int, String> {
    val layerRegex = Regex("""<layer\b([^>]*)>""")
    val typeRegex = Regex("""\btype="([^"]+)"""")
    val extruderRegex = Regex("""\bextruder="([^"]+)"""")
    val colorRegex = Regex("""\bcolor="([^"]+)"""")
    val pairs = linkedMapOf<Int, String>()
    layerRegex.findAll(xml).forEach { match ->
        val attrs = match.groupValues[1]
        val type = typeRegex.find(attrs)?.groupValues?.getOrNull(1)
        if (type != "1" && type != "2") return@forEach
        val extruder = extruderRegex.find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return@forEach
        val colour = colorRegex.find(attrs)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }?.take(7)
            ?: return@forEach
        if (extruder !in pairs) pairs[extruder] = colour
    }
    return pairs
}

/**
 * Visible for unit tests — folds layer-tool data into an existing canonical
 * list. For every 1-based extruder index referenced by layer-tool entries,
 * ensures a [FilamentEntry] exists at `fileIndex = extruder - 1`. Synthesised
 * entries take their colour from the layer-tool XML when available.
 *
 * Layer-tool entries created here use [FilamentSource.LAYER_TOOL]. Existing
 * entries (FILE_COLOUR / PAINT_DERIVED) are preserved untouched — the layer-
 * tool data is only consulted to extend the list when it references higher
 * indices.
 */
internal fun mergeLayerToolData(
    base: CanonicalFilamentList,
    info: LayerToolCustomGcodeXmlInfo,
    colourPairs: Map<Int, String> = emptyMap(),
): CanonicalFilamentList {
    if (info.extruders.isEmpty()) return base
    val maxExtruder = info.extruders.maxOrNull() ?: return base
    if (maxExtruder <= base.size) return base  // every extruder already covered

    val expanded = base.filaments.toMutableList()
    while (expanded.size < maxExtruder) {
        val nextIndex = expanded.size            // 0-based fileIndex about to be added
        val extruder = nextIndex + 1             // 1-based extruder index
        val colour = colourPairs[extruder] ?: "#808080"
        expanded.add(
            FilamentEntry(
                fileIndex = nextIndex,
                color = colour,
                materialType = null,
                source = FilamentSource.LAYER_TOOL,
            )
        )
    }
    return base.copy(filaments = expanded)
}

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
