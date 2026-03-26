package com.u1.slicer.gcode

import java.io.File
import java.util.zip.ZipFile

object LayerToolPauseInjector {
    private const val EPSILON = 0.0001f
    private val layerRegex = Regex("""<layer\b([^>]*)>""")
    private val topZRegex = Regex("""\btop_z="([^"]+)"""")
    private val typeRegex = Regex("""\btype="([^"]+)"""")
    private val pauseRegex = Regex("""^\s*; ?PAUSE_PRINT\s*$""")
    private val zCommentRegex = Regex("""^\s*;Z:([0-9.]+)\s*$""")

    fun injectFrom3mf(gcodePath: String, model3mf: File): Boolean {
        if (!model3mf.exists() || !model3mf.name.endsWith(".3mf", ignoreCase = true)) return false

        val pauseTargets = mutableListOf<Float>()
        val pauseCommand = ZipFile(model3mf).use { zip ->
            zip.getEntry("Metadata/custom_gcode_per_layer.xml")?.let { entry ->
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                pauseTargets += extractPauseTargets(xml)
            }
            zip.getEntry("Metadata/project_settings.config")?.let { entry ->
                val json = zip.getInputStream(entry).bufferedReader().readText()
                Regex(""""machine_pause_gcode"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.getOrNull(1)
            }
        } ?: "M400 U1"

        if (pauseTargets.isEmpty()) return false

        val gcodeFile = File(gcodePath)
        if (!gcodeFile.exists()) return false

        val pendingTargets = pauseTargets.distinct().sorted().toMutableList()
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
                                    while (pendingTargets.isNotEmpty() && z > pendingTargets.first() + EPSILON) {
                                        if (!hasPauseImmediatelyBefore()) {
                                            writer.write("; PAUSE_PRINT\n")
                                            writer.write(pauseCommand)
                                            writer.write("\n")
                                            writer.write("\n")
                                            injected = true
                                            lastNonBlank = "; PAUSE_PRINT"
                                        }
                                        pendingTargets.removeAt(0)
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

    private fun extractPauseTargets(xml: String): List<Float> =
        layerRegex.findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val type = typeRegex.find(attrs)?.groupValues?.getOrNull(1)
            if (type != "1" && type != "2") return@mapNotNull null
            topZRegex.find(attrs)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        }.sorted().toList()
}
