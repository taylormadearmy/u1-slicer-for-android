package com.u1.slicer

import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.SliceResult
import com.u1.slicer.debug.TestCommandReceiver
import com.u1.slicer.navigation.U1NavGraph
import com.u1.slicer.navigation.Routes
import com.u1.slicer.printer.PrinterViewModel
import com.u1.slicer.ui.JobsScreen
import com.u1.slicer.ui.PrinterScreen
import com.u1.slicer.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val diagnostics by lazy { DiagnosticsStore(this) }
    private val viewModel: SlicerViewModel by viewModels()
    private val printerViewModel: PrinterViewModel by viewModels()
    private var testReceiver: TestCommandReceiver? = null

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
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    viewModel.loadModel(uri)
                    intent.action = null
                    intent.data = null
                }
            }
            Intent.ACTION_SEND -> {
                // Try file URI first (EXTRA_STREAM)
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    Log.i("SlicerVM", "ACTION_SEND with EXTRA_STREAM: $uri")
                    viewModel.loadModel(uri)
                    intent.action = null
                    intent.removeExtra(Intent.EXTRA_STREAM)
                    return
                }

                // Fallback: text URL (e.g. Bambu Handy shares MakerWorld URLs as text/plain)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    Log.i("SlicerVM", "ACTION_SEND with EXTRA_TEXT: $text")
                    viewModel.importFromSharedUrl(text)
                    intent.action = null
                    intent.removeExtra(Intent.EXTRA_TEXT)
                }
            }
        }
    }

    private fun clearStaleCacheOnUpgrade() {
        val prefs = getSharedPreferences("upgrade_state", MODE_PRIVATE)
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        } catch (_: Exception) { -1 }
        val apkLastUpdate = try {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        } catch (_: Exception) { 0L }

        val detector = UpgradeDetector()
        val saved = UpgradeDetector.State(
            lastVersionCode = prefs.getInt("lastVersionCode", -1),
            savedApkUpdateTime = prefs.getLong("lastApkUpdateTime", 0L),
        )
        val current = UpgradeDetector.Current(
            versionCode = currentVersion,
            apkUpdateTime = apkLastUpdate,
        )

        when (detector.detect(saved, current)) {
            UpgradeDetector.Result.APK_CHANGED -> {
                // APK changed — aggressively delete ALL cached 3MF and G-code files.
                // We used to force a relaunch here to try to recover stale native state,
                // but v1.3.64 showed the real fix was the guarded first post-upgrade slice
                // path, not the restart itself.
                val count = detector.filesToClearOnUpgrade(filesDir).onEach { it.delete() }.size
                val reason = if (saved.lastVersionCode != currentVersion)
                    "version ${saved.lastVersionCode}→$currentVersion" else "APK reinstalled"
                Log.i("SlicerVM", "APK change detected ($reason): cleared $count cached files, continuing in current process")
                diagnostics.recordEvent(
                    "upgrade_check",
                    mapOf(
                        "result" to "APK_CHANGED",
                        "reason" to reason,
                        "clearedFiles" to count,
                        "savedVersionCode" to saved.lastVersionCode,
                        "savedApkUpdateTime" to saved.savedApkUpdateTime,
                        "currentVersionCode" to currentVersion,
                        "currentApkUpdateTime" to apkLastUpdate
                    )
                )
                prefs.edit()
                    .putInt("lastVersionCode", currentVersion)
                    .putLong("lastApkUpdateTime", apkLastUpdate)
                    .apply()
                diagnostics.markUpgradePendingForCurrentSession("apk_changed", null)
            }
            UpgradeDetector.Result.SAME_APK -> {
                // Same APK — still clear known transient cache patterns as a safety net.
                val count = detector.filesToClearOnStartup(filesDir).onEach { it.delete() }.size
                diagnostics.recordEvent(
                    "upgrade_check",
                    mapOf(
                        "result" to "SAME_APK",
                        "clearedFiles" to count,
                        "savedVersionCode" to saved.lastVersionCode,
                        "savedApkUpdateTime" to saved.savedApkUpdateTime,
                        "currentVersionCode" to currentVersion,
                        "currentApkUpdateTime" to apkLastUpdate
                    )
                )
                if (count > 0) {
                    Log.i("SlicerVM", "Cleared $count cached 3MF files on startup")
                }
            }
            UpgradeDetector.Result.FIRST_INSTALL -> {
                // Nothing to clear on first install.
                diagnostics.recordEvent(
                    "upgrade_check",
                    mapOf(
                        "result" to "FIRST_INSTALL",
                        "currentVersionCode" to currentVersion,
                        "currentApkUpdateTime" to apkLastUpdate
                    )
                )
            }
        }
        prefs.edit()
            .putInt("lastVersionCode", currentVersion)
            .putLong("lastApkUpdateTime", apkLastUpdate)
            .apply()
    }

    override fun onDestroy() {
        testReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        testReceiver = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics.recordEvent(
            "app_launch",
            mapOf(
                "savedInstanceState" to (savedInstanceState != null),
                "intentAction" to intent?.action,
                "sessionStartedWithPendingRestart" to diagnostics.sessionStartedAfterPendingRestart()
            )
        )

        // Request notification permission for Android 13+ (required for SlicingService
        // foreground notification to be visible)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        // Clear stale cached 3MF files on version upgrade.
        // The sanitizer/embedder output format changes between versions — stale files
        // cause "Coordinate outside allowed range" Clipper errors in OrcaSlicer.
        clearStaleCacheOnUpgrade()

        // Only handle VIEW intents on fresh launch, not on recreation
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        // Register debug test command receiver (debug builds only)
        val isDebug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (isDebug) {
            // Navigation callback will be set once Compose creates the navController
            var navigateCallback: ((String) -> Unit) = { screen ->
                Log.w("TestCmd", "NavController not ready yet, ignoring navigate to: $screen")
            }
            testReceiver = TestCommandReceiver(viewModel, printerViewModel) { screen ->
                navigateCallback(screen)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(testReceiver, TestCommandReceiver.intentFilter(), RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(testReceiver, TestCommandReceiver.intentFilter())
            }
            Log.i("TestCmd", "TestCommandReceiver registered (debug build)")

            // Store navigateCallback setter for use inside Compose
            viewModel.setNavigateCallback = { cb -> navigateCallback = cb }
        }

        setContent {
            U1SlicerTheme {
                val navController = rememberNavController()

                // Shared tab navigation helpers — single-top + pop to start to avoid stacking
                val navigateTab = { route: String ->
                    navController.navigate(route) {
                        popUpTo(Routes.PREPARE) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                // Wire up the test receiver's navigate callback now that we have navController
                if (isDebug) {
                    LaunchedEffect(navController) {
                        viewModel.setNavigateCallback?.invoke { screen ->
                            val route = when (screen.lowercase()) {
                                "slicer", "prepare" -> Routes.PREPARE
                                "preview" -> Routes.PREVIEW
                                "printer" -> Routes.PRINTER
                                "jobs" -> Routes.JOBS
                                "settings" -> Routes.SETTINGS
                                else -> screen // allow direct route names
                            }
                            navigateTab(route)
                        }
                    }
                }
                val pickFileMimeTypes = arrayOf(
                    "application/sla",
                    "model/stl",
                    "application/vnd.ms-pki.stl",
                    "application/octet-stream",
                    "model/3mf",
                    "application/vnd.ms-3mfdocument",
                    "model/obj",
                    "*/*"
                )

                U1NavGraph(
                    navController = navController,
                    viewModel = viewModel,
                    printerViewModel = printerViewModel,
                    onPickFile = { filePickerLauncher.launch(pickFileMimeTypes) },
                    onSaveGcode = { gcodeSaveLauncher.launch("output.gcode") },
                    prepareContent = {
                        PrepareScreen(
                            viewModel = viewModel,
                            onPickFile = { filePickerLauncher.launch(pickFileMimeTypes) },
                            onNavigatePrepare = { },
                            onNavigatePreview = { navigateTab(Routes.PREVIEW) },
                            onNavigateSettings = { navigateTab(Routes.SETTINGS) },
                            onNavigatePrinter = { navigateTab(Routes.PRINTER) },
                            onNavigateJobs = { navigateTab(Routes.JOBS) },
                            onNavigateModelViewer = { navController.navigate(Routes.MODEL_VIEWER) }
                        )
                    },
                    previewContent = {
                        PreviewScreen(
                            viewModel = viewModel,
                            onNavigatePrepare = { navigateTab(Routes.PREPARE) },
                            onNavigatePreview = { },
                            onNavigateSettings = { navigateTab(Routes.SETTINGS) },
                            onNavigatePrinter = { navigateTab(Routes.PRINTER) },
                            onSendToPrinter = { gcodePath ->
                                printerViewModel.sendAndPrint(gcodePath)
                                navigateTab(Routes.PRINTER)
                            },
                            onUploadOnly = { gcodePath ->
                                printerViewModel.sendUploadOnly(gcodePath)
                                navigateTab(Routes.PRINTER)
                            },
                            onNavigateJobs = { navigateTab(Routes.JOBS) },
                            onNavigateGcodeViewer3D = { navController.navigate(Routes.GCODE_VIEWER_3D) },
                            onShareGcode = { viewModel.shareGcode() },
                            onSaveGcode = { gcodeSaveLauncher.launch("output.gcode") }
                        )
                    },
                    printerContent = {
                        val filaments by viewModel.filaments.collectAsState(initial = emptyList())
                        PrinterScreen(
                            viewModel = printerViewModel,
                            filaments = filaments,
                            onNavigateSettings = { navigateTab(Routes.SETTINGS) },
                            onNavigatePrepare = { navigateTab(Routes.PREPARE) },
                            onNavigatePreview = { navigateTab(Routes.PREVIEW) },
                            onNavigatePrinter = { },
                            onNavigateJobs = { navigateTab(Routes.JOBS) }
                        )
                    },
                    jobsContent = {
                        val jobs by viewModel.sliceJobs.collectAsState(initial = emptyList())
                        JobsScreen(
                            jobs = jobs,
                            onDelete = { viewModel.deleteJob(it) },
                            onDeleteAll = { viewModel.deleteAllJobs() },
                            onShare = { viewModel.shareJobGcode(it) },
                            onNavigatePrepare = { navigateTab(Routes.PREPARE) },
                            onNavigatePreview = { navigateTab(Routes.PREVIEW) },
                            onNavigatePrinter = { navigateTab(Routes.PRINTER) },
                            onNavigateJobs = { },
                            onNavigateSettings = { navigateTab(Routes.SETTINGS) }
                        )
                    },
                    settingsContent = {
                        SettingsScreen(
                            viewModel = viewModel,
                            printerViewModel = printerViewModel,
                            onShareDiagnostics = { viewModel.shareDiagnostics() },
                            onNavigateFilaments = { navController.navigate(Routes.FILAMENTS) },
                            onNavigatePrepare = { navigateTab(Routes.PREPARE) },
                            onNavigatePreview = { navigateTab(Routes.PREVIEW) },
                            onNavigatePrinter = { navigateTab(Routes.PRINTER) },
                            onNavigateJobs = { navigateTab(Routes.JOBS) },
                            onNavigateSettings = { }
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
        primary = Color(0xFF2196F3),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF0D3A6E),
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
// Shared Bottom Navigation Bar
// =============================================================================
@Composable
fun U1BottomNavBar(
    selectedTab: String,
    onNavigatePrepare: () -> Unit,
    onNavigatePreview: () -> Unit,
    onNavigatePrinter: () -> Unit,
    onNavigateJobs: () -> Unit,
    onNavigateSettings: () -> Unit
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        U1BottomNavItems(
            selectedTab = selectedTab,
            onNavigatePrepare = onNavigatePrepare,
            onNavigatePreview = onNavigatePreview,
            onNavigatePrinter = onNavigatePrinter,
            onNavigateJobs = onNavigateJobs,
            onNavigateSettings = onNavigateSettings
        )
    }
}

@Composable
fun RowScope.U1BottomNavItems(
    selectedTab: String,
    onNavigatePrepare: () -> Unit,
    onNavigatePreview: () -> Unit,
    onNavigatePrinter: () -> Unit,
    onNavigateJobs: () -> Unit,
    onNavigateSettings: () -> Unit
) {
    NavigationBarItem(
        icon = { Icon(Icons.Default.ViewInAr, null) },
        label = { Text("Prepare") },
        selected = selectedTab == "prepare",
        onClick = onNavigatePrepare
    )
    NavigationBarItem(
        icon = { Icon(Icons.Default.Layers, null) },
        label = { Text("Preview") },
        selected = selectedTab == "preview",
        onClick = onNavigatePreview
    )
    NavigationBarItem(
        icon = { Icon(Icons.Default.Print, null) },
        label = { Text("Printer") },
        selected = selectedTab == "printer",
        onClick = onNavigatePrinter
    )
    NavigationBarItem(
        icon = { Icon(Icons.Default.History, null) },
        label = { Text("Jobs") },
        selected = selectedTab == "jobs",
        onClick = onNavigateJobs
    )
    NavigationBarItem(
        icon = { Icon(Icons.Default.Settings, null) },
        label = { Text("Settings") },
        selected = selectedTab == "settings",
        onClick = onNavigateSettings
    )
}

// =============================================================================
// Prepare Screen
// =============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepareScreen(
    viewModel: SlicerViewModel,
    onPickFile: () -> Unit,
    onNavigatePrepare: () -> Unit,
    onNavigatePreview: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigatePrinter: () -> Unit,
    onNavigateJobs: () -> Unit,
    onNavigateModelViewer: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val config by viewModel.config.collectAsState()
    val slicingOverrides by viewModel.slicingOverrides.collectAsState()
    val coreVersion by viewModel.coreVersion.collectAsState()
    val showPlateSelector by viewModel.showPlateSelector.collectAsState()
    val showMultiColorDialog by viewModel.showMultiColorDialog.collectAsState()
    val colorMapping by viewModel.colorMapping.collectAsState()
    val threeMfInfo by viewModel.threeMfInfo.collectAsState()
    val filaments by viewModel.filaments.collectAsState(initial = emptyList())
    val extruderPresets by viewModel.extruderPresets.collectAsState()
    val copyCount by viewModel.copyCount.collectAsState()
    val modelScale by viewModel.modelScale.collectAsState()
    val extruderColors by viewModel.activeExtruderColors.collectAsState()

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
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                U1BottomNavItems(
                    selectedTab = "prepare",
                    onNavigatePrepare = onNavigatePrepare,
                    onNavigatePreview = onNavigatePreview,
                    onNavigatePrinter = onNavigatePrinter,
                    onNavigateJobs = onNavigateJobs,
                    onNavigateSettings = onNavigateSettings
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        // Determine what to show — even during Slicing/SliceComplete, Prepare shows the model
        val modelLoaded = state is SlicerViewModel.SlicerState.ModelLoaded ||
                state is SlicerViewModel.SlicerState.Slicing ||
                state is SlicerViewModel.SlicerState.SliceComplete

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    // Extra top padding when model loaded to make room for the sticky slice button
                    .padding(top = if (modelLoaded) 72.dp else 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when {
                    state is SlicerViewModel.SlicerState.Idle -> {
                        PrepareEmptyState(
                            onPickFile = onPickFile
                        )
                    }
                    state is SlicerViewModel.SlicerState.Loading -> {
                        val s = state as SlicerViewModel.SlicerState.Loading
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
                    state is SlicerViewModel.SlicerState.Error -> {
                        ErrorCard(
                            (state as SlicerViewModel.SlicerState.Error).message,
                            onPickFile,
                            onRestart = { viewModel.restartApp() },
                            onShareDiagnostics = { viewModel.shareDiagnostics() }
                        )
                    }
                    modelLoaded -> {
                        val info = when (val s = state) {
                            is SlicerViewModel.SlicerState.ModelLoaded -> s.info
                            is SlicerViewModel.SlicerState.Slicing -> null
                            is SlicerViewModel.SlicerState.SliceComplete -> null
                            else -> null
                        }
                        // Inline 3D model preview
                        val modelPath = viewModel.previewModelPath
                        if (modelPath != null && (
                            modelPath.endsWith(".stl", ignoreCase = true) ||
                            modelPath.endsWith(".3mf", ignoreCase = true)
                        )) {
                            var showInfoDialog by remember { mutableStateOf(false) }
                            val positions = viewModel.getPlacementPositions()
                            val loadedInfo = info
                            InlineModelPreview(
                                modelFilePath = modelPath,
                                onFullScreen = if (modelPath.endsWith(".stl", ignoreCase = true))
                                    onNavigateModelViewer else ({}),
                                extruderColors = extruderColors,
                                extruderMap = viewModel.buildExtruderMap(),
                                colorMapping = colorMapping,
                                objectPositions = positions,
                                modelSizeX = loadedInfo?.sizeX ?: 0f,
                                modelSizeY = loadedInfo?.sizeY ?: 0f,
                                wipeTowerEnabled = config.wipeTowerEnabled,
                                wipeTowerX = config.wipeTowerX,
                                wipeTowerY = config.wipeTowerY,
                                wipeTowerWidth = config.wipeTowerWidth,
                                wipeTowerDepth = config.wipeTowerWidth,
                                onPositionsChanged = { pos, towerPos ->
                                    viewModel.applyPlacementPositions(pos, towerPos)
                                },
                                onInfoClick = { showInfoDialog = true },
                                modelScale = modelScale
                            )
                            if (showInfoDialog && loadedInfo != null) {
                                ModelInfoDialog(
                                    info = loadedInfo,
                                    threeMfInfo = threeMfInfo,
                                    config = config,
                                    onToggleWipeTower = {
                                        viewModel.updateConfig { c -> c.copy(wipeTowerEnabled = !c.wipeTowerEnabled) }
                                    },
                                    onReassign = { viewModel.showMultiColorReassign() },
                                    onDismiss = { showInfoDialog = false }
                                )
                            }
                        }
                        // Inline extruder/color assignment + prime tower toggle
                        PrintSetupSection(
                            detectedColors = threeMfInfo?.detectedColors ?: emptyList(),
                            colorMapping = colorMapping,
                            extruderPresets = extruderPresets,
                            filaments = filaments,
                            wipeTowerEnabled = config.wipeTowerEnabled,
                            extruderCount = config.extruderCount,
                            onMappingChange = { newMapping ->
                                viewModel.applyMultiColorAssignments(newMapping, extruderPresets, filaments)
                            },
                            onToggleWipeTower = {
                                viewModel.updateConfig { c -> c.copy(wipeTowerEnabled = !c.wipeTowerEnabled) }
                            },
                            onAutoMap = {
                                viewModel.reAutoMapColors(extruderPresets, filaments)
                            }
                        )
                        // Scale & copies controls
                        ScaleSection(
                            scale = modelScale,
                            onScaleChange = { viewModel.setModelScale(it) },
                            copyCount = copyCount,
                            onSetCopyCount = viewModel::setCopyCount
                        )
                        // Single-color extruder picker (hidden for multi-color models)
                        if (colorMapping == null && state is SlicerViewModel.SlicerState.ModelLoaded) {
                            val selectedExtruder by viewModel.selectedExtruder.collectAsState()
                            ExtruderPickerRow(
                                selectedExtruder = selectedExtruder,
                                extruderPresets = extruderPresets,
                                onSelect = { viewModel.setSelectedExtruder(it) }
                            )
                        }
                        ConfigCard(
                            config, viewModel::updateConfig,
                            slicingOverrides = slicingOverrides,
                            onOverridesChange = { viewModel.saveSlicingOverrides(it) }
                        )
                    }
                }
            }

            // Sticky slice button overlayed at the top
            if (modelLoaded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background,
                                    MaterialTheme.colorScheme.background,
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .align(Alignment.TopCenter)
                ) {
                    SliceButton(onClick = {
                        viewModel.startSlicing()
                        onNavigatePreview()
                    })
                }
            }
        }
    }
}

// =============================================================================
// Prepare Empty State — dimmed bed background with + button overlay
// =============================================================================
@Composable
fun PrepareEmptyState(onPickFile: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Decorative grid lines to suggest build plate
        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val gridColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.04f)
            val step = 30.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(x, 0f),
                    androidx.compose.ui.geometry.Offset(x, size.height))
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, y),
                    androidx.compose.ui.geometry.Offset(size.width, y))
                y += step
            }
        }

        // Semi-transparent overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
        )

        // Content overlay
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FloatingActionButton(
                onClick = onPickFile,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.Add, "Load model", modifier = Modifier.size(32.dp))
            }
            Text(
                "Load a 3D Model",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Supports STL, 3MF, OBJ, STEP",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

// =============================================================================
// Preview Screen
// =============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    viewModel: SlicerViewModel,
    onNavigatePrepare: () -> Unit,
    onNavigatePreview: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigatePrinter: () -> Unit,
    onSendToPrinter: (gcodePath: String) -> Unit = {},
    onUploadOnly: (gcodePath: String) -> Unit = {},
    onNavigateJobs: () -> Unit,
    onNavigateGcodeViewer3D: () -> Unit,
    onShareGcode: () -> Unit,
    onSaveGcode: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val coreVersion by viewModel.coreVersion.collectAsState()
    val parsedGcode by viewModel.parsedGcode.collectAsState()
    val extruderColors by viewModel.activeExtruderColors.collectAsState()
    val config by viewModel.config.collectAsState()

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
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                U1BottomNavItems(
                    selectedTab = "preview",
                    onNavigatePrepare = onNavigatePrepare,
                    onNavigatePreview = onNavigatePreview,
                    onNavigatePrinter = onNavigatePrinter,
                    onNavigateJobs = onNavigateJobs,
                    onNavigateSettings = onNavigateSettings
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
                is SlicerViewModel.SlicerState.Slicing -> {
                    SlicingProgressCard(s.progress, s.stage)
                }
                is SlicerViewModel.SlicerState.SliceComplete -> {
                    SliceCompleteCard(
                        result = s.result,
                        onShare = onShareGcode,
                        onSave = onSaveGcode,
                        onSendToPrinter = { onSendToPrinter(s.result.gcodePath) },
                        onUploadOnly = { onUploadOnly(s.result.gcodePath) },
                        perExtruderFilamentMm = parsedGcode?.perExtruderFilamentMm ?: emptyList(),
                        bedTemp = config.bedTemp,
                        extruderColors = extruderColors.filter { it.isNotBlank() }
                    )
                    // Inline 3D G-code preview (auto-downsampled for large models)
                    if (parsedGcode != null && parsedGcode!!.layers.isNotEmpty()) {
                        InlineGcodePreview(
                            parsedGcode = parsedGcode!!,
                            extruderColors = extruderColors,
                            slicerLayerCount = s.result.totalLayers,
                            onExpand = onNavigateGcodeViewer3D
                        )
                    }
                }
                is SlicerViewModel.SlicerState.Error -> {
                    ErrorCard(
                        s.message,
                        onRetry = { onNavigatePrepare() },
                        onRestart = { viewModel.restartApp() },
                        onShareDiagnostics = { viewModel.shareDiagnostics() }
                    )
                }
                else -> {
                    // Empty state — no slice results yet, show empty bed
                    PreviewEmptyState()
                }
            }
        }
    }
}

// =============================================================================
// Preview Empty State — empty build plate
// =============================================================================
@Composable
fun PreviewEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Decorative grid lines to suggest build plate
        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val gridColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.04f)
            val step = 30.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(x, 0f),
                    androidx.compose.ui.geometry.Offset(x, size.height))
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, y),
                    androidx.compose.ui.geometry.Offset(size.width, y))
                y += step
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Layers,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
            Text(
                "No slice results",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                "Load a model and slice it to see the preview",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}

// =============================================================================
// UI Components
// =============================================================================

// IdleContent removed — replaced by PrepareEmptyState

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
    onSetCopyCount: (Int) -> Unit = {},
    slicingOverrides: com.u1.slicer.data.SlicingOverrides = com.u1.slicer.data.SlicingOverrides(),
    onOverridesChange: ((com.u1.slicer.data.SlicingOverrides) -> Unit)? = null
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                    if (onOverridesChange != null) {
                        com.u1.slicer.ui.SlicingOverridesAccordion(
                            overrides = slicingOverrides,
                            onOverridesChange = onOverridesChange
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
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
    // Track elapsed time so user knows slicing is still active even when
    // PrusaSlicer doesn't report sub-step progress for a while.
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            elapsedSeconds++
        }
    }
    fun formatTime(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
    val elapsed = remember(elapsedSeconds) { formatTime(elapsedSeconds) }
    // Estimate remaining time from elapsed + progress. Wait until we have
    // meaningful data (>5% progress and >5s elapsed) to avoid wild swings.
    val estimate = remember(elapsedSeconds, progress) {
        if (progress > 5 && elapsedSeconds > 5) {
            val totalEstSec = (elapsedSeconds * 100.0 / progress).toInt()
            val remainingSec = (totalEstSec - elapsedSeconds).coerceAtLeast(0)
            "~${formatTime(remainingSec)} remaining"
        } else null
    }

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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp)) {
                // Indeterminate ring behind the determinate one — always spinning
                // so user sees motion even when % is "stuck" on a long step.
                CircularProgressIndicator(
                    modifier = Modifier.size(80.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    strokeWidth = 6.dp
                )
                CircularProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.size(80.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 6.dp
                )
            }
            Text("$progress%", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                stage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (estimate != null) {
                Text(
                    estimate,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Text(
                "Elapsed: $elapsed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
fun SliceCompleteCard(
    result: SliceResult,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onSendToPrinter: () -> Unit = {},
    onUploadOnly: () -> Unit = {},
    perExtruderFilamentMm: List<Float> = emptyList(),
    bedTemp: Int = 0,
    extruderColors: List<String> = emptyList()
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
            if (bedTemp > 0) {
                InfoRow("Bed Temp", "${bedTemp}\u00B0C")
            }
            if (perExtruderFilamentMm.size > 1) {
                perExtruderFilamentMm.forEachIndexed { i, mm ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Colour swatch
                        val colorHex = extruderColors.getOrNull(i) ?: "#808080"
                        val color = try {
                            Color(android.graphics.Color.parseColor(colorHex))
                        } catch (_: Exception) {
                            Color.Gray
                        }
                        Canvas(modifier = Modifier.size(12.dp)) {
                            drawCircle(color = color)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "E${i + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.width(28.dp)
                        )
                        Text(
                            "%.0f mm (%.1f g)".format(mm, mm * 0.00125f * 1.24f),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSendToPrinter,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Print, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Print", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onUploadOnly,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Upload")
                }
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
fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
    onRestart: (() -> Unit)? = null,
    onShareDiagnostics: (() -> Unit)? = null
) {
    val isClipperError = message.contains("Coordinate outside allowed range", ignoreCase = true) ||
        message.contains("clipper", ignoreCase = true)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Try Again")
                }
                if (isClipperError && onRestart != null) {
                    OutlinedButton(
                        onClick = onRestart,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Text("Restart App")
                    }
                }
                if (isClipperError && onShareDiagnostics != null) {
                    OutlinedButton(onClick = onShareDiagnostics) {
                        Text("Share Diagnostics")
                    }
                }
            }
        }
    }
}

@Composable
fun InlineModelPreview(
    modelFilePath: String,
    onFullScreen: () -> Unit,
    extruderColors: List<String> = emptyList(),
    extruderMap: Map<Int, Byte>? = null,
    colorMapping: List<Int>? = null,
    // Placement mode
    objectPositions: FloatArray? = null,
    modelSizeX: Float = 0f,
    modelSizeY: Float = 0f,
    wipeTowerEnabled: Boolean = false,
    wipeTowerX: Float = 0f,
    wipeTowerY: Float = 0f,
    wipeTowerWidth: Float = 60f,
    wipeTowerDepth: Float = 60f,
    onPositionsChanged: ((FloatArray, Pair<Float, Float>) -> Unit)? = null,
    onInfoClick: (() -> Unit)? = null,
    modelScale: SlicerViewModel.ModelScale = SlicerViewModel.ModelScale()
) {
    var mesh by remember { mutableStateOf<com.u1.slicer.viewer.MeshData?>(null) }
    var viewerView by remember { mutableStateOf<com.u1.slicer.viewer.ModelViewerView?>(null) }
    val placementEnabled = objectPositions != null && onPositionsChanged != null

    // Mutable copies of positions for drag interaction
    val objPositions = remember(objectPositions) {
        objectPositions?.copyOf() ?: floatArrayOf()
    }
    var towerX by remember(wipeTowerX) { mutableFloatStateOf(wipeTowerX) }
    var towerY by remember(wipeTowerY) { mutableFloatStateOf(wipeTowerY) }

    LaunchedEffect(modelFilePath, extruderMap, colorMapping?.size) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = java.io.File(modelFilePath)
                mesh = when {
                    modelFilePath.endsWith(".stl", ignoreCase = true) ->
                        com.u1.slicer.viewer.StlParser.parse(file)
                    modelFilePath.endsWith(".3mf", ignoreCase = true) ->
                        com.u1.slicer.viewer.ThreeMfMeshParser.parse(
                            file,
                            extruderMap = extruderMap,
                            detectedColorCount = colorMapping?.size ?: 0
                        )
                    else -> null
                }
            } catch (_: Throwable) { }
        }
    }

    // Track whether we've already uploaded this mesh to avoid redundant VBO re-uploads
    // when only colors/mapping change (B22 fix).
    var lastSetMesh by remember { mutableStateOf<com.u1.slicer.viewer.MeshData?>(null) }

    // Combined effect: keys on mesh + viewer + colors + mapping so it fires when ANY changes.
    // Fixes B22 race: previously mesh loaded on IO (slow) while colors arrived via StateFlow
    // (fast). Separate LaunchedEffects had timing gaps — colors effect fired before mesh was
    // ready (skip), then mesh effect read stale empty colors from closure (skip).
    LaunchedEffect(mesh, viewerView, extruderColors, colorMapping) {
        val m = mesh; val v = viewerView
        if (m != null && v != null) {
            // Only call setMesh when the mesh instance actually changed
            if (m !== lastSetMesh) {
                v.setMesh(m)
                lastSetMesh = m
            }
            if (extruderColors.isNotEmpty()) {
                v.setExtruderColors(extruderColors)
            }
            // Apply recolor when we have both mesh and colors
            if (m.hasPerVertexColor && extruderColors.isNotEmpty()) {
                fun toRgba(hex: String): FloatArray {
                    if (hex.isBlank()) return floatArrayOf(0.7f, 0.7f, 0.7f, 1f)
                    return try {
                        val c = android.graphics.Color.parseColor(hex)
                        floatArrayOf(
                            android.graphics.Color.red(c) / 255f,
                            android.graphics.Color.green(c) / 255f,
                            android.graphics.Color.blue(c) / 255f, 1f
                        )
                    } catch (_: Exception) { floatArrayOf(0.91f, 0.48f, 0f, 1f) }
                }
                val palette = if (colorMapping != null) {
                    // Multi-color: remap mesh indices → slot colors
                    colorMapping.map { slot -> toRgba(extruderColors.getOrElse(slot) { "" }) }
                } else {
                    // Single-color: palette[0] = first non-blank color
                    listOf(toRgba(extruderColors.firstOrNull { it.isNotBlank() } ?: ""))
                }
                v.recolorMesh(palette)
            }
        } else if (v != null && extruderColors.isNotEmpty()) {
            // Mesh not ready yet but colors changed — just update instance colors
            v.setExtruderColors(extruderColors)
        }
    }

    // Update renderer with model scale
    LaunchedEffect(viewerView, modelScale) {
        val v = viewerView ?: return@LaunchedEffect
        v.renderer.modelScale = floatArrayOf(modelScale.x, modelScale.y, modelScale.z)
        v.requestRender()
    }

    // Update renderer with placement data
    LaunchedEffect(viewerView, placementEnabled, objPositions, towerX, towerY) {
        val v = viewerView ?: return@LaunchedEffect
        if (placementEnabled) {
            v.placementMode = true
            // Only reset the camera when placement positions are first assigned (not on every
            // drag update — setting pendingCameraReset on every recomposition would reset
            // azimuth/elevation/pan mid-drag, corrupting subsequent screenToBed calculations).
            val firstPlacement = v.renderer.instancePositions == null
            v.renderer.instancePositions = objPositions
            if (firstPlacement) v.renderer.pendingCameraReset = true
            if (wipeTowerEnabled) {
                v.renderer.wipeTower = com.u1.slicer.viewer.ModelRenderer.WipeTowerInfo(
                    towerX, towerY, wipeTowerWidth, wipeTowerDepth
                )
            }
            v.onObjectMoved = { index, dx, dy ->
                val count = objPositions.size / 2
                if (index < count) {
                    // Move object — use scaled size for bed bounds
                    val i = index
                    val scaledSizeX = modelSizeX * modelScale.x
                    val scaledSizeY = modelSizeY * modelScale.y
                    objPositions[i * 2] = (objPositions[i * 2] + dx).coerceIn(0f, maxOf(0f, 270f - scaledSizeX))
                    objPositions[i * 2 + 1] = (objPositions[i * 2 + 1] + dy).coerceIn(0f, maxOf(0f, 270f - scaledSizeY))
                    v.renderer.instancePositions = objPositions.copyOf()
                    onPositionsChanged?.invoke(objPositions.copyOf(), Pair(towerX, towerY))
                } else {
                    // Move wipe tower
                    towerX = (towerX + dx).coerceIn(0f, 270f - wipeTowerWidth)
                    towerY = (towerY + dy).coerceIn(0f, 270f - wipeTowerDepth)
                    v.renderer.wipeTower = com.u1.slicer.viewer.ModelRenderer.WipeTowerInfo(
                        towerX, towerY, wipeTowerWidth, wipeTowerDepth
                    )
                    onPositionsChanged?.invoke(objPositions.copyOf(), Pair(towerX, towerY))
                }
            }
            v.requestRender()
        } else {
            v.placementMode = false
            v.renderer.instancePositions = null
            v.renderer.wipeTower = null
            v.onObjectMoved = null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(if (placementEnabled) 340.dp else 300.dp)
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
            // Top-right overlay buttons
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                if (onInfoClick != null) {
                    IconButton(onClick = onInfoClick) {
                        Icon(Icons.Default.Info, "Model info",
                            tint = Color.White.copy(alpha = 0.8f))
                    }
                }
                IconButton(onClick = onFullScreen) {
                    Icon(Icons.Default.Fullscreen, "Full screen",
                        tint = Color.White.copy(alpha = 0.8f))
                }
            }
            // Placement mode indicator
            if (placementEnabled) {
                Text(
                    "Drag to move objects",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
            // Scale overlay
            val isScaled = modelScale.x != 1f || modelScale.y != 1f || modelScale.z != 1f
            if (isScaled) {
                val scaleText = if (modelScale.x == modelScale.y && modelScale.y == modelScale.z) {
                    "%.0f%%".format(modelScale.x * 100)
                } else {
                    "X:%.0f%% Y:%.0f%% Z:%.0f%%".format(
                        modelScale.x * 100, modelScale.y * 100, modelScale.z * 100)
                }
                Text(
                    scaleText,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(
                            Color.Black.copy(alpha = 0.6f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun ModelInfoDialog(
    info: ModelInfo,
    threeMfInfo: com.u1.slicer.bambu.ThreeMfInfo?,
    config: com.u1.slicer.data.SliceConfig,
    onToggleWipeTower: () -> Unit,
    onReassign: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ViewInAr, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(info.filename, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("Format", info.format.uppercase())
                InfoRow("Dimensions", info.dimensionString)
                InfoRow("Triangles", "%,d".format(info.triangleCount))
                InfoRow("Volumes", info.volumeCount.toString())
                InfoRow("Manifold", if (info.isManifold) "Yes" else "No")

                if (threeMfInfo != null && threeMfInfo.isBambu) {
                    Divider(modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Text("Bambu Studio File", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium)
                    if (threeMfInfo.detectedExtruderCount > 1)
                        InfoRow("Extruders Detected", threeMfInfo.detectedExtruderCount.toString())
                    if (threeMfInfo.detectedColors.isNotEmpty())
                        InfoRow("Colors", threeMfInfo.detectedColors.joinToString(", "))
                    if (threeMfInfo.hasPaintData)
                        InfoRow("Paint Data", "Yes (per-triangle)")
                    if (threeMfInfo.isMultiPlate)
                        InfoRow("Plates", threeMfInfo.plates.size.toString())
                }

                if (config.extruderCount > 1) {
                    Divider(modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Wipe Tower", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = config.wipeTowerEnabled,
                            onCheckedChange = { onToggleWipeTower() })
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * Inline section shown on the model-loaded screen for extruder/color assignment
 * and prime tower toggle. Replaces the popup MultiColorDialog for normal workflow.
 * Shows nothing for single-color models with only one extruder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSetupSection(
    detectedColors: List<String>,
    colorMapping: List<Int>?,
    extruderPresets: List<com.u1.slicer.data.ExtruderPreset>,
    filaments: List<com.u1.slicer.data.FilamentProfile>,
    wipeTowerEnabled: Boolean,
    extruderCount: Int,
    onMappingChange: (List<Int>) -> Unit,
    onToggleWipeTower: () -> Unit,
    onAutoMap: (() -> Unit)? = null
) {
    val isMultiColor = detectedColors.isNotEmpty() && colorMapping != null
    val showSection = isMultiColor || extruderCount > 1
    if (!showSection) return

    val mapping = remember(colorMapping) { colorMapping?.toMutableStateList() ?: mutableStateListOf() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Print Setup", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            if (isMultiColor) {
                if (onAutoMap != null) {
                    OutlinedButton(
                        onClick = onAutoMap,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Auto Map to Extruders", style = MaterialTheme.typography.labelMedium)
                    }
                }
                detectedColors.forEachIndexed { colorIdx, modelColor ->
                    var expanded by remember { mutableStateOf(false) }
                    val selectedSlot = mapping.getOrElse(colorIdx) { 0 }
                    val selectedPreset = extruderPresets.firstOrNull { it.index == selectedSlot }
                        ?: extruderPresets.firstOrNull()
                    val profileId = selectedPreset?.filamentProfileId
                    val profile = filaments.firstOrNull { it.id == profileId }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Model color swatch
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(com.u1.slicer.ui.parseHexColor(modelColor))
                                .border(1.dp, MaterialTheme.colorScheme.outline,
                                    androidx.compose.foundation.shape.CircleShape)
                        )
                        Text("Color ${colorIdx + 1}", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(54.dp))

                        // Extruder slot picker
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = selectedPreset?.label ?: "E1",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                leadingIcon = {
                                    Box(modifier = Modifier.size(14.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(selectedPreset?.let { com.u1.slicer.ui.parseHexColor(it.color) } ?: Color.White))
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                singleLine = true
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                extruderPresets.forEach { preset ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Box(modifier = Modifier.size(12.dp)
                                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                                    .background(com.u1.slicer.ui.parseHexColor(preset.color)))
                                                Text("${preset.label} · ${preset.materialType}",
                                                    style = MaterialTheme.typography.bodySmall)
                                            }
                                        },
                                        onClick = {
                                            if (colorIdx < mapping.size) mapping[colorIdx] = preset.index
                                            else while (mapping.size <= colorIdx) mapping.add(0)
                                                .also { mapping[colorIdx] = preset.index }
                                            onMappingChange(mapping.toList())
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Temp from profile
                        Text(
                            "${profile?.nozzleTemp ?: 210}°C",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.width(44.dp)
                        )
                    }
                }
            }

            // Prime tower toggle (always show when multi-extruder)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FilterNone, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Prime Tower", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = wipeTowerEnabled, onCheckedChange = { onToggleWipeTower() })
            }
        }
    }
}

@Composable
fun ScaleSection(
    scale: SlicerViewModel.ModelScale,
    onScaleChange: (SlicerViewModel.ModelScale) -> Unit,
    copyCount: Int = 1,
    onSetCopyCount: (Int) -> Unit = {}
) {
    var uniformMode by remember { mutableStateOf(true) }
    var uniformValue by remember(scale) { mutableFloatStateOf(scale.uniform) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.OpenWith, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scale & Copies", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Uniform", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.width(4.dp))
                    Switch(checked = uniformMode, onCheckedChange = { uniformMode = it })
                }
            }

            // Copies
            Text("Copies: $copyCount", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = copyCount.toFloat(),
                onValueChange = { v -> onSetCopyCount(v.toInt()) },
                valueRange = 1f..16f,
                steps = 14
            )

            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

            if (uniformMode) {
                val pct = "%.0f%%".format(uniformValue * 100)
                Text("Scale: $pct", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = uniformValue,
                    onValueChange = { v ->
                        uniformValue = v
                        onScaleChange(SlicerViewModel.ModelScale(v, v, v))
                    },
                    valueRange = 0.1f..3f,
                    steps = 28
                )
            } else {
                listOf("X" to scale.x, "Y" to scale.y, "Z" to scale.z).forEach { (axis, v) ->
                    Text("$axis: ${"%.0f%%".format(v * 100)}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = v,
                        onValueChange = { nv ->
                            val ns = when (axis) {
                                "X" -> scale.copy(x = nv)
                                "Y" -> scale.copy(y = nv)
                                else -> scale.copy(z = nv)
                            }
                            onScaleChange(ns)
                        },
                        valueRange = 0.1f..3f,
                        steps = 28
                    )
                }
            }

            if (scale.x != 1f || scale.y != 1f || scale.z != 1f) {
                TextButton(
                    onClick = {
                        uniformValue = 1f
                        onScaleChange(SlicerViewModel.ModelScale())
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Reset to 100%", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtruderPickerRow(
    selectedExtruder: Int,
    extruderPresets: List<com.u1.slicer.data.ExtruderPreset>,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Extruder:", style = MaterialTheme.typography.bodyMedium)
        for (i in 0 until 4) {
            val preset = extruderPresets.firstOrNull { it.index == i }
            val color = preset?.color?.takeIf { it.isNotBlank() && it != "#FFFFFF" }
                ?: com.u1.slicer.data.ExtruderPreset.DEFAULT_COLORS[i]
            val parsedColor = try {
                Color(android.graphics.Color.parseColor(color))
            } catch (_: Exception) { Color.Gray }

            FilterChip(
                selected = selectedExtruder == i,
                onClick = { onSelect(i) },
                label = { Text("E${i + 1}") },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(parsedColor, androidx.compose.foundation.shape.CircleShape)
                    )
                }
            )
        }
    }
}

@Composable
fun InlineGcodePreview(
    parsedGcode: com.u1.slicer.gcode.ParsedGcode,
    extruderColors: List<String>,
    slicerLayerCount: Int = 0,
    onExpand: () -> Unit
) {
    var viewerView by remember { mutableStateOf<com.u1.slicer.viewer.GcodeViewerView?>(null) }
    val gcodeLayerCount = parsedGcode.layers.size
    // Use slicer's totalLayers for display (correct print layers), fall back to parsed count
    val displayLayerCount = if (slicerLayerCount > 0) slicerLayerCount else gcodeLayerCount
    var maxLayer by remember { mutableIntStateOf(gcodeLayerCount - 1) }

    LaunchedEffect(parsedGcode, extruderColors, viewerView) {
        val v = viewerView ?: return@LaunchedEffect
        maxLayer = gcodeLayerCount - 1
        v.queueEvent {
            if (extruderColors.isNotEmpty()) {
                v.renderer.setExtruderColors(extruderColors)
            }
            v.renderer.uploadGcode(parsedGcode)
        }
        v.requestRender()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(280.dp)) {
            // GL view
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        com.u1.slicer.viewer.GcodeViewerView(ctx).also { view ->
                            viewerView = view
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // Expand button overlay
                IconButton(
                    onClick = onExpand,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                ) {
                    Icon(Icons.Default.Fullscreen, "Full screen",
                        tint = Color.White.copy(alpha = 0.8f))
                }
                // Layer label overlay — map gcode layer index to display layer
                val displayLayer = if (gcodeLayerCount > 0)
                    ((maxLayer.toLong() * displayLayerCount) / gcodeLayerCount).toInt().coerceIn(1, displayLayerCount)
                else 1
                Text(
                    "Layer $displayLayer/$displayLayerCount",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                )
            }
            // Vertical layer slider on the right
            if (gcodeLayerCount > 1) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(52.dp)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("$displayLayerCount", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                    BoxWithConstraints(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val sliderLength = maxHeight
                        Slider(
                            value = maxLayer.toFloat(),
                            onValueChange = { v ->
                                maxLayer = v.roundToInt()
                                viewerView?.setLayerRange(0, maxLayer)
                            },
                            valueRange = 0f..(gcodeLayerCount - 1).toFloat(),
                            modifier = Modifier
                                .requiredWidth(sliderLength)
                                .graphicsLayer { rotationZ = -90f }
                        )
                    }
                    Text("1", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
