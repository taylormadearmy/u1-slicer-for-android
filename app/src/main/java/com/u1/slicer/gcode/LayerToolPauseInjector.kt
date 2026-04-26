package com.u1.slicer.gcode

import androidx.annotation.VisibleForTesting
import com.u1.slicer.BuildConfig
import com.u1.slicer.bambu.parseLayerToolSegments
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

object LayerToolPauseInjector {
    private const val EPSILON = 0.0001f
    private val pauseRegex = Regex("""^\s*; ?PAUSE_PRINT\s*$""")
    private val zCommentRegex = Regex("""^\s*;Z:([0-9.]+)\s*$""")
    private val gcodeParamRegex = Regex("""\b([A-Za-z])([+-]?\d*\.?\d+)""")
    private val toolOnlyRegex = Regex("""^T([0-9]+(?:\.[0-9]+)?)\s*$""")
    private val smTargetTempRegex = Regex("""\bTARGET_TEMP=(\d+(?:\.\d+)?)\b""")
    private val smIndexRegex = Regex("""\bINDEX=(\d+(?:\.\d+)?)\b""")
    private const val NATIVE_TOOLCHANGE_MARKER = "; CP TOOLCHANGE START"

    /**
     * One layer-change row in Bambu `custom_gcode_per_layer.xml`.
     * [topZ] is the first layer whose Z is strictly above this (see inject loop).
     * [extruderBambu] is 1-based as in the file (1→T0, 2→T1, …). If missing, treated as 1.
     */
    internal data class PauseTarget(val topZ: Float, val extruderBambu: Int)

    fun injectFrom3mf(
        gcodePath: String,
        model3mf: File,
        plateIdx: Int,
        getPlateData: ((Int) -> String?)?
    ): Boolean {
        if (!model3mf.exists() || !model3mf.name.endsWith(".3mf", ignoreCase = true)) return false

        val xmlTargets = mutableListOf<PauseTarget>()
        var nozzleTemps: Map<Int, Int>? = null
        val pauseCommand = ZipFile(model3mf).use { zip ->
            zip.getEntry("Metadata/custom_gcode_per_layer.xml")?.let { entry ->
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                xmlTargets += extractPauseTargets(xml)
            }
            zip.getEntry("Metadata/project_settings.config")?.let { entry ->
                val json = zip.getInputStream(entry).bufferedReader().readText()
                nozzleTemps = parseNozzleTemperatures(json)
                Regex(""""machine_pause_gcode"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.getOrNull(1)
            }
        } ?: "M400 U1"

        // Native path: read customGcode from g_model via JNI. Production passes a method
        // reference to NativeLibrary.nativeGetPlateData. Returns null when no model is loaded,
        // plateIdx is out of range, or the plate slot is null — including post-slice state
        // where g_model may have been mutated and no longer contains the source customGcode.
        // XML path is the permanent fallback for those cases.
        val nativeJson = if (plateIdx >= 0 && getPlateData != null) {
            try { getPlateData(plateIdx) } catch (_: Throwable) { null }
        } else null
        val nativeTargetsOrNull = nativeJson?.let { extractPauseTargetsFromNativeJson(it) }

        // In debug builds, when BOTH paths produced data, assert they agree. Silent-G-code-
        // corruption tripwire for future changes to either data source. If it fires, stop.
        if (BuildConfig.DEBUG && nativeTargetsOrNull != null && nativeTargetsOrNull.isNotEmpty()) {
            val xmlSorted = xmlTargets
                .distinctBy { it.topZ to it.extruderBambu }
                .sortedWith(compareBy({ it.topZ }, { it.extruderBambu }))
            val nativeSorted = nativeTargetsOrNull
                .distinctBy { it.topZ to it.extruderBambu }
                .sortedWith(compareBy({ it.topZ }, { it.extruderBambu }))
            if (xmlSorted.isNotEmpty()) {
                check(xmlSorted == nativeSorted) {
                    "LayerToolPauseInjector dual-path divergence: " +
                        "xml=$xmlSorted native=$nativeSorted plateIdx=$plateIdx model=${model3mf.name}"
                }
            }
        }

        // Prefer native-derived targets when present and non-empty (reflects the live g_model
        // customGcode at injection time — most accurate for per-plate selection). Fall through
        // to XML when native is null (model cleared) or empty (post-slice state that drops
        // plates_custom_gcodes, or embedded file with metadata stripped for native-slice
        // fallback — see flippyFlappyMini_embedDropsLayerToolMetadataForNativeSliceFallback).
        val pauseTargets: MutableList<PauseTarget> =
            nativeTargetsOrNull?.takeIf { it.isNotEmpty() }
                ?.toMutableList()
                ?: xmlTargets.toMutableList()

        if (pauseTargets.isEmpty()) return false

        val gcodeFile = File(gcodePath)
        if (!gcodeFile.exists()) return false
        if (containsNativeToolchangeWorkflow(gcodeFile)) return false
        val gcodeToolTemps = parseToolNozzleTemperaturesFromGcode(gcodeFile)
        val gcodeSmTargetTemps = parseSmTargetTempsFromGcode(gcodeFile)
        val gcodeLastNozzleTemp = parseLastNozzleTempFromGcode(gcodeFile)

        val pendingTargets = pauseTargets
            .distinctBy { it.topZ to it.extruderBambu }
            .sortedWith(compareBy({ it.topZ }, { it.extruderBambu }))
            .toMutableList()
        if (pendingTargets.isEmpty()) return false

        val parentDir = gcodeFile.parentFile ?: gcodeFile.absoluteFile.parentFile
        val tempFile = File(
            parentDir,
            ".pause_inject_${gcodeFile.name}.${System.nanoTime()}"
        )

        var injected = false
        var lastNonBlank: String? = null

        fun hasPauseImmediatelyBefore(): Boolean {
            val v = lastNonBlank ?: return false
            return pauseRegex.matches(v)
        }

        try {
            gcodeFile.bufferedReader().use { reader ->
                tempFile.bufferedWriter().use { writer ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.trim() == ";LAYER_CHANGE") {
                            val next = reader.readLine()
                            if (next != null) {
                                val z = zCommentRegex.find(next)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                                if (z != null) {
                                    while (pendingTargets.isNotEmpty() && z > pendingTargets.first().topZ + EPSILON) {
                                        val target = pendingTargets.removeAt(0)
                                        if (!hasPauseImmediatelyBefore()) {
                                            writer.write("; PAUSE_PRINT\n")
                                            writer.write(pauseCommand)
                                            writer.write("\n")
                                            val toolIndex = target.extruderBambu - 1
                                            if (toolIndex in 1..3) {
                                                writer.write("; layer_tool extruder ${target.extruderBambu} → T$toolIndex\n")
                                                writer.write("T$toolIndex\n")
                                                // Prefer 3MF project settings; fall back to explicit M104/M109 Tn in source G-code.
                                                val setTemp = nozzleTemps?.get(toolIndex)
                                                    ?: gcodeToolTemps[toolIndex]
                                                    ?: gcodeSmTargetTemps[toolIndex]
                                                    ?: gcodeLastNozzleTemp
                                                if (setTemp != null && setTemp in 1..400) {
                                                    writer.write("M109 S$setTemp T$toolIndex\n")
                                                }
                                            }
                                            writer.write("\n")
                                            injected = true
                                            lastNonBlank = "; PAUSE_PRINT"
                                        }
                                    }
                                }
                                writer.write(line)
                                writer.write("\n")
                                if (line.isNotBlank()) lastNonBlank = line.trim()
                                writer.write(next)
                                writer.write("\n")
                                if (next.isNotBlank()) lastNonBlank = next.trim()
                                line = reader.readLine()
                                continue
                            }
                        }
                        writer.write(line)
                        writer.write("\n")
                        if (line.isNotBlank()) lastNonBlank = line.trim()
                        line = reader.readLine()
                    }
                }
            }

            if (!injected) {
                tempFile.delete()
                return false
            }
            tempFile.copyTo(gcodeFile, overwrite = true)
            return true
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun extractPauseTargets(xml: String): List<PauseTarget> =
        parseLayerToolSegments(xml).map { PauseTarget(it.topZ, it.extruderBambu) }

    /**
     * Decode the `customGcode` array from [com.u1.slicer.NativeLibrary.nativeGetPlateData]'s JSON
     * payload into [PauseTarget] rows. Accepts only canonical native type strings `"ColorChange"`
     * and `"ToolChange"`. `printZ` (Double) narrows to [Float]; `extruder` stays 1-based.
     * Returns an empty list on any parse error — never throws.
     *
     * Paired with the Kotlin-XML path [extractPauseTargets]: native wins when g_model has
     * customGcode rows present; XML is the permanent fallback for post-slice / embedded-file
     * cases where native returns null or empty. See
     * `docs/superpowers/plans/2026-04-24-phase1-layer-tool-pause-injector.md`.
     */
    private fun extractPauseTargetsFromNativeJson(plateJson: String): List<PauseTarget> {
        return try {
            val obj = JSONObject(plateJson)
            val arr: JSONArray = obj.optJSONArray("customGcode") ?: return emptyList()
            val out = ArrayList<PauseTarget>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val type = row.optString("type", "")
                if (type != "ColorChange" && type != "ToolChange") continue
                val topZ = row.optDouble("printZ", Double.NaN)
                if (topZ.isNaN()) continue
                val extruder = row.optInt("extruder", 1)
                out.add(PauseTarget(topZ.toFloat(), extruder))
            }
            out.sortedWith(compareBy({ it.topZ }, { it.extruderBambu }))
        } catch (_: JSONException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @VisibleForTesting
    internal fun extractPauseTargetsFromNativeJsonForTest(plateJson: String): List<PauseTarget> =
        extractPauseTargetsFromNativeJson(plateJson)

    /** Bambu `project_settings.config` JSON: `nozzle_temperature` array index matches T index (0 = T0, …). */
    private fun parseNozzleTemperatures(projectSettingsJson: String): Map<Int, Int>? {
        return try {
            val obj = JSONObject(projectSettingsJson.trim())
            val arr = obj.optJSONArray("nozzle_temperature") ?: return null
            val temps = mutableMapOf<Int, Int>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                val n = s.substringBefore(".").toIntOrNull() ?: continue
                if (n in 1..400) temps[i] = n
            }
            temps.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fallback parser: read explicit `M104/M109 ... Tn` commands already present in generated G-code.
     * This covers sanitized 3MF variants where `project_settings.config` is unavailable.
     */
    private fun parseToolNozzleTemperaturesFromGcode(gcodeFile: File): Map<Int, Int> {
        return try {
            val temps = mutableMapOf<Int, Int>()
            var currentTool: Int? = null
            gcodeFile.forEachLine { raw ->
                val line = raw.substringBefore(';').trim()
                toolOnlyRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { tool ->
                    currentTool = tool
                }
                if (!line.startsWith("M104") && !line.startsWith("M109")) return@forEachLine
                var tool: Int? = null
                var temp: Int? = null
                gcodeParamRegex.findAll(line).forEach { match ->
                    val key = match.groupValues[1].uppercase()
                    val value = match.groupValues[2]
                    when (key) {
                        "T" -> tool = value.substringBefore('.').toIntOrNull()
                        "S" -> temp = value.substringBefore('.').toIntOrNull()
                    }
                }
                // If the G-code line doesn't explicitly set Tn (e.g. `M109 S170`), assume it applies to
                // the current tool selected previously (`Tn`).
                val t = tool ?: currentTool
                val s = temp
                if (t != null && s != null && s in 1..400) {
                    temps[t] = s
                    currentTool = t
                }
            }
            temps
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Parse target temps from Snapmaker helper commands such as:
     *   SM_PRINT_START_LINE INDEX=1 TARGET_TEMP=220
     *   SM_PRINT_FLOW_CALIBRATE INDEX=1 TARGET_TEMP=220
     * Index values map to tool indices in compact-space G-code (0-based for T0/T1/... in this app).
     */
    private fun parseSmTargetTempsFromGcode(gcodeFile: File): Map<Int, Int> {
        return try {
            val temps = mutableMapOf<Int, Int>()
            gcodeFile.forEachLine { raw ->
                val line = raw.substringBefore(';').trim()
                if (!line.startsWith("SM_PRINT_START_LINE") && !line.startsWith("SM_PRINT_FLOW_CALIBRATE")) {
                    return@forEachLine
                }
                val index = smIndexRegex.find(line)?.groupValues?.getOrNull(1)
                    ?.substringBefore('.')
                    ?.toIntOrNull()
                val temp = smTargetTempRegex.find(line)?.groupValues?.getOrNull(1)
                    ?.substringBefore('.')
                    ?.toIntOrNull()
                if (index != null && temp != null && temp in 1..400) {
                    temps[index] = temp
                }
            }
            temps
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Last seen non-zero nozzle temperature from executable M104/M109 lines. */
    private fun parseLastNozzleTempFromGcode(gcodeFile: File): Int? {
        return try {
            var last: Int? = null
            gcodeFile.forEachLine { raw ->
                val line = raw.substringBefore(';').trim()
                if (!line.startsWith("M104") && !line.startsWith("M109")) return@forEachLine
                val temp = gcodeParamRegex.findAll(line)
                    .firstOrNull { it.groupValues[1].equals("S", ignoreCase = true) }
                    ?.groupValues?.getOrNull(2)
                    ?.substringBefore('.')
                    ?.toIntOrNull()
                if (temp != null && temp in 1..400) last = temp
            }
            last
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Painted/per-part multicolour jobs already contain native Orca toolchange sequences.
     * Injecting additional PAUSE_PRINT segments on top of those creates bogus extra segments
     * and can skew preview/extruder usage logic.
     */
    private fun containsNativeToolchangeWorkflow(gcodeFile: File): Boolean {
        return try {
            gcodeFile.useLines { lines ->
                lines.any { it.contains(NATIVE_TOOLCHANGE_MARKER) }
            }
        } catch (_: Exception) {
            false
        }
    }
}
