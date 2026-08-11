package com.u1.slicer.slice

data class SliceCapabilityProfile(
    val beta: Boolean,
    val supportsUpload: Boolean,
    val supportsStart: Boolean,
    val supportsAmsMapping: Boolean,
    val supportsImportedProcessProfiles: Boolean,
    val supportsColorMix: Boolean,
    val supportsTopSurfaceMixModes: Boolean,
)

fun capabilityProfileFor(target: SlicerTarget): SliceCapabilityProfile =
    when (target) {
        SlicerTarget.SnapmakerU1 -> SliceCapabilityProfile(
            beta = false,
            supportsUpload = true,
            supportsStart = true,
            supportsAmsMapping = false,
            supportsImportedProcessProfiles = true,
            supportsColorMix = true,
            supportsTopSurfaceMixModes = true,
        )
        else -> SliceCapabilityProfile(
            beta = true,
            supportsUpload = true,
            supportsStart = true,
            supportsAmsMapping = true,
            supportsImportedProcessProfiles = false,
            supportsColorMix = false,
            supportsTopSurfaceMixModes = false,
        )
    }
