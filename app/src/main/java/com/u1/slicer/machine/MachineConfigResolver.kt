package com.u1.slicer.machine

import com.u1.slicer.bambu.resolveTargetedSliceConfig
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.SlicerTarget
import com.u1.slicer.wipeTowerClampBounds

/**
 * Applies a resolved machine profile to the generic slice configuration path.
 *
 * Bambu targets continue to use [com.u1.slicer.bambu.resolveTargetedSliceConfig]; this
 * resolver is for Snapmaker and non-Bambu machine families only.
 */
fun resolveMachineSliceConfig(profile: MachineProfile, base: SliceConfig): SliceConfig {
    val (maxWipeTowerX, maxWipeTowerY) = wipeTowerClampBounds(
        bedSizeX = profile.bedSizeX,
        bedSizeY = profile.bedSizeY,
        towerWidth = base.wipeTowerWidth,
        towerDepth = base.wipeTowerWidth,
    )
    return base.copy(
        bedSizeX = profile.bedSizeX,
        bedSizeY = profile.bedSizeY,
        maxPrintHeight = profile.maxPrintHeight,
        machineStartGcode = profile.machineStartGcode,
        machineEndGcode = profile.machineEndGcode,
        machineChangeFilamentGcode = profile.machineChangeFilamentGcode,
        machineTarget = if (profile.family == MachineFamily.SNAPMAKER) {
            "SNAPMAKER_U1"
        } else {
            "GENERIC"
        },
        wipeTowerX = base.wipeTowerX.coerceIn(0f, maxWipeTowerX),
        wipeTowerY = base.wipeTowerY.coerceIn(0f, maxWipeTowerY),
    )
}

/**
 * Resolves the active printer into the native slice configuration.
 *
 * Generic printers share [SlicerTarget.SnapmakerU1] as the app's non-Bambu route, so the
 * selected [MachineProfile] must decide the actual machine geometry and G-code policy.
 */
fun resolveActiveMachineSliceConfig(
    target: SlicerTarget,
    profile: MachineProfile,
    base: SliceConfig,
): SliceConfig = if (target == SlicerTarget.SnapmakerU1) {
    resolveMachineSliceConfig(profile, base)
} else {
    resolveTargetedSliceConfig(target, base)
}

/** Machine templates passed through JNI only when a raw model has no embedded profile. */
fun machineGcodeForSlice(
    target: SlicerTarget,
    profile: MachineProfile,
    hasEmbeddedProfile: Boolean,
): Pair<String, String> = if (target == SlicerTarget.SnapmakerU1 && !hasEmbeddedProfile) {
    profile.machineStartGcode to profile.machineEndGcode
} else {
    "" to ""
}
