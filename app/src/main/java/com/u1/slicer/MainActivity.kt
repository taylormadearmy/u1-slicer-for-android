package com.u1.slicer

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.content.pm.PackageInfoCompat
import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.SliceResult
import com.u1.slicer.data.WipeTowerDepthEstimator
import com.u1.slicer.debug.TestCommandReceiver
import com.u1.slicer.navigation.U1NavGraph
import com.u1.slicer.navigation.Routes
import com.u1.slicer.printer.PrinterViewModel
import com.u1.slicer.ui.JobsScreen
import com.u1.slicer.ui.PrinterScreen
import com.u1.slicer.ui.SettingsScreen
import com.u1.slicer.viewer.MeshData
import com.u1.slicer.viewer.NativePreviewMesh
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
    private val diagnostics by lazy { DiagnosticsStore(this) }
    private val viewModel: SlicerViewModel by viewModels()
    private val printerViewModel: PrinterViewModel by viewModels()
    private var testReceiver: TestCommandReceiver? = null
    private var launchApkUpdateTime: Long = 0L
    private var pendingNavigateTo: String? = null
    private var navigateTabCallback: ((String) -> Unit)? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.setLoadingFromPicker()
            val name = viewModel.getFileDisplayName(it) ?: ""
            if (name.isEmpty() || SlicerViewModel.isSupportedFile(name)) {
                viewModel.loadModel(it)
            } else {
                viewModel.showUnsupportedFileError(name)
            }
        }
    }

    private val gcodeSaveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { viewModel.saveGcodeTo(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(AppEventNotifier.EXTRA_NAVIGATE_TO)?.let { route ->
            intent.removeExtra(AppEventNotifier.EXTRA_NAVIGATE_TO)
            val cb = navigateTabCallback
            if (cb != null) {
                cb(route)
            } else {
                pendingNavigateTo = route
            }
        }
        handleIncomingIntent(intent)
    }

    private fun isSupportedIncomingModel(uri: Uri): Pair<Boolean, String?> {
        val displayName = viewModel.getFileDisplayName(uri)
        val fallbackName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
        val candidateName = displayName ?: fallbackName
        val mimeType = contentResolver.getType(uri)
        val supportedMimeTypes = setOf(
            "application/sla",
            "application/octet-stream",
            "binary/octet-stream",
            "application/zip",
            "application/x-zip-compressed",
            "model/stl",
            "application/vnd.ms-pki.stl",
            "application/vnd.ms-3mfdocument",
            "model/3mf"
        )
        val supported = when {
            !candidateName.isNullOrBlank() -> SlicerViewModel.isSupportedFile(candidateName)
            !mimeType.isNullOrBlank() -> mimeType in supportedMimeTypes
            else -> false
        }
        return supported to candidateName
    }

    private fun importIncomingModelUri(uri: Uri) {
        val (supported, candidateName) = isSupportedIncomingModel(uri)
        val name = candidateName ?: ""
        diagnostics.recordEvent(
            "incoming_model_uri",
            mapOf(
                "uri" to uri.toString(),
                "scheme" to uri.scheme,
                "displayName" to name,
                "mimeType" to contentResolver.getType(uri),
                "supported" to supported
            )
        )
        if (supported) {
            viewModel.loadModel(uri)
        } else {
            viewModel.showUnsupportedFileError(if (name.isNotBlank()) name else "shared file")
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent != null) {
            diagnostics.recordEvent(
                "incoming_intent",
                mapOf(
                    "action" to intent.action,
                    "type" to intent.type,
                    "data" to intent.data?.toString(),
                    "hasExtraStream" to intent.hasExtra(Intent.EXTRA_STREAM),
                    "hasExtraText" to intent.hasExtra(Intent.EXTRA_TEXT)
                )
            )
        }
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    importIncomingModelUri(uri)
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
                    importIncomingModelUri(uri)
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
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                diagnostics.recordEvent(
                    "incoming_send_multiple",
                    mapOf(
                        "count" to (uris?.size ?: 0),
                        "type" to intent.type,
                        "uris" to (uris?.map { it.toString() } ?: emptyList<String>())
                    )
                )
                val selectedUri = uris?.firstOrNull { uri ->
                    isSupportedIncomingModel(uri).first
                } ?: uris?.firstOrNull()
                if (selectedUri != null) {
                    Log.i("SlicerVM", "ACTION_SEND_MULTIPLE using EXTRA_STREAM: $selectedUri")
                    importIncomingModelUri(selectedUri)
                    intent.action = null
                    intent.removeExtra(Intent.EXTRA_STREAM)
                }
            }
        }
    }

    private fun clearStaleCacheOnUpgrade(): Boolean {
        val prefs = getSharedPreferences("upgrade_state", MODE_PRIVATE)
        val currentVersion = try {
            PackageInfoCompat.getLongVersionCode(
                packageManager.getPackageInfo(packageName, 0)
            ).toInt()
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
                // APK changed — aggressively delete generated cache files and exit.
                // Android 15 blocks background self-relaunches, so we keep the purge and
                // ask the user to reopen the app manually for a fresh process.
                val cacheFilesCleared = UpgradeDetector.clearIntermediateCache(filesDir)
                val transientFilesCleared = UpgradeDetector.clearUpgradeTransientFiles(filesDir)
                cacheDir.deleteRecursively()
                val count = cacheFilesCleared + transientFilesCleared
                val reason = if (saved.lastVersionCode != currentVersion)
                    "version ${saved.lastVersionCode}→$currentVersion" else "APK reinstalled"
                Log.i("SlicerVM", "APK change detected ($reason): cleared $count cached files/directories, exiting for a fresh process")
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
                val persisted = prefs.edit()
                    .putInt("lastVersionCode", currentVersion)
                    .putLong("lastApkUpdateTime", apkLastUpdate)
                    .commit()
                diagnostics.recordEvent(
                    "upgrade_state_persisted",
                    mapOf(
                        "success" to persisted,
                        "versionCode" to currentVersion,
                        "apkUpdateTime" to apkLastUpdate
                    )
                )
                // APK upgrade provides fresh native state — clear stale clipper
                // recovery markers so we don't double-restart or report a false
                // hard_crash_during_slice from the previous version's session.
                diagnostics.consumeClipperRecoveryPending()
                diagnostics.consumeSliceInProgressMarker()
                diagnostics.markUpgradeRestartRequested("apk_changed", null)
                viewModel.clearModel()
                android.os.Process.killProcess(android.os.Process.myPid())
                return true
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
        return false
    }

    private fun getCurrentApkUpdateTime(): Long {
        return try {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        } catch (_: Exception) {
            0L
        }
    }

    override fun onDestroy() {
        testReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        testReceiver = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val currentApkUpdateTime = getCurrentApkUpdateTime()
        if (launchApkUpdateTime != 0L && currentApkUpdateTime != launchApkUpdateTime) {
            diagnostics.recordEvent(
                "apk_changed_while_running",
                mapOf(
                    "launchApkUpdateTime" to launchApkUpdateTime,
                    "currentApkUpdateTime" to currentApkUpdateTime
                )
            )
            Log.w(
                "SlicerVM",
                "APK changed while the app process was still alive; forcing cold restart"
            )
            launchApkUpdateTime = currentApkUpdateTime
            clearStaleCacheOnUpgrade()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchApkUpdateTime = getCurrentApkUpdateTime()
        diagnostics.recordEvent(
            "app_launch",
            mapOf(
                "savedInstanceState" to (savedInstanceState != null),
                "intentAction" to intent?.action
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
        if (clearStaleCacheOnUpgrade()) return

        // Only handle VIEW intents on fresh launch, not on recreation
        if (savedInstanceState == null) {
            intent.getStringExtra(AppEventNotifier.EXTRA_NAVIGATE_TO)?.let { route ->
                pendingNavigateTo = route
                intent.removeExtra(AppEventNotifier.EXTRA_NAVIGATE_TO)
            }
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
                var sharedPreviewCameraState by remember {
                    mutableStateOf<com.u1.slicer.viewer.CameraViewState?>(null)
                }
                val appSlicerState by viewModel.state.collectAsState()
                val sharedPreviewModelKey = when (val s = appSlicerState) {
                    is SlicerViewModel.SlicerState.Loading -> "loading:${s.message}"
                    else -> viewModel.previewModelPath ?: viewModel.currentModelPath
                }
                LaunchedEffect(sharedPreviewModelKey) {
                    sharedPreviewCameraState = null
                }

                // Wire up notification deep-link navigation and consume any pending cold-start route
                LaunchedEffect(navController) {
                    navigateTabCallback = { route -> navigateTab(route) }
                    pendingNavigateTo?.let { route ->
                        pendingNavigateTo = null
                        navigateTab(route)
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
                    "*/*"  // fallback — Android file managers don't recognize model/* MIME types
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
                            onBrowseMakerWorld = { navController.navigate(Routes.MAKERWORLD_BROWSER) },
                            onNavigatePrepare = { },
                            onNavigatePreview = { navigateTab(Routes.PREVIEW) },
                            onNavigateSettings = { navigateTab(Routes.SETTINGS) },
                            onNavigatePrinter = { navigateTab(Routes.PRINTER) },
                            onNavigateJobs = { navigateTab(Routes.JOBS) },
                            onNavigateModelViewer = { navController.navigate(Routes.MODEL_VIEWER) },
                            sharedPreviewCameraState = sharedPreviewCameraState,
                            onSharedPreviewCameraStateChange = { sharedPreviewCameraState = it },
                            onResetPreviewCamera = { sharedPreviewCameraState = null },
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
                            onSaveGcode = { gcodeSaveLauncher.launch("output.gcode") },
                            sharedPreviewCameraState = sharedPreviewCameraState,
                            onSharedPreviewCameraStateChange = { sharedPreviewCameraState = it },
                            onResetPreviewCamera = { sharedPreviewCameraState = null }
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
                        val ctx = LocalContext.current
                        JobsScreen(
                            jobs = jobs,
                            onDelete = { viewModel.deleteJob(it) },
                            onDeleteAll = { viewModel.deleteAllJobs() },
                            onShare = { viewModel.shareJobGcode(it) },
                            onViewGcode = { job ->
                                viewModel.loadJobGcodeForViewer(job) { success ->
                                    if (success) {
                                        navigateTab(Routes.PREVIEW)
                                    } else {
                                        android.widget.Toast.makeText(ctx, "G-code file not found — it may have been cleared on upgrade.", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onReopenModel = { job ->
                                viewModel.reopenJobToEdit(job, onMissing = {
                                    android.widget.Toast.makeText(ctx, "Source model not found — it may have been cleared on upgrade.", android.widget.Toast.LENGTH_LONG).show()
                                })
                                navigateTab(Routes.PREPARE)
                            },
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
                            onNavigateSettings = { },
                            onNavigateMakerWorldLogin = { navController.navigate(Routes.MAKERWORLD_BROWSER) }
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
    onBrowseMakerWorld: () -> Unit = {},
    onNavigatePrepare: () -> Unit,
    onNavigatePreview: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigatePrinter: () -> Unit,
    onNavigateJobs: () -> Unit,
    onNavigateModelViewer: () -> Unit,
    sharedPreviewCameraState: com.u1.slicer.viewer.CameraViewState?,
    onSharedPreviewCameraStateChange: (com.u1.slicer.viewer.CameraViewState) -> Unit,
    onResetPreviewCamera: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val config by viewModel.config.collectAsState()
    val slicingOverrides by viewModel.slicingOverrides.collectAsState()
    val plateType by viewModel.plateType.collectAsState()
    val coreVersion by viewModel.coreVersion.collectAsState()
    val modelInfo by viewModel.modelInfo.collectAsState()
    val showPlateSelector by viewModel.showPlateSelector.collectAsState()
    val showMultiColorDialog by viewModel.showMultiColorDialog.collectAsState()
    val colorMapping by viewModel.colorMapping.collectAsState()
    val threeMfInfo by viewModel.threeMfInfo.collectAsState()
    val filaments by viewModel.filaments.collectAsState(initial = emptyList())
    val extruderPresets by viewModel.extruderPresets.collectAsState()
    val copyCount by viewModel.copyCount.collectAsState()
    val modelScale by viewModel.modelScale.collectAsState()
    val modelRotation by viewModel.modelRotation.collectAsState()
    val extruderColors by viewModel.activeExtruderColors.collectAsState()
    val layerToolOnly by viewModel.layerToolOnly.collectAsState()
    val sourceConfig by viewModel.sourceConfig.collectAsState()
    var captureViewer by remember { mutableStateOf<com.u1.slicer.viewer.ModelViewerView?>(null) }

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
            currentMapping = colorMapping,
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
                            onPickFile = onPickFile,
                            onBrowseMakerWorld = onBrowseMakerWorld
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
                                Text(s.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                            }
                        }
                    }
                    state is SlicerViewModel.SlicerState.Error -> {
                        ErrorCard(
                            (state as SlicerViewModel.SlicerState.Error).message,
                            onPickFile,
                            onResetAndRetry = { viewModel.recoverFromClipperError() },
                            onRestart = { viewModel.restartApp() },
                            onShareDiagnostics = { viewModel.shareDiagnostics() }
                        )
                    }
                    modelLoaded -> {
                        val info = resolvePreparePreviewModelInfo(state, modelInfo)
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
                                modelTriangleCount = loadedInfo?.triangleCount ?: 0,
                                onFullScreen = if (modelPath.endsWith(".stl", ignoreCase = true))
                                    onNavigateModelViewer else ({}),
                                extruderColors = extruderColors,
                                extruderMap = viewModel.buildExtruderMap(),
                                colorMapping = colorMapping,
                                hasPaintData = threeMfInfo?.hasPaintData == true,
                                objectPositions = positions,
                                modelSizeX = loadedInfo?.sizeX ?: 0f,
                                modelSizeY = loadedInfo?.sizeY ?: 0f,
                                wipeTowerEnabled = config.wipeTowerEnabled,
                                wipeTowerX = config.wipeTowerX,
                                wipeTowerY = config.wipeTowerY,
                                wipeTowerWidth = resolveWipeTowerWidth(config, slicingOverrides),
                                wipeTowerDepth = resolveWipeTowerDepth(loadedInfo?.sizeZ ?: 0f, slicingOverrides),
                                onPositionsChanged = { pos, towerPos ->
                                    viewModel.applyPlacementPositions(pos, towerPos)
                                },
                                onInfoClick = { showInfoDialog = true },
                                modelScale = modelScale,
                                modelRotation = modelRotation,
                                cameraState = sharedPreviewCameraState,
                                onCameraStateChange = onSharedPreviewCameraStateChange,
                                onViewerReady = { captureViewer = it },
                                onResetView = { captureViewer?.resetView(); onResetPreviewCamera?.invoke() },
                                layerToolOnly = layerToolOnly,
                                layerToolSegments = threeMfInfo?.layerToolSegments,
                                cachedMesh = viewModel.cachedPrepareMesh,
                                onMeshCached = { viewModel.cachedPrepareMesh = it }
                            )
                            if (showInfoDialog && loadedInfo != null) {
                                ModelInfoDialog(
                                    info = loadedInfo,
                                    threeMfInfo = threeMfInfo,
                                    config = config,
                                    onToggleWipeTower = { viewModel.togglePrimeTower() },
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
                            onToggleWipeTower = { viewModel.togglePrimeTower() },
                            onAutoMap = {
                                viewModel.reAutoMapColors(extruderPresets, filaments)
                            }
                        )
                        // Scale & copies controls
                        ScaleSection(
                            scale = modelScale,
                            onScaleChange = { viewModel.setModelScale(it) },
                            copyCount = copyCount,
                            onSetCopyCount = viewModel::setCopyCount,
                            rotation = modelRotation,
                            onRotationChange = { viewModel.setModelRotation(it) }
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
                            onOverridesChange = { viewModel.saveSlicingOverrides(it) },
                            plateType = plateType,
                            onPlateTypeChange = { viewModel.setPlateType(it) },
                            bedTemp = config.bedTemp,
                            onBedTempChange = { viewModel.setBedTemp(it) },
                            sourceConfig = sourceConfig
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
                        val viewer = captureViewer
                        if (viewer != null) {
                            // Navigate only after PixelCopy completes — navigating first can
                            // destroy the GL surface before the capture finishes (bugbot B1).
                            viewer.captureBitmap { bitmap ->
                                viewModel.setPendingThumbnailBitmap(bitmap)
                                viewModel.startSlicing()
                                onNavigatePreview()
                            }
                        } else {
                            viewModel.startSlicing()
                            onNavigatePreview()
                        }
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
fun PrepareEmptyState(onPickFile: () -> Unit, onBrowseMakerWorld: () -> Unit = {}) {
    val context = LocalContext.current
    var showMakerWorldInfo by remember { mutableStateOf(false) }
    if (showMakerWorldInfo) {
        MakerWorldModeDialog(onDismiss = { showMakerWorldInfo = false })
    }
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
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://makerworld.com/en")).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                    }
                    context.startActivity(intent)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Open MakerWorld in Browser")
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBrowseMakerWorld) {
                    Text("Browse MakerWorld in App")
                }
                IconButton(onClick = { showMakerWorldInfo = true }) {
                    Icon(Icons.Default.Info, "MakerWorld help")
                }
            }
        }
    }

}

@Composable
private fun MakerWorldModeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
        title = { Text("Choose A MakerWorld Path") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Open MakerWorld in Browser",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Best for signed-in browsing. Google login works here, and it is the most reliable way to download a 3MF/STL or share a MakerWorld model link back to U1 Slicer.",
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider()
                Text(
                    "Browse MakerWorld in App",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Best for quick public browsing and direct in-app downloads. Google sign-in is not supported inside the app browser, and browser login will not sync back into it.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    )
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
    onSaveGcode: () -> Unit,
    sharedPreviewCameraState: com.u1.slicer.viewer.CameraViewState?,
    onSharedPreviewCameraStateChange: (com.u1.slicer.viewer.CameraViewState) -> Unit,
    onResetPreviewCamera: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsState()
    val coreVersion by viewModel.coreVersion.collectAsState()
    val parsedGcode by viewModel.parsedGcode.collectAsState()
    val extruderColors by viewModel.activeExtruderColors.collectAsState()
    val colorMapping by viewModel.colorMapping.collectAsState()
    val threeMfInfo by viewModel.threeMfInfo.collectAsState()
    val config by viewModel.config.collectAsState()
    val extruderPresets by viewModel.extruderPresets.collectAsState()

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
        val scrollState = rememberScrollState()
        val hasPinnedActions = state is SlicerViewModel.SlicerState.SliceComplete
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
                    .padding(top = if (hasPinnedActions) 104.dp else 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (val s = state) {
                is SlicerViewModel.SlicerState.Slicing -> {
                    SlicingProgressCard(s.progress, s.stage, onCancel = { viewModel.cancelSlicing() })
                }
                is SlicerViewModel.SlicerState.SliceComplete -> {
                    // B52: inform user when G-code preview is stride-sampled
                    if (parsedGcode?.isPreviewSimplified == true) {
                        val ctx = LocalContext.current
                        LaunchedEffect(parsedGcode) {
                            android.widget.Toast.makeText(
                                ctx,
                                "Large G-code \u2014 preview simplified",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    // Inline 3D G-code preview (auto-downsampled for large models)
                    if (parsedGcode != null && parsedGcode!!.layers.isNotEmpty()) {
                        // key() forces Compose to discard and recreate InlineGcodePreview when
                        // the gcode path changes (e.g. Jobs tab "View G-code" while a different
                        // model is already shown). ParsedGcode contains List<GcodeLayer> so Compose
                        // treats it as unstable and may skip recomposition, leaving the LaunchedEffect
                        // inside InlineGcodePreview with the old key and the wrong gcode on screen.
                        key(s.result.gcodePath) {
                        // B48: H2C models (>4 model colours) — slicer's T0-T3 are physical
                        // slot indices. Don't pass model→slot colorMapping to G-code preview.
                        // Normal painted models (<=4 colours) still need the mapping for
                        // tool→slot colour remapping.
                        val isH2c = threeMfInfo?.hasPaintData == true &&
                            (colorMapping?.distinct()?.size ?: 0) >= 4 &&
                            (colorMapping?.size ?: 0) > (colorMapping?.distinct()?.size ?: 0)
                        val gcodeColorMapping = if (isH2c) null else colorMapping
                        InlineGcodePreview(
                            parsedGcode = parsedGcode!!,
                            extruderColors = extruderColors,
                            colorMapping = gcodeColorMapping,
                            slicerLayerCount = s.result.totalLayers,
                            onExpand = onNavigateGcodeViewer3D,
                            cameraState = sharedPreviewCameraState,
                            onCameraStateChange = onSharedPreviewCameraStateChange,
                            onResetView = onResetPreviewCamera
                        )
                        }
                    }
                    SliceCompleteSummaryCard(
                        result = s.result,
                        perExtruderFilamentMm = parsedGcode?.perExtruderFilamentMm ?: emptyList(),
                        wipeTowerFilamentMm = parsedGcode?.wipeTowerFilamentMm ?: 0f,
                        bedTemp = config.bedTemp,
                        extruderColors = extruderColors.filter { it.isNotBlank() },
                        colorMapping = colorMapping,
                        extruderPresets = extruderPresets
                    )
                }
                is SlicerViewModel.SlicerState.Error -> {
                    ErrorCard(
                        s.message,
                        onRetry = { onNavigatePrepare() },
                        onResetAndRetry = {
                            viewModel.resetAppState()
                            onNavigatePrepare()
                        },
                        onRestart = { viewModel.restartApp() },
                        onShareDiagnostics = { viewModel.shareDiagnostics() }
                    )
                }
                else -> {
                    // Empty state — no slice results yet, show empty bed
                    val canSlice = state is SlicerViewModel.SlicerState.ModelLoaded
                    PreviewEmptyState(
                        modelLoaded = canSlice,
                        onSliceNow = if (canSlice) {{ viewModel.startSlicing() }} else null
                    )
                }
            }
            }
            if (state is SlicerViewModel.SlicerState.SliceComplete) {
                val result = (state as SlicerViewModel.SlicerState.SliceComplete).result
                SliceCompleteActionBar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    onShare = onShareGcode,
                    onSave = onSaveGcode,
                    onSendToPrinter = { onSendToPrinter(result.gcodePath) },
                    onUploadOnly = { onUploadOnly(result.gcodePath) }
                )
            }
        }
    }
}

// =============================================================================
// Preview Empty State — empty build plate
// =============================================================================
@Composable
fun PreviewEmptyState(
    modelLoaded: Boolean = false,
    onSliceNow: (() -> Unit)? = null
) {
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
            if (modelLoaded && onSliceNow != null) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onSliceNow) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Slice Now")
                }
            } else {
                Text(
                    "Load a model and slice it to see the preview",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
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
fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

@Composable
fun ConfigCard(
    config: com.u1.slicer.data.SliceConfig,
    onUpdate: ((com.u1.slicer.data.SliceConfig) -> com.u1.slicer.data.SliceConfig) -> Unit,
    copyCount: Int = 1,
    onSetCopyCount: (Int) -> Unit = {},
    slicingOverrides: com.u1.slicer.data.SlicingOverrides = com.u1.slicer.data.SlicingOverrides(),
    onOverridesChange: ((com.u1.slicer.data.SlicingOverrides) -> Unit)? = null,
    plateType: com.u1.slicer.data.PlateType? = null,
    onPlateTypeChange: ((com.u1.slicer.data.PlateType) -> Unit)? = null,
    bedTemp: Int? = null,
    onBedTempChange: ((Int) -> Unit)? = null,
    sourceConfig: Map<String, Any>? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Slice Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
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

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

            if (onOverridesChange != null) {
                com.u1.slicer.ui.SlicingOverridesAccordion(
                    overrides = slicingOverrides,
                    onOverridesChange = onOverridesChange,
                    defaultExpandedSection = null,
                    plateType = plateType,
                    onPlateTypeChange = onPlateTypeChange,
                    bedTemp = bedTemp,
                    onBedTempChange = onBedTempChange,
                    sourceConfig = sourceConfig
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            InfoRow("Nozzle Diameter", "${config.nozzleDiameter} mm")
            InfoRow("Filament", config.filamentType)
            InfoRow("Build Volume", "270 x 270 x 270 mm")
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
fun SlicingProgressCard(progress: Int, stage: String, onCancel: (() -> Unit)? = null) {
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
            if (onCancel != null) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
fun SliceCompleteActionBar(
    modifier: Modifier = Modifier,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onSendToPrinter: () -> Unit = {},
    onUploadOnly: () -> Unit = {}
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16361A)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
fun SliceCompleteSummaryCard(
    result: SliceResult,
    perExtruderFilamentMm: List<Float> = emptyList(),
    wipeTowerFilamentMm: Float = 0f,
    bedTemp: Int = 0,
    extruderColors: List<String> = emptyList(),
    colorMapping: List<Int>? = null,
    extruderPresets: List<com.u1.slicer.data.ExtruderPreset> = emptyList()
) {
    val displaySlots = remember(perExtruderFilamentMm, colorMapping) {
        buildPerExtruderDisplaySlots(perExtruderFilamentMm.size, colorMapping)
    }
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
                Text("Slice Summary", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = Color(0xFF81C784))
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            InfoRow("Layers", result.totalLayers.toString())
            InfoRow("Est. Time", result.estimatedTimeFormatted)
            InfoRow("Filament", result.estimatedFilamentFormatted)
            if (bedTemp > 0) {
                InfoRow("Bed Temp", "${bedTemp}\u00B0C")
            }
            if (perExtruderFilamentMm.isNotEmpty()) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Text(
                    "Per Extruder",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                perExtruderFilamentMm.chunked(2).forEachIndexed { rowIndex, rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEachIndexed { columnIndex, mm ->
                            val i = rowIndex * 2 + columnIndex
                            val slot = displaySlots.getOrElse(i) { i.coerceIn(0, 3) }
                            val colorHex = extruderColors.getOrNull(slot) ?: "#808080"
                            val color = try {
                                Color(android.graphics.Color.parseColor(colorHex))
                            } catch (_: Exception) {
                                Color.Gray
                            }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        Color.White.copy(alpha = 0.04f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Canvas(modifier = Modifier.size(12.dp)) {
                                    drawCircle(color = color)
                                }
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    val materialType = resolveExtruderMaterialType(slot, extruderPresets)
                                    Text(
                                        if (materialType.isNotEmpty()) "E${slot + 1} · $materialType" else "E${slot + 1}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        "%.0f mm (%.1f g)".format(mm, mm * 0.00125f * 1.24f),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (wipeTowerFilamentMm > 0.5f) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                InfoRow(
                    "Prime Tower Waste",
                    "%.0f mm (%.1f g)".format(wipeTowerFilamentMm, wipeTowerFilamentMm * 0.00125f * 1.24f),
                    valueColor = Color(0xFFFFB74D)
                )
            }
        }
    }
}

/**
 * Return the materialType label for an extruder slot, or empty string if the slot is unknown.
 * Used by SliceCompleteSummaryCard to show material next to each colour swatch (F65).
 */
internal fun resolveExtruderMaterialType(slot: Int, presets: List<com.u1.slicer.data.ExtruderPreset>): String =
    presets.firstOrNull { it.index == slot }?.materialType ?: ""

/**
 * Build a stable display-slot order for per-extruder filament summaries.
 *
 * perExtruderFilamentMm comes from compact slicer output order, while users pick physical
 * slots via colorMapping (e.g. compact [0,1] mapped to physical [2,1] => E3, E2). We show
 * used mapped slots first, then fill with remaining physical slots.
 */
internal fun buildPerExtruderDisplaySlots(count: Int, colorMapping: List<Int>?): List<Int> {
    if (count <= 0) return emptyList()
    if (colorMapping.isNullOrEmpty()) return (0 until count).map { it.coerceIn(0, 3) }

    val ordered = mutableListOf<Int>()
    colorMapping.forEach { slot ->
        if (slot in 0..3 && slot !in ordered) ordered += slot
    }
    for (slot in 0..3) {
        if (slot !in ordered) ordered += slot
    }
    while (ordered.size < count) {
        ordered += ordered.lastOrNull() ?: 0
    }
    return ordered.take(count)
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
    onResetAndRetry: (() -> Unit)? = null,
    onRestart: (() -> Unit)? = null,
    onShareDiagnostics: (() -> Unit)? = null
) {
    val isClipperError = message.contains("Coordinate outside allowed range", ignoreCase = true) ||
        message.contains("clipper", ignoreCase = true) ||
        message.contains("impossible coordinates", ignoreCase = true) ||
        message.contains("invalid output", ignoreCase = true)
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isClipperError && onResetAndRetry != null) {
                    Button(
                        onClick = onResetAndRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Reset & Retry")
                    }
                } else {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Try Again")
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
    modelTriangleCount: Int = 0,
    onFullScreen: () -> Unit,
    extruderColors: List<String> = emptyList(),
    // B49: ViewModel-level mesh cache for instant reload on tab switch
    cachedMesh: MeshData? = null,
    onMeshCached: ((MeshData) -> Unit)? = null,
    extruderMap: Map<Int, Byte>? = null,
    colorMapping: List<Int>? = null,
    // Use Kotlin ThreeMfMeshParser only for painted/SEMM models (hasPaintData=true).
    // All other 3MF files use the native getPreparePreviewMesh() path (QEM decimation).
    // Avoids parsing giant uncompressed XML streams for large multi-colour models (F1 calendar).
    hasPaintData: Boolean = false,
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
    modelScale: SlicerViewModel.ModelScale = SlicerViewModel.ModelScale(),
    modelRotation: SlicerViewModel.ModelRotation = SlicerViewModel.ModelRotation(),
    cameraState: com.u1.slicer.viewer.CameraViewState? = null,
    onCameraStateChange: ((com.u1.slicer.viewer.CameraViewState) -> Unit)? = null,
    onViewerReady: ((com.u1.slicer.viewer.ModelViewerView?) -> Unit)? = null,
    onResetView: (() -> Unit)? = null,
    // F46: layer-tool (Hueforge) Z-band recolour
    layerToolOnly: Boolean = false,
    layerToolSegments: List<com.u1.slicer.bambu.LayerToolSegment>? = null
) {
    // B49: initialize from ViewModel cache for instant reload on tab switch
    var mesh by remember { mutableStateOf(cachedMesh) }
    var viewerView by remember { mutableStateOf<com.u1.slicer.viewer.ModelViewerView?>(null) }
    var parseRequestId by remember { mutableIntStateOf(0) }
    var viewerLoading by remember(modelFilePath) { mutableStateOf(true) }
    val previewTooLarge = remember(modelTriangleCount) {
        com.u1.slicer.viewer.NativePreviewMesh.wouldExceedSafePreviewBudget(modelTriangleCount)
    }
    val nativeThreeMfPreview = remember(modelFilePath) {
        modelFilePath.endsWith(".3mf", ignoreCase = true)
    }
    val placementConfig = remember(
        nativeThreeMfPreview,
        objectPositions,
        onPositionsChanged,
        wipeTowerEnabled
    ) {
        buildPreparePreviewPlacementConfig(
            nativeThreeMfPreview = nativeThreeMfPreview,
            objectPositionsPresent = objectPositions != null,
            onPositionsChangedPresent = onPositionsChanged != null,
            wipeTowerEnabled = wipeTowerEnabled
        )
    }
    // Track whether we've already uploaded this mesh to avoid redundant VBO re-uploads
    // when only colors/mapping change (B22 fix).
    var lastSetMesh by remember { mutableStateOf<com.u1.slicer.viewer.MeshData?>(null) }
    val placementEnabled = placementConfig.objectPlacementEnabled

    // Mutable copies of positions for drag interaction
    val objPositions = remember(objectPositions) {
        objectPositions?.copyOf() ?: floatArrayOf()
    }
    var towerX by remember(wipeTowerX) { mutableFloatStateOf(wipeTowerX) }
    var towerY by remember(wipeTowerY) { mutableFloatStateOf(wipeTowerY) }

    LaunchedEffect(modelFilePath, extruderMap, colorMapping?.size) {
        val requestId = parseRequestId + 1
        parseRequestId = requestId
        // B49: don't clear mesh/GL when a ViewModel cache exists and the native
        // rotation path handles the preview (3MF files).  The parse effect returns
        // null for 3MF — clearing the mesh here just kills the cached preview and
        // forces a slow re-fetch from native.
        val isNativePreviewPath = modelFilePath.endsWith(".3mf", ignoreCase = true)
        if (cachedMesh == null || !isNativePreviewPath) {
            viewerLoading = true
            mesh = null
            lastSetMesh = null
            viewerView?.clearMesh()
        }
        val parsedMesh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = java.io.File(modelFilePath)
                when {
                    modelFilePath.endsWith(".stl", ignoreCase = true) ->
                        com.u1.slicer.viewer.StlParser.parse(file)
                    modelFilePath.endsWith(".3mf", ignoreCase = true) ->
                        // B46 fix: ALL 3MF models use the native getPreparePreviewMesh()
                        // path via the rotation LaunchedEffect below. The Kotlin
                        // ThreeMfMeshParser created seam artifacts at color boundaries
                        // and lost color regions for painted/SEMM models. The native path
                        // produces clean meshes with correct per-state color grouping.
                        null  // rotation effect owns this fetch for all 3MF
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        }
        if (parseRequestId == requestId) {
            // B49: don't overwrite cached mesh with null from the 3MF native path
            if (parsedMesh != null || cachedMesh == null) {
                mesh = parsedMesh
            }
            // For non-painted 3MF: null is expected — rotation effect owns the fetch.
            // Keep viewerLoading=true so the spinner stays until that effect delivers the mesh.
            if (parsedMesh == null && !nativeThreeMfPreview) viewerLoading = false
        }
    }

    // Combined effect: keys on mesh + viewer + colors + mapping so it fires when ANY changes.
    // Fixes B22 race: previously mesh loaded on IO (slow) while colors arrived via StateFlow
    // (fast). Separate LaunchedEffects had timing gaps — colors effect fired before mesh was
    // ready (skip), then mesh effect read stale empty colors from closure (skip).
    LaunchedEffect(mesh, viewerView, extruderColors, colorMapping, cameraState, layerToolOnly, layerToolSegments) {
        val m = mesh; val v = viewerView
        if (m != null && v != null) {
            // Only call setMesh when the mesh instance actually changed
            if (m !== lastSetMesh) {
                v.setMesh(m)
                cameraState?.let { v.applyCameraState(it) }
                lastSetMesh = m
            }
            if (extruderColors.isNotEmpty()) {
                v.setExtruderColors(extruderColors)
            }
            // F46: Z-band recolour for layer-tool (Hueforge) models
            if (layerToolOnly && layerToolSegments != null && extruderColors.isNotEmpty() && colorMapping != null) {
                val palette = colorMapping.map { slot -> SlicerViewModel.staticHexColorToFloatArray(extruderColors.getOrElse(slot) { "" }) }
                Log.i("InlineModelPreview", "recolorByZBands segments=${layerToolSegments.size} paletteSize=${palette.size}")
                m.recolorByZBands(layerToolSegments, palette)
                v.refreshColors()  // upload recolorByZBands result to GPU without overwriting with recolor()
            } else if (m.hasPerVertexColor && extruderColors.isNotEmpty()) {
                // Apply recolor when we have both mesh and colors (paint-data models)
                val palette = if (colorMapping != null) {
                    // Multi-color: remap mesh indices → slot colors
                    colorMapping.map { slot -> SlicerViewModel.staticHexColorToFloatArray(extruderColors.getOrElse(slot) { "" }) }
                } else {
                    // Single-color: palette[0] = first non-blank color
                    listOf(SlicerViewModel.staticHexColorToFloatArray(extruderColors.firstOrNull { it.isNotBlank() } ?: ""))
                }
                Log.i(
                    "InlineModelPreview",
                    "recolor mapping=$colorMapping " +
                        "extruderColors=$extruderColors paletteSize=${palette.size} " +
                        "hasMeshColors=${m.hasPerVertexColor}"
                )
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

    // Re-fetch preview mesh when rotation changes (all 3MF models).
    // B46 fix: painted/SEMM models also use this native path now — the Kotlin
    // ThreeMfMeshParser path created seam artifacts and lost color boundaries.
    // Rotation is not user-adjustable for painted models, but the initial call
    // (rot=0,0,0) correctly initializes instance transforms via setModelRotation()
    // before calling getPreparePreviewMesh().
    // Uses previewMutex to serialize against concurrent fetches from other composable instances —
    // setModelRotation mutates global native state; concurrent getPreparePreviewMesh reads race it.
    //
    // Debounce: the rotation slider emits many intermediate values while dragging.
    // For large models (F1 calendar, 8M tris), getPreparePreviewMesh takes 30+ seconds.
    // Without debouncing, each intermediate value cancels the previous LaunchedEffect,
    // wasting the 30s computation and restarting.  The initial call (rot=0,0,0) skips
    // the delay so model load isn't slowed.
    LaunchedEffect(modelRotation) {
        val rot = modelRotation

        // B49: reuse cached mesh if available (instant reload on tab switch).
        // Still set lastSetMesh=null to force the GL view to receive it.
        if (mesh != null && cachedMesh != null) {
            lastSetMesh = null  // force setMesh() on fresh GL view
            return@LaunchedEffect
        }

        // Debounce: skip delay for initial fetch (rot=0,0,0) so model loads instantly.
        // For user-initiated rotation changes, wait 300ms for the slider to settle.
        val isInitialFetch = rot.x == 0f && rot.y == 0f && rot.z == 0f
        if (!isInitialFetch) {
            viewerLoading = true  // show "Preparing preview…" overlay while re-computing
            kotlinx.coroutines.delay(300)
        }

        val newMesh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            NativeLibrary.previewMutex.withLock {
                try {
                    val lib = NativeLibrary()
                    lib.setModelRotation(rot.x, rot.y, rot.z)
                    lib.getPreparePreviewMesh(NativePreviewMesh.MAX_DECIMATED_TRIANGLES)?.toMeshData()
                } catch (_: Throwable) {
                    null
                }
            }
        }
        if (newMesh != null) {
            mesh = newMesh
            onMeshCached?.invoke(newMesh)  // B49: save to ViewModel cache
            lastSetMesh = null  // force setMesh() on the GL thread
        }
        if (!isInitialFetch) viewerLoading = false
    }

    // Update renderer with placement data
    LaunchedEffect(viewerView, placementEnabled, objPositions, towerX, towerY, placementConfig.wipeTowerVisible) {
        val v = viewerView ?: return@LaunchedEffect
        if (placementEnabled) {
            v.placementMode = true
            // Only reset the camera when placement positions are first assigned (not on every
            // drag update — setting pendingCameraReset on every recomposition would reset
            // azimuth/elevation/pan mid-drag, corrupting subsequent screenToBed calculations).
            val firstPlacement = v.renderer.instancePositions == null
            v.renderer.instancePositions = objPositions
            if (firstPlacement) v.renderer.pendingCameraReset = true
            if (placementConfig.wipeTowerVisible) {
                v.renderer.wipeTower = com.u1.slicer.viewer.ModelRenderer.WipeTowerInfo(
                    towerX, towerY, wipeTowerWidth, wipeTowerDepth
                )
            } else {
                v.renderer.wipeTower = null
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
            v.renderer.wipeTower = if (placementConfig.wipeTowerVisible) {
                com.u1.slicer.viewer.ModelRenderer.WipeTowerInfo(
                    towerX, towerY, wipeTowerWidth, wipeTowerDepth
                )
            } else {
                null
            }
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
            if (!previewTooLarge) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        com.u1.slicer.viewer.ModelViewerView(ctx).also { view ->
                            viewerView = view
                            onViewerReady?.invoke(view)
                            view.onCameraChanged = onCameraStateChange
                            view.setOnContentReady { viewerLoading = false }
                            mesh?.let { view.setMesh(it) }
                            cameraState?.let { view.applyCameraState(it) }
                        }
                    },
                    update = { view ->
                        viewerView = view
                        view.onCameraChanged = onCameraStateChange
                        view.setOnContentReady { viewerLoading = false }
                        cameraState?.let {
                            if (view.camera.snapshot() != it) {
                                view.applyCameraState(it)
                            }
                        }
                    },
                    onRelease = { view ->
                        if (viewerView === view) { viewerView = null; onViewerReady?.invoke(null) }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LaunchedEffect(modelFilePath) {
                    viewerView = null
                }
            }
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
            // Reset-view button (bottom-end, only when mesh is loaded)
            if (mesh != null && onResetView != null) {
                androidx.compose.material3.IconButton(
                    onClick = { onResetView.invoke() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.4f),
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterCenterFocus,
                        contentDescription = "Reset view",
                        tint = Color.White
                    )
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
            if (viewerLoading && !previewTooLarge) {
                ViewerLoadingOverlay("Preparing preview…")
            }
            if (previewTooLarge) {
                LargePreviewFallback(
                    triangleCount = modelTriangleCount,
                    modelSizeX = modelSizeX * modelScale.x,
                    modelSizeY = modelSizeY * modelScale.y,
                    wipeTowerDepth = wipeTowerDepth,
                    objectPositions = objPositions.copyOf(),
                    wipeTowerVisible = placementConfig.wipeTowerVisible,
                    wipeTowerWidth = wipeTowerWidth,
                    initialTowerX = towerX,
                    initialTowerY = towerY,
                    onPositionsChanged = onPositionsChanged,
                    onInfoClick = onInfoClick
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
            val isRotated = modelRotation.x != 0f || modelRotation.y != 0f || modelRotation.z != 0f
            if (isRotated) {
                val rotText = buildString {
                    val parts = listOf("X" to modelRotation.x, "Y" to modelRotation.y, "Z" to modelRotation.z)
                        .filter { (_, v) -> v != 0f }
                    if (parts.size == 1) {
                        append("${parts[0].first}: %.0f°".format(parts[0].second))
                    } else {
                        append(parts.joinToString(" ") { (ax, v) -> "$ax:%.0f°".format(v) })
                    }
                }
                Text(
                    rotText,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 36.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

internal data class PreparePreviewPlacementConfig(
    val objectPlacementEnabled: Boolean,
    val wipeTowerVisible: Boolean
)

internal fun buildPreparePreviewPlacementConfig(
    nativeThreeMfPreview: Boolean,
    objectPositionsPresent: Boolean,
    onPositionsChangedPresent: Boolean,
    wipeTowerEnabled: Boolean
): PreparePreviewPlacementConfig {
    // Native 3MF previews still need placement hit-testing so objects and the wipe tower
    // can be selected and moved on the Prepare plate.
    val objectPlacementEnabled = objectPositionsPresent && onPositionsChangedPresent
    return PreparePreviewPlacementConfig(
        objectPlacementEnabled = objectPlacementEnabled,
        wipeTowerVisible = wipeTowerEnabled
    )
}

internal fun resolvePreparePreviewModelInfo(
    state: SlicerViewModel.SlicerState,
    cachedModelInfo: ModelInfo?
): ModelInfo? = when (state) {
    is SlicerViewModel.SlicerState.ModelLoaded -> state.info
    is SlicerViewModel.SlicerState.Slicing -> cachedModelInfo
    is SlicerViewModel.SlicerState.SliceComplete -> cachedModelInfo
    else -> cachedModelInfo
}

@Composable
private fun LargePreviewFallback(
    triangleCount: Int,
    modelSizeX: Float,
    modelSizeY: Float,
    wipeTowerDepth: Float,
    objectPositions: FloatArray,
    wipeTowerVisible: Boolean,
    wipeTowerWidth: Float,
    initialTowerX: Float,
    initialTowerY: Float,
    onPositionsChanged: ((FloatArray, Pair<Float, Float>) -> Unit)? = null,
    onInfoClick: (() -> Unit)? = null
) {
    val objPositions = remember(objectPositions) { objectPositions.copyOf() }
    var towerX by remember(initialTowerX) { mutableFloatStateOf(initialTowerX) }
    var towerY by remember(initialTowerY) { mutableFloatStateOf(initialTowerY) }
    var draggingIdx by remember { mutableIntStateOf(-1) }
    var showInfoDialog by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        com.u1.slicer.ui.SimplifiedPlacementBed(
            modifier = Modifier.fillMaxSize(),
            modelSizeX = modelSizeX,
            modelSizeY = modelSizeY,
            towerDepthMm = wipeTowerDepth,
            copyCount = objPositions.size / 2,
            wipeTowerEnabled = wipeTowerVisible,
            wipeTowerWidthMm = wipeTowerWidth,
            objectX = List(objPositions.size / 2) { idx -> objPositions[idx * 2] },
            objectY = List(objPositions.size / 2) { idx -> objPositions[idx * 2 + 1] },
            towerX = towerX,
            towerY = towerY,
            draggingIdx = draggingIdx,
            onDraggingIdxChange = { draggingIdx = it },
            onObjectMove = { index, x, y ->
                objPositions[index * 2] = x
                objPositions[index * 2 + 1] = y
                onPositionsChanged?.invoke(objPositions.copyOf(), Pair(towerX, towerY))
            },
            onTowerMove = { x, y ->
                towerX = x
                towerY = y
                onPositionsChanged?.invoke(objPositions.copyOf(), Pair(towerX, towerY))
            }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            shape = RoundedCornerShape(999.dp),
            color = Color.Black.copy(alpha = 0.72f)
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "Lightweight preview",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelSmall
                )
                if (onInfoClick != null) {
                    IconButton(
                        onClick = onInfoClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Preview info",
                            tint = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }

        if (showInfoDialog) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                title = { Text("Lightweight preview") },
                text = {
                    Text(
                        "This file has %,d triangles, so the full 3D Prepare preview is skipped to keep memory use low. You can still drag the model footprint and wipe tower on the bed."
                            .format(triangleCount)
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) {
                        Text("Close")
                    }
                },
                dismissButton = if (onInfoClick != null) {
                    {
                        TextButton(onClick = onInfoClick) {
                            Text("Model info")
                        }
                    }
                } else null
            )
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
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Print Setup", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                } // end AnimatedVisibility Column
            } // end AnimatedVisibility
        }
    }
}

private data class RotAxis(
    val label: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val steps: Int
)

@Composable
fun ScaleSection(
    scale: SlicerViewModel.ModelScale,
    onScaleChange: (SlicerViewModel.ModelScale) -> Unit,
    copyCount: Int = 1,
    onSetCopyCount: (Int) -> Unit = {},
    rotation: SlicerViewModel.ModelRotation = SlicerViewModel.ModelRotation(),
    onRotationChange: (SlicerViewModel.ModelRotation) -> Unit = {}
) {
    var uniformMode by remember { mutableStateOf(true) }
    var uniformValue by remember(scale) { mutableFloatStateOf(scale.uniform) }
    var expanded by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header row — tappable to collapse/expand
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.OpenWith, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scale, Copies & Rotation", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                            text = { Text("Scale") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                            text = { Text("Rotation") })
                    }

                    if (selectedTab == 0) {
                        // --- Scale tab ---
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Copies: $copyCount", style = MaterialTheme.typography.labelMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Uniform", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(Modifier.width(4.dp))
                                Switch(checked = uniformMode, onCheckedChange = { uniformMode = it })
                            }
                        }
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
                    } else {
                        // --- Rotation tab ---
                        val axes = listOf(
                            RotAxis("Tilt (X)", rotation.x, -180f..180f, 35),
                            RotAxis("Tilt (Y)", rotation.y, -180f..180f, 35),
                            RotAxis("Rotate on bed (Z)", rotation.z, 0f..360f, 71)
                        )
                        axes.forEachIndexed { idx, ax ->
                            Text("${ax.label}: ${"%.0f°".format(ax.value)}",
                                style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = ax.value,
                                onValueChange = { nv ->
                                    onRotationChange(when (idx) {
                                        0 -> rotation.copy(x = nv)
                                        1 -> rotation.copy(y = nv)
                                        else -> rotation.copy(z = nv)
                                    })
                                },
                                valueRange = ax.range,
                                steps = ax.steps
                            )
                        }
                        if (rotation.x != 0f || rotation.y != 0f || rotation.z != 0f) {
                            TextButton(
                                onClick = { onRotationChange(SlicerViewModel.ModelRotation()) },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Reset to 0°", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
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
    colorMapping: List<Int>? = null,
    slicerLayerCount: Int = 0,
    onExpand: () -> Unit,
    cameraState: com.u1.slicer.viewer.CameraViewState? = null,
    onCameraStateChange: ((com.u1.slicer.viewer.CameraViewState) -> Unit)? = null,
    onResetView: (() -> Unit)? = null
) {
    var viewerView by remember { mutableStateOf<com.u1.slicer.viewer.GcodeViewerView?>(null) }
    var viewerLoading by remember(parsedGcode) { mutableStateOf(true) }
    var showTravel by remember { mutableStateOf(false) }
    val gcodeLayerCount = parsedGcode.layers.size
    // Use slicer's totalLayers for display (correct print layers), fall back to parsed count
    val displayLayerCount = if (slicerLayerCount > 0) slicerLayerCount else gcodeLayerCount
    var maxLayer by remember { mutableIntStateOf(gcodeLayerCount - 1) }
    val displayLayer = if (gcodeLayerCount > 0)
        ((maxLayer.toLong() * displayLayerCount) / gcodeLayerCount).toInt().coerceIn(1, displayLayerCount)
    else 1

    val previewColors = remember(extruderColors, colorMapping) {
        normalizeGcodePreviewColors(extruderColors, colorMapping)
    }

    LaunchedEffect(parsedGcode, previewColors, viewerView, cameraState) {
        val v = viewerView ?: return@LaunchedEffect
        viewerLoading = true
        maxLayer = gcodeLayerCount - 1
        if (previewColors.isNotEmpty()) {
            v.setExtruderColors(previewColors)
        }
        v.setGcode(parsedGcode)
        cameraState?.let { v.applyCameraState(it) }
        v.requestRender()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        com.u1.slicer.viewer.GcodeViewerView(ctx).also { view ->
                            viewerView = view
                            view.onCameraChanged = onCameraStateChange
                            view.setOnContentReady { viewerLoading = false }
                            cameraState?.let { view.applyCameraState(it) }
                        }
                    },
                    update = { view ->
                        viewerView = view
                        view.onCameraChanged = onCameraStateChange
                        view.setOnContentReady { viewerLoading = false }
                        cameraState?.let {
                            if (view.camera.snapshot() != it) {
                                view.applyCameraState(it)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                ) {
                    if (onResetView != null) {
                        IconButton(onClick = {
                            onResetView.invoke()
                        }) {
                            Icon(
                                Icons.Default.FilterCenterFocus,
                                "Reset view",
                                tint = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                    IconButton(onClick = {
                        showTravel = !showTravel
                        viewerView?.setShowTravel(showTravel)
                    }) {
                        Icon(
                            if (showTravel) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            "Toggle travel moves",
                            tint = if (showTravel) Color.White
                                   else Color.White.copy(alpha = 0.4f)
                        )
                    }
                    IconButton(onClick = onExpand) {
                        Icon(Icons.Default.Fullscreen, "Full screen",
                            tint = Color.White.copy(alpha = 0.8f))
                    }
                }
                // Layer label overlay — map gcode layer index to display layer
                Text(
                    "Layer $displayLayer/$displayLayerCount",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                )
                if (viewerLoading) {
                    ViewerLoadingOverlay("Preparing preview…")
                }
            }
            if (gcodeLayerCount > 1) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Layer 1",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Layer $displayLayer/$displayLayerCount",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Layer $displayLayerCount",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Slider(
                            value = maxLayer.toFloat(),
                            onValueChange = { v ->
                                maxLayer = v.roundToInt()
                                viewerView?.setLayerRange(0, maxLayer)
                            },
                            valueRange = 0f..(gcodeLayerCount - 1).toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

internal fun normalizeGcodePreviewColors(
    extruderColors: List<String>,
    colorMapping: List<Int>?
): List<String> {
    val normalized = MutableList(4) { "" }
    for (slot in 0..3) {
        normalized[slot] = extruderColors.getOrNull(slot).orEmpty()
    }
    // Ensure compact tool indices (T0/T1...) also resolve to the mapped slot colors.
    if (!colorMapping.isNullOrEmpty()) {
        colorMapping.take(4).forEachIndexed { compactIdx, slot ->
            if (slot in 0..3) {
                val slotColor = extruderColors.getOrNull(slot).orEmpty()
                if (slotColor.isNotBlank()) {
                    normalized[compactIdx] = slotColor
                }
            }
        }
    }
    return normalized
}

@Composable
private fun ViewerLoadingOverlay(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.24f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Black.copy(alpha = 0.58f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = Color.White
                )
                Text(
                    label,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
