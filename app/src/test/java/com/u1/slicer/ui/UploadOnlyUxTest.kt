package com.u1.slicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Upload-Only UX (2026-06-03). Structural guard — the project has no Compose
 * UI test harness. Asserts the send-to-hold path routes to the read-only
 * UploadConfirmationDialog (not the slot picker) and the button is renamed.
 *
 * Spec: docs/superpowers/specs/2026-06-03-upload-only-ux-design.md
 */
class UploadOnlyUxTest {

    private fun source(rel: String): String {
        val f = listOf(File(rel), File("../$rel")).firstOrNull { it.exists() }
            ?: error("$rel not found from ${File(".").absolutePath}")
        return f.readText()
    }

    @Test
    fun uploadConfirmationDialog_exists() {
        val src = source("app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt")
        assertTrue(
            "UploadConfirmationDialog composable must exist",
            src.contains("fun UploadConfirmationDialog(")
        )
    }

    @Test
    fun uploadOnlyAction_routesToConfirmationDialog() {
        val src = source("app/src/main/java/com/u1/slicer/MainActivity.kt")
        assertTrue(
            "Upload Only must render UploadConfirmationDialog",
            src.contains("UploadConfirmationDialog(")
        )
    }

    private fun uploadConfirmationDialogBody(): String {
        val src = source("app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt")
        val start = src.indexOf("fun UploadConfirmationDialog(")
        require(start >= 0) { "UploadConfirmationDialog not found" }
        val bodyStart = src.indexOf('{', src.indexOf(')', start))
        var depth = 0
        var i = bodyStart
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return src.substring(bodyStart, i + 1) }
            }
            i++
        }
        error("UploadConfirmationDialog body not terminated")
    }

    @Test
    fun uploadConfirmationDialog_showsSlicedMaterialNotFileDeclared() {
        // Regression: the sheet must show the material the G-code was SLICED
        // with (slot presets / overrides win — e.g. PETG), not the file-declared
        // material (e.g. PLA), so it matches the Per-Extruder summary. Guards
        // that the dialog consumes a resolved-materials param, not only
        // entry.materialType.
        val body = uploadConfirmationDialogBody()
        assertTrue(
            "UploadConfirmationDialog must display resolved sliced materials " +
                "(slicedMaterials), not only file-declared entry.materialType",
            body.contains("slicedMaterials")
        )
    }

    @Test
    fun uploadOnlyBranch_feedsResolvedSlicedMaterials() {
        // The Upload Only branch must pass the resolved per-filament materials
        // into the sheet so the displayed material matches the slice.
        val src = source("app/src/main/java/com/u1/slicer/MainActivity.kt")
        val callIdx = src.indexOf("UploadConfirmationDialog(")
        require(callIdx >= 0) { "UploadConfirmationDialog call not found" }
        val callBlock = src.substring(callIdx, minOf(callIdx + 2200, src.length))
        assertTrue(
            "Upload Only must pass canonical sliced materials through viewModel.sliceTimeMaterials(...)",
            callBlock.contains("slicedMaterials =") &&
                callBlock.contains("viewModel.sliceTimeMaterials(")
        )
    }

    @Test
    fun uploadOnlyBranch_threadsPhysicalToolSpaceForMixGcode() {
        val main = source("app/src/main/java/com/u1/slicer/MainActivity.kt")
        assertTrue(
            "Upload Only must pass the current source tool-space into sendRemapForAction",
            main.contains("sourceToolSpace =")
        )
        assertTrue(
            "Upload Only confirmation must know when it is showing already-physical mix G-code",
            main.contains("physicalToolSpace =")
        )

        val dialog = source("app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt")
        assertTrue(
            "UploadConfirmationDialog must accept physicalToolSpace",
            dialog.contains("physicalToolSpace: Boolean")
        )
    }

    @Test
    fun outlinedSendButton_renamedToUploadOnly() {
        val src = source("app/src/main/java/com/u1/slicer/MainActivity.kt")
        assertTrue("Button must read \"Upload Only\"", src.contains("\"Upload Only\""))
        assertFalse(
            "Stale \"Map & Upload\" label must be gone",
            src.contains("\"Map & Upload\"")
        )
    }
}
