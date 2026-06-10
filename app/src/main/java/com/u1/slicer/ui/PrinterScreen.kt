package com.u1.slicer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentLibraryEntry
import com.u1.slicer.data.FilamentProfile
import com.u1.slicer.data.LibraryState
import com.u1.slicer.printer.PrinterViewModel
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import com.u1.slicer.gcode.ExcludeObjectInfo
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterScreen(
    viewModel: PrinterViewModel,
    filaments: List<FilamentProfile> = emptyList(),
    excludeObjects: List<ExcludeObjectInfo> = emptyList(),
    onNavigateSettings: () -> Unit = {},
    onNavigatePrepare: () -> Unit = {},
    onNavigatePreview: () -> Unit = {},
    onNavigatePrinter: () -> Unit = {},
    onNavigateJobs: () -> Unit = {},
) {
    val status by viewModel.status.collectAsState()
    val sendingState by viewModel.sendingState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val extruderPresets by viewModel.extruderPresets.collectAsState()
    val webcamCandidates by viewModel.webcamCandidates.collectAsState()
    val remoteScreenAvailable by viewModel.remoteScreenAvailable.collectAsState()
    val context = LocalContext.current
    val skippedObjects by viewModel.skippedObjects.collectAsState()
    var showSkipSheet by remember { mutableStateOf(false) }
    val activeNickname by viewModel.activeNickname.collectAsState()
    val printerCount by viewModel.printerCount.collectAsState()
    val printerList by viewModel.printerList.collectAsState()
    val activePrinterId by viewModel.activePrinterId.collectAsState()
    var showSwitcher by remember { mutableStateOf(false) }

    // Task 7: OpenPrintTag filament library state for the slot editor's "Library" tab.
    val libraryState by viewModel.libraryState.collectAsState()
    val libraryFavourites by viewModel.libraryFavourites.collectAsState()
    val libraryRecents by viewModel.libraryRecents.collectAsState()

    var editingHeater by remember { mutableStateOf<String?>(null) }
    var editingValue by remember { mutableStateOf("") }
    val heaterError by viewModel.heaterError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(heaterError) {
        heaterError?.let { snackbarHostState.showSnackbar(it); viewModel.clearHeaterError() }
    }
    LaunchedEffect(status.isPrinting, status.isPaused) {
        if (!status.isPrinting && !status.isPaused) editingHeater = null
    }

    // Camera feed polling
    var cameraFrame by remember { mutableStateOf<Bitmap?>(null) }
    var showFullscreen by remember { mutableStateOf(false) }
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

    DisposableEffect(Unit) {
        viewModel.startCameraKeepalive()
        onDispose { viewModel.stopCameraKeepalive() }
    }

    if (showSkipSheet) {
        SkipObjectsSheet(
            objects = excludeObjects,
            skippedObjects = skippedObjects,
            onSkip = { name -> viewModel.skipObject(name) },
            onDismiss = { showSkipSheet = false }
        )
    }

    if (showSwitcher) {
        com.u1.slicer.ui.printer.PrinterSwitcherSheet(
            printers = printerList,
            activeId = activePrinterId,
            activePrintingFilename = if (status.isPrinting) status.filename else null,
            onSelect = { selected -> viewModel.switchActivePrinter(selected.id) },
            onDismiss = { showSwitcher = false },
        )
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
                actions = {
                    com.u1.slicer.ui.printer.ActivePrinterChip(
                        activeNickname = activeNickname,
                        printerCount = printerCount,
                        onClick = { showSwitcher = true },
                    )
                    val isLightOn by viewModel.isLightOn.collectAsState()
                    if (status.isConnected) {
                        IconButton(onClick = { viewModel.toggleLight() }) {
                            Icon(
                                Icons.Default.Lightbulb,
                                contentDescription = if (isLightOn == true) "Printer light on" else "Printer light off",
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
        bottomBar = {
            com.u1.slicer.U1BottomNavBar(
                selectedTab = "printer",
                onNavigatePrepare = onNavigatePrepare,
                onNavigatePreview = onNavigatePreview,
                onNavigatePrinter = onNavigatePrinter,
                onNavigateJobs = onNavigateJobs,
                onNavigateSettings = onNavigateSettings
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                is PrinterViewModel.SendingState.Preparing -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Column {
                            Text("Preparing G-code\u2026", fontWeight = FontWeight.Medium)
                            Text("Getting your file ready to send",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
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
                is PrinterViewModel.SendingState.PrintStarted -> Card(
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
                is PrinterViewModel.SendingState.UploadComplete -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B3D1E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50))
                            Text("Uploaded successfully!", color = Color(0xFF81C784), fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "To print, tap Print on the Preview screen or select the file on your printer.",
                            color = Color(0xFF81C784).copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall
                        )
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
                            Box {
                                Image(
                                    bitmap = frame.asImageBitmap(),
                                    contentDescription = "Camera feed",
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.FillWidth
                                )
                                IconButton(
                                    onClick = { showFullscreen = true },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .background(
                                            color = Color.Black.copy(alpha = 0.4f),
                                            shape = CircleShape
                                        )
                                        .size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Fullscreen,
                                        contentDescription = "Fullscreen",
                                        tint = Color.White
                                    )
                                }
                            }
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

            if (showFullscreen) {
                val fullFrame = cameraFrame
                Dialog(
                    onDismissRequest = { showFullscreen = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        if (fullFrame != null) {
                            Image(
                                bitmap = fullFrame.asImageBitmap(),
                                contentDescription = "Camera feed fullscreen",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        IconButton(
                            onClick = { showFullscreen = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(
                                    color = Color.Black.copy(alpha = 0.4f),
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.FullscreenExit,
                                contentDescription = "Exit fullscreen",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // ── Remote Screen (paxx12 extended firmware) ─────────────────────
            if (status.isConnected && remoteScreenAvailable) {
                val screenUrl = viewModel.remoteScreenUrl()
                if (screenUrl != null) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(screenUrl))
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Monitor, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Remote Screen")
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
                            val badgeText = resolveStatusBadge(status.state, sendingState is PrinterViewModel.SendingState.PrintStarted)
                            val badgeColor = when (badgeText) {
                                "PRINTING" -> Color(0xFF4CAF50)
                                "PAUSED" -> Color(0xFFFFC107)
                                "STARTING\u2026" -> Color(0xFF81C784)
                                "COMPLETE" -> Color(0xFF2196F3)
                                "ERROR" -> Color(0xFFEF5350)
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
                                if (excludeObjects.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { showSkipSheet = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.GridView, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Skip objects")
                                    }
                                }
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Temperatures", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary)
                            // F82: cooldown all heaters. Always safe — no head motion.
                            if (status.isConnected) {
                                TextButton(onClick = { viewModel.cooldownAll() }) {
                                    Icon(Icons.Default.AcUnit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Cooldown")
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                        // F82: allow temperature edit any time the printer is reachable,
                        // not just during print. No head motion involved.
                        val canEdit = status.isConnected
                        TempTile(
                            label = "Bed", actual = status.bedTemp, target = status.bedTarget,
                            isEditing = editingHeater == "heater_bed",
                            editingValue = if (editingHeater == "heater_bed") editingValue else "",
                            onEditStart = if (canEdit) {{ editingHeater = "heater_bed"; editingValue = status.bedTarget.toInt().toString() }} else null,
                            onEditValueChange = { editingValue = it },
                            onEditDone = { val v = editingValue.toIntOrNull(); if (v != null) viewModel.setHeaterTemperature("heater_bed", v); editingHeater = null }
                        )

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
                                        val heaterKey = if (ext.index == 0) "extruder" else "extruder${ext.index}"
                                        TempTile(
                                            label = "E${ext.index + 1}",
                                            actual = ext.temp,
                                            target = ext.target,
                                            modifier = Modifier.weight(1f),
                                            isEditing = editingHeater == heaterKey,
                                            editingValue = if (editingHeater == heaterKey) editingValue else "",
                                            onEditStart = if (canEdit) {{ editingHeater = heaterKey; editingValue = ext.target.toInt().toString() }} else null,
                                            onEditValueChange = { editingValue = it },
                                            onEditDone = { val v = editingValue.toIntOrNull(); if (v != null) viewModel.setHeaterTemperature(heaterKey, v); editingHeater = null }
                                        )
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // ── F82: Custom G-code (idle only) ─────────────────────────
                // Tighter gate than "!isPrinting && !isPaused": `isIdle` excludes
                // error/disconnected, where freeform G-code would either be
                // rejected by Klipper or surprise the user.
                if (status.isConnected && status.isIdle) {
                    var customGcode by remember { mutableStateOf("") }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Custom G-code", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary)
                            Text(
                                "Sends a single line to Klipper. Use for one-off " +
                                "commands when idle (e.g. M104 S0, SET_PRESSURE_ADVANCE). " +
                                "You own the risk — motion commands can crash the head.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            OutlinedTextField(
                                value = customGcode,
                                onValueChange = { customGcode = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("G-code (e.g. M104 S0)") },
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    viewModel.sendCustomGcode(customGcode)
                                    customGcode = ""
                                },
                                enabled = customGcode.isNotBlank(),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Send")
                            }
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
                            onUpdate = { viewModel.updateExtruderPreset(it) },
                            libraryState = libraryState,
                            libraryFavourites = libraryFavourites,
                            libraryRecents = libraryRecents,
                            onToggleLibraryFavourite = { slug -> viewModel.toggleLibraryFavourite(slug) },
                            onRetryLibrary = { viewModel.retryLibraryLoad() },
                            onImportProfile = { entry, onDone -> viewModel.importLibraryProfile(entry, onDone) },
                            onRecordRecent = { slug -> viewModel.recordLibraryRecent(slug) },
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
    onUpdate: (ExtruderPreset) -> Unit,
    libraryState: LibraryState = LibraryState.Loading,
    libraryFavourites: List<String> = emptyList(),
    libraryRecents: List<String> = emptyList(),
    onToggleLibraryFavourite: (String) -> Unit = {},
    onRetryLibrary: () -> Unit = {},
    onImportProfile: (FilamentLibraryEntry, (Long) -> Unit) -> Unit = { _, _ -> },
    onRecordRecent: (String) -> Unit = {},
) {
    var showEdit by remember { mutableStateOf(false) }
    if (showEdit) {
        ExtruderSlotEditDialog(preset = preset, filaments = filaments,
            onSave = { onUpdate(it); showEdit = false },
            onDismiss = { showEdit = false },
            libraryState = libraryState,
            libraryFavourites = libraryFavourites,
            libraryRecents = libraryRecents,
            onToggleLibraryFavourite = onToggleLibraryFavourite,
            onRetryLibrary = onRetryLibrary,
            onImportProfile = onImportProfile,
            onRecordRecent = onRecordRecent)
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
            val profileName = filaments.firstOrNull { it.id == preset.filamentProfileId }?.name
            Text(profileName ?: preset.materialType, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}

// ── HSV colour conversion helpers (F64) ──────────────────────────────────────────

/**
 * Convert HSV (h∈[0,360), s∈[0,1], v∈[0,1]) to a #RRGGBB hex string.
 * Pure math — no android.graphics dependency so it is unit-testable on JVM.
 */
internal fun hsvToHex(h: Float, s: Float, v: Float): String {
    val hi = (h / 60f).toInt() % 6
    val f = h / 60f - (h / 60f).toInt()
    val p = v * (1f - s)
    val q = v * (1f - f * s)
    val t = v * (1f - (1f - f) * s)
    val (r, g, b) = when (hi) {
        0 -> Triple(v, t, p)
        1 -> Triple(q, v, p)
        2 -> Triple(p, v, t)
        3 -> Triple(p, q, v)
        4 -> Triple(t, p, v)
        else -> Triple(v, p, q)
    }
    return "#%02X%02X%02X".format((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
}

/**
 * Parse a #RRGGBB hex string to [hue, saturation, value] (FloatArray of size 3).
 * Input may optionally omit the leading '#'.
 */
internal fun hexToHsv(hex: String): FloatArray {
    val clean = if (hex.startsWith('#')) hex.substring(1) else hex
    val packed = clean.toLongOrNull(16) ?: return floatArrayOf(0f, 0f, 1f)
    val r = ((packed shr 16) and 0xFF) / 255f
    val g = ((packed shr 8) and 0xFF) / 255f
    val b = (packed and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }.let { if (it < 0) it + 360f else it }
    val s = if (max == 0f) 0f else delta / max
    return floatArrayOf(h, s, max)
}

// ── HSV snap helper (F79) ────────────────────────────────────────────────────────

/**
 * Pure logic for hue-drag behaviour when the current colour is achromatic.
 *
 * When saturation is near-zero (white / grey / black), dragging the hue strip has
 * no visible effect because hue is irrelevant at s=0.  This helper snaps saturation
 * (and, when value is also near-zero, value too) to 1.0 so the chosen hue becomes
 * immediately visible.  When the colour is already saturated, s and v are left
 * unchanged.
 */
internal object HsvPickerSnap {
    private const val ACHROMATIC_THRESHOLD = 0.05f

    /**
     * Compute the (h, s, v) to apply when the user drags/taps the hue strip.
     * If [currentSaturation] is below [ACHROMATIC_THRESHOLD] snap s to 1.0.
     * If [currentValue] is also below [ACHROMATIC_THRESHOLD] (pure black), snap v to 1.0
     * so the hue is not masked by darkness.
     */
    @Suppress("UNUSED_PARAMETER")
    fun snapOnHueChange(
        currentHue: Float,
        currentSaturation: Float,
        currentValue: Float,
        newHue: Float,
    ): Triple<Float, Float, Float> {
        val s = if (currentSaturation < ACHROMATIC_THRESHOLD) 1f else currentSaturation
        val v = if (currentValue < ACHROMATIC_THRESHOLD) 1f else currentValue
        return Triple(newHue, s, v)
    }
}

// ── HSV Colour Picker composable (F64) ───────────────────────────────────────────

/**
 * A compact HSV colour picker consisting of:
 *  - A hue strip (full-width, 24dp tall)
 *  - A saturation×value box (full-width, square aspect ratio)
 *
 * Both are interactive via tap and drag. [onHsvChange] is called on every gesture event.
 */
@Composable
internal fun HsvColorPicker(
    hue: Float,
    saturation: Float,
    value: Float,
    onHsvChange: (h: Float, s: Float, v: Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Hue strip
        val hueBarHeight = 24.dp
        val hueColors = listOf(
            Color.Red, Color(0xFFFFFF00), Color.Green,
            Color.Cyan, Color.Blue, Color(0xFFFF00FF), Color.Red
        )
        Box(modifier = Modifier.fillMaxWidth().height(hueBarHeight)) {
            Canvas(modifier = Modifier
                .fillMaxSize()
                .pointerInput(saturation, value) {
                    detectTapGestures { offset ->
                        val h = (offset.x / size.width * 360f).coerceIn(0f, 359.9f)
                        val (nh, ns, nv) = HsvPickerSnap.snapOnHueChange(hue, saturation, value, h)
                        onHsvChange(nh, ns, nv)
                    }
                }
                .pointerInput(saturation, value) {
                    detectDragGestures { change, _ ->
                        val h = (change.position.x / size.width * 360f).coerceIn(0f, 359.9f)
                        val (nh, ns, nv) = HsvPickerSnap.snapOnHueChange(hue, saturation, value, h)
                        onHsvChange(nh, ns, nv)
                    }
                }
            ) {
                drawRect(brush = Brush.horizontalGradient(hueColors))
                // Thumb indicator — triple-ring for visibility on any background (F79)
                val thumbX = hue / 360f * size.width
                val thumbCy = size.height / 2f
                drawCircle(Color.Black, radius = 11f, center = Offset(thumbX, thumbCy), style = Stroke(3f))
                drawCircle(Color.White, radius = 8f, center = Offset(thumbX, thumbCy), style = Stroke(2f))
                drawCircle(Color.Black, radius = 3f, center = Offset(thumbX, thumbCy))
            }
        }

        // Saturation × Value box
        val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f)) {
            Canvas(modifier = Modifier
                .fillMaxSize()
                .pointerInput(hue) {
                    detectTapGestures { offset ->
                        val s = (offset.x / size.width).coerceIn(0f, 1f)
                        val v = (1f - offset.y / size.height).coerceIn(0f, 1f)
                        onHsvChange(hue, s, v)
                    }
                }
                .pointerInput(hue) {
                    detectDragGestures { change, _ ->
                        val s = (change.position.x / size.width).coerceIn(0f, 1f)
                        val v = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        onHsvChange(hue, s, v)
                    }
                }
            ) {
                // Saturation gradient (white → hue colour) left to right
                drawRect(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
                // Value gradient (transparent → black) top to bottom
                drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                // Thumb indicator — triple-ring for visibility on any background (F79)
                val thumbX = saturation * size.width
                val thumbY = (1f - value) * size.height
                drawCircle(Color.Black, radius = 16f, center = Offset(thumbX, thumbY), style = Stroke(3f))
                drawCircle(Color.White, radius = 13f, center = Offset(thumbX, thumbY), style = Stroke(2f))
                drawCircle(Color.Black, radius = 4f, center = Offset(thumbX, thumbY))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtruderSlotEditDialog(
    preset: ExtruderPreset,
    filaments: List<FilamentProfile> = emptyList(),
    onSave: (ExtruderPreset) -> Unit,
    onDismiss: () -> Unit,
    libraryState: LibraryState = LibraryState.Loading,
    libraryFavourites: List<String> = emptyList(),
    libraryRecents: List<String> = emptyList(),
    onToggleLibraryFavourite: (String) -> Unit = {},
    onRetryLibrary: () -> Unit = {},
    onImportProfile: (FilamentLibraryEntry, (Long) -> Unit) -> Unit = { _, _ -> },
    onRecordRecent: (String) -> Unit = {},
) {
    var color by remember { mutableStateOf(preset.color) }
    var materialType by remember { mutableStateOf(preset.materialType) }
    var filamentExpanded by remember { mutableStateOf(false) }

    // Track which filament profile is linked (if any)
    var linkedProfileId by remember { mutableStateOf(preset.filamentProfileId) }
    val linkedProfile = filaments.firstOrNull { it.id == linkedProfileId }

    // HSV state derived from initial colour; kept in sync with the hex field
    val initialHsv = remember { hexToHsv(preset.color) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var hsv by remember { mutableFloatStateOf(initialHsv[2]) }  // "value" component

    // Task 7: tab 0 = Colour (existing HSV editor), tab 1 = OpenPrintTag Library picker.
    var tab by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${preset.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }) {
                        Text("Colour", modifier = Modifier.padding(vertical = 10.dp))
                    }
                    Tab(selected = tab == 1, onClick = { tab = 1 }) {
                        Text("Library", modifier = Modifier.padding(vertical = 10.dp))
                    }
                }

                if (tab == 1) {
                    FilamentLibraryPicker(
                        state = libraryState,
                        favourites = libraryFavourites,
                        recents = libraryRecents,
                        onToggleFavourite = onToggleLibraryFavourite,
                        // Applies to the dialog's local state (not the VM) — this dialog commits on Save,
                        // so a library pick must stay un-persisted until the user confirms.
                        onPick = { entry ->
                            entry.hex?.let {
                                color = it
                                val p = hexToHsv(it); hue = p[0]; sat = p[1]; hsv = p[2]
                            }
                            if (entry.material.isNotBlank()) materialType = entry.material
                            linkedProfileId = null
                            onRecordRecent(entry.slug)
                            tab = 0
                        },
                        onImport = { entry ->
                            entry.hex?.let {
                                color = it
                                val p = hexToHsv(it); hue = p[0]; sat = p[1]; hsv = p[2]
                            }
                            if (entry.material.isNotBlank()) materialType = entry.material
                            onImportProfile(entry) { id -> linkedProfileId = id }
                            tab = 0
                        },
                        onRetry = onRetryLibrary,
                    )
                }
                // Compose lambdas must not early-return past sibling composables
                // (unbalanced groups crash on recomposition) — use a sibling branch.
                if (tab == 0) {
                    HsvColorPicker(
                        hue = hue,
                        saturation = sat,
                        value = hsv,
                        onHsvChange = { h, s, v ->
                            hue = h; sat = s; hsv = v
                            color = hsvToHex(h, s, v)
                        }
                    )
                    OutlinedTextField(
                        value = color,
                        onValueChange = { newHex ->
                            color = newHex
                            // Sync picker when user types a valid hex
                            val parsed = hexToHsv(newHex)
                            if (parsed[1] != 0f || parsed[2] != 0f || newHex.trimStart('#').length == 6) {
                                hue = parsed[0]; sat = parsed[1]; hsv = parsed[2]
                            }
                        },
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
                                                Text("${profile.material} · ${profile.nozzleTemp}°C",
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
                                "Nozzle ${linkedProfile.nozzleTemp}°C · Bed ${linkedProfile.bedTemp}°C",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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

        if (entry.matchedName != null) {
            Text(
                "${entry.matchedName} (matched)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun TempTile(
    label: String,
    actual: Float,
    target: Float,
    modifier: Modifier = Modifier.fillMaxWidth(),
    isEditing: Boolean = false,
    editingValue: String = "",
    onEditStart: (() -> Unit)? = null,
    onEditValueChange: ((String) -> Unit)? = null,
    onEditDone: (() -> Unit)? = null,
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
        Box(modifier = Modifier.padding(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text("%.0f\u00B0C".format(actual),
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textColor)
                if (isEditing) {
                    BasicTextField(
                        value = editingValue,
                        onValueChange = { onEditValueChange?.invoke(it.filter { c -> c.isDigit() }) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { onEditDone?.invoke() }),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .width(56.dp)
                            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                } else {
                    Text("\u2192 %.0f\u00B0C".format(target),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
            if (onEditStart != null && !isEditing) {
                IconButton(
                    onClick = onEditStart,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit target temperature",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

/**
 * Determine the status badge text for the Printer screen.
 *
 * When a print was just sent ([justSent] == true) but Moonraker hasn't
 * transitioned to "printing" yet, we show "STARTING…" instead of the
 * stale previous-print state (e.g. "CANCELLED").
 */
internal fun resolveStatusBadge(printerState: String, justSent: Boolean): String {
    val staleStates = setOf("cancelled", "standby", "complete", "error")
    return when {
        printerState == "printing" -> "PRINTING"
        printerState == "paused"   -> "PAUSED"
        justSent && printerState in staleStates -> "STARTING\u2026"
        printerState == "complete" -> "COMPLETE"
        printerState == "error"    -> "ERROR"
        else -> printerState.uppercase()
    }
}

// \u2500\u2500 Skip Objects bottom sheet \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkipObjectsSheet(
    objects: List<ExcludeObjectInfo>,
    skippedObjects: Set<String>,
    onSkip: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Skip objects",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            ExcludeObjectBedCanvas(
                objects = objects,
                skippedObjects = skippedObjects,
                onSkip = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(objects) { obj ->
                    val skipped = obj.name in skippedObjects
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = obj.name,
                            modifier = Modifier.weight(1f),
                            color = if (skipped)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else
                                MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (skipped) {
                            Text(
                                text = "Skipped",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        } else {
                            TextButton(onClick = { onSkip(obj.name) }) {
                                Text("Exclude")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExcludeObjectBedCanvas(
    objects: List<ExcludeObjectInfo>,
    skippedObjects: Set<String>,
    onSkip: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bedMm = 270f
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.aspectRatio(1f)) {
        val scalePx = with(density) { maxWidth.toPx() } / bedMm

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(objects, skippedObjects, scalePx) {
                    val minTapRadiusPx = with(density) { 24.dp.toPx() }
                    detectTapGestures { tap ->
                        for (obj in objects) {
                            if (obj.name in skippedObjects) continue
                            val hit = if (obj.polygon.isNotEmpty()) {
                                val pts = obj.polygon.map { (x, y) ->
                                    Offset(x * scalePx, (bedMm - y) * scalePx)
                                }
                                pointInPolygon(tap, pts)
                            } else {
                                val c = Offset(
                                    obj.center.first * scalePx,
                                    (bedMm - obj.center.second) * scalePx
                                )
                                (tap - c).getDistance() <= minTapRadiusPx
                            }
                            if (hit) { onSkip(obj.name); break }
                        }
                    }
                }
        ) {
            // Dark bed background
            drawRect(androidx.compose.ui.graphics.Color(0xFF1A1A2E))

            for (obj in objects) {
                val skipped = obj.name in skippedObjects
                val strokeColor = if (skipped)
                    androidx.compose.ui.graphics.Color(0xFF555555)
                else
                    androidx.compose.ui.graphics.Color(0xFF4CAF50)
                val fillAlpha = if (skipped) 0.15f else 0.25f

                if (obj.polygon.isNotEmpty()) {
                    val path = Path().apply {
                        val pts = obj.polygon.map { (x, y) ->
                            Offset(x * scalePx, (bedMm - y) * scalePx)
                        }
                        moveTo(pts[0].x, pts[0].y)
                        pts.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }
                    drawPath(path, strokeColor.copy(alpha = fillAlpha), style = Fill)
                    drawPath(path, strokeColor, style = Stroke(width = 2.dp.toPx()))
                } else {
                    val c = Offset(
                        obj.center.first * scalePx,
                        (bedMm - obj.center.second) * scalePx
                    )
                    drawCircle(
                        strokeColor,
                        radius = 12.dp.toPx(),
                        center = c,
                        alpha = if (skipped) 0.4f else 1f
                    )
                }
            }
        }
    }
}

private fun pointInPolygon(point: Offset, polygon: List<Offset>): Boolean {
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val xi = polygon[i].x; val yi = polygon[i].y
        val xj = polygon[j].x; val yj = polygon[j].y
        if ((yi > point.y) != (yj > point.y) &&
            point.x < (xj - xi) * (point.y - yi) / (yj - yi) + xi
        ) inside = !inside
        j = i
    }
    return inside
}
