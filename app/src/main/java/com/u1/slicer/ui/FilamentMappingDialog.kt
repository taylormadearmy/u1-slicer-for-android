package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentSource

/**
 * Phase 2.4 — print-time **Filament mapping** dialog.
 *
 * Appears when the user taps Send to Printer. Lets the user assign each of
 * the file's filaments to one of the four physical extruder slots. Auto-
 * suggests via colour-distance match against extruder presets; user can
 * override any row. Two filaments mapping to the same slot triggers the
 * inline "what should happen?" picker (pause / same colour / reslice merged).
 *
 * Returns the user's chosen `colorMapping: List<Int>` via [onConfirm].
 *
 * @param canonicalList The file's canonical filament list (per
 *   [com.u1.slicer.data.canonicalListAtLoad]).
 * @param extruderPresets The user's saved physical-slot loadout.
 * @param initialMapping If non-null and matching `canonicalList.size`, used
 *   as the starting picker state. Lets the dialog remember per-file
 *   mappings across reopens (Phase 2.4 persistence is a follow-up — for
 *   now the caller passes the existing colorMapping if present).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilamentMappingDialog(
    canonicalList: CanonicalFilamentList,
    extruderPresets: List<ExtruderPreset>,
    initialMapping: List<Int>? = null,
    onConfirm: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val mapping = remember(canonicalList, extruderPresets) {
        val seed = if (initialMapping != null && initialMapping.size == canonicalList.size) {
            initialMapping
        } else {
            autoSuggestMapping(canonicalList, extruderPresets)
        }
        seed.toMutableStateList()
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Don't let Compose constrain us to the platform-default narrow
        // dialog width — the slot picker needs the full screen width to
        // show "E1 · PLA" without truncating.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Filament mapping",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (canonicalList.size == 1)
                        "Pick which physical extruder to print this on."
                    else
                        "Assign each of the ${canonicalList.size} filaments to a physical extruder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(canonicalList.filaments) { idx, entry ->
                        FilamentMappingRow(
                            fileIndex = idx,
                            fileColor = entry.color,
                            filamentMaterial = entry.materialType,
                            sourceLabel = sourceShortLabel(entry.source),
                            extruderPresets = extruderPresets,
                            selectedSlot = mapping.getOrElse(idx) { 0 },
                            onSlotPicked = { slot ->
                                if (idx < mapping.size) mapping[idx] = slot
                            }
                        )
                    }
                }

                // Overlap chip — count filaments mapping to the same slot.
                val duplicateSlots = mapping.groupingBy { it }.eachCount()
                    .filterValues { it > 1 }.keys
                if (duplicateSlots.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            duplicateSlots.forEach { slot ->
                                val sharingIndices = mapping.mapIndexedNotNull { i, s ->
                                    if (s == slot) (i + 1) else null
                                }
                                Text(
                                    "E${slot + 1} chosen for filaments ${sharingIndices.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(
                                "These will print as the same physical filament — " +
                                    "the printer won't pause to swap unless you've set " +
                                    "that up separately.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Auto-suggest reset
                OutlinedButton(
                    onClick = {
                        val fresh = autoSuggestMapping(canonicalList, extruderPresets)
                        mapping.clear()
                        mapping.addAll(fresh)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Default.AutoFixHigh, null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Auto-suggest", style = MaterialTheme.typography.labelMedium)
                }

                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(mapping.toList()) }) {
                        Text("Send to Printer")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilamentMappingRow(
    fileIndex: Int,
    fileColor: String,
    filamentMaterial: String?,
    sourceLabel: String?,
    extruderPresets: List<ExtruderPreset>,
    selectedSlot: Int,
    onSlotPicked: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedPreset = extruderPresets.firstOrNull { it.index == selectedSlot }
        ?: extruderPresets.firstOrNull()

    // Phase 2.8 — material mismatch detection.
    // The filament's expected material (from the file or the user's
    // Prepare-screen override) may not match the slot's preset material
    // (what the user has loaded). Surface a non-blocking chip so the
    // user knows the temps may not match the loaded spool.
    val slotMaterial = selectedPreset?.materialType
    val mismatch = filamentMaterial != null
        && slotMaterial != null
        && !filamentMaterial.equals(slotMaterial, ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // File-colour swatch
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(parseHexColor(fileColor))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Filament ${fileIndex + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                fileColor.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (sourceLabel != null) {
                Text(
                    sourceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
            }
        }

        // Slot picker — wider so the "E1 · PLA" label fits without truncation.
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = "${selectedPreset?.label ?: "E1"} · ${selectedPreset?.materialType ?: "PLA"}",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                selectedPreset?.let { parseHexColor(it.color) } ?: Color.White
                            )
                    )
                },
                modifier = Modifier.menuAnchor().width(150.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                extruderPresets.forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(parseHexColor(preset.color))
                                        .border(0.5.dp,
                                            MaterialTheme.colorScheme.outline, CircleShape)
                                )
                                Text(
                                    "${preset.label} · ${preset.materialType}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        },
                        onClick = { onSlotPicked(preset.index); expanded = false }
                    )
                }
            }
        }
    }
        if (mismatch) {
            // Material-mismatch chip — non-blocking advisory.
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    "Slot loaded as $slotMaterial, filament needs $filamentMaterial",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun sourceShortLabel(source: FilamentSource): String? = when (source) {
    FilamentSource.PAINT_DERIVED -> "paint segment"
    FilamentSource.LAYER_TOOL -> "layer tool"
    FilamentSource.STL_DEFAULT -> "STL default"
    FilamentSource.OBJECT_DEFAULT -> "object default"
    FilamentSource.FILE_COLOUR -> null  // most common, hide label
}

/**
 * Visible for testing — auto-suggests a [colorMapping] from the canonical
 * list against the user's extruder presets via colour-distance match. This
 * is exactly the same logic as today's slice-time
 * [findClosestExtruder]-driven flow, but applied at print time so the
 * user always sees and confirms the suggestion before the G-code uploads.
 */
internal fun autoSuggestMapping(
    canonicalList: CanonicalFilamentList,
    extruderPresets: List<ExtruderPreset>,
): List<Int> = canonicalList.filaments.map { entry ->
    findClosestExtruder(entry.color, extruderPresets)?.index ?: 0
}
