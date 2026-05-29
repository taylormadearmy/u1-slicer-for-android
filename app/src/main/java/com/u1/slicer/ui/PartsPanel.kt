package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u1.slicer.SlicerViewModel

/**
 * F66 — Parts panel for the Edit panel. Shows one row per volume of the
 * selected object. Each row renders the volume's currently-assigned filament
 * the SAME way as [com.u1.slicer.MainActivity.SingleColorFilamentRow] —
 * colour swatch + filament label + material chip — so the per-volume picker
 * is consistent with how the rest of the Prepare screen presents filaments.
 *
 * The picker dialog lists the four U1 extruder slots, each rendered as a
 * colour swatch + material chip. Slot-to-physical mapping happens at Send
 * time via the existing FilamentMappingDialog (unchanged).
 */
@Composable
fun PartsPanel(
    objIdx: Int,
    viewModel: SlicerViewModel,
    modifier: Modifier = Modifier,
) {
    val modelVersion by viewModel.modelAddVersion.collectAsState()
    val volumeCount = remember(objIdx, modelVersion) { viewModel.volumeCount(objIdx) }
    val perVolume by viewModel.perVolumeExtruders.collectAsState()
    val activeColors by viewModel.activeExtruderColors.collectAsState()
    val presets by viewModel.extruderPresets.collectAsState()
    var expanded by remember(objIdx) { mutableStateOf(false) }

    if (volumeCount <= 1) return

    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text("Parts ($volumeCount) " + if (expanded) "▲" else "▼")
        }
        if (expanded) {
            for (v in 0 until volumeCount) {
                val key = "$objIdx:$v"
                val slot = perVolume[key] ?: viewModel.volumeExtruder(objIdx, v).coerceAtLeast(1)
                val partName = remember(objIdx, v, modelVersion) {
                    viewModel.volumeName(objIdx, v).ifBlank { "Part ${v + 1}" }
                }
                PartFilamentRow(
                    partName = partName,
                    currentSlot = slot,
                    extruderColors = activeColors,
                    extruderPresets = presets,
                    onSelectSlot = { newSlot -> viewModel.setVolumeExtruder(objIdx, v, newSlot) },
                    modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * F66 — per-object filament picker for single-volume objects. Surfaced on the
 * [EditPanel] when the selected object has exactly one volume and the model is
 * multi-colour — answers the user complaint "if you select a single part with
 * no part list then there's no way to colour it".
 */
@Composable
fun SingleObjectFilamentRow(
    objIdx: Int,
    viewModel: SlicerViewModel,
    modifier: Modifier = Modifier,
) {
    val modelVersion by viewModel.modelAddVersion.collectAsState()
    val perVolume by viewModel.perVolumeExtruders.collectAsState()
    val activeColors by viewModel.activeExtruderColors.collectAsState()
    val presets by viewModel.extruderPresets.collectAsState()

    // Treat the lone volume as "the part" — same setVolumeExtruder API,
    // single fixed volIdx = 0.
    val key = "$objIdx:0"
    val slot = perVolume[key] ?: viewModel.volumeExtruder(objIdx, 0).coerceAtLeast(1)
    val name = remember(objIdx, modelVersion) {
        viewModel.objectName(objIdx).ifBlank { "Object ${objIdx + 1}" }
    }

    PartFilamentRow(
        partName = name,
        currentSlot = slot,
        extruderColors = activeColors,
        extruderPresets = presets,
        onSelectSlot = { newSlot -> viewModel.setVolumeExtruder(objIdx, 0, newSlot) },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun PartFilamentRow(
    partName: String,
    currentSlot: Int,                              // 1-indexed
    extruderColors: List<String>,
    extruderPresets: List<com.u1.slicer.data.ExtruderPreset>,
    onSelectSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = parseHexColor(extruderColors.getOrNull(currentSlot - 1) ?: "")
    val material = remember(currentSlot, extruderPresets) {
        extruderPresets.firstOrNull { it.index == currentSlot }?.materialType ?: "PLA"
    }
    var picking by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable { picking = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Text(
            partName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        AssistChip(onClick = { picking = true }, label = { Text(material) })
    }

    if (picking) {
        FilamentSlotChooserDialog(
            currentSlot = currentSlot,
            extruderColors = extruderColors,
            extruderPresets = extruderPresets,
            onPick = { newSlot ->
                onSelectSlot(newSlot)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun FilamentSlotChooserDialog(
    currentSlot: Int,
    extruderColors: List<String>,
    extruderPresets: List<com.u1.slicer.data.ExtruderPreset>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign to filament") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Slot mapping happens when you tap Send →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (slot in 1..4) {
                    val swatch = parseHexColor(extruderColors.getOrNull(slot - 1) ?: "")
                    val mat = extruderPresets.firstOrNull { it.index == slot }?.materialType ?: "PLA"
                    val isCurrent = slot == currentSlot
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                            )
                            .clickable { onPick(slot) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Filament $slot" + if (isCurrent) " · current" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                mat,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
