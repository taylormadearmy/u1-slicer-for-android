package com.u1.slicer.bambu

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Produces a disposable physical/mix-tool-space 3MF; source files are never changed. */
object ThreeMfColourRemapper {
    private val paintAttr = Regex("""((?:paint_color|mmu_segmentation|slic3rpe:mmu_segmentation)=\")([^"]+)(\")""")
    private val extruderMetadata = Regex("""(<metadata\b[^>]*key=\"extruder\"[^>]*value=\")([0-9]+)(\")""")

    fun remap(
        input: File,
        output: File,
        sourceStateToToolState: Map<Int, Int>,
        sourceFileIndexToToolState: Map<Int, Int>,
    ): File {
        ZipFile(input).use { source ->
            ZipOutputStream(FileOutputStream(output)).use { dest ->
                source.entries().asSequence().forEach { entry ->
                    dest.putNextEntry(ZipEntry(entry.name))
                    val bytes = source.getInputStream(entry).readBytes()
                    val rewritten = when {
                        entry.name.endsWith(".model") -> rewriteModel(bytes, sourceStateToToolState)
                        entry.name == "Metadata/model_settings.config" || entry.name == "Metadata/Slic3r_PE_model.config" ->
                            rewriteModelSettings(bytes, sourceFileIndexToToolState)
                        else -> bytes
                    }
                    dest.write(rewritten)
                    dest.closeEntry()
                }
            }
        }
        return output
    }

    private fun rewriteModel(bytes: ByteArray, states: Map<Int, Int>): ByteArray =
        paintAttr.replace(String(bytes)) { m ->
            val replacement = PaintColorRemapper.remap(m.groupValues[2], states) ?: m.groupValues[2]
            m.groupValues[1] + replacement + m.groupValues[3]
        }.toByteArray()

    private fun rewriteModelSettings(bytes: ByteArray, fileToTool: Map<Int, Int>): ByteArray =
        extruderMetadata.replace(String(bytes)) { m ->
            val sourceIndex = m.groupValues[2].toIntOrNull()?.minus(1)
            val state = sourceIndex?.let(fileToTool::get) ?: m.groupValues[2].toInt()
            m.groupValues[1] + state + m.groupValues[3]
        }.toByteArray()
}
