package com.u1.slicer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentProfile
import com.u1.slicer.printer.PrinterViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterScreen(
    viewModel: PrinterViewModel,
    filaments: List<FilamentProfile> = emptyList(),
    onBack: () -> Unit,
    onNavigateSettings: () -> Unit = {}
) {
    val status by viewModel.status.collectAsState()
    val sendingState by viewModel.sendingState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val extruderPresets by viewModel.extruderPresets.collectAsState()
    val webcamCandidates by viewModel.webcamCandidates.collectAsState()

    // Camera feed polling
    var cameraFrame by remember { mutableStateOf<Bitmap?>(null) }
    var candidateIndex by remember { mutableStateOf(0) }

    LaunchedEffect(webcamCandidates) {
        candidateIndex = 0
        if (webcamCandidates.isEmpty()) return@LaunchedEffect
        val http = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        while (true) {
            val baseUrl = webcamCandidates.getOrNull(candidateIndex)
                ?: webcamCandidates.last()
            val sep = if (baseUrl.contains('?')) '&' else '?'
            val pollUrl = "$baseUrl${sep}_cb=${System.currentTimeMillis()}"
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val resp = http.newCall(Request.Builder().url(pollUrl).build()).execute()
                    val b = resp.body?.bytes()
                    resp.close()
                    b
                }
                if (bytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) cameraFrame = bmp
                    else if (candidateIndex < webcamCandidates.size - 1) candidateIndex++
                } else if (candidateIndex < webcamCandidates.size - 1) candidateIndex++
            } catch (_: Exception) {
                if (candidateIndex < webcamCandidates.size - 1) candidateIndex++
            }
            delay(500)
        }
    }

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
                actions = {
                    val isLightOn by viewModel.isLightOn.collectAsState()
                    if (status.isConnected) {
                        IconButton(onClick = { viewModel.toggleLight() }) {
                            Icon(
                                if (isLightOn == true) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = if (isLightOn == true) "Light On" else "Light Off",
                                tint = if (isLightOn == true) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, "Printer settings")
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

            // ── Upload / Send status ────────────────────────────────────────
            when (val s = sendingState) {
                is PrinterViewModel.SendingState.Uploading -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Column {
                            Text("Uploading G-code\u2026", fontWeight = FontWeight.Medium)
                            Text("This may take a moment for large files",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
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
                        Text(s.message, color = Color.White.copy(alpha = 0.9f))
                    }
                }
                else -> {}
            }

            // ── Camera feed ─────────────────────────────────────────────────
            if (status.isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D1A)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val frame = cameraFrame
                        if (frame != null) {
                            Image(
                                bitmap = frame.asImageBitmap(),
                                contentDescription = "Camera feed",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(Icons.Default.Videocam, null,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    modifier = Modifier.size(40.dp))
                                Text(
                                    if (webcamCandidates.isEmpty()) "No camera found" else "Connecting to camera\u2026",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // ── Print Status ────────────────────────────────────────────────
            if (status.isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Print, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Print Status", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.weight(1f))
                            val (badgeColor, badgeText) = when {
                                status.isPrinting -> Color(0xFF4CAF50) to "PRINTING"
                                status.isPaused   -> Color(0xFFFFC107) to "PAUSED"
                                status.state == "complete" -> Color(0xFF2196F3) to "COMPLETE"
                                status.state == "error"    -> Color(0xFFEF5350) to "ERROR"
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) to status.state.uppercase()
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = badgeColor.copy(alpha = 0.15f)
                            ) {
                                Text(badgeText, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = badgeColor, style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold)
                            }
                        }

                        if (status.filename.isNotBlank()) {
                            Text(status.filename,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1)
                        }

                        if (status.isPrinting || status.isPaused || status.state == "complete") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Progress", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                    Text("${status.progressPercent}%", fontWeight = FontWeight.Medium)
                                }
                                LinearProgressIndicator(
                                    progress = { status.progress },
                                    modifier = Modifier.fillMaxWidth().height(8.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surface
                                )
                            }
                            if (status.printDuration > 0) {
                                Row(modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Print time", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                    Text(status.printTimeFormatted, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }

                // ── Temperatures ────────────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Temperatures", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                        TempTile(label = "Bed", actual = status.bedTemp, target = status.bedTarget)

                        val extruders = status.extruders.ifEmpty {
                            if (status.nozzleTemp > 0 || status.nozzleTarget > 0)
                                listOf(com.u1.slicer.network.ExtruderStatus(0, status.nozzleTemp, status.nozzleTarget))
                            else emptyList()
                        }
                        if (extruders.isNotEmpty()) {
                            extruders.chunked(2).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    row.forEach { ext ->
                                        TempTile(
                                            label = "E${ext.index + 1}",
                                            actual = ext.temp,
                                            target = ext.target,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // ── Print controls ──────────────────────────────────────────
                if (status.isPrinting || status.isPaused) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status.isPrinting) {
                            Button(
                                onClick = { viewModel.pausePrint() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107))
                            ) {
                                Icon(Icons.Default.Pause, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Pause")
                            }
                        }
                        if (status.isPaused) {
                            Button(
                                onClick = { viewModel.resumePrint() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Resume")
                            }
                        }
                        Button(
                            onClick = { viewModel.cancelPrint() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Stop, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                }
            }

            // ── Extruder Slots ──────────────────────────────────────────────
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
                            filaments = filaments,
                            onUpdate = { viewModel.updateExtruderPreset(it) }
                        )
                    }
                }
            }

            // ── Not connected hint ──────────────────────────────────────────
            if (!status.isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.WifiOff, null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(40.dp))
                        Text("Not connected",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Text("Set up printer connection in Settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

// ── Extruder slot row (inline color + type editing) ───────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtruderSlotRow(
    preset: ExtruderPreset,
    filaments: List<FilamentProfile> = emptyList(),
    onUpdate: (ExtruderPreset) -> Unit
) {
    var showEdit by remember { mutableStateOf(false) }
    if (showEdit) {
        ExtruderSlotEditDialog(preset = preset, filaments = filaments,
            onSave = { onUpdate(it); showEdit = false },
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
    filaments: List<FilamentProfile> = emptyList(),
    onSave: (ExtruderPreset) -> Unit,
    onDismiss: () -> Unit
) {
    var color by remember { mutableStateOf(preset.color) }
    var materialType by remember { mutableStateOf(preset.materialType) }
    var materialExpanded by remember { mutableStateOf(false) }
    var filamentExpanded by remember { mutableStateOf(false) }
    val materials = listOf("PLA", "PETG", "ABS", "TPU", "ASA", "PA", "PVA", "HIPS")

    // Track which filament profile is linked (if any)
    var linkedProfileId by remember { mutableStateOf(preset.filamentProfileId) }
    val linkedProfile = filaments.firstOrNull { it.id == linkedProfileId }

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
                // Filament profile picker — links a FilamentProfile for temperature/speed settings
                if (filaments.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = filamentExpanded, onExpandedChange = { filamentExpanded = it }) {
                        OutlinedTextField(
                            value = linkedProfile?.name ?: "None",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Filament Profile") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = filamentExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = filamentExpanded, onDismissRequest = { filamentExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("None") },
                                onClick = { linkedProfileId = null; filamentExpanded = false }
                            )
                            filaments.forEach { profile ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                                            Text("${profile.material} · ${profile.nozzleTemp}°C · ${profile.printSpeed.toInt()} mm/s",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        linkedProfileId = profile.id
                                        materialType = profile.material
                                        filamentExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (linkedProfile != null) {
                        Text(
                            "Nozzle ${linkedProfile.nozzleTemp}°C · Bed ${linkedProfile.bedTemp}°C · ${linkedProfile.printSpeed.toInt()} mm/s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(preset.copy(
                    color = color.let { if (it.startsWith("#")) it else "#$it" },
                    materialType = materialType,
                    filamentProfileId = linkedProfileId
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
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(14.dp),
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
private fun TempTile(
    label: String,
    actual: Float,
    target: Float,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val atTemp = target > 0 && kotlin.math.abs(actual - target) < 5f
    val heating = target > 0 && !atTemp
    val tileColor = when {
        atTemp  -> Color(0xFF4CAF50).copy(alpha = 0.12f)
        heating -> Color(0xFFFFC107).copy(alpha = 0.12f)
        else    -> MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
    }
    val textColor = when {
        atTemp  -> Color(0xFF4CAF50)
        heating -> Color(0xFFFFC107)
        else    -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = tileColor
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text("%.0f\u00B0C".format(actual),
                fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textColor)
            Text("\u2192 %.0f\u00B0C".format(target),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}
