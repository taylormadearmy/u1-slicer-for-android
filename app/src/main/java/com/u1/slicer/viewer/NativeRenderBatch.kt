package com.u1.slicer.viewer

import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * A chunk of interleaved 3D geometry.
 * Format per vertex: x, y, z, nx, ny, nz, r, g, b, a (10 floats = 40 bytes)
 */
class NativeRenderBatch(
    val geometry: FloatBuffer,
    val materialIndices: ByteBuffer?,
    val triangleCount: Int,
    val bounds: FloatArray? = null
) {
    @Volatile
    var colorBuffer: FloatBuffer? = null
}
