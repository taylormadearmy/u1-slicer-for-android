package com.u1.slicer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.PlateType
import com.u1.slicer.data.SlicingOverrides

/**
 * Shared slicing overrides UI components used by both SettingsScreen and PrepareScreen (ConfigCard).
 * All 5 accordion sections: Layer & Infill, Support, Prime Tower, Temperature, Other.
 */

@Composable
fun SlicingOverridesAccordion(
    overrides: SlicingOverrides,
    onOverridesChange: (SlicingOverrides) -> Unit,
    defaultExpandedSection: String? = null,
    plateType: PlateType? = null,
    onPlateTypeChange: ((PlateType) -> Unit)? = null,
    bedTemp: Int? = null,
    onBedTempChange: ((Int) -> Unit)? = null,
    sourceConfig: Map<String, Any>? = null,
    // Phase 2 §4 Step 7 — file-filament count drives the support-filament
    // dropdown's option range. OrcaSlicer's `support_filament` /
    // `support_interface_filament` config keys are 1-based indices into the
    // filament_colour array (file-filament space), not slot indices. When
    // null, falls back to the default 4-slot range for backwards-compat.
    filamentCount: Int? = null,
) {
    var expandedSection by remember { mutableStateOf<String?>(defaultExpandedSection) }

    ExpandableOverrideSection(
        title = "Layer & Infill",
        icon = Icons.Default.Layers,
        expanded = expandedSection == "layer",
        onToggle = { expandedSection = if (expandedSection == "layer") null else "layer" }
    ) {
        OverrideRow(
            label = "Layer Height",
            override = overrides.layerHeight,
            defaultHint = "0.2 mm",
            onModeChange = { mode -> onOverridesChange(overrides.copy(layerHeight = overrides.layerHeight.copy(mode = mode))) },
            fileKey = "layer_height",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideFloatField(
                    value = overrides.layerHeight.value ?: 0.2f,
                    suffix = "mm",
                    onValueChange = { onOverridesChange(overrides.copy(layerHeight = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Infill Density",
            override = overrides.infillDensity,
            defaultHint = "15%",
            onModeChange = { mode -> onOverridesChange(overrides.copy(infillDensity = overrides.infillDensity.copy(mode = mode))) },
            fileKey = "sparse_infill_density",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideFloatField(
                    value = (overrides.infillDensity.value ?: 0.15f) * 100f,
                    suffix = "%",
                    onValueChange = { onOverridesChange(overrides.copy(infillDensity = OverrideValue(OverrideMode.OVERRIDE, it / 100f))) }
                )
            }
        )

        OverrideRow(
            label = "Wall Count",
            override = overrides.wallCount,
            defaultHint = "2",
            onModeChange = { mode -> onOverridesChange(overrides.copy(wallCount = overrides.wallCount.copy(mode = mode))) },
            fileKey = "wall_loops",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideIntField(
                    value = overrides.wallCount.value ?: 2,
                    onValueChange = { onOverridesChange(overrides.copy(wallCount = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        val wallGenerators = listOf("classic" to "Classic", "arachne" to "Arachne")
        OverrideRow(
            label = "Wall Generator",
            override = overrides.wallGenerator,
            defaultHint = "Arachne",
            onModeChange = { mode -> onOverridesChange(overrides.copy(wallGenerator = overrides.wallGenerator.copy(mode = mode))) },
            fileKey = "wall_generator",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideDropdown(
                    value = overrides.wallGenerator.value ?: "arachne",
                    options = wallGenerators.map { it.first },
                    labels = wallGenerators.map { it.second },
                    onValueChange = { onOverridesChange(overrides.copy(wallGenerator = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        val seamPositions = listOf(
            "nearest" to "Nearest",
            "aligned" to "Aligned",
            "aligned_back" to "Aligned Back",
            "back" to "Back",
            "random" to "Random"
        )
        OverrideRow(
            label = "Seam Position",
            override = overrides.seamPosition,
            defaultHint = "Aligned",
            onModeChange = { mode -> onOverridesChange(overrides.copy(seamPosition = overrides.seamPosition.copy(mode = mode))) },
            fileKey = "seam_position",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideDropdown(
                    value = overrides.seamPosition.value ?: "aligned",
                    options = seamPositions.map { it.first },
                    labels = seamPositions.map { it.second },
                    onValueChange = { onOverridesChange(overrides.copy(seamPosition = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Infill Pattern",
            override = overrides.infillPattern,
            defaultHint = "gyroid",
            onModeChange = { mode -> onOverridesChange(overrides.copy(infillPattern = overrides.infillPattern.copy(mode = mode))) },
            fileKey = "sparse_infill_pattern",
            sourceConfig = sourceConfig,
            valueContent = {
                val patterns = listOf("gyroid", "grid", "lines", "honeycomb", "cubic", "triangles", "rectilinear", "adaptive_cubic", "lightning")
                OverrideDropdown(
                    value = overrides.infillPattern.value ?: "gyroid",
                    options = patterns,
                    onValueChange = { onOverridesChange(overrides.copy(infillPattern = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Infill Speed",
            override = overrides.sparseInfillSpeed,
            defaultHint = "auto",
            onModeChange = { mode -> onOverridesChange(overrides.copy(sparseInfillSpeed = overrides.sparseInfillSpeed.copy(mode = mode))) },
            fileKey = "sparse_infill_speed",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideIntField(
                    value = overrides.sparseInfillSpeed.value ?: 0,
                    suffix = "mm/s",
                    onValueChange = { onOverridesChange(overrides.copy(sparseInfillSpeed = OverrideValue(OverrideMode.OVERRIDE, it.coerceAtLeast(0)))) }
                )
            }
        )

        OverrideRow(
            label = "Top Shell Layers",
            override = overrides.topShellLayers,
            defaultHint = "5",
            onModeChange = { mode -> onOverridesChange(overrides.copy(topShellLayers = overrides.topShellLayers.copy(mode = mode))) },
            fileKey = "top_shell_layers",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideIntField(
                    value = overrides.topShellLayers.value ?: 5,
                    onValueChange = { onOverridesChange(overrides.copy(topShellLayers = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0, 20)))) }
                )
            }
        )

        OverrideRow(
            label = "Bottom Shell Layers",
            override = overrides.bottomShellLayers,
            defaultHint = "4",
            onModeChange = { mode -> onOverridesChange(overrides.copy(bottomShellLayers = overrides.bottomShellLayers.copy(mode = mode))) },
            fileKey = "bottom_shell_layers",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideIntField(
                    value = overrides.bottomShellLayers.value ?: 4,
                    onValueChange = { onOverridesChange(overrides.copy(bottomShellLayers = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0, 20)))) }
                )
            }
        )

        val surfacePatterns = listOf(
            "monotonic" to "Monotonic",
            "monotonicaline" to "Monotonic Line",
            "alignedrectilinear" to "Aligned Rectilinear",
            "concentric" to "Concentric",
            "hilbertcurve" to "Hilbert Curve",
            "archimedeanChords" to "Archimedean Chords",
            "octagramspiral" to "Octagram Spiral"
        )
        OverrideRow(
            label = "Top Surface Pattern",
            override = overrides.topSurfacePattern,
            defaultHint = "Monotonic",
            onModeChange = { mode -> onOverridesChange(overrides.copy(topSurfacePattern = overrides.topSurfacePattern.copy(mode = mode))) },
            fileKey = "top_surface_pattern",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideDropdown(
                    value = overrides.topSurfacePattern.value ?: "monotonic",
                    options = surfacePatterns.map { it.first },
                    labels = surfacePatterns.map { it.second },
                    onValueChange = { onOverridesChange(overrides.copy(topSurfacePattern = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Bottom Surface Pattern",
            override = overrides.bottomSurfacePattern,
            defaultHint = "Monotonic",
            onModeChange = { mode -> onOverridesChange(overrides.copy(bottomSurfacePattern = overrides.bottomSurfacePattern.copy(mode = mode))) },
            fileKey = "bottom_surface_pattern",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideDropdown(
                    value = overrides.bottomSurfacePattern.value ?: "monotonic",
                    options = surfacePatterns.map { it.first },
                    labels = surfacePatterns.map { it.second },
                    onValueChange = { onOverridesChange(overrides.copy(bottomSurfacePattern = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Reduce Infill Retraction",
            override = overrides.reduceInfillRetraction,
            defaultHint = "Off",
            onModeChange = { mode -> onOverridesChange(overrides.copy(reduceInfillRetraction = overrides.reduceInfillRetraction.copy(mode = mode))) },
            fileKey = "reduce_infill_retraction",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideToggle(
                    value = overrides.reduceInfillRetraction.value ?: false,
                    onValueChange = { onOverridesChange(overrides.copy(reduceInfillRetraction = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )
    }

    ExpandableOverrideSection(
        title = "Support",
        icon = Icons.Default.Architecture,
        expanded = expandedSection == "support",
        onToggle = { expandedSection = if (expandedSection == "support") null else "support" }
    ) {
        OverrideRow(
            label = "Supports",
            override = overrides.supports,
            defaultHint = "Off",
            onModeChange = { mode -> onOverridesChange(overrides.copy(supports = overrides.supports.copy(mode = mode))) },
            fileKey = "enable_support",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideToggle(
                    value = overrides.supports.value ?: false,
                    onValueChange = { onOverridesChange(overrides.copy(supports = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        if (overrides.supports.mode == OverrideMode.OVERRIDE && overrides.supports.value == true) {
            OverrideRow(
                label = "  Support Type",
                override = overrides.supportType,
                defaultHint = "Normal (Auto)",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportType = overrides.supportType.copy(mode = mode))) },
                fileKey = "support_type",
                sourceConfig = sourceConfig,
                valueContent = {
                    val types = listOf("normal(auto)" to "Normal", "tree(auto)" to "Tree")
                    OverrideDropdown(
                        value = overrides.supportType.value ?: "normal(auto)",
                        options = types.map { it.first },
                        labels = types.map { it.second },
                        onValueChange = { onOverridesChange(overrides.copy(supportType = OverrideValue(OverrideMode.OVERRIDE, it))) }
                    )
                }
            )

            OverrideRow(
                label = "  Threshold Angle",
                override = overrides.supportAngle,
                defaultHint = "30\u00B0",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportAngle = overrides.supportAngle.copy(mode = mode))) },
                fileKey = "support_threshold_angle",
                sourceConfig = sourceConfig,
                valueContent = {
                    OverrideIntField(
                        value = overrides.supportAngle.value ?: 30,
                        suffix = "\u00B0",
                        onValueChange = { onOverridesChange(overrides.copy(supportAngle = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0, 90)))) }
                    )
                }
            )

            OverrideRow(
                label = "  Build Plate Only",
                override = overrides.supportBuildPlateOnly,
                defaultHint = "Off",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportBuildPlateOnly = overrides.supportBuildPlateOnly.copy(mode = mode))) },
                fileKey = "support_on_build_plate_only",
                sourceConfig = sourceConfig,
                valueContent = {
                    OverrideToggle(
                        value = overrides.supportBuildPlateOnly.value ?: false,
                        onValueChange = { onOverridesChange(overrides.copy(supportBuildPlateOnly = OverrideValue(OverrideMode.OVERRIDE, it))) }
                    )
                }
            )

            OverrideRow(
                label = "  Support Pattern",
                override = overrides.supportPattern,
                defaultHint = "Default",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportPattern = overrides.supportPattern.copy(mode = mode))) },
                fileKey = "support_base_pattern",
                sourceConfig = sourceConfig,
                valueContent = {
                    val patterns = listOf("default", "rectilinear", "rectilinear_grid", "honeycomb", "lightning")
                    OverrideDropdown(
                        value = overrides.supportPattern.value ?: "default",
                        options = patterns,
                        onValueChange = { onOverridesChange(overrides.copy(supportPattern = OverrideValue(OverrideMode.OVERRIDE, it))) }
                    )
                }
            )

            OverrideRow(
                label = "  Pattern Spacing",
                override = overrides.supportPatternSpacing,
                defaultHint = "2.5 mm",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportPatternSpacing = overrides.supportPatternSpacing.copy(mode = mode))) },
                fileKey = "support_base_pattern_spacing",
                sourceConfig = sourceConfig,
                valueContent = {
                    OverrideFloatField(
                        value = overrides.supportPatternSpacing.value ?: 2.5f,
                        suffix = "mm",
                        onValueChange = { onOverridesChange(overrides.copy(supportPatternSpacing = OverrideValue(OverrideMode.OVERRIDE, it))) }
                    )
                }
            )

            OverrideRow(
                label = "  Interface Top Layers",
                override = overrides.supportInterfaceTopLayers,
                defaultHint = "3",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportInterfaceTopLayers = overrides.supportInterfaceTopLayers.copy(mode = mode))) },
                fileKey = "support_interface_top_layers",
                sourceConfig = sourceConfig,
                valueContent = {
                    OverrideIntField(
                        value = overrides.supportInterfaceTopLayers.value ?: 3,
                        onValueChange = { onOverridesChange(overrides.copy(supportInterfaceTopLayers = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0, 10)))) }
                    )
                }
            )

            OverrideRow(
                label = "  Interface Bottom Layers",
                override = overrides.supportInterfaceBottomLayers,
                defaultHint = "0",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportInterfaceBottomLayers = overrides.supportInterfaceBottomLayers.copy(mode = mode))) },
                fileKey = "support_interface_bottom_layers",
                sourceConfig = sourceConfig,
                valueContent = {
                    OverrideIntField(
                        value = overrides.supportInterfaceBottomLayers.value ?: 0,
                        onValueChange = { onOverridesChange(overrides.copy(supportInterfaceBottomLayers = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0, 10)))) }
                    )
                }
            )

            // Phase 2 §4 Step 7 — `support_filament` and
            // `support_interface_filament` are 1-based indices into the file's
            // canonical filament list (NOT physical slot indices). The slicer
            // reads them as "use file-filament N to print supports / interface".
            // For multi-filament files, the dropdown shows up to filamentCount
            // entries; falls back to 1..4 (slot count) when filamentCount is
            // null (settings screen with no model loaded).
            val filamentOptionsRange = (1..(filamentCount ?: 4))
            OverrideRow(
                label = "  Support Filament",
                override = overrides.supportFilament,
                defaultHint = "Default",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportFilament = overrides.supportFilament.copy(mode = mode))) },
                fileKey = "support_filament",
                sourceConfig = sourceConfig,
                valueContent = {
                    val options = listOf(0 to "Default") + filamentOptionsRange.map { it to "Filament $it" }
                    OverrideDropdown(
                        value = (overrides.supportFilament.value ?: 0).toString(),
                        options = options.map { it.first.toString() },
                        labels = options.map { it.second },
                        onValueChange = { onOverridesChange(overrides.copy(supportFilament = OverrideValue(OverrideMode.OVERRIDE, it.toIntOrNull() ?: 0))) }
                    )
                }
            )

            OverrideRow(
                label = "  Interface Filament",
                override = overrides.supportInterfaceFilament,
                defaultHint = "Default",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportInterfaceFilament = overrides.supportInterfaceFilament.copy(mode = mode))) },
                fileKey = "support_interface_filament",
                sourceConfig = sourceConfig,
                valueContent = {
                    val options = listOf(0 to "Default") + filamentOptionsRange.map { it to "Filament $it" }
                    OverrideDropdown(
                        value = (overrides.supportInterfaceFilament.value ?: 0).toString(),
                        options = options.map { it.first.toString() },
                        labels = options.map { it.second },
                        onValueChange = { onOverridesChange(overrides.copy(supportInterfaceFilament = OverrideValue(OverrideMode.OVERRIDE, it.toIntOrNull() ?: 0))) }
                    )
                }
            )

            OverrideRow(
                label = "  XY Distance",
                override = overrides.supportXyDistance,
                defaultHint = "0.35 mm",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportXyDistance = overrides.supportXyDistance.copy(mode = mode))) },
                fileKey = "support_object_xy_distance",
                sourceConfig = sourceConfig,
                valueContent = {
                    OverrideFloatField(
                        value = overrides.supportXyDistance.value ?: 0.35f,
                        suffix = "mm",
                        onValueChange = { onOverridesChange(overrides.copy(supportXyDistance = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0f, 5f)))) }
                    )
                }
            )

            OverrideRow(
                label = "  Interface Pattern",
                override = overrides.supportInterfacePattern,
                defaultHint = "Auto",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportInterfacePattern = overrides.supportInterfacePattern.copy(mode = mode))) },
                fileKey = "support_interface_pattern",
                sourceConfig = sourceConfig,
                valueContent = {
                    val patterns = listOf("auto" to "Auto", "rectilinear" to "Rectilinear",
                        "concentric" to "Concentric", "rectilinear_grid" to "Rectilinear Grid")
                    OverrideDropdown(
                        value = overrides.supportInterfacePattern.value ?: "auto",
                        options = patterns.map { it.first },
                        labels = patterns.map { it.second },
                        onValueChange = { onOverridesChange(overrides.copy(supportInterfacePattern = OverrideValue(OverrideMode.OVERRIDE, it))) }
                    )
                }
            )

            OverrideRow(
                label = "  Interface Spacing",
                override = overrides.supportInterfaceSpacing,
                defaultHint = "0.5 mm",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportInterfaceSpacing = overrides.supportInterfaceSpacing.copy(mode = mode))) },
                fileKey = "support_interface_spacing",
                sourceConfig = sourceConfig,
                valueContent = {
                    OverrideFloatField(
                        value = overrides.supportInterfaceSpacing.value ?: 0.5f,
                        suffix = "mm",
                        onValueChange = { onOverridesChange(overrides.copy(supportInterfaceSpacing = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0f, 5f)))) }
                    )
                }
            )

            OverrideRow(
                label = "  Support Speed",
                override = overrides.supportSpeed,
                defaultHint = "auto",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportSpeed = overrides.supportSpeed.copy(mode = mode))) },
                fileKey = "support_speed",
                sourceConfig = sourceConfig,
                valueContent = {
                    OverrideIntField(
                        value = overrides.supportSpeed.value ?: 0,
                        suffix = "mm/s",
                        onValueChange = { onOverridesChange(overrides.copy(supportSpeed = OverrideValue(OverrideMode.OVERRIDE, it.coerceAtLeast(0)))) }
                    )
                }
            )

            // Tree support parameters — only shown when tree support type is selected
            val isTree = (overrides.supportType.value ?: "normal(auto)").startsWith("tree")
            if (isTree) {
                OverrideRow(
                    label = "  Branch Angle",
                    override = overrides.treeSupportBranchAngle,
                    defaultHint = "40\u00B0",
                    onModeChange = { mode -> onOverridesChange(overrides.copy(treeSupportBranchAngle = overrides.treeSupportBranchAngle.copy(mode = mode))) },
                    fileKey = "tree_support_branch_angle",
                    sourceConfig = sourceConfig,
                    valueContent = {
                        OverrideIntField(
                            value = overrides.treeSupportBranchAngle.value ?: 40,
                            suffix = "\u00B0",
                            onValueChange = { onOverridesChange(overrides.copy(treeSupportBranchAngle = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0, 60)))) }
                        )
                    }
                )

                OverrideRow(
                    label = "  Branch Distance",
                    override = overrides.treeSupportBranchDistance,
                    defaultHint = "5.0 mm",
                    onModeChange = { mode -> onOverridesChange(overrides.copy(treeSupportBranchDistance = overrides.treeSupportBranchDistance.copy(mode = mode))) },
                    fileKey = "tree_support_branch_distance",
                    sourceConfig = sourceConfig,
                    valueContent = {
                        OverrideFloatField(
                            value = overrides.treeSupportBranchDistance.value ?: 5.0f,
                            suffix = "mm",
                            onValueChange = { onOverridesChange(overrides.copy(treeSupportBranchDistance = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0.5f, 20f)))) }
                        )
                    }
                )

                OverrideRow(
                    label = "  Branch Diameter",
                    override = overrides.treeSupportBranchDiameter,
                    defaultHint = "5.0 mm",
                    onModeChange = { mode -> onOverridesChange(overrides.copy(treeSupportBranchDiameter = overrides.treeSupportBranchDiameter.copy(mode = mode))) },
                    fileKey = "tree_support_branch_diameter",
                    sourceConfig = sourceConfig,
                    valueContent = {
                        OverrideFloatField(
                            value = overrides.treeSupportBranchDiameter.value ?: 5.0f,
                            suffix = "mm",
                            onValueChange = { onOverridesChange(overrides.copy(treeSupportBranchDiameter = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0.5f, 20f)))) }
                        )
                    }
                )
            }
        }
    }

    ExpandableOverrideSection(
        title = "Prime Tower",
        icon = Icons.Default.ViewInAr,
        expanded = expandedSection == "tower",
        onToggle = { expandedSection = if (expandedSection == "tower") null else "tower" }
    ) {
        OverrideRow(
            label = "Prime Tower",
            override = overrides.primeTower,
            defaultHint = "Off",
            onModeChange = { mode -> onOverridesChange(overrides.copy(primeTower = overrides.primeTower.copy(mode = mode))) },
            fileKey = "enable_prime_tower",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideToggle(
                    value = overrides.primeTower.value ?: false,
                    onValueChange = { onOverridesChange(overrides.copy(primeTower = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Prime Volume",
            override = overrides.primeVolume,
            defaultHint = "45",
            onModeChange = { mode -> onOverridesChange(overrides.copy(primeVolume = overrides.primeVolume.copy(mode = mode))) },
            fileKey = "prime_volume",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideIntField(
                    value = overrides.primeVolume.value ?: 45,
                    onValueChange = { onOverridesChange(overrides.copy(primeVolume = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Tower Brim Width",
            override = overrides.primeTowerBrimWidth,
            defaultHint = "3 mm",
            onModeChange = { mode -> onOverridesChange(overrides.copy(primeTowerBrimWidth = overrides.primeTowerBrimWidth.copy(mode = mode))) },
            fileKey = "prime_tower_brim_width",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideFloatField(
                    value = overrides.primeTowerBrimWidth.value ?: 3f,
                    suffix = "mm",
                    onValueChange = { onOverridesChange(overrides.copy(primeTowerBrimWidth = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Brim Chamfer",
            override = overrides.primeTowerBrimChamfer,
            defaultHint = "On",
            onModeChange = { mode -> onOverridesChange(overrides.copy(primeTowerBrimChamfer = overrides.primeTowerBrimChamfer.copy(mode = mode))) },
            fileKey = "prime_tower_brim_chamfer",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideToggle(
                    value = overrides.primeTowerBrimChamfer.value ?: true,
                    onValueChange = { onOverridesChange(overrides.copy(primeTowerBrimChamfer = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Chamfer Max Width",
            override = overrides.primeTowerChamferMaxWidth,
            defaultHint = "5 mm",
            onModeChange = { mode -> onOverridesChange(overrides.copy(primeTowerChamferMaxWidth = overrides.primeTowerChamferMaxWidth.copy(mode = mode))) },
            fileKey = "prime_tower_brim_chamfer_max_width",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideFloatField(
                    value = overrides.primeTowerChamferMaxWidth.value ?: 5f,
                    suffix = "mm",
                    onValueChange = { onOverridesChange(overrides.copy(primeTowerChamferMaxWidth = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Tower Width",
            override = overrides.primeTowerWidth,
            defaultHint = "35 mm",
            onModeChange = { mode -> onOverridesChange(overrides.copy(primeTowerWidth = overrides.primeTowerWidth.copy(mode = mode))) },
            fileKey = "prime_tower_width",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideFloatField(
                    value = overrides.primeTowerWidth.value ?: 35f,
                    suffix = "mm",
                    onValueChange = { onOverridesChange(overrides.copy(primeTowerWidth = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Tower Rotation",
            override = overrides.wipeTowerRotationAngle,
            defaultHint = "0°",
            onModeChange = { mode -> onOverridesChange(overrides.copy(wipeTowerRotationAngle = overrides.wipeTowerRotationAngle.copy(mode = mode))) },
            fileKey = "wipe_tower_rotation_angle",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideFloatField(
                    value = overrides.wipeTowerRotationAngle.value ?: 0f,
                    suffix = "°",
                    onValueChange = { onOverridesChange(overrides.copy(wipeTowerRotationAngle = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )
    }

    ExpandableOverrideSection(
        title = "Temperature",
        icon = Icons.Default.Thermostat,
        expanded = expandedSection == "temp",
        onToggle = { expandedSection = if (expandedSection == "temp") null else "temp" }
    ) {
        if (plateType != null && onPlateTypeChange != null) {
            PlateTypeRow(selectedPlate = plateType, onPlateChange = onPlateTypeChange)
        }
        if (bedTemp != null && onBedTempChange != null) {
            // Simple editable bed temp field — value is set by plate type preset
            // and can be adjusted directly by the user.
            BedTempField(value = bedTemp, onValueChange = onBedTempChange)
        } else {
            // Fallback: 3-way override toggle (when plate type UI is not wired)
            OverrideRow(
                label = "Bed Temp",
                override = overrides.bedTemp,
                defaultHint = "60\u00B0C",
                onModeChange = { mode -> onOverridesChange(overrides.copy(bedTemp = overrides.bedTemp.copy(mode = mode))) },
                fileKey = "hot_plate_temp",
                sourceConfig = sourceConfig,
                valueContent = {
                    OverrideIntField(
                        value = overrides.bedTemp.value ?: 60,
                        suffix = "\u00B0C",
                        onValueChange = { onOverridesChange(overrides.copy(bedTemp = OverrideValue(OverrideMode.OVERRIDE, it))) }
                    )
                }
            )
        }
    }

    ExpandableOverrideSection(
        title = "Other",
        icon = Icons.Default.Tune,
        expanded = expandedSection == "other",
        onToggle = { expandedSection = if (expandedSection == "other") null else "other" }
    ) {
        OverrideRow(
            label = "Brim Width",
            override = overrides.brimWidth,
            defaultHint = "0 mm",
            onModeChange = { mode -> onOverridesChange(overrides.copy(brimWidth = overrides.brimWidth.copy(mode = mode))) },
            fileKey = "brim_width",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideFloatField(
                    value = overrides.brimWidth.value ?: 0f,
                    suffix = "mm",
                    onValueChange = { onOverridesChange(overrides.copy(brimWidth = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Skirt Loops",
            override = overrides.skirtLoops,
            defaultHint = "0",
            onModeChange = { mode -> onOverridesChange(overrides.copy(skirtLoops = overrides.skirtLoops.copy(mode = mode))) },
            fileKey = "skirt_loops",
            sourceConfig = sourceConfig,
            valueContent = {
                OverrideIntField(
                    value = overrides.skirtLoops.value ?: 0,
                    onValueChange = { onOverridesChange(overrides.copy(skirtLoops = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Flow Calibration", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = overrides.flowCalibration,
                onCheckedChange = { onOverridesChange(overrides.copy(flowCalibration = it)) }
            )
        }
    }
}

// ---- Reusable override UI components ----

@Composable
fun ExpandableOverrideSection(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    "Toggle"
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content
                )
            }
        }
    }
}

internal fun formatFileValue(value: Any): String = when (value) {
    is Boolean -> if (value) "on" else "off"
    is String -> when {
        value == "1" -> "on"
        value == "0" -> "off"
        value.endsWith("%") -> value
        value.contains("(") -> value.substringBefore("(").trim()
            .replaceFirstChar { it.uppercase() }
        else -> value.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
    is Number -> value.toString()
    is List<*> -> value.firstOrNull()?.let { formatFileValue(it) } ?: value.toString()
    else -> value.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> OverrideRow(
    label: String,
    override: OverrideValue<T>,
    defaultHint: String,
    onModeChange: (OverrideMode) -> Unit,
    fileKey: String? = null,
    sourceConfig: Map<String, Any>? = null,
    valueContent: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (override.mode == OverrideMode.ORCA_DEFAULT) {
                Text(
                    defaultHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        val fileValue = fileKey?.let { key -> sourceConfig?.get(key)?.let { formatFileValue(it) } }
        if (fileValue != null) {
            Text(
                "File: $fileValue",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }

        val modes = OverrideMode.entries
        val modeLabels = listOf("File", "Orca", "Override")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = override.mode == mode,
                    onClick = { onModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size)
                ) {
                    Text(modeLabels[index], style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (override.mode == OverrideMode.OVERRIDE) {
            valueContent()
        }
    }
}

@Composable
fun OverrideFloatField(
    value: Float,
    suffix: String = "",
    onValueChange: (Float) -> Unit
) {
    var text by remember(value) { mutableStateOf(if (value == value.toInt().toFloat()) value.toInt().toString() else "%.2f".format(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toFloatOrNull()?.let { f -> onValueChange(f) }
        },
        suffix = if (suffix.isNotEmpty()) {{ Text(suffix) }} else null,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true
    )
}

@Composable
fun OverrideIntField(
    value: Int,
    suffix: String = "",
    onValueChange: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let { i -> onValueChange(i) }
        },
        suffix = if (suffix.isNotEmpty()) {{ Text(suffix) }} else null,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverrideDropdown(
    value: String,
    options: List<String>,
    labels: List<String> = options,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = labels.getOrElse(options.indexOf(value)) { value }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { idx, option ->
                DropdownMenuItem(
                    text = { Text(labels.getOrElse(idx) { option }) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Plate type selector row: four chips for Textured PEI, Smooth PEI, Cool Plate, Engineering Plate.
 * Selecting a plate updates the bed temperature preset automatically via [onPlateChange].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlateTypeRow(
    selectedPlate: PlateType,
    onPlateChange: (PlateType) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Plate Type", style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            PlateType.entries.forEachIndexed { index, plate ->
                SegmentedButton(
                    selected = selectedPlate == plate,
                    onClick = { onPlateChange(plate) },
                    shape = SegmentedButtonDefaults.itemShape(index, PlateType.entries.size)
                ) {
                    Text(
                        when (plate) {
                            PlateType.TEXTURED_PEI -> "Textured"
                            PlateType.SMOOTH_PEI -> "Smooth"
                            PlateType.COOL_PLATE -> "Cool"
                            PlateType.ENGINEERING_PLATE -> "Eng."
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/**
 * Simple bed temp field: shows the current value (set by plate type preset) and lets the
 * user adjust it directly.  No File/Orca/Override toggle — just a number you can change.
 */
@Composable
fun BedTempField(value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let { temp -> onValueChange(temp) }
        },
        label = { Text("Bed Temp") },
        suffix = { Text("\u00B0C") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}

@Composable
fun OverrideToggle(value: Boolean, onValueChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (value) "Enabled" else "Disabled",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = value, onCheckedChange = onValueChange)
    }
}
