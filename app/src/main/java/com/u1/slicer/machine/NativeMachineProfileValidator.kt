package com.u1.slicer.machine

import android.content.Context
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeValidator
import com.u1.slicer.gcode.gcodeDialect
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Slices a one-layer calibration solid before an external machine profile can be saved. */
class NativeMachineProfileValidator(context: Context) {
    private val appContext = context.applicationContext

    suspend fun validate(profile: MachineProfile): MachineProfileValidationResult = withContext(Dispatchers.IO) {
        if (!NativeLibrary.isLoaded) {
            return@withContext MachineProfileValidationResult.failure("Native slicer is unavailable.")
        }
        NativeLibrary.previewMutex.withLock {
            val native = NativeLibrary()
            try {
                native.clearModel()
                val model = validationModel()
                if (!native.loadModel(model.absolutePath)) {
                    return@withLock MachineProfileValidationResult.failure(
                        "Could not load the profile validation model.",
                    )
                }
                val info = native.getModelInfo()
                    ?: return@withLock MachineProfileValidationResult.failure(
                        "Could not inspect the profile validation model.",
                    )
                val lowerLeftX = (profile.bedSizeX - info.sizeX) / 2f
                val lowerLeftY = (profile.bedSizeY - info.sizeY) / 2f
                if (!native.setModelInstances(floatArrayOf(lowerLeftX, lowerLeftY))) {
                    return@withLock MachineProfileValidationResult.failure(
                        "Could not place the profile validation model on the print bed.",
                    )
                }
                val result = native.slice(validationConfig(profile))
                    ?: return@withLock MachineProfileValidationResult.failure(
                        "Native slicer returned no validation result.",
                    )
                if (!result.success) {
                    return@withLock MachineProfileValidationResult.failure(
                        result.errorMessage.ifBlank { "Native profile validation failed." },
                    )
                }
                val gcodeFile = File(result.gcodePath)
                if (!gcodeFile.isFile || gcodeFile.length() == 0L) {
                    return@withLock MachineProfileValidationResult.failure(
                        "Profile validation produced no G-code.",
                    )
                }
                val gcode = gcodeFile.readText()
                val dialect = GcodeValidator.validateDialect(gcode, profile.gcodeDialect())
                if (!dialect.isValid) {
                    return@withLock MachineProfileValidationResult.failure(dialect.describe())
                }
                val bounds = GcodeValidator.checkBedBounds(
                    gcode = gcode,
                    bedMaxX = profile.bedSizeX + PROFILE_EXTRUSION_MARGIN_MM,
                    bedMaxY = profile.bedSizeY + PROFILE_EXTRUSION_MARGIN_MM,
                    bedMinX = -PROFILE_EXTRUSION_MARGIN_MM,
                    bedMinY = -PROFILE_EXTRUSION_MARGIN_MM,
                    nonExtrudingTravelMarginMm = PROFILE_TRAVEL_MARGIN_MM,
                )
                if (!bounds.withinBounds) {
                    return@withLock MachineProfileValidationResult.failure(
                        "Generated G-code exceeds the ${profile.bedSizeX.toInt()} x " +
                            "${profile.bedSizeY.toInt()} mm print bed: " +
                            bounds.violatingLines.joinToString(),
                    )
                }
                MachineProfileValidationResult.success()
            } catch (error: Exception) {
                MachineProfileValidationResult.failure(error.message ?: "Native profile validation failed.")
            } finally {
                native.clearModel()
            }
        }
    }

    private fun validationModel(): File {
        val file = File(appContext.cacheDir, "machine-profile-validation-10x10x0.2.stl")
        if (!file.isFile || file.length() == 0L) {
            appContext.assets.open(VALIDATION_MODEL_ASSET).use { input ->
                file.outputStream().use(input::copyTo)
            }
        }
        return file
    }

    private fun validationConfig(profile: MachineProfile): SliceConfig = resolveMachineSliceConfig(
        profile,
        SliceConfig(
            layerHeight = 0.2f,
            firstLayerHeight = 0.2f,
            perimeters = 1,
            topSolidLayers = 1,
            bottomSolidLayers = 1,
            fillDensity = 0f,
            printSpeed = 40f,
            travelSpeed = 100f,
            firstLayerSpeed = 20f,
            nozzleTemp = 210,
            bedTemp = 60,
            nozzleDiameter = profile.nozzleDiameter,
            extruderCount = profile.extruderCount,
            extruderTemps = IntArray(profile.extruderCount) { 210 },
            wipeTowerEnabled = false,
        ),
    )

    companion object {
        const val VALIDATION_MODEL_ASSET = "machine/profile-validation-cube.stl"
        // Vendor start G-code may prime just outside the printable polygon.
        private const val PROFILE_EXTRUSION_MARGIN_MM = 5.0
        // CORE One parks at Y=-9; travel gets 5 mm beyond the extrusion envelope.
        private const val PROFILE_TRAVEL_MARGIN_MM = 5.0
    }
}
