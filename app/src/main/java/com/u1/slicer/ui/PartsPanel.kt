package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.u1.slicer.SlicerViewModel

/**
 * F66 — Parts panel. Shown beneath the object-scoped controls in [EditPanel]
 * whenever the selected object has more than one volume. Each row exposes
 * one volume's filament-slot assignment; tapping opens a chooser that lists
 * the four U1 extruder slots with their currently-loaded colours.
 */
@Composable
fun PartsPanel(
    objIdx: Int,
    viewModel: SlicerViewModel,
    modifier: Modifier = Modifier,
) {
    val volumeCount = remember(objIdx) { viewModel.volumeCount(objIdx) }
    val perVolume by viewModel.perVolumeExtruders.collectAsState()
    val activeColors by viewModel.activeExtruderColors.collectAsState()
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
                val name = remember(objIdx, v) {
                    viewModel.volumeName(objIdx, v).ifBlank { "Part ${v + 1}" }
                }
                PartRow(
                    name = name,
                    currentSlot = slot,
                    extruderColors = activeColors,
                    onSelectSlot = { newSlot -> viewModel.setVolumeExtruder(objIdx, v, newSlot) },
                    modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PartRow(
    name: String,
    currentSlot: Int,                  // 1-indexed
    extruderColors: List<String>,
    onSelectSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var chooserOpen by remember { mutableStateOf(false) }
    val color = parseHexColorOrDefault(extruderColors.getOrNull(currentSlot - 1))

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(color),
        )
        OutlinedButton(
            onClick = { chooserOpen = true },
        ) { Text("E$currentSlot") }
    }

    if (chooserOpen) {
        AlertDialog(
            onDismissRequest = { chooserOpen = false },
            title = { Text("Assign part to filament slot") },
            text = {
                Column {
                    for (slot in 1..4) {
                        val rowColor = parseHexColorOrDefault(extruderColors.getOrNull(slot - 1))
                        TextButton(
                            onClick = { onSelectSlot(slot); chooserOpen = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier.size(16.dp).clip(CircleShape).background(rowColor),
                                )
                                Text("E$slot" + if (slot == currentSlot) " (current)" else "")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chooserOpen = false }) { Text("Cancel") }
            },
        )
    }
}

private fun parseHexColorOrDefault(hex: String?): Color {
    if (hex.isNullOrBlank()) return Color(0xFF888888)
    return try {
        val cleaned = hex.removePrefix("#")
        val parsed = cleaned.toLong(16)
        when (cleaned.length) {
            6 -> Color(0xFF000000 or parsed)
            8 -> Color(parsed)
            else -> Color(0xFF888888)
        }
    } catch (_: Throwable) {
        Color(0xFF888888)
    }
}
