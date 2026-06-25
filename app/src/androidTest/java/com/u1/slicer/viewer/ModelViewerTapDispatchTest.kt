package com.u1.slicer.viewer

import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ByteOrder

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F66 regression test for the v2.10.0 tap-to-select bug.
 *
 * Manual test on Pixel 8a found that tapping a model on the Prepare screen
 * never fired `onTriangleTapped`, so the Edit panel couldn't be reached.
 * Root cause: in placement mode, `handleActionDown` sets `draggingIndex` the
 * moment a finger lands on the model. On lift-up `wasDragging` is therefore
 * true even if the finger never moved, and the previous dispatch condition
 * `else if (!wasDragging && !tapMovedTooFar && ...)` skipped the tap callback.
 *
 * Fix: drop the `!wasDragging` guard. A no-movement down/up sequence is a tap,
 * regardless of whether placement mode armed a drag candidate.
 *
 * This test sets `draggingIndex` via reflection (mimicking what
 * `handleActionDown` would have done), dispatches `handleActionUp` directly,
 * and verifies that the tap callback fires.
 *
 * The view + GestureDetector construction requires a Looper, so we hop to the
 * instrumentation main thread via runOnMainSync.
 */
@RunWith(AndroidJUnit4::class)



class ModelViewerTapDispatchTest {

    @Test
    fun handleActionUp_inPlacementMode_withNoMovement_firesTapCallback() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        var emptyCallbackFired = false
        var triCallbackFired = false

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ModelViewerView(ctx)
            view.placementMode = true
            view.onTriangleTapped = { triCallbackFired = true }
            view.onEmptyTap = { emptyCallbackFired = true }

            // Simulate the state handleActionDown would have set: draggingIndex
            // = 0 (a draggable object was hit), tapDownTime = now, no movement.
            setPrivate(view, "draggingIndex", 0)
            val now = SystemClock.uptimeMillis()
            setPrivate(view, "tapDownTime", now)
            setPrivate(view, "tapMovedTooFar", false)

            val up = MotionEvent.obtain(
                now, now + 50L, MotionEvent.ACTION_UP, 500f, 500f, 0
            )
            try {
                invokeProtected(view, "handleActionUp", MotionEvent::class.java, up)
            } finally {
                up.recycle()
            }
        }

        assertTrue(
            "Expected onEmptyTap to fire — the dispatcher must enter the tap branch " +
                "even when draggingIndex >= 0 (placement-mode click on a model). " +
                "If this fails, the tap-to-select regression has returned.",
            emptyCallbackFired,
        )
        assertFalse(
            "onTriangleTapped should not fire without a real mesh — pickTriangle returns -1.",
            triCallbackFired,
        )
    }

    @Test
    fun setTrianglePickingPositions_fromMeshData_populatesPickingArray() {
        // Proves the wire that v2.10.0 was missing: a real loaded mesh produces
        // a non-empty per-triangle picking array via MeshData.toPickingPositions,
        // and view.setTrianglePickingPositions stores it. Before the fix,
        // pickingPositions stayed null after a Prepare-screen mesh load and
        // every tap on the model fired onEmptyTap (the user's reported symptom).
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val asset = java.io.File(ctx.cacheDir, "tap-pick-test.stl")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("3DBenchy.stl").use { input -> asset.outputStream().use { input.copyTo(it) } }

        val lib = com.u1.slicer.NativeLibrary()
        assertTrue(lib.loadModel(asset.absolutePath))
        val previewMesh = lib.getPreparePreviewMesh()
        assertNotNull("preview mesh should be available", previewMesh)
        val meshData = previewMesh!!.toMeshData()
        assertNotNull("toMeshData should succeed", meshData)
        assertTrue("mesh should have triangles", meshData!!.vertexCount > 0)

        val pickPositions = meshData.toPickingPositions()
        assertEquals(
            "picking array length must be vertexCount * 3 (xyz per vertex)",
            meshData.vertexCount * 3,
            pickPositions.size,
        )
        // Spot-check: the first vertex's xyz must equal the mesh's vertex 0
        // positions extracted from the interleaved buffer (offsets 0,1,2).
        assertEquals(meshData.vertices.get(0), pickPositions[0], 0.0001f)
        assertEquals(meshData.vertices.get(1), pickPositions[1], 0.0001f)
        assertEquals(meshData.vertices.get(2), pickPositions[2], 0.0001f)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ModelViewerView(ctx)
            view.setMesh(meshData)
            view.setTrianglePickingPositions(pickPositions)

            // Reflect-read pickingPositions to prove the wiring stored the
            // array. If null, the InlineModelPreview LaunchedEffect change
            // didn't take effect.
            val field = ModelViewerView::class.java.getDeclaredField("pickingPositions")
            field.isAccessible = true
            val stored = field.get(view) as FloatArray?
            assertNotNull(
                "pickingPositions must be non-null after setTrianglePickingPositions. " +
                    "If null, pickTriangle returns -1 for every tap and the Edit panel " +
                    "is structurally unreachable on the Prepare screen.",
                stored,
            )
            assertEquals(pickPositions.size, stored!!.size)
        }

        lib.clearModel()
    }

    @Test
    fun handleActionUp_withRealMovement_doesNotFireTapCallback() {
        // Guards against false-positive tap firing on actual drags. If the user
        // moves past touch slop, tapMovedTooFar becomes true and the dispatcher
        // skips the tap branch — onDragEnded fires alone.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        var tapFired = false

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ModelViewerView(ctx)
            view.placementMode = true
            view.onTriangleTapped = { tapFired = true }
            view.onEmptyTap = { tapFired = true }

            setPrivate(view, "draggingIndex", 0)
            val now = SystemClock.uptimeMillis()
            setPrivate(view, "tapDownTime", now)
            setPrivate(view, "tapMovedTooFar", true)  // <-- moved past slop

            val up = MotionEvent.obtain(
                now, now + 50L, MotionEvent.ACTION_UP, 500f, 500f, 0
            )
            try {
                invokeProtected(view, "handleActionUp", MotionEvent::class.java, up)
            } finally {
                up.recycle()
            }
        }

        assertFalse(
            "Tap callback must not fire on an actual drag (tapMovedTooFar=true).",
            tapFired,
        )
    }

    // ---- Reflection helpers -------------------------------------------------

    private fun setPrivate(target: Any, name: String, value: Any) {
        val f = ModelViewerView::class.java.getDeclaredField(name)
        f.isAccessible = true
        when (value) {
            is Int -> f.setInt(target, value)
            is Long -> f.setLong(target, value)
            is Boolean -> f.setBoolean(target, value)
            else -> f.set(target, value)
        }
    }

    private fun invokeProtected(target: Any, name: String, paramType: Class<*>, arg: Any) {
        // handleActionUp is protected on BaseGLViewerView; declared on ModelViewerView
        // as override fun. getDeclaredMethod finds the override on the subclass.
        val m = ModelViewerView::class.java.getDeclaredMethod(name, paramType)
        m.isAccessible = true
        m.invoke(target, arg)
    }
}

private val com.u1.slicer.viewer.MeshData.vertices: FloatArray get() {
    val buf = batches[0].geometry.duplicate()
    val arr = FloatArray(buf.capacity())
    buf.get(arr)
    return arr
}
private val com.u1.slicer.viewer.MeshData.extruderIndices: ByteArray get() {
    val buf = batches[0].materialIndices!!.duplicate()
    val arr = ByteArray(buf.capacity())
    buf.get(arr)
    return arr
}

