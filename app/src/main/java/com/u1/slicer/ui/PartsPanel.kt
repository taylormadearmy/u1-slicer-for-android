package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
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
 * F66 — Parts panel. Source-of-truth for each filament row depends on whether
 * the model declares filaments:
 *
 *   - **3MF with `canonicalFilamentList`** (multi-colour Bambu / SEMM / Hueforge):
 *     rows + picker entries come from the **file's** declared filaments
 *     (`resolvedFilamentColors` for colour, `displayedFilamentMaterials` for
 *     material). The picker shows N filaments (file-declared count), not 4
 *     printer slots. Label is "Filament N" matching `PrintSetupSection`.
 *
 *   - **STL / non-canonical**: fall back to the 4 printer extruder slots with
 *     their loaded colours/materials. STLs genuinely don't declare filaments
 *     so picking a printer slot is the right thing.
 *
 * The `setVolumeExtruder` JNI call still takes a 1-based index. For 3MFs the
 * native side already interprets `extruder_id` as a file-filament index
 * (`sapil_arrange.cpp:661`), so the same write path works for both cases.
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
    if (volumeCount <= 1) return

    var expanded by remember(objIdx) { mutableStateOf(false) }

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
                    viewModel = viewModel,
                    onSelectSlot = { newSlot -> viewModel.setVolumeExtruder(objIdx, v, newSlot) },
                    modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun SingleObjectFilamentRow(
    objIdx: Int,
    viewModel: SlicerViewModel,
    modifier: Modifier = Modifier,
) {
    val modelVersion by viewModel.modelAddVersion.collectAsState()
    val perVolume by viewModel.perVolumeExtruders.collectAsState()

    val key = "$objIdx:0"
    val slot = perVolume[key] ?: viewModel.volumeExtruder(objIdx, 0).coerceAtLeast(1)
    val name = remember(objIdx, modelVersion) {
        viewModel.objectName(objIdx).ifBlank { "Object ${objIdx + 1}" }
    }

    PartFilamentRow(
        partName = name,
        currentSlot = slot,
        viewModel = viewModel,
        onSelectSlot = { newSlot -> viewModel.setVolumeExtruder(objIdx, 0, newSlot) },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Resolve the (colour, material, label) tuple for a given filament index, choosing
 *  between the file's canonical filament list (for 3MFs) and the printer's slot
 *  presets (for STLs). Returns 1-based label since slots are 1-indexed by Orca
 *  convention. */
@Composable
private fun resolveFilamentChip(
    slot: Int,                                  // 1-based
    viewModel: SlicerViewModel,
): Triple<androidx.compose.ui.graphics.Color, String, String> {
    val canonical by viewModel.canonicalFilamentList.collectAsState()
    val resolvedColors by viewModel.resolvedFilamentColors.collectAsState()
    val displayedMaterials by viewModel.displayedFilamentMaterials.collectAsState()
    val activeColors by viewModel.activeExtruderColors.collectAsState()
    val presets by viewModel.extruderPresets.collectAsState()

    val canonicalSize = canonical?.size ?: 0
    val useFile = canonical != null && slot - 1 < canonicalSize

    return if (useFile) {
        val idx = slot - 1
        val hex = resolvedColors.getOrNull(idx) ?: ""
        val mat = displayedMaterials.getOrNull(idx)?.first ?: "PLA"
        Triple(parseHexColor(hex), mat, "Filament $slot")
    } else {
        val hex = activeColors.getOrNull(slot - 1) ?: ""
        val mat = presets.firstOrNull { it.index == slot }?.materialType ?: "PLA"
        Triple(parseHexColor(hex), mat, "Filament $slot")
    }
}

@Composable
private fun PartFilamentRow(
    partName: String,
    currentSlot: Int,
    viewModel: SlicerViewModel,
    onSelectSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (color, material, _) = resolveFilamentChip(currentSlot, viewModel)
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
        FilamentChooserDialog(
            currentSlot = currentSlot,
            viewModel = viewModel,
            onPick = { newSlot ->
                onSelectSlot(newSlot)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun FilamentChooserDialog(
    currentSlot: Int,
    viewModel: SlicerViewModel,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val canonical by viewModel.canonicalFilamentList.collectAsState()
    // File-filament count for 3MFs; otherwise 4 physical slots.
    val rowCount = canonical?.size ?: 4

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign to filament") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (canonical != null)
                        "Pick which file-declared filament this part uses. Slot mapping happens at Send →"
                    else
                        "Slot mapping happens when you tap Send →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (slot in 1..rowCount) {
                    val (swatch, mat, label) = resolveFilamentChip(slot, viewModel)
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
                                label + if (isCurrent) " · current" else "",
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
