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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.u1.slicer.data.FilamentProfile
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.CanonicalFilamentList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncFilamentsDialog(
    canonicalList: CanonicalFilamentList,
    extruderPresets: List<ExtruderPreset>,
    initialMapping: List<Int>? = null,
    hasActiveOverrides: Boolean = false,
    promptReason: String? = null,
    title: String = "Sync filaments from printer",
    description: String = "Override the file's colours and materials to match the physical loaded extruders.",
    confirmLabel: String = "Sync",
    onConfirm: (List<Int>) -> Unit,
    onRestoreAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val mapping = remember(initialMapping, canonicalList.size, extruderPresets) {
        mutableStateListOf<Int>().apply {
            if (initialMapping != null && initialMapping.size == canonicalList.size) {
                addAll(initialMapping)
            } else {
                addAll(com.u1.slicer.ui.autoSuggestMapping(canonicalList, extruderPresets))
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (promptReason != null) {
                    Text(
                        promptReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(canonicalList.filaments) { idx, entry ->
                        SyncFilamentRow(
                            fileIndex = idx,
                            fileColor = entry.color,
                            extruderPresets = extruderPresets,
                            selectedSlot = mapping.getOrElse(idx) { 0 },
                            onSlotPicked = { slot ->
                                if (idx < mapping.size) mapping[idx] = slot
                            },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        val fresh = com.u1.slicer.ui.autoSuggestMapping(canonicalList, extruderPresets)
                        mapping.clear()
                        mapping.addAll(fresh)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.AutoFixHigh, null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Auto-suggest mapping", style = MaterialTheme.typography.labelMedium)
                }

                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    if (hasActiveOverrides) {
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = { onRestoreAll(); onDismiss() },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) { Text("Restore File Defaults") }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onConfirm(mapping.toList()) }) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncFilamentRow(
    fileIndex: Int,
    fileColor: String,
    extruderPresets: List<ExtruderPreset>,
    selectedSlot: Int,
    onSlotPicked: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedPreset = extruderPresets.firstOrNull { it.index == selectedSlot } ?: extruderPresets.firstOrNull()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
        }

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
                modifier = Modifier.menuAnchor().width(170.dp),
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
                                        .border(
                                            0.5.dp,
                                            MaterialTheme.colorScheme.outline,
                                            CircleShape
                                        )
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
}
