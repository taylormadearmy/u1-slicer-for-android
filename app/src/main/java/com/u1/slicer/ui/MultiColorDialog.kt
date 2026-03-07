package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentProfile

/**
 * Per-extruder assignment used by the slicer.
 * Index = extruder slot (0-based). Color from the slot, temperature from the filament profile.
 */
data class ExtruderAssignment(
    val extruderIndex: Int,
    val color: String,        // from ExtruderPreset.color
    val temperature: Int = 210,
    val filamentName: String = ""
)

/**
 * Euclidean color distance in RGB space. Returns 0..441 (max sqrt(255²×3)).
 */
fun colorDistance(hex1: String, hex2: String): Double {
    fun parse(h: String): Triple<Int, Int, Int>? = try {
        val c = android.graphics.Color.parseColor(if (h.startsWith("#")) h else "#$h")
        Triple(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
    } catch (_: Exception) { null }
    val (r1, g1, b1) = parse(hex1) ?: return Double.MAX_VALUE
    val (r2, g2, b2) = parse(hex2) ?: return Double.MAX_VALUE
    val dr = (r1 - r2).toDouble(); val dg = (g1 - g2).toDouble(); val db = (b1 - b2).toDouble()
    return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
}

/** Find the extruder preset whose color is closest to the given hex color. */
fun findClosestExtruder(color: String, presets: List<ExtruderPreset>): ExtruderPreset? =
    presets.minByOrNull { colorDistance(color, it.color) }

/**
 * Dialog shown when a multi-color 3MF is loaded.
 *
 * Shows each detected model color and lets the user choose which extruder slot it maps to.
 * Extruder dropdowns display the slot's physical filament color.
 * Auto-maps by color distance between model color and slot color.
 *
 * @param detectedColors  Hex colors detected from the 3MF (one per object/extruder)
 * @param extruderPresets Current extruder slot config (color + material type from Printer page)
 * @param filaments       Filament library, used to look up temperature for the assigned slot's profile
 * @param onConfirm       Called with (extruderIndex per detected color)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiColorDialog(
    detectedColors: List<String>,
    extruderPresets: List<ExtruderPreset>,
    filaments: List<FilamentProfile>,
    onConfirm: (List<Int>) -> Unit,   // extruderIndex for each detectedColor
    onDismiss: () -> Unit
) {
    // Auto-map: for each model color, find the closest extruder slot by color distance
    val initialMapping = remember(detectedColors, extruderPresets) {
        detectedColors.map { modelColor ->
            val closest = findClosestExtruder(modelColor, extruderPresets)
            closest?.index ?: 0
        }
    }

    val mapping = remember(initialMapping) { initialMapping.toMutableStateList() }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Multi-Color Detected", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                val hasPresetColors = extruderPresets.any { it.color != "#FFFFFF" && it.color != "#ffffff" }
                Text(
                    if (hasPresetColors)
                        "${detectedColors.size} color(s) detected. Mapped to nearest extruder — override if needed."
                    else
                        "${detectedColors.size} color(s) detected. Assign each to an extruder slot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(detectedColors) { colorIdx, modelColor ->
                        ColorToExtruderRow(
                            modelColor = modelColor,
                            colorIndex = colorIdx,
                            selectedExtruder = mapping.getOrElse(colorIdx) { 0 },
                            extruderPresets = extruderPresets,
                            onSelect = { extruderIdx -> if (colorIdx < mapping.size) mapping[colorIdx] = extruderIdx }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                val uniqueExtruders = mapping.distinct().size
                if (uniqueExtruders > 1) {
                    Text(
                        "Using $uniqueExtruders extruders — a wipe tower will be added.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(mapping.toList()) }) { Text("Apply") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorToExtruderRow(
    modelColor: String,
    colorIndex: Int,
    selectedExtruder: Int,
    extruderPresets: List<ExtruderPreset>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedPreset = extruderPresets.firstOrNull { it.index == selectedExtruder }
        ?: extruderPresets.firstOrNull()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Model color swatch
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(parseHexColor(modelColor))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text("Color ${colorIndex + 1}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(modelColor.uppercase(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface)
        }

        // Extruder slot picker
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selectedPreset?.label ?: "E1",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(selectedPreset?.let { parseHexColor(it.color) } ?: Color.White)
                    )
                },
                modifier = Modifier.menuAnchor().width(110.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                extruderPresets.forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(modifier = Modifier.size(14.dp).clip(CircleShape)
                                    .background(parseHexColor(preset.color))
                                    .border(0.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
                                Text("${preset.label} · ${preset.materialType}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                        },
                        onClick = { onSelect(preset.index); expanded = false }
                    )
                }
            }
        }
    }
}

fun parseHexColor(hex: String): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        when (cleaned.length) {
            6 -> Color(android.graphics.Color.parseColor("#FF$cleaned"))
            8 -> Color(android.graphics.Color.parseColor("#$cleaned"))
            else -> Color.Gray
        }
    } catch (_: Exception) {
        Color.Gray
    }
}
