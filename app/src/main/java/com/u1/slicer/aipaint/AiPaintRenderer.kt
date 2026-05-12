package com.u1.slicer.aipaint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class CameraAngle {
    FRONT, BACK, LEFT_ISO, RIGHT_ISO;

    fun rotationMatrix(): FloatArray = when (this) {
        FRONT     -> floatArrayOf( 1f, 0f, 0f,  0f, 0f, -1f,  0f, 1f, 0f)
        BACK      -> floatArrayOf(-1f, 0f, 0f,  0f, 0f,  1f,  0f, 1f, 0f)
        LEFT_ISO  -> rot3(yDeg = -45f, xDeg = 30f)
        RIGHT_ISO -> rot3(yDeg =  45f, xDeg = 30f)
    }
}

object AiPaintRenderer {

    private val REGION_COLOURS = intArrayOf(
        Color.rgb(255, 0, 0),     // region 0 = red
        Color.rgb(0, 255, 0),     // region 1 = green
        Color.rgb(0, 255, 255),   // region 2 = cyan
        Color.rgb(255, 255, 0)    // region 3 = yellow
    )

    fun renderShaded(positions: FloatArray, w: Int, h: Int, angle: CameraAngle): Bitmap =
        render(positions, w, h, angle) { _, nx, ny, nz ->
            val intensity = max(0.15f, 0.6f * nx + 0.7f * ny + 0.5f * nz)
            val v = (intensity * 210).toInt().coerceIn(30, 240)
            Color.rgb(v + 15, v + 10, v)
        }

    fun renderRegions(positions: FloatArray, regionIds: IntArray, w: Int, h: Int, angle: CameraAngle): Bitmap =
        render(positions, w, h, angle) { triIdx, _, _, _ ->
            REGION_COLOURS[regionIds[triIdx].coerceIn(0, 3)]
        }

    private fun render(
        positions: FloatArray, w: Int, h: Int, angle: CameraAngle,
        colorFn: (triIdx: Int, nx: Float, ny: Float, nz: Float) -> Int
    ): Bitmap {
        val nTri = positions.size / 9
        val rot = angle.rotationMatrix()
        val bg = Color.rgb(30, 30, 35)

        data class Tri(
            val sx0: Float, val sy0: Float,
            val sx1: Float, val sy1: Float,
            val sx2: Float, val sy2: Float,
            val depth: Float, val color: Int
        )

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE

        val projected = Array(nTri) { i ->
            val b = i * 9
            val (rx0, ry0, rz0) = rot3apply(rot, positions[b], positions[b + 1], positions[b + 2])
            val (rx1, ry1, rz1) = rot3apply(rot, positions[b + 3], positions[b + 4], positions[b + 5])
            val (rx2, ry2, rz2) = rot3apply(rot, positions[b + 6], positions[b + 7], positions[b + 8])
            val (nx, ny, nz) = faceNormal(rx0, ry0, rz0, rx1, ry1, rz1, rx2, ry2, rz2)
            val depth = (rz0 + rz1 + rz2) / 3f
            minX = minOf(minX, rx0, rx1, rx2); maxX = maxOf(maxX, rx0, rx1, rx2)
            minY = minOf(minY, ry0, ry1, ry2); maxY = maxOf(maxY, ry0, ry1, ry2)
            Tri(rx0, ry0, rx1, ry1, rx2, ry2, depth, colorFn(i, nx, ny, nz))
        }

        val spanX = (maxX - minX).coerceAtLeast(1e-6f)
        val spanY = (maxY - minY).coerceAtLeast(1e-6f)
        val scale = min(w, h) * 0.85f / max(spanX, spanY)
        val cx = w / 2f - (minX + maxX) / 2f * scale
        val cy = h / 2f + (minY + maxY) / 2f * scale

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bg)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        projected.sortedByDescending { it.depth }.forEach { t ->
            path.reset()
            path.moveTo(t.sx0 * scale + cx, -t.sy0 * scale + cy)
            path.lineTo(t.sx1 * scale + cx, -t.sy1 * scale + cy)
            path.lineTo(t.sx2 * scale + cx, -t.sy2 * scale + cy)
            path.close()
            paint.color = t.color
            canvas.drawPath(path, paint)
        }
        return bmp
    }

    private fun faceNormal(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float
    ): Triple<Float, Float, Float> {
        val ax = x1 - x0; val ay = y1 - y0; val az = z1 - z0
        val bx = x2 - x0; val by = y2 - y0; val bz = z2 - z0
        val nx = ay * bz - az * by
        val ny = az * bx - ax * bz
        val nz = ax * by - ay * bx
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f)
        return Triple(nx / len, ny / len, nz / len)
    }

    private fun rot3apply(m: FloatArray, x: Float, y: Float, z: Float) =
        Triple(
            m[0] * x + m[1] * y + m[2] * z,
            m[3] * x + m[4] * y + m[5] * z,
            m[6] * x + m[7] * y + m[8] * z
        )
}

private fun rot3(yDeg: Float, xDeg: Float): FloatArray {
    val ry = Math.toRadians(yDeg.toDouble()).toFloat()
    val rx = Math.toRadians(xDeg.toDouble()).toFloat()
    val cy = cos(ry); val sy = sin(ry)
    val cx = cos(rx); val sx = sin(rx)
    return floatArrayOf(
         cy,     sy * sx,  sy * cx,
         0f,     cx,      -sx,
        -sy,     cy * sx,  cy * cx
    )
}
