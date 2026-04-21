package com.u1.slicer

import com.u1.slicer.bambu.ThreeMfInfo
import java.io.File
import java.util.Locale

enum class ExportArtifactKind {
    Sanitized3mf,
    SanitizedEmbedded3mf
}

data class ExportableModelArtifacts(
    val sourceDisplayName: String,
    val selectedPlateId: Int?,
    val isBambu: Boolean,
    val sanitizedFile: File?,
    val embeddedFile: File?,
    val info: ThreeMfInfo?
) {
    fun fileFor(kind: ExportArtifactKind): File? = when (kind) {
        ExportArtifactKind.Sanitized3mf -> sanitizedFile
        ExportArtifactKind.SanitizedEmbedded3mf -> embeddedFile
    }
}

object ModelExportArtifacts {
    fun current(
        sourceDisplayName: String,
        selectedPlateId: Int?,
        sanitizedFile: File?,
        embeddedFile: File?,
        info: ThreeMfInfo?
    ): ExportableModelArtifacts? {
        if (info?.isBambu != true) return null
        val normalizedSourceName = sourceDisplayName.ifBlank {
            sanitizedFile?.name ?: embeddedFile?.name ?: "model.3mf"
        }
        val exportable = ExportableModelArtifacts(
            sourceDisplayName = normalizedSourceName,
            selectedPlateId = selectedPlateId,
            isBambu = true,
            sanitizedFile = sanitizedFile?.takeIf(::isExportableThreeMf),
            embeddedFile = embeddedFile?.takeIf(::isExportableThreeMf),
            info = info
        )
        return exportable.takeIf {
            it.sanitizedFile != null || it.embeddedFile != null
        }
    }

    fun suggestedFilename(
        sourceDisplayName: String,
        kind: ExportArtifactKind
    ): String {
        val baseName = sourceDisplayName.substringBeforeLast(".", sourceDisplayName)
            .ifBlank { "model" }
        return when (kind) {
            ExportArtifactKind.Sanitized3mf -> "$baseName.sanitized.3mf"
            ExportArtifactKind.SanitizedEmbedded3mf -> "$baseName.sanitized-embedded.3mf"
        }
    }

    fun buildDebugSummary(
        artifacts: ExportableModelArtifacts,
        currentWorkingFile: File?
    ): String {
        val info = artifacts.info
        val usedExtruders = info?.usedExtruderIndices
            ?.sorted()
            ?.joinToString(", ")
            ?.ifBlank { "none" }
            ?: "none"
        return buildString {
            appendLine("Source file: ${artifacts.sourceDisplayName}")
            appendLine("Current working file: ${currentWorkingFile?.name ?: "none"}")
            appendLine("Bambu file: ${yesNo(artifacts.isBambu)}")
            appendLine("Multi-plate: ${yesNo(info?.isMultiPlate == true)}")
            appendLine("Selected plate: ${artifacts.selectedPlateId?.toString() ?: "none"}")
            appendLine("Detected extruders: ${info?.detectedExtruderCount ?: 0}")
            appendLine("Paint data: ${yesNo(info?.hasPaintData == true)}")
            appendLine("Paint supports: ${yesNo(info?.hasPaintSupports == true)}")
            appendLine("Layer-tool changes: ${yesNo(info?.hasLayerToolChanges == true)}")
            appendLine("Used extruder indices: $usedExtruders")
            appendLine("Object-extruder map size: ${info?.objectExtruderMap?.size ?: 0}")
            appendLine("Sanitized artifact: ${describeArtifact(artifacts.sanitizedFile)}")
            appendLine("Sanitized + embedded artifact: ${describeArtifact(artifacts.embeddedFile)}")
        }.trimEnd()
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    private fun describeArtifact(file: File?): String {
        if (file == null) return "unavailable"
        return "${file.name} (${file.absolutePath})"
    }

    private fun isExportableThreeMf(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return file.name.lowercase(Locale.US).endsWith(".3mf")
    }
}
