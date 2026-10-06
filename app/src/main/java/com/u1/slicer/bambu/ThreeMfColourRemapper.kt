package com.u1.slicer.bambu

import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Produces a disposable physical/mix-tool-space 3MF; source files are never changed. */
object ThreeMfColourRemapper {
    private val paintAttr = Pattern.compile("""((?:paint_color|mmu_segmentation|slic3rpe:mmu_segmentation)=\")([^"]+)(\")""")
    private val extruderMetadata = Pattern.compile("""(<metadata\b[^>]*key=\"extruder\"[^>]*value=\")([0-9]+)(\")""")

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
        rewriteAttributes(bytes, paintAttr) { value ->
            PaintColorRemapper.remap(value, states) ?: value
        }

    private fun rewriteModelSettings(bytes: ByteArray, fileToTool: Map<Int, Int>): ByteArray =
        rewriteAttributes(bytes, extruderMetadata) { value ->
            val sourceIndex = value.toIntOrNull()?.minus(1)
            val state = sourceIndex?.let(fileToTool::get) ?: value.toInt()
            state.toString()
        }

    private fun rewriteAttributes(bytes: ByteArray, pattern: Pattern, rewriteValue: (String) -> String): ByteArray {
        val text = String(bytes, Charsets.UTF_8)
        // Kotlin's transform-based Regex.replace creates a matcher per match. Android's
        // matcher copies the entire input into native memory, so reuse a single matcher.
        val matcher = pattern.matcher(text)
        val result = StringBuilder(text.length)
        var copiedUntil = 0
        while (matcher.find()) {
            result.append(text, copiedUntil, matcher.start(2))
            result.append(rewriteValue(matcher.group(2)!!))
            copiedUntil = matcher.end(2)
        }
        result.append(text, copiedUntil, text.length)
        return result.toString().toByteArray(Charsets.UTF_8)
    }
}
