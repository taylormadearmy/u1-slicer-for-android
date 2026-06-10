package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Phase 2.6c — colour-only edit dialog for a single filament on the
 * Prepare screen. Wraps the existing [HsvColorPicker] with a preview
 * swatch + Save / Cancel / Reset buttons.
 *
 * The dialog stores transient HSV state internally; the result hex is
 * returned via [onSave]. [initialHex] seeds the picker. [onReset] is
 * shown when the caller provides it, letting the user clear an
 * override without picking a new colour first.
 *
 * Material-type editing lives on the Prepare row's chip (DropdownMenu);
 * this dialog is colour-only to keep the surface small.
 *
 * [libraryContent] is non-null only in physical-slot contexts (AiPaint slot
 * dialog / PrinterScreen slot editor). When supplied, a two-tab switch appears
 * — "Custom colour" hosts the existing HSV editor; "Library" hosts the
 * OpenPrintTag picker (which applies via its own Use buttons). When null the
 * dialog is unchanged (Prepare per-file + mix-slot callers).
 */
@Composable
fun FilamentColorEditDialog(
    initialHex: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    onReset: (() -> Unit)? = null,
    libraryContent: (@Composable () -> Unit)? = null,
) {
    val initialHsv = remember(initialHex) { hexToHsv(initialHex) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    val currentHex = hsvToHex(hue, sat, value)
    var tab by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Filament colour",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

                if (libraryContent != null) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }) {
                            Text("Custom colour", modifier = Modifier.padding(vertical = 10.dp))
                        }
                        Tab(selected = tab == 1, onClick = { tab = 1 }) {
                            Text("Library", modifier = Modifier.padding(vertical = 10.dp))
                        }
                    }
                }

                if (libraryContent != null && tab == 1) {
                    libraryContent()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(currentHex))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                        Text(
                            currentHex.uppercase(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    HsvColorPicker(
                        hue = hue,
                        saturation = sat,
                        value = value,
                        onHsvChange = { h, s, v -> hue = h; sat = s; value = v },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onReset != null) {
                            TextButton(onClick = { onReset(); onDismiss() }) {
                                Text("Reset")
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = { onSave(currentHex); onDismiss() }) { Text("Save") }
                    }
                }
            }
        }
    }
}
