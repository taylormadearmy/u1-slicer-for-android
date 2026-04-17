package com.u1.slicer.viewer

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.sin

/**
 * Orbit camera for 3D model viewing.
 * Orbits around a target point with azimuth/elevation/distance.
 * Uses Z-up convention matching the 3D printer bed (XY bed plane, Z height).
 *
 * All scalar fields are Double for precision at zoom extremes; values are downcast
 * to Float only at shader uniform upload in updateViewMatrix/updateProjectionMatrix.
 */
data class CameraViewState(
    val azimuth: Double,
    val elevation: Double,
    val distance: Double,
    val panX: Double,
    val panY: Double,
    val targetX: Double,
    val targetY: Double,
    val targetZ: Double
)

class Camera {
    @Volatile var azimuth = -45.0       // horizontal rotation (degrees)
    @Volatile var elevation = 45.0      // vertical rotation (degrees, 0=horizon, 90=top-down)
    @Volatile var distance = 300.0      // distance from target
    @Volatile var panX = 0.0            // pan offset X (bed X direction)
    @Volatile var panY = 0.0            // pan offset Y (bed Y direction)
    @Volatile var targetX = 0.0
    @Volatile var targetY = 0.0
    @Volatile var targetZ = 0.0

    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)
    val mvpMatrix = FloatArray(16)
    val normalMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    fun setTarget(x: Double, y: Double, z: Double) {
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

    fun rotate(dAzimuth: Double, dElevation: Double) {
        azimuth += dAzimuth
        elevation = (elevation + dElevation).coerceIn(5.0, 89.0)
    }

    fun zoom(factor: Double) {
        distance = (distance * factor).coerceIn(10.0, 2000.0)
    }

    fun pan(dx: Double, dy: Double) {
        // Pan in the camera's local XY plane (projected onto bed).
        // Camera right = forward × worldUp = (-sin(az), cos(az), 0)
        // Camera "up" projected onto XY (perpendicular to right) = (-cos(az), -sin(az), 0)
        val radAz = Math.toRadians(azimuth)
        val rightX = -sin(radAz)
        val rightY =  cos(radAz)
        val upX = -cos(radAz)
        val upY = -sin(radAz)
        panX += rightX * dx + upX * dy
        panY += rightY * dx + upY * dy
    }

    fun updateViewMatrix() {
        val radAz = Math.toRadians(azimuth)
        val radEl = Math.toRadians(elevation)

        // Z-up: eye orbits around target in XY plane, Z is height
        val eyeX = (targetX + panX + distance * cos(radEl) * cos(radAz)).toFloat()
        val eyeY = (targetY + panY + distance * cos(radEl) * sin(radAz)).toFloat()
        val eyeZ = (targetZ + distance * sin(radEl)).toFloat()

        Matrix.setLookAtM(
            viewMatrix, 0,
            eyeX, eyeY, eyeZ,
            (targetX + panX).toFloat(), (targetY + panY).toFloat(), targetZ.toFloat(),
            0f, 0f, 1f  // Z-up
        )
    }

    fun updateProjectionMatrix(width: Int, height: Int) {
        val aspect = width.toFloat() / height.toFloat()
        val near = (distance * 0.05).coerceAtLeast(1.0).toFloat()
        val far = (distance * 5.0).toFloat()
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
