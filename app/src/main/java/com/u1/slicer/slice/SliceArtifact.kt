package com.u1.slicer.slice

import com.u1.slicer.bambu.BambuProjectWriter
import com.u1.slicer.bambu.BambuH2DGcodeTransformer
import com.u1.slicer.bambu.resolveH2DFilamentMap
import com.u1.slicer.data.Printer
import com.u1.slicer.gcode.GcodeToolRemapper
import java.io.File

sealed class SliceArtifact {
    abstract val sourceModelName: String
    abstract val target: SlicerTarget

    data class MoonrakerGcodeArtifact(
        override val sourceModelName: String,
        val gcodeFile: File,
        override val target: SlicerTarget = SlicerTarget.SnapmakerU1,
    ) : SliceArtifact()

    data class BambuProjectArtifact(
        override val sourceModelName: String,
        val projectFile: File,
        val plateId: Int,
        val sourceFilamentIndices: List<Int> = emptyList(),
        val filamentNozzleMap: List<Int> = emptyList(),
        override val target: SlicerTarget = SlicerTarget.BambuA1Mini,
    ) : SliceArtifact()
}

fun SliceArtifact.isCompatibleWith(printer: Printer?): Boolean = printer != null && target.isCompatibleWith(
    kind = printer.kind,
    bambuModel = printer.bambu?.model,
)

fun buildSliceArtifact(
    target: SlicerTarget,
    sourceModelName: String,
    gcodeFile: File,
    workingDir: File,
    plateId: Int,
    filamentColours: List<String>,
    filamentTypes: List<String>,
    filamentNozzleAssignments: List<Int> = emptyList(),
): SliceArtifact = buildSliceArtifactWithTimings(
    target = target,
    sourceModelName = sourceModelName,
    gcodeFile = gcodeFile,
    workingDir = workingDir,
    plateId = plateId,
    filamentColours = filamentColours,
    filamentTypes = filamentTypes,
    filamentNozzleAssignments = filamentNozzleAssignments,
    onPackagingStage = null,
)

fun buildSliceArtifactWithTimings(
    target: SlicerTarget,
    sourceModelName: String,
    gcodeFile: File,
    workingDir: File,
    plateId: Int,
    filamentColours: List<String>,
    filamentTypes: List<String>,
    filamentNozzleAssignments: List<Int> = emptyList(),
    onPackagingStage: ((stage: String, elapsedMs: Long) -> Unit)?,
): SliceArtifact = when (target) {
    SlicerTarget.SnapmakerU1 -> SliceArtifact.MoonrakerGcodeArtifact(
        sourceModelName = sourceModelName,
        gcodeFile = gcodeFile,
    )
    else -> if (target.family == SliceTargetFamily.BAMBU && target.supportsLocalSlicing) {
        var stageStartedNs = System.nanoTime()
        fun stageComplete(name: String) {
            onPackagingStage?.invoke(name, (System.nanoTime() - stageStartedNs) / 1_000_000L)
            stageStartedNs = System.nanoTime()
        }
        val usedFilamentIndices = readUsedFilamentIndicesFromGcodeHeader(gcodeFile)
        val narrowedFilaments = narrowBambuProjectFilaments(
            usedIndices = usedFilamentIndices,
            filamentColours = filamentColours,
            filamentTypes = filamentTypes,
        )
        stageComplete("filament_header")
        val projectFile = File(
            workingDir,
            // Bambu firmware recognizes sliced projects by the compound suffix.
            // A plain .3mf is treated as an editable model project, not a print job.
            "${gcodeFile.nameWithoutExtension}.gcode.3mf",
        )
        val compactedGcode = if (usedFilamentIndices.isNotEmpty()) {
            File(workingDir, "${gcodeFile.nameWithoutExtension}.bambu-compact.gcode").also { compacted ->
                val toolMap = usedFilamentIndices.mapIndexed { compact, source -> source to compact }.toMap()
                GcodeToolRemapper.remapCopy(
                    sourcePath = gcodeFile.absolutePath,
                    outputPath = compacted.absolutePath,
                    toolMap = toolMap,
                    filamentIndices = usedFilamentIndices,
                )
            }
        } else {
            gcodeFile
        }
        stageComplete("tool_compaction")
        val projectSourceIndices = usedFilamentIndices.ifEmpty {
            filamentColours.indices.toList().ifEmpty { listOf(0) }
        }
        val filamentNozzleMap = if (target == SlicerTarget.BambuH2D) {
            val inferredNozzleMap = BambuH2DGcodeTransformer.inferFilamentMap(
                source = compactedGcode,
                filamentCount = projectSourceIndices.size,
            )
            resolveH2DFilamentMap(
                sourceFilamentIndices = projectSourceIndices,
                explicitNozzleAssignments = filamentNozzleAssignments,
                inferredNozzleAssignments = inferredNozzleMap,
            )
        } else {
            List(projectSourceIndices.size) { 1 }
        }
        stageComplete("nozzle_inference")
        val packagedGcode = if (target == SlicerTarget.BambuH2D) {
            File(workingDir, "${gcodeFile.nameWithoutExtension}.h2d.gcode").also { h2dGcode ->
                BambuH2DGcodeTransformer.transformCopy(
                    source = compactedGcode,
                    output = h2dGcode,
                    filamentMap = filamentNozzleMap,
                )
            }
        } else {
            compactedGcode
        }
        stageComplete("h2d_transform")
        BambuProjectWriter.writeSinglePlateProject(
            outputFile = projectFile,
            gcodeFile = packagedGcode,
            modelName = sourceModelName,
            plateId = plateId,
            filamentColours = narrowedFilaments.first,
            filamentTypes = narrowedFilaments.second,
            sourceFilamentIndices = usedFilamentIndices,
            target = target,
            filamentNozzleMap = filamentNozzleMap,
        )
        stageComplete("archive_write")
        SliceArtifact.BambuProjectArtifact(
            sourceModelName = sourceModelName,
            projectFile = projectFile,
            plateId = plateId,
            sourceFilamentIndices = projectSourceIndices,
            filamentNozzleMap = filamentNozzleMap,
            target = target,
        )
    } else {
        SliceArtifact.MoonrakerGcodeArtifact(
            sourceModelName = sourceModelName,
            gcodeFile = gcodeFile,
        )
    }
}

internal fun narrowBambuProjectFilamentsFromGcodeHeader(
    gcodeFile: File,
    filamentColours: List<String>,
    filamentTypes: List<String>,
): Pair<List<String>, List<String>> {
    val usedIndices = readUsedFilamentIndicesFromGcodeHeader(gcodeFile)
    return narrowBambuProjectFilaments(usedIndices, filamentColours, filamentTypes)
}

private fun narrowBambuProjectFilaments(
    usedIndices: List<Int>,
    filamentColours: List<String>,
    filamentTypes: List<String>,
): Pair<List<String>, List<String>> {
    if (usedIndices.isEmpty()) return filamentColours to filamentTypes

    val fallbackColour = filamentColours.firstOrNull() ?: "#FFFFFF"
    val narrowedColours = usedIndices.map { index -> filamentColours.getOrNull(index) ?: fallbackColour }

    val fallbackType = filamentTypes.firstOrNull() ?: "PLA"
    val narrowedTypes = usedIndices.map { index -> filamentTypes.getOrNull(index) ?: fallbackType }
    return narrowedColours to narrowedTypes
}

internal fun readUsedFilamentIndicesFromGcodeHeader(gcodeFile: File): List<Int> {
    // Orca writes this summary in the generated G-code footer, after all commands.
    var filamentUsageLine: String? = null
    gcodeFile.forEachLine { line ->
        if (line.startsWith("; filament used [mm]")) filamentUsageLine = line
    }

    return filamentUsageLine
        ?.substringAfter('=', "")
        ?.split(',')
        ?.mapIndexedNotNull { index, raw ->
            raw.trim().toFloatOrNull()?.takeIf { it > 0f }?.let { index }
        }
        ?: emptyList()
}
