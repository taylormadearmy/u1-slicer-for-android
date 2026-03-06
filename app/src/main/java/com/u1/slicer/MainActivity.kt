package com.u1.slicer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.SliceResult
import com.u1.slicer.navigation.U1NavGraph
import com.u1.slicer.navigation.Routes

class MainActivity : ComponentActivity() {
    private val viewModel: SlicerViewModel by viewModels()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.loadModel(it) }
    }

    private val gcodeSaveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { viewModel.saveGcodeTo(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                viewModel.loadModel(uri)
                // Clear the intent to prevent re-processing on recreation
                intent.action = null
                intent.data = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only handle VIEW intents on fresh launch, not on recreation
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }
        setContent {
            U1SlicerTheme {
                val navController = rememberNavController()
                U1NavGraph(
                    navController = navController,
                    viewModel = viewModel,
                    onPickFile = {
                        filePickerLauncher.launch(arrayOf(
                            "application/sla",
                            "model/stl",
                            "application/vnd.ms-pki.stl",
                            "application/octet-stream",
                            "model/3mf",
                            "application/vnd.ms-3mfdocument",
                            "model/obj",
                            "*/*"
                        ))
                    },
                    onSaveGcode = {
                        gcodeSaveLauncher.launch("output.gcode")
                    },
                    slicerContent = {
                        SlicerScreen(
                            viewModel = viewModel,
                            onPickFile = {
                                filePickerLauncher.launch(arrayOf(
                                    "application/sla",
                                    "model/stl",
                                    "application/vnd.ms-pki.stl",
                                    "application/octet-stream",
                                    "model/3mf",
                                    "application/vnd.ms-3mfdocument",
                                    "model/obj",
                                    "*/*"
                                ))
                            },
                            onNavigateSettings = { navController.navigate(Routes.SETTINGS) },
                            onNavigatePrinter = { navController.navigate(Routes.PRINTER) },
                            onNavigateFilaments = { navController.navigate(Routes.FILAMENTS) },
                            onNavigateJobs = { navController.navigate(Routes.JOBS) },
                            onNavigateGcodeViewer3D = { navController.navigate(Routes.GCODE_VIEWER_3D) },
                            onNavigateModelViewer = { navController.navigate(Routes.MODEL_VIEWER) },
                            onNavigatePlacementViewer = { navController.navigate(Routes.PLACEMENT_VIEWER) },
                            onShareGcode = { viewModel.shareGcode() },
                            onSaveGcode = { gcodeSaveLauncher.launch("output.gcode") }
                        )
                    }
                )
            }
        }
    }
}

// =============================================================================
// Theme
// =============================================================================
@Composable
fun U1SlicerTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFFE87A00),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF3D2000),
        secondary = Color(0xFF4FC3F7),
        surface = Color(0xFF1A1A2E),
        surfaceVariant = Color(0xFF222240),
        background = Color(0xFF0F0F1E),
        onBackground = Color(0xFFE0E0E0),
        onSurface = Color(0xFFE0E0E0),
        error = Color(0xFFEF5350)
    )
    MaterialTheme(
        colorScheme = darkColorScheme,
        content = content
    )
}

// =============================================================================
// Main Screen
// =============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlicerScreen(
    viewModel: SlicerViewModel,
    onPickFile: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigatePrinter: () -> Unit,
    onNavigateFilaments: () -> Unit,
    onNavigateJobs: () -> Unit,
    onNavigateGcodeViewer3D: () -> Unit,
    onNavigateModelViewer: () -> Unit,
    onNavigatePlacementViewer: () -> Unit,
    onShareGcode: () -> Unit,
    onSaveGcode: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val config by viewModel.config.collectAsState()
    val coreVersion by viewModel.coreVersion.collectAsState()
    val gcodePreview by viewModel.gcodePreview.collectAsState()
    val parsedGcode by viewModel.parsedGcode.collectAsState()
    val showPlateSelector by viewModel.showPlateSelector.collectAsState()
    val showMultiColorDialog by viewModel.showMultiColorDialog.collectAsState()
    val threeMfInfo by viewModel.threeMfInfo.collectAsState()
    val filaments by viewModel.filaments.collectAsState(initial = emptyList())
    val extruderPresets by viewModel.extruderPresets.collectAsState()
    val importLoading by viewModel.importLoading.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val copyCount by viewModel.copyCount.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }

    // MakerWorld import dialog
    if (showImportDialog) {
        com.u1.slicer.ui.ImportUrlDialog(
            isLoading = importLoading,
            progress = importProgress,
            error = importError,
            onImport = { url ->
                viewModel.importFromUrl(url)
            },
            onDismiss = {
                showImportDialog = false
                viewModel.clearImportError()
            }
        )
    }

    // Close dialog on successful import
    LaunchedEffect(state) {
        if (state is SlicerViewModel.SlicerState.ModelLoaded && showImportDialog) {
            showImportDialog = false
        }
    }

    // Plate selector dialog
    if (showPlateSelector && threeMfInfo != null) {
        com.u1.slicer.ui.PlateSelectDialog(
            plates = threeMfInfo!!.plates,
            onSelect = { viewModel.selectPlate(it) },
            onDismiss = { viewModel.dismissPlateSelector() },
            info = threeMfInfo
        )
    }

    // Multi-color assignment dialog
    if (showMultiColorDialog && threeMfInfo != null) {
        com.u1.slicer.ui.MultiColorDialog(
            detectedColors = threeMfInfo!!.detectedColors,
            extruderPresets = extruderPresets,
            filaments = filaments,
            onConfirm = { mapping ->
                viewModel.applyMultiColorAssignments(mapping, extruderPresets, filaments)
            },
            onDismiss = { viewModel.dismissMultiColorDialog() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("U1 Slicer", fontWeight = FontWeight.Bold)
                        Text(
                            coreVersion,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    if (state !is SlicerViewModel.SlicerState.Idle) {
                        IconButton(onClick = { viewModel.clearModel() }) {
                            Icon(Icons.Default.Clear, "Clear model")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.ViewInAr, null) },
                    label = { Text("Slicer") },
                    selected = true,
                    onClick = { }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Print, null) },
                    label = { Text("Printer") },
                    selected = false,
                    onClick = onNavigatePrinter
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Palette, null) },
                    label = { Text("Filaments") },
                    selected = false,
                    onClick = onNavigateFilaments
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, null) },
                    label = { Text("Jobs") },
                    selected = false,
                    onClick = onNavigateJobs
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Settings") },
                    selected = false,
                    onClick = onNavigateSettings
                )
            }
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
            when (val s = state) {
                is SlicerViewModel.SlicerState.Idle -> {
                    IdleContent(
                        onPickFile = onPickFile,
                        onImportUrl = { showImportDialog = true }
                    )
                }
                is SlicerViewModel.SlicerState.Loading -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text("Loading ${s.filename}…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
                is SlicerViewModel.SlicerState.ModelLoaded -> {
                    ModelInfoCard(s.info)
                    if (threeMfInfo != null && threeMfInfo!!.isBambu) {
                        BambuInfoCard(threeMfInfo!!)
                    }
                    if (config.extruderCount > 1) {
                        MultiColorInfoCard(
                            extruderCount = config.extruderCount,
                            colors = threeMfInfo?.detectedColors ?: emptyList(),
                            wipeTowerEnabled = config.wipeTowerEnabled,
                            onToggleWipeTower = {
                                viewModel.updateConfig { c -> c.copy(wipeTowerEnabled = !c.wipeTowerEnabled) }
                            },
                            onReassign = { viewModel.showMultiColorReassign() }
                        )
                    }
                    // Inline 3D model preview (STL and 3MF)
                    val modelPath = viewModel.currentModelPath
                    if (modelPath != null && (
                        modelPath.endsWith(".stl", ignoreCase = true) ||
                        modelPath.endsWith(".3mf", ignoreCase = true)
                    )) {
                        InlineModelPreview(
                            modelFilePath = modelPath,
                            onFullScreen = if (modelPath.endsWith(".stl", ignoreCase = true))
                                onNavigateModelViewer else ({})
                        )
                    }
                    // Arrange button — always available when model is loaded
                    OutlinedButton(
                        onClick = onNavigatePlacementViewer,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.GridOn, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Arrange on Bed")
                    }
                    ConfigCard(config, viewModel::updateConfig, copyCount, viewModel::setCopyCount)
                    SliceButton(onClick = { viewModel.startSlicing() })
                }
                is SlicerViewModel.SlicerState.Slicing -> {
                    SlicingProgressCard(s.progress, s.stage)
                }
                is SlicerViewModel.SlicerState.SliceComplete -> {
                    SliceCompleteCard(
                        result = s.result,
                        onShare = onShareGcode,
                        onSave = onSaveGcode,
                        onSendToPrinter = onNavigatePrinter
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.backToModelLoaded() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Re-slice")
                        }
                        if (parsedGcode != null && parsedGcode!!.layers.isNotEmpty()) {
                            OutlinedButton(
                                onClick = onNavigateGcodeViewer3D,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.ViewInAr, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("3D View")
                            }
                        }
                    }
                    if (gcodePreview.isNotEmpty()) {
                        GcodePreviewCard(gcodePreview)
                    }
                }
                is SlicerViewModel.SlicerState.Error -> {
                    ErrorCard(s.message, onPickFile)
                }
            }
        }
    }
}

// =============================================================================
// UI Components
// =============================================================================

@Composable
fun IdleContent(onPickFile: () -> Unit, onImportUrl: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                "Load a 3D Model",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Supports STL, 3MF, OBJ, STEP",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onPickFile,
                modifier = Modifier.fillMaxWidth(0.6f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Browse Files")
            }
            OutlinedButton(
                onClick = onImportUrl,
                modifier = Modifier.fillMaxWidth(0.6f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Language, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("MakerWorld")
            }
        }
    }
}

@Composable
fun ModelInfoCard(info: ModelInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ViewInAr, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(info.filename, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            InfoRow("Format", info.format.uppercase())
            InfoRow("Dimensions", info.dimensionString)
            InfoRow("Triangles", "%,d".format(info.triangleCount))
            InfoRow("Volumes", info.volumeCount.toString())
            InfoRow("Manifold", if (info.isManifold) "Yes" else "No")
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ConfigCard(
    config: com.u1.slicer.data.SliceConfig,
    onUpdate: ((com.u1.slicer.data.SliceConfig) -> com.u1.slicer.data.SliceConfig) -> Unit,
    copyCount: Int = 1,
    onSetCopyCount: (Int) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Slice Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle settings"
                    )
                }
            }

            // Quick summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickStat("Layer", "%.2f mm".format(config.layerHeight))
                QuickStat("Infill", "%.0f%%".format(config.fillDensity * 100))
                QuickStat("Support", if (config.supportEnabled) "On" else "Off")
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                    Text("Layer Height (mm)", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = config.layerHeight,
                        onValueChange = { v -> onUpdate { it.copy(layerHeight = v) } },
                        valueRange = 0.05f..0.6f,
                        steps = 10
                    )

                    Text("Infill Density (%)", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = config.fillDensity,
                        onValueChange = { v -> onUpdate { it.copy(fillDensity = v) } },
                        valueRange = 0f..1f,
                        steps = 19
                    )

                    // Infill pattern dropdown
                    InfillPatternDropdown(
                        selected = config.fillPattern,
                        onSelect = { v -> onUpdate { it.copy(fillPattern = v) } }
                    )

                    Text("Perimeters", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = config.perimeters.toFloat(),
                        onValueChange = { v -> onUpdate { it.copy(perimeters = v.toInt()) } },
                        valueRange = 1f..6f,
                        steps = 4
                    )

                    Text("Top Solid Layers: ${config.topSolidLayers}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = config.topSolidLayers.toFloat(),
                        onValueChange = { v -> onUpdate { it.copy(topSolidLayers = v.toInt()) } },
                        valueRange = 0f..10f,
                        steps = 9
                    )

                    Text("Bottom Solid Layers: ${config.bottomSolidLayers}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = config.bottomSolidLayers.toFloat(),
                        onValueChange = { v -> onUpdate { it.copy(bottomSolidLayers = v.toInt()) } },
                        valueRange = 0f..10f,
                        steps = 9
                    )

                    // Temperature fields
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConfigTextField(
                            label = "Nozzle \u00B0C",
                            value = config.nozzleTemp.toString(),
                            onValueChange = { v ->
                                v.toIntOrNull()?.let { temp -> onUpdate { it.copy(nozzleTemp = temp) } }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        ConfigTextField(
                            label = "Bed \u00B0C",
                            value = config.bedTemp.toString(),
                            onValueChange = { v ->
                                v.toIntOrNull()?.let { temp -> onUpdate { it.copy(bedTemp = temp) } }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Speed field
                    ConfigTextField(
                        label = "Print Speed (mm/s)",
                        value = config.printSpeed.toInt().toString(),
                        onValueChange = { v ->
                            v.toFloatOrNull()?.let { spd -> onUpdate { it.copy(printSpeed = spd) } }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Brim width
                    Text("Brim Width: ${"%.1f".format(config.brimWidth)} mm", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = config.brimWidth,
                        onValueChange = { v -> onUpdate { it.copy(brimWidth = v) } },
                        valueRange = 0f..20f,
                        steps = 19
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Support Enabled")
                        Switch(
                            checked = config.supportEnabled,
                            onCheckedChange = { v -> onUpdate { it.copy(supportEnabled = v) } }
                        )
                    }

                    // Multiple copies
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Text("Copies: $copyCount", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = copyCount.toFloat(),
                        onValueChange = { v -> onSetCopyCount(v.toInt()) },
                        valueRange = 1f..16f,
                        steps = 14
                    )

                    InfoRow("Nozzle Diameter", "${config.nozzleDiameter} mm")
                    InfoRow("Filament", config.filamentType)
                    InfoRow("Build Volume", "270 x 270 x 270 mm")
                }
            }
        }
    }
}

@Composable
fun QuickStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfillPatternDropdown(selected: String, onSelect: (String) -> Unit) {
    val patterns = listOf("gyroid", "grid", "honeycomb", "line", "rectilinear", "triangles", "cubic")
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Infill Pattern") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            patterns.forEach { pattern ->
                DropdownMenuItem(
                    text = { Text(pattern.replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onSelect(pattern)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun ConfigTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        label = { Text(label) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}

@Composable
fun SliceButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text("Slice Model", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SlicingProgressCard(progress: Int, stage: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                progress = progress / 100f,
                modifier = Modifier.size(80.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 6.dp
            )
            Text("$progress%", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(stage, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun SliceCompleteCard(
    result: SliceResult,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onSendToPrinter: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1B3D1E)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50))
                Spacer(Modifier.width(8.dp))
                Text("Slicing Complete", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = Color(0xFF81C784))
            }
            Divider(color = Color.White.copy(alpha = 0.1f))
            InfoRow("Layers", result.totalLayers.toString())
            InfoRow("Est. Time", result.estimatedTimeFormatted)
            InfoRow("Filament", result.estimatedFilamentFormatted)

            Spacer(Modifier.height(4.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share")
                }
                OutlinedButton(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.SaveAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
            }

            Button(
                onClick = onSendToPrinter,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Print, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send to Printer", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun GcodePreviewCard(gcode: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A2E)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text("G-code Preview", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0D0D1A))
                    .padding(12.dp)
            ) {
                Text(
                    gcode,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF80CBC4),
                    maxLines = 30,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun BambuInfoCard(info: com.u1.slicer.bambu.ThreeMfInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A2A3D)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = Color(0xFF4FC3F7))
                Spacer(Modifier.width(8.dp))
                Text("Bambu Studio File", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = Color(0xFF4FC3F7))
                Spacer(Modifier.width(4.dp))
                Text("(sanitized)", style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4FC3F7).copy(alpha = 0.6f))
            }
            if (info.detectedExtruderCount > 1) {
                InfoRow("Extruders Detected", info.detectedExtruderCount.toString())
            }
            if (info.detectedColors.isNotEmpty()) {
                InfoRow("Colors", info.detectedColors.joinToString(", "))
            }
            if (info.hasPaintData) {
                InfoRow("Paint Data", "Yes (per-triangle)")
            }
            if (info.isMultiPlate) {
                InfoRow("Plates", info.plates.size.toString())
            }
        }
    }
}

@Composable
fun MultiColorInfoCard(
    extruderCount: Int,
    colors: List<String>,
    wipeTowerEnabled: Boolean,
    onToggleWipeTower: () -> Unit,
    onReassign: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A3D2A)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Palette, null, tint = Color(0xFF81C784))
                Spacer(Modifier.width(8.dp))
                Text("Multi-Color Mode", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = Color(0xFF81C784))
            }
            InfoRow("Extruders", extruderCount.toString())
            if (colors.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Colors: ", style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f))
                    colors.take(4).forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(com.u1.slicer.ui.parseHexColor(hex))
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Wipe Tower", style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f))
                Switch(
                    checked = wipeTowerEnabled,
                    onCheckedChange = { onToggleWipeTower() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF81C784),
                        checkedTrackColor = Color(0xFF81C784).copy(alpha = 0.4f)
                    )
                )
            }
            OutlinedButton(
                onClick = onReassign,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reassign Filaments")
            }
        }
    }
}

@Composable
fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF3D1A1A)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Error", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.error)
            }
            Text(message, color = Color.White.copy(alpha = 0.8f))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Try Again")
            }
        }
    }
}

@Composable
fun InlineModelPreview(
    modelFilePath: String,
    onFullScreen: () -> Unit
) {
    var mesh by remember { mutableStateOf<com.u1.slicer.viewer.MeshData?>(null) }
    var viewerView by remember { mutableStateOf<com.u1.slicer.viewer.ModelViewerView?>(null) }

    LaunchedEffect(modelFilePath) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = java.io.File(modelFilePath)
                mesh = when {
                    modelFilePath.endsWith(".stl", ignoreCase = true) ->
                        com.u1.slicer.viewer.StlParser.parse(file)
                    modelFilePath.endsWith(".3mf", ignoreCase = true) ->
                        com.u1.slicer.viewer.ThreeMfMeshParser.parse(file)
                    else -> null
                }
            } catch (_: Throwable) { }
        }
    }

    LaunchedEffect(mesh, viewerView) {
        val m = mesh; val v = viewerView
        if (m != null && v != null) v.setMesh(m)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    com.u1.slicer.viewer.ModelViewerView(ctx).also { view ->
                        viewerView = view
                        mesh?.let { view.setMesh(it) }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // Fullscreen button overlay
            IconButton(
                onClick = onFullScreen,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.Default.Fullscreen,
                    "Full screen",
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}
