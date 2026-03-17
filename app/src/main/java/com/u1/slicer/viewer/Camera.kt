package com.u1.slicer.viewer

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.sin

/**
 * Orbit camera for 3D model viewing.
 * Orbits around a target point with azimuth/elevation/distance.
 * Uses Z-up convention matching the 3D printer bed (XY bed plane, Z height).
 */
data class CameraViewState(
    val azimuth: Float,
    val elevation: Float,
    val distance: Float,
    val panX: Float,
    val panY: Float,
    val targetX: Float,
    val targetY: Float,
    val targetZ: Float
)

class Camera {
    var azimuth = -45f       // horizontal rotation (degrees)
    var elevation = 45f      // vertical rotation (degrees, 0=horizon, 90=top-down)
    var distance = 300f      // distance from target
    var panX = 0f            // pan offset X (bed X direction)
    var panY = 0f            // pan offset Y (bed Y direction)
    var targetX = 0f
    var targetY = 0f
    var targetZ = 0f

    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)
    val mvpMatrix = FloatArray(16)
    val normalMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    fun setTarget(x: Float, y: Float, z: Float) {
        targetX = x; targetY = y; targetZ = z
    }

    fun snapshot(): CameraViewState = CameraViewState(
        azimuth = azimuth,
        elevation = elevation,
        distance = distance,
        panX = panX,
        panY = panY,
        targetX = targetX,
        targetY = targetY,
        targetZ = targetZ
    )

    fun restore(state: CameraViewState) {
        azimuth = state.azimuth
        elevation = state.elevation
        distance = state.distance
        panX = state.panX
        panY = state.panY
        targetX = state.targetX
        targetY = state.targetY
        targetZ = state.targetZ
    }

    fun rotate(dAzimuth: Float, dElevation: Float) {
        azimuth += dAzimuth
        elevation = (elevation + dElevation).coerceIn(5f, 89f)
    }

    fun zoom(factor: Float) {
        distance = (distance * factor).coerceIn(10f, 2000f)
    }

    fun pan(dx: Float, dy: Float) {
        // Pan in the camera's local XY plane (projected onto bed).
        // Camera right = forward × worldUp = (-sin(az), cos(az), 0)
        // Camera "up" projected onto XY (perpendicular to right) = (-cos(az), -sin(az), 0)
        val radAz = Math.toRadians(azimuth.toDouble())
        val rightX = -sin(radAz).toFloat()
        val rightY =  cos(radAz).toFloat()
        val upX = -cos(radAz).toFloat()
        val upY = -sin(radAz).toFloat()
        panX += rightX * dx + upX * dy
        panY += rightY * dx + upY * dy
    }

    fun updateViewMatrix() {
        val radAz = Math.toRadians(azimuth.toDouble())
        val radEl = Math.toRadians(elevation.toDouble())

        // Z-up: eye orbits around target in XY plane, Z is height
        val eyeX = targetX + panX + (distance * cos(radEl) * cos(radAz)).toFloat()
        val eyeY = targetY + panY + (distance * cos(radEl) * sin(radAz)).toFloat()
        val eyeZ = targetZ + (distance * sin(radEl)).toFloat()

        Matrix.setLookAtM(
            viewMatrix, 0,
            eyeX, eyeY, eyeZ,
            targetX + panX, targetY + panY, targetZ,
            0f, 0f, 1f  // Z-up
        )
    }

    fun updateProjectionMatrix(width: Int, height: Int) {
        val aspect = width.toFloat() / height.toFloat()
        val near = (distance * 0.01f).coerceAtLeast(0.1f)
        val far = distance * 10f
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, near, far)
    }

    fun computeMVP(modelMatrix: FloatArray = IDENTITY) {
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        // Normal matrix = transpose(inverse(modelView))
        Matrix.invertM(normalMatrix, 0, tempMatrix, 0)
        transposeInPlace(normalMatrix)
    }

    private fun transposeInPlace(m: FloatArray) {
        fun swap(i: Int, j: Int) { val t = m[i]; m[i] = m[j]; m[j] = t }
        swap(1, 4); swap(2, 8); swap(3, 12)
        swap(6, 9); swap(7, 13); swap(11, 14)
    }

    companion object {
        val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    }
}
