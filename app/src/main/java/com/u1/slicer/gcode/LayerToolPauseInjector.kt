package com.u1.slicer.gcode

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

object LayerToolPauseInjector {
    private const val EPSILON = 0.0001f
    private val layerRegex = Regex("""<layer\b([^>]*)>""")
    private val topZRegex = Regex("""\btop_z="([^"]+)"""")
    private val typeRegex = Regex("""\btype="([^"]+)"""")
    private val extruderXmlRegex = Regex("""\bextruder="([^"]+)"""")
    private val pauseRegex = Regex("""^\s*; ?PAUSE_PRINT\s*$""")
    private val zCommentRegex = Regex("""^\s*;Z:([0-9.]+)\s*$""")
    private val gcodeParamRegex = Regex("""\b([A-Za-z])([+-]?\d*\.?\d+)""")
    private val toolOnlyRegex = Regex("""^T([0-9]+(?:\.[0-9]+)?)\s*$""")

    /**
     * One layer-change row in Bambu `custom_gcode_per_layer.xml`.
     * [topZ] is the first layer whose Z is strictly above this (see inject loop).
     * [extruderBambu] is 1-based as in the file (1→T0, 2→T1, …). If missing, treated as 1.
     */
    private data class PauseTarget(val topZ: Float, val extruderBambu: Int)

    fun injectFrom3mf(gcodePath: String, model3mf: File): Boolean {
        if (!model3mf.exists() || !model3mf.name.endsWith(".3mf", ignoreCase = true)) return false

        val pauseTargets = mutableListOf<PauseTarget>()
        var nozzleTemps: Map<Int, Int>? = null
        val pauseCommand = ZipFile(model3mf).use { zip ->
            zip.getEntry("Metadata/custom_gcode_per_layer.xml")?.let { entry ->
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                pauseTargets += extractPauseTargets(xml)
            }
            zip.getEntry("Metadata/project_settings.config")?.let { entry ->
                val json = zip.getInputStream(entry).bufferedReader().readText()
                nozzleTemps = parseNozzleTemperatures(json)
                Regex(""""machine_pause_gcode"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.getOrNull(1)
            }
        } ?: "M400 U1"

        if (pauseTargets.isEmpty()) return false

        val gcodeFile = File(gcodePath)
        if (!gcodeFile.exists()) return false
        val gcodeToolTemps = parseToolNozzleTemperaturesFromGcode(gcodeFile)

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
                                                if (setTemp != null && setTemp in 1..400) {
                                                    writer.write("M109 S$setTemp\n")
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
        layerRegex.findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val type = typeRegex.find(attrs)?.groupValues?.getOrNull(1)
            if (type != "1" && type != "2") return@mapNotNull null
            val topZ = topZRegex.find(attrs)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                ?: return@mapNotNull null
            val extruderBambu = extruderXmlRegex.find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            PauseTarget(topZ, extruderBambu)
        }.sortedWith(compareBy({ it.topZ }, { it.extruderBambu })).toList()

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
}
