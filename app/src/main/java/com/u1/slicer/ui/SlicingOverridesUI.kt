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
import com.u1.slicer.data.SlicingOverrides

/**
 * Shared slicing overrides UI components used by both SettingsScreen and PrepareScreen (ConfigCard).
 * All 5 accordion sections: Layer & Infill, Support, Prime Tower, Temperature, Other.
 */

@Composable
fun SlicingOverridesAccordion(
    overrides: SlicingOverrides,
    onOverridesChange: (SlicingOverrides) -> Unit
) {
    var expandedSection by remember { mutableStateOf<String?>(null) }

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
            valueContent = {
                OverrideIntField(
                    value = overrides.wallCount.value ?: 2,
                    onValueChange = { onOverridesChange(overrides.copy(wallCount = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )

        OverrideRow(
            label = "Infill Pattern",
            override = overrides.infillPattern,
            defaultHint = "gyroid",
            onModeChange = { mode -> onOverridesChange(overrides.copy(infillPattern = overrides.infillPattern.copy(mode = mode))) },
            valueContent = {
                val patterns = listOf("gyroid", "grid", "lines", "honeycomb", "cubic", "triangles", "rectilinear")
                OverrideDropdown(
                    value = overrides.infillPattern.value ?: "gyroid",
                    options = patterns,
                    onValueChange = { onOverridesChange(overrides.copy(infillPattern = OverrideValue(OverrideMode.OVERRIDE, it))) }
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
                valueContent = {
                    OverrideIntField(
                        value = overrides.supportInterfaceBottomLayers.value ?: 0,
                        onValueChange = { onOverridesChange(overrides.copy(supportInterfaceBottomLayers = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0, 10)))) }
                    )
                }
            )

            OverrideRow(
                label = "  Support Extruder",
                override = overrides.supportFilament,
                defaultHint = "Default",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportFilament = overrides.supportFilament.copy(mode = mode))) },
                valueContent = {
                    val options = listOf(0 to "Default") + (1..4).map { it to "Extruder $it" }
                    OverrideDropdown(
                        value = (overrides.supportFilament.value ?: 0).toString(),
                        options = options.map { it.first.toString() },
                        labels = options.map { it.second },
                        onValueChange = { onOverridesChange(overrides.copy(supportFilament = OverrideValue(OverrideMode.OVERRIDE, it.toIntOrNull() ?: 0))) }
                    )
                }
            )

            OverrideRow(
                label = "  Interface Extruder",
                override = overrides.supportInterfaceFilament,
                defaultHint = "Default",
                onModeChange = { mode -> onOverridesChange(overrides.copy(supportInterfaceFilament = overrides.supportInterfaceFilament.copy(mode = mode))) },
                valueContent = {
                    val options = listOf(0 to "Default") + (1..4).map { it to "Extruder $it" }
                    OverrideDropdown(
                        value = (overrides.supportInterfaceFilament.value ?: 0).toString(),
                        options = options.map { it.first.toString() },
                        labels = options.map { it.second },
                        onValueChange = { onOverridesChange(overrides.copy(supportInterfaceFilament = OverrideValue(OverrideMode.OVERRIDE, it.toIntOrNull() ?: 0))) }
                    )
                }
            )
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
            valueContent = {
                OverrideFloatField(
                    value = overrides.primeTowerChamferMaxWidth.value ?: 5f,
                    suffix = "mm",
                    onValueChange = { onOverridesChange(overrides.copy(primeTowerChamferMaxWidth = OverrideValue(OverrideMode.OVERRIDE, it))) }
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
        OverrideRow(
            label = "Bed Temp",
            override = overrides.bedTemp,
            defaultHint = "60\u00B0C",
            onModeChange = { mode -> onOverridesChange(overrides.copy(bedTemp = overrides.bedTemp.copy(mode = mode))) },
            valueContent = {
                OverrideIntField(
                    value = overrides.bedTemp.value ?: 60,
                    suffix = "\u00B0C",
                    onValueChange = { onOverridesChange(overrides.copy(bedTemp = OverrideValue(OverrideMode.OVERRIDE, it))) }
                )
            }
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> OverrideRow(
    label: String,
    override: OverrideValue<T>,
    defaultHint: String,
    onModeChange: (OverrideMode) -> Unit,
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
