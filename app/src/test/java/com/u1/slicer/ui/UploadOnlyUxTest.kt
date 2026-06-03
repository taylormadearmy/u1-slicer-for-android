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
