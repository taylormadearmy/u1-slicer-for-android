package com.u1.slicer.viewer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F95: structural guard for the translucent negative/modifier-volume render path. The
 * recolor + boundary math is behaviourally unit-tested in [MeshDataTest] and
 * [NativePreviewMeshTest], and the native emission is covered on-device in
 * `NativePreparePreviewTest`. This test guards the two pieces that have no JVM harness:
 *
 *  1. the GL renderer draws the trailing modifier block in a separate blended, depth-write-off
 *     pass (so it shows through the solid body), and the opaque/outline passes stop at the
 *     model-part boundary; and
 *  2. the preview-fetch sites tag [NativePreviewMesh.modifierBlockStartTriangle] from the
 *     native accessor before [NativePreviewMesh.toMeshData], so the boundary reaches MeshData.
 */
class ModifierBlockRenderTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/$relative"),
            File("../app/src/main/java/$relative"),
            File("src/main/java/$relative"),
        )
        return (candidates.firstOrNull { it.exists() }
            ?: error("$relative not found from ${File(".").absolutePath}")).readText()
    }

    @Test
    fun renderer_drawsModifierTail_blendedWithDepthWriteOff() {
        val src = source("com/u1/slicer/viewer/ModelRenderer.kt")
        assertTrue(
            "ModelRenderer must define drawModifierTail",
            src.contains("fun drawModifierTail(")
        )
        // Scope the assertions to the drawModifierTail body.
        val start = src.indexOf("fun drawModifierTail(")
        val bodyStart = src.indexOf('{', src.indexOf(')', start))
        var depth = 0; var i = bodyStart; var end = -1
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i + 1; break } }
            }
            i++
        }
        val body = src.substring(bodyStart, end)
        assertTrue("modifier pass must enable blending", body.contains("GL_BLEND"))
        assertTrue(
            "modifier pass must use SRC_ALPHA/ONE_MINUS_SRC_ALPHA blending",
            body.contains("GL_SRC_ALPHA") && body.contains("GL_ONE_MINUS_SRC_ALPHA")
        )
        assertTrue(
            "modifier pass must disable depth writes so it doesn't occlude the solid body",
            body.contains("glDepthMask(false)")
        )
        assertTrue(
            "modifier pass must restore depth writes",
            body.contains("glDepthMask(true)")
        )
        // B140: internal negative/modifier volumes are occluded by the opaque body unless the
        // pass disables depth-testing (x-ray overlay). Without this the F95 feature is invisible.
        assertTrue(
            "modifier pass must disable depth-test so internal modifiers show through the body",
            body.contains("glDisable(GLES30.GL_DEPTH_TEST)")
        )
        assertTrue(
            "modifier pass must re-enable depth-test afterwards",
            body.contains("glEnable(GLES30.GL_DEPTH_TEST)")
        )
    }

    @Test
    fun renderer_singleMeshDraws_stopAtModelPartBoundary_andDrawTail() {
        val src = source("com/u1/slicer/viewer/ModelRenderer.kt")
        // Both single-mesh draw paths must bound the opaque pass to model parts and then
        // render the translucent tail.
        assertTrue(
            "drawModel/drawModelAt must call drawModifierTail(...) at least twice",
            Regex("drawModifierTail\\(").findAll(src).count() >= 2
        )
        assertTrue(
            "single-mesh + outline draws must use modelPartVertexCount(mesh) so they stop " +
                "before the translucent modifier block",
            Regex("modelPartVertexCount\\(mesh\\)").findAll(src).count() >= 3
        )
    }

    @Test
    fun previewFetchSites_tagModifierBlockStart_fromNativeAccessor() {
        for (rel in listOf(
            "com/u1/slicer/MainActivity.kt",
            "com/u1/slicer/ui/ModelViewerScreen.kt",
        )) {
            val src = source(rel)
            assertTrue(
                "$rel must set modifierBlockStartTriangle from nativeGetPreviewModifierBlockStart()",
                src.contains("modifierBlockStartTriangle = ") &&
                    src.contains("nativeGetPreviewModifierBlockStart()")
            )
        }
    }
}
