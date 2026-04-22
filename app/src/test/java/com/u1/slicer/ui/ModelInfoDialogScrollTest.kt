package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B89: Info (ℹ︎) menu on Prepare preview must be scrollable. Long filenames
 * or portrait orientation previously pushed the action buttons off-screen.
 *
 * Structural guard because the project has no Compose UI test harness. This
 * scopes to the [ModelInfoDialog] function body and asserts both:
 *
 * - a [rememberScrollState] is created
 * - the content [Column] applies [verticalScroll]
 */
class ModelInfoDialogScrollTest {

    private fun mainActivitySource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/u1/slicer/MainActivity.kt"),
            File("../app/src/main/java/com/u1/slicer/MainActivity.kt"),
            File("src/main/java/com/u1/slicer/MainActivity.kt")
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("MainActivity.kt not found from ${File(".").absolutePath}")
        return f.readText()
    }

    private fun modelInfoDialogBody(): String {
        val src = mainActivitySource()
        val header = "fun ModelInfoDialog("
        val start = src.indexOf(header)
        require(start >= 0) { "ModelInfoDialog not found in MainActivity.kt" }
        // Walk forward from the opening brace of the function body and match braces.
        val bodyStart = src.indexOf('{', src.indexOf(')', start))
        require(bodyStart >= 0) { "Could not locate ModelInfoDialog body brace" }
        var depth = 0
        var i = bodyStart
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(bodyStart, i + 1)
                }
            }
            i++
        }
        error("ModelInfoDialog body not terminated")
    }

    @Test
    fun modelInfoDialog_usesRememberScrollState() {
        val body = modelInfoDialogBody()
        assertTrue(
            "ModelInfoDialog must create a ScrollState via rememberScrollState()",
            body.contains("rememberScrollState()")
        )
    }

    @Test
    fun modelInfoDialog_contentColumnAppliesVerticalScroll() {
        val body = modelInfoDialogBody()
        assertTrue(
            "ModelInfoDialog content Column must apply .verticalScroll(...) " +
                "so long metadata + action buttons remain reachable in portrait " +
                "or with long filenames",
            body.contains(".verticalScroll(")
        )
    }
}
