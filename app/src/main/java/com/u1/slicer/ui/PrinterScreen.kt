package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.printer.PrinterViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterScreen(
    viewModel: PrinterViewModel,
    onBack: () -> Unit
) {
    val status by viewModel.status.collectAsState()
    val printerUrl by viewModel.printerUrl.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val sendingState by viewModel.sendingState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val extruderPresets by viewModel.extruderPresets.collectAsState()

    var urlInput by remember(printerUrl) { mutableStateOf(printerUrl) }

    // Sync preview dialog
    if (syncState is PrinterViewModel.SyncState.Preview) {
        FilamentSyncDialog(
            entries = (syncState as PrinterViewModel.SyncState.Preview).entries,
            onConfirm = { applyColors, applyTypes ->
                viewModel.applySyncResult(
                    (syncState as PrinterViewModel.SyncState.Preview).entries,
                    applyColors, applyTypes
                )
            },
            onDismiss = { viewModel.dismissSync() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Printer", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Extruder Slots ─────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Extruder Slots", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary)

                        if (status.isConnected) {
                            TextButton(
                                onClick = { viewModel.syncFilaments() },
                                enabled = syncState !is PrinterViewModel.SyncState.Loading
                            ) {
                                if (syncState is PrinterViewModel.SyncState.Loading) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                } else {
                                    Icon(Icons.Default.Sync, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text("Sync")
                            }
                        }
                    }

                    if (syncState is PrinterViewModel.SyncState.Error) {
                        Text(
                            (syncState as PrinterViewModel.SyncState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    extruderPresets.forEach { preset ->
                        ExtruderSlotRow(
                            preset = preset,
                            onUpdate = { viewModel.updateExtruderPreset(it) }
                        )
                    }
                }
            }

            // ── Connection ─────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Moonraker Connection", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary)

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("Printer URL") },
                        placeholder = { Text("192.168.1.100") },
                        supportingText = { Text("http:// and port 7125 added automatically if missing") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                viewModel.updateUrl(urlInput)
                                viewModel.testConnection()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Connect") }

                        when (connectionState) {
                            is PrinterViewModel.ConnectionState.Testing ->
                                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                            is PrinterViewModel.ConnectionState.Connected ->
                                Icon(Icons.Default.CheckCircle, null,
                                    tint = Color(0xFF4CAF50), modifier = Modifier.size(36.dp))
                            else -> {}
                        }
                    }

                    if (connectionState is PrinterViewModel.ConnectionState.Failed) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Error, null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Text(
                                (connectionState as PrinterViewModel.ConnectionState.Failed).reason,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // ── Printer Status ─────────────────────────────────────────────
            if (status.isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Print, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Printer Status", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        StatusRow("State", status.state.replaceFirstChar { it.uppercase() },
                            color = when {
                                status.isPrinting -> Color(0xFF4CAF50)
                                status.isPaused -> Color(0xFFFFC107)
                                else -> MaterialTheme.colorScheme.onSurface
                            })
                        if (status.isPrinting || status.isPaused) {
                            StatusRow("Progress", "${status.progressPercent}%")
                            StatusRow("File", status.filename)
                            StatusRow("Print Time", status.printTimeFormatted)
                            LinearProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }

                // Temperature card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Temperatures", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary)
                        TempRow("Bed", status.bedTemp, status.bedTarget)
                        for (ext in status.extruders) { TempRow("Extruder ${ext.index + 1}", ext.temp, ext.target) }
                        if (status.extruders.isEmpty()) { TempRow("Nozzle", status.nozzleTemp, status.nozzleTarget) }
                    }
                }

                // Print controls
                if (status.isPrinting || status.isPaused) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status.isPrinting) {
                            Button(onClick = { viewModel.pausePrint() }, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107))) {
                                Icon(Icons.Default.Pause, null); Spacer(Modifier.width(4.dp)); Text("Pause")
                            }
                        }
                        if (status.isPaused) {
                            Button(onClick = { viewModel.resumePrint() }, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Resume")
                            }
                        }
                        Button(onClick = { viewModel.cancelPrint() }, modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp)); Text("Cancel")
                        }
                    }
                }
            }

            // ── Send feedback ──────────────────────────────────────────────
            when (val s = sendingState) {
                is PrinterViewModel.SendingState.Uploading -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("Uploading G-code to printer...")
                    }
                }
                is PrinterViewModel.SendingState.Success -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B3D1E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50))
                        Text("Print started!", color = Color(0xFF81C784), fontWeight = FontWeight.Bold)
                    }
                }
                is PrinterViewModel.SendingState.Error -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3D1A1A)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                        Text(s.message, color = Color.White.copy(alpha = 0.8f))
                    }
                }
                else -> {}
            }
        }
    }
}

// ── Extruder slot row (inline color + type editing) ───────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtruderSlotRow(preset: ExtruderPreset, onUpdate: (ExtruderPreset) -> Unit) {
    var showEdit by remember { mutableStateOf(false) }
    if (showEdit) {
        ExtruderSlotEditDialog(preset = preset, onSave = { onUpdate(it); showEdit = false },
            onDismiss = { showEdit = false })
    }
    val slotColor = try { Color(android.graphics.Color.parseColor(preset.color)) }
                   catch (_: Exception) { Color.White }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable { showEdit = true }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(slotColor)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        Column(modifier = Modifier.weight(1f)) {
            Text(preset.label, fontWeight = FontWeight.Medium)
            Text(preset.materialType, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtruderSlotEditDialog(
    preset: ExtruderPreset,
    onSave: (ExtruderPreset) -> Unit,
    onDismiss: () -> Unit
) {
    var color by remember { mutableStateOf(preset.color) }
    var materialType by remember { mutableStateOf(preset.materialType) }
    var materialExpanded by remember { mutableStateOf(false) }
    val materials = listOf("PLA", "PETG", "ABS", "TPU", "ASA", "PA", "PVA", "HIPS")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${preset.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = color,
                    onValueChange = { color = it },
                    label = { Text("Color (#RRGGBB)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(
                            try { Color(android.graphics.Color.parseColor(
                                if (color.startsWith("#")) color else "#$color")) }
                            catch (_: Exception) { Color.White }
                        ))
                    }
                )
                ExposedDropdownMenuBox(expanded = materialExpanded, onExpandedChange = { materialExpanded = it }) {
                    OutlinedTextField(value = materialType, onValueChange = {}, readOnly = true,
                        label = { Text("Material") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = materialExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor())
                    ExposedDropdownMenu(expanded = materialExpanded, onDismissRequest = { materialExpanded = false }) {
                        materials.forEach { mat ->
                            DropdownMenuItem(text = { Text(mat) }, onClick = { materialType = mat; materialExpanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(preset.copy(
                    color = color.let { if (it.startsWith("#")) it else "#$it" },
                    materialType = materialType
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Sync preview dialog ────────────────────────────────────────────────────────

@Composable
private fun FilamentSyncDialog(
    entries: List<PrinterViewModel.SyncPreviewEntry>,
    onConfirm: (applyColors: Boolean, applyTypes: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var applyColors by remember { mutableStateOf(true) }
    var applyTypes by remember { mutableStateOf(true) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Sync from Printer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Review changes per extruder slot.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries) { entry -> SyncEntryRow(entry, applyColors, applyTypes) }
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyColors, onCheckedChange = { applyColors = it })
                    Spacer(Modifier.width(4.dp))
                    Text("Apply colors")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyTypes, onCheckedChange = { applyTypes = it })
                    Spacer(Modifier.width(4.dp))
                    Text("Apply material types")
                }

                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(applyColors, applyTypes) },
                        enabled = applyColors || applyTypes) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun SyncEntryRow(entry: PrinterViewModel.SyncPreviewEntry, applyColors: Boolean, applyTypes: Boolean) {
    fun parseColor(hex: String?): Color = try {
        Color(android.graphics.Color.parseColor(hex ?: "#808080"))
    } catch (_: Exception) { Color.Gray }

    val currentColor = parseColor(entry.currentColor)
    val newColor = entry.newColor?.let { parseColor(it) }

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(entry.label, fontWeight = FontWeight.Medium, modifier = Modifier.width(28.dp))
        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(currentColor)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        Text(entry.currentType, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(48.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (newColor != null) {
            Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            Box(modifier = Modifier.size(24.dp).clip(CircleShape)
                .background(if (applyColors) newColor else currentColor)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
            Text(
                if (applyTypes && entry.newType != null) entry.newType else entry.currentType,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text("No data", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun StatusRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, fontWeight = FontWeight.Medium,
            color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TempRow(name: String, actual: Float, target: Float) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text("%.0f\u00B0C / %.0f\u00B0C".format(actual, target), fontWeight = FontWeight.Medium,
            color = if (target > 0 && actual > 0) {
                if (kotlin.math.abs(actual - target) < 5) Color(0xFF4CAF50) else Color(0xFFFFC107)
            } else MaterialTheme.colorScheme.onSurface)
    }
}
