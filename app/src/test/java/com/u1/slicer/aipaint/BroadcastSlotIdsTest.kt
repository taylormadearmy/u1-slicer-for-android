package com.u1.slicer.aipaint

import com.u1.slicer.viewer.NativePreviewMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * B111 unit tests for [broadcastSlotIdsToOriginalMesh] and the stride exposed by
 * [subsampleMeshForAiPaint]. The bug they guard against: Smart Paint's export
 * previously wrote the *subsampled* mesh to disk, replacing the original geometry
 * with ~3% of itself. Correct behaviour: stride-N subsample's per-triangle slot
 * ids must broadcast back to the N source triangles each one represents.
 */
class BroadcastSlotIdsTest {

    @Test fun strideOne_isIdentity_widenedToInt() {
        val sub = byteArrayOf(0, 1, 2, 3, 0, 1)
        val out = broadcastSlotIdsToOriginalMesh(sub, originalTriCount = 6, stride = 1)
        assertEquals(6, out.size)
        for (i in 0 until 6) assertEquals(sub[i].toInt() and 0xFF, out[i])
    }

    @Test fun strideOne_unsignedWidening_handlesHighByteValues() {
        val sub = byteArrayOf(255.toByte(), 200.toByte(), 128.toByte())
        val out = broadcastSlotIdsToOriginalMesh(sub, originalTriCount = 3, stride = 1)
        assertEquals(255, out[0])
        assertEquals(200, out[1])
        assertEquals(128, out[2])
    }

    @Test fun strideTwo_broadcastsEachSubsampledTriangleToTwoSourceTriangles() {
        val sub = byteArrayOf(1, 2, 3)              // 3 subsampled
        val out = broadcastSlotIdsToOriginalMesh(sub, originalTriCount = 6, stride = 2)
        // t=0,1 → s=0 → 1   t=2,3 → s=1 → 2   t=4,5 → s=2 → 3
        assertEquals(intArrayOf(1, 1, 2, 2, 3, 3).toList(), out.toList())
    }

    @Test fun strideThirty_axolotlShape_subsampledIndexNeverExceedsBounds() {
        // Mirrors the v2.2.0 axolotl bug: 880_184 originals, stride=30 → 29_340 subsampled
        val originalTriCount = 880_184
        val stride = 30
        val subsampledCount = (originalTriCount + stride - 1) / stride // 29_340
        val sub = ByteArray(subsampledCount) { (it % 4).toByte() }
        val out = broadcastSlotIdsToOriginalMesh(sub, originalTriCount, stride)
        assertEquals(originalTriCount, out.size)
        // Spot-check a few mappings: t=0 → s=0, t=29 → s=0, t=30 → s=1, t=880183 → s=29339
        assertEquals(sub[0].toInt() and 0xFF, out[0])
        assertEquals(sub[0].toInt() and 0xFF, out[29])
        assertEquals(sub[1].toInt() and 0xFF, out[30])
        assertEquals(sub[29339].toInt() and 0xFF, out[880183])
    }

    @Test fun tailTriangle_pastLastSubsampledIndex_clampsToLastSlot() {
        // Edge case: when originalTriCount / stride leaves a remainder, the last few
        // originals map to a subsampled index that doesn't exist. They should clamp to
        // the final subsampled slot, not throw.
        val sub = byteArrayOf(5, 7)               // 2 subsampled
        val out = broadcastSlotIdsToOriginalMesh(sub, originalTriCount = 7, stride = 3)
        // t=0,1,2 → s=0 → 5   t=3,4,5 → s=1 → 7   t=6 → s=2 (out of bounds) → clamp to s=1 → 7
        assertEquals(intArrayOf(5, 5, 5, 7, 7, 7, 7).toList(), out.toList())
    }

    @Test fun emptySubsampledArray_returnsZeros() {
        val out = broadcastSlotIdsToOriginalMesh(ByteArray(0), originalTriCount = 5, stride = 1)
        assertEquals(intArrayOf(0, 0, 0, 0, 0).toList(), out.toList())
    }

    @Test fun strideZero_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            broadcastSlotIdsToOriginalMesh(byteArrayOf(0), originalTriCount = 1, stride = 0)
        }
    }

    @Test fun strideNegative_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            broadcastSlotIdsToOriginalMesh(byteArrayOf(0), originalTriCount = 1, stride = -1)
        }
    }

    @Test fun subsampleHelper_belowCap_returnsIdentityStrideOne() {
        // 10 triangles, cap 100 → no subsampling, stride=1, mesh is the same reference.
        val mesh = NativePreviewMesh(
            trianglePositions = FloatArray(10 * 9),
            extruderIndices = ByteArray(10),
        )
        val sub = subsampleMeshForAiPaint(mesh, targetCount = 100)
        assertEquals(1, sub.stride)
        assertEquals(10, sub.mesh.trianglePositions.size / 9)
    }

    @Test fun subsampleHelper_atCap_returnsStrideOne() {
        val mesh = NativePreviewMesh(
            trianglePositions = FloatArray(30 * 9),
            extruderIndices = ByteArray(30),
        )
        val sub = subsampleMeshForAiPaint(mesh, targetCount = 30)
        assertEquals(1, sub.stride)
    }

    @Test fun subsampleHelper_aboveCap_stridesAndExposesStride() {
        // 90 triangles, cap 30 → stride = (90+29)/30 = 3
        val triCount = 90
        val mesh = NativePreviewMesh(
            trianglePositions = FloatArray(triCount * 9) { it.toFloat() },
            extruderIndices = ByteArray(triCount) { (it % 4).toByte() },
        )
        val sub = subsampleMeshForAiPaint(mesh, targetCount = 30)
        assertEquals(3, sub.stride)
        assertEquals(30, sub.mesh.trianglePositions.size / 9)
        // First subsampled triangle = first source triangle
        assertEquals(0f, sub.mesh.trianglePositions[0], 0f)
        // Second subsampled triangle = source triangle at index `stride` (3)
        assertEquals((3 * 9).toFloat(), sub.mesh.trianglePositions[9], 0f)
    }

    @Test fun subsampleHelper_axolotlShape_strideThirty() {
        // The exact axolotl repro: 880_184 → 29_340 with stride=30.
        val triCount = 880_184
        val mesh = NativePreviewMesh(
            trianglePositions = FloatArray(triCount * 9),
            extruderIndices = ByteArray(triCount),
        )
        val sub = subsampleMeshForAiPaint(mesh, targetCount = 30_000)
        assertEquals(30, sub.stride)
        assertEquals(29_340, sub.mesh.trianglePositions.size / 9)
    }

    @Test fun broadcastAndSubsample_roundTripPreservesPaintAcrossSourceTriangles() {
        // End-to-end: subsample → assign slot per subsampled triangle → broadcast back.
        // Every source triangle in the stride-N window of a subsampled triangle must
        // come back with the same slot as the subsampled triangle that represents it.
        val originalTriCount = 100
        val mesh = NativePreviewMesh(
            trianglePositions = FloatArray(originalTriCount * 9),
            extruderIndices = ByteArray(originalTriCount),
        )
        val sub = subsampleMeshForAiPaint(mesh, targetCount = 25) // stride=4, count=25
        val subsampledRegions = ByteArray(sub.mesh.trianglePositions.size / 9) { i -> ((i % 3) + 1).toByte() }
        val out = broadcastSlotIdsToOriginalMesh(subsampledRegions, originalTriCount, sub.stride)
        assertEquals(originalTriCount, out.size)
        for (sIdx in subsampledRegions.indices) {
            val expected = subsampledRegions[sIdx].toInt() and 0xFF
            val firstOriginal = sIdx * sub.stride
            val lastOriginal = ((sIdx + 1) * sub.stride - 1).coerceAtMost(originalTriCount - 1)
            for (t in firstOriginal..lastOriginal) {
                assertEquals("t=$t expected $expected", expected, out[t])
            }
        }
    }
}
