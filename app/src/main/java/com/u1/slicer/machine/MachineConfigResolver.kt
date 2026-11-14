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
    if (profile.family == MachineFamily.GENERIC) {
        val snapshot = com.u1.slicer.slice.GenericMachineProfile.fromPersistedJson(profile.machineJson)
        return snapshot.applyTo(base, if (snapshot.gcodeFlavor == "klipper") SlicerTarget.GenericKlipper else SlicerTarget.GenericMarlin)
    }
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
 * Generic targets use the immutable selected snapshot. Catalogue metadata cannot
 * override its geometry or machine commands.
 */
fun resolveActiveMachineSliceConfig(
    target: SlicerTarget,
    profile: MachineProfile,
    base: SliceConfig,
    machineProfile: com.u1.slicer.slice.GenericMachineProfile? = null,
): SliceConfig = if (target == SlicerTarget.SnapmakerU1) {
    resolveMachineSliceConfig(profile, base)
} else {
    resolveTargetedSliceConfig(target, base, machineProfile ?: if (target == SlicerTarget.ElegooCentauriCarbon) {
        com.u1.slicer.slice.GenericMachineProfile.centauriCarbon()
    } else if (target.family == com.u1.slicer.slice.SliceTargetFamily.GENERIC) {
        com.u1.slicer.slice.GenericMachineProfile.fromJson(profile.machineJson)
    } else null)
}

/** Generic machine templates always belong to the selected machine, not an embedded source. */
fun machineGcodeForSlice(
    target: SlicerTarget,
    profile: MachineProfile,
    hasEmbeddedProfile: Boolean,
): Pair<String, String> = if (profile.family == MachineFamily.GENERIC ||
    target == SlicerTarget.SnapmakerU1 && !hasEmbeddedProfile) {
    profile.machineStartGcode to profile.machineEndGcode
} else {
    "" to ""
}
