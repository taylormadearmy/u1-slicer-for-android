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
                val implicitTool = sourceFileIndexToToolState[0]?.takeIf { it != 1 }
                val needsDefaultAssignment = implicitTool != null &&
                    source.getEntry("Metadata/model_settings.config") == null &&
                    source.getEntry("Metadata/Slic3r_PE_model.config") == null
                var defaultObjectIds: List<String> = emptyList()
                source.entries().asSequence().forEach { entry ->
                    dest.putNextEntry(ZipEntry(entry.name))
                    val bytes = source.getInputStream(entry).readBytes()
                    if (needsDefaultAssignment && entry.name == "3D/3dmodel.model") {
                        val build = Regex("""<build\b[^>]*>([\s\S]*?)</build>""")
                            .find(bytes.toString(Charsets.UTF_8))?.groupValues?.get(1).orEmpty()
                        defaultObjectIds = Regex("""\bobjectid\s*=\s*["']([0-9]+)["']""")
                            .findAll(build).map { it.groupValues[1] }.distinct().toList()
                    }
                    val rewritten = when {
                        entry.name.endsWith(".model") -> rewriteModel(bytes, sourceStateToToolState)
                        entry.name == "Metadata/model_settings.config" || entry.name == "Metadata/Slic3r_PE_model.config" ->
                            rewriteModelSettings(bytes, sourceFileIndexToToolState)
                        else -> bytes
                    }
                    dest.write(rewritten)
                    dest.closeEntry()
                }
                if (defaultObjectIds.isNotEmpty()) {
                    val settings = buildString {
                        append("<config>")
                        defaultObjectIds.forEach { id ->
                            append("<object id=\"$id\"><metadata key=\"extruder\" value=\"$implicitTool\"/></object>")
                        }
                        append("</config>")
                    }
                    dest.putNextEntry(ZipEntry("Metadata/model_settings.config"))
                    dest.write(settings.toByteArray(Charsets.UTF_8))
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

    private fun rewriteAttributes(
        bytes: ByteArray,
        pattern: Pattern,
        transform: (String) -> String,
    ): ByteArray {
        val xml = String(bytes)
        // Kotlin Regex.replace resets Android's native matcher for every match,
        // copying the entire XML each time. Reuse one matcher for painted meshes.
        val matcher = pattern.matcher(xml)
        val rewritten = StringBuilder(xml.length)
        var copiedUntil = 0
        while (matcher.find()) {
            rewritten.append(xml, copiedUntil, matcher.start(2))
            rewritten.append(transform(checkNotNull(matcher.group(2))))
            copiedUntil = matcher.end(2)
        }
        rewritten.append(xml, copiedUntil, xml.length)
        return rewritten.toString().toByteArray()
    }
}
