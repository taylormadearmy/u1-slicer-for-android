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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.content.pm.PackageInfoCompat
import com.u1.slicer.data.ModelInfo
import com.u1.slicer.util.toFloatLenient
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val diagnostics by lazy { DiagnosticsStore(this) }
    private val viewModel: SlicerViewModel by viewModels()
    private val printerViewModel: PrinterViewModel by viewModels()
    private var testReceiver: TestCommandReceiver? = null
    private var launchApkUpdateTime: Long = 0L
    private var pendingNavigateTo: String? = null
    private var navigateTabCallback: ((String) -> Unit)? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        viewModel.setLoadingFromPicker()
        // F77 (GitHub #109): multi-select picker. One file uses the legacy
        // path; many files run through the multi-STL combiner.
        val supportedUris = uris.filter {
            val name = viewModel.getFileDisplayName(it) ?: ""
            name.isEmpty() || SlicerViewModel.isSupportedFile(name)
        }
        val rejected = uris - supportedUris.toSet()
        if (rejected.isNotEmpty()) {
            val name = viewModel.getFileDisplayName(rejected.first()) ?: ""
            viewModel.showUnsupportedFileError(name)
            return@registerForActivityResult
        }
        when (supportedUris.size) {
            0 -> Unit
            1 -> viewModel.loadModel(supportedUris.first())
            else -> viewModel.loadMultipleModels(supportedUris)
        }
    }

    private val addToBedLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.addModelFile(uri)
    }

    private val gcodeSaveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { viewModel.saveGcodeTo(it) }
    }

    private val sanitizedModelExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.ms-3mfdocument")
    ) { uri ->
        if (uri != null) {
            exportModelArtifact(ExportArtifactKind.Sanitized3mf, uri)
        }
    }

    private val embeddedModelExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.ms-3mfdocument")
    ) { uri ->
        if (uri != null) {
            exportModelArtifact(ExportArtifactKind.SanitizedEmbedded3mf, uri)
        }
    }

    private fun exportModelArtifact(kind: ExportArtifactKind, uri: Uri) {
        viewModel.exportArtifactTo(kind, uri) { result ->
            val message = result.fold(
                onSuccess = {
                    when (kind) {
                        ExportArtifactKind.Sanitized3mf -> "Sanitized 3MF exported"
                        ExportArtifactKind.SanitizedEmbedded3mf -> "Sanitized + embedded 3MF exported"
                    }
                },
                onFailure = { error ->
                    "Export failed: ${error.message ?: error.javaClass.simpleName}"
                }
            )
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyModelDebugSummary() {
        val summary = viewModel.buildModelDebugSummary() ?: return
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("U1 Slicer Model Debug Summary", summary)
        )
        android.widget.Toast.makeText(this, "Debug summary copied", android.widget.Toast.LENGTH_SHORT).show()
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

        // Request notification permission for Android 13+ (required for LongOpService
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

                // Phase 2.4 — Filament mapping dialog state. When the user
                // taps Send to Printer (or Upload Only), we capture the
                // requested gcode path and the action ("print" vs
                // "upload-only") here, then show the dialog. On confirm we
                // applyPrintTimeRemap to a temp file and dispatch the action
                // with the remapped path.
                var pendingMappingSend by remember {
                    mutableStateOf<PendingMappingSend?>(null)
                }
                // Bug fix (2026-05-01): hoist the IO scope used by the
                // FilamentMappingDialog onConfirm out of the
                // `pendingMappingSend?.let { ... }` gate. Pre-fix the scope
                // was declared inside the gate; onConfirm ran:
                //   pendingMappingSend = null
                //   navigateTab(Routes.PRINTER)
                //   scope.launch(IO) { applyPrintTimeRemap(); sendAndPrint() }
                // Setting pendingMappingSend = null caused the gate to leave
                // composition on the next frame, which cancelled the
                // rememberCoroutineScope tied to it. The scope.launch
                // dispatched into a dying scope and the IO coroutine was
                // silently killed before applyPrintTimeRemap or
                // sendAndPrint executed — print never sent to printer, no
                // logs, no error UI. Tying the scope to the parent
                // composable (which lives across the dialog lifecycle)
                // makes the IO work survive the dialog dismissal.
                val sendActionScope = rememberCoroutineScope()
                val appSlicerState by viewModel.state.collectAsState()
                // F88: collect model name so Save/Share G-code can suggest a meaningful filename.
                val currentModelFileName by viewModel.modelFileName.collectAsState()
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

                // F89: surface toast events from the ViewModel (e.g. "Couldn't resume <name>").
                val toastContext = LocalContext.current
                LaunchedEffect(Unit) {
                    viewModel.toastEvents.collect { msg ->
                        android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }

                // F89: ViewModel-driven navigation (e.g. restore-to-Preview after Resume).
                LaunchedEffect(Unit) {
                    viewModel.navigateEvents.collect { route ->
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
                    onSaveGcode = {
                        val suggested = "${com.u1.slicer.data.ModelFileNaming.baseName(
                            currentModelFileName, "output.gcode"
                        )}.gcode"
                        gcodeSaveLauncher.launch(suggested)
                    },
                    prepareContent = {
                        PrepareScreen(
                            viewModel = viewModel,
                            onPickFile = { filePickerLauncher.launch(pickFileMimeTypes) },
                            onAddFileToBed = { addToBedLauncher.launch(pickFileMimeTypes) },
                            onBrowseMakerWorld = { navController.navigate(Routes.MAKERWORLD_BROWSER) },
                            onNavigatePrepare = { },
                            onNavigatePreview = { navigateTab(Routes.PREVIEW) },
                            onNavigateSettings = { navigateTab(Routes.SETTINGS) },
                            onNavigatePrinter = { navigateTab(Routes.PRINTER) },
                            onNavigateJobs = { navigateTab(Routes.JOBS) },
                            onNavigateModelViewer = { navController.navigate(Routes.MODEL_VIEWER) },
                            onExportSanitized3mf = {
                                viewModel.suggestedArtifactFilename(ExportArtifactKind.Sanitized3mf)?.let { filename ->
                                    sanitizedModelExportLauncher.launch(filename)
                                }
                            },
                            onExportSanitizedEmbedded3mf = {
                                viewModel.suggestedArtifactFilename(ExportArtifactKind.SanitizedEmbedded3mf)?.let { filename ->
                                    embeddedModelExportLauncher.launch(filename)
                                }
                            },
                            onCopyModelDebugSummary = { copyModelDebugSummary() },
                            sharedPreviewCameraState = sharedPreviewCameraState,
                            onSharedPreviewCameraStateChange = { sharedPreviewCameraState = it },
                            onResetPreviewCamera = { sharedPreviewCameraState = null },
                            onNavigateAiPaint = { navController.navigate(Routes.AI_PAINT) },
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
                                pendingMappingSend = PendingMappingSend(
                                    gcodePath = gcodePath,
                                    action = PendingMappingSend.Action.PrintAndUpload,
                                )
                            },
                            onUploadOnly = { gcodePath ->
                                pendingMappingSend = PendingMappingSend(
                                    gcodePath = gcodePath,
                                    action = PendingMappingSend.Action.UploadOnly,
                                )
                            },
                            onNavigateJobs = { navigateTab(Routes.JOBS) },
                            onNavigateGcodeViewer3D = { navController.navigate(Routes.GCODE_VIEWER_3D) },
                            onShareGcode = { viewModel.shareGcode() },
                            onSaveGcode = {
                                val suggested = "${com.u1.slicer.data.ModelFileNaming.baseName(
                                    currentModelFileName, "output.gcode"
                                )}.gcode"
                                gcodeSaveLauncher.launch(suggested)
                            },
                            sharedPreviewCameraState = sharedPreviewCameraState,
                            onSharedPreviewCameraStateChange = { sharedPreviewCameraState = it },
                            onResetPreviewCamera = { sharedPreviewCameraState = null }
                        )
                    },
                    printerContent = {
                        val filaments by viewModel.filaments.collectAsState(initial = emptyList())
                        val excludeObjects by viewModel.excludeObjects.collectAsState()
                        PrinterScreen(
                            viewModel = printerViewModel,
                            filaments = filaments,
                            excludeObjects = excludeObjects,
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
                            onNavigateProcessProfiles = { navController.navigate(Routes.PROCESS_PROFILES) },
                            onNavigatePrepare = { navigateTab(Routes.PREPARE) },
                            onNavigatePreview = { navigateTab(Routes.PREVIEW) },
                            onNavigatePrinter = { navigateTab(Routes.PRINTER) },
                            onNavigateJobs = { navigateTab(Routes.JOBS) },
                            onNavigateSettings = { },
                            onNavigateMakerWorldLogin = { navController.navigate(Routes.MAKERWORLD_BROWSER) }
                        )
                    }
                )

                // Phase 2.4 — Filament mapping dialog interposed between
                // Send and the actual upload. Always shown when the user
                // taps Send (PrintAndUpload) or Upload Only.
                pendingMappingSend?.let { pending ->
                    // Phase 2 (2026-04-28, revised after adversarial review)
                    // — three-state canonical lookup. The lookup runs on
                    // Dispatchers.IO via produceState, so the initial
                    // composition has no answer yet. Pre-revision, that
                    // initial-null was indistinguishable from "no canonical
                    // list" and the fallback branch would send the raw
                    // canonical-fileIndex G-code straight to the printer
                    // before the lookup could complete.
                    //
                    // CanonicalLookup encodes the three states explicitly:
                    //   Loading  — IO in flight; keep dialog alive
                    //   Absent   — IO finished, no canonical list (legacy
                    //              file, STL with no canonical, etc.)
                    //   Present  — IO finished with a list; show dialog
                    val canonicalState by produceState<CanonicalLookup>(
                        initialValue = CanonicalLookup.Loading,
                        key1 = pending.gcodePath,
                    ) {
                        val list = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            viewModel.getCanonicalFilamentList()
                        }
                        value = if (list != null) CanonicalLookup.Present(list) else CanonicalLookup.Absent
                    }
                    val extruderPresets by viewModel.extruderPresets.collectAsState()
                    val currentMapping by viewModel.colorMapping.collectAsState()
                    val overrides by viewModel.filamentOverrides.collectAsState()
                    val threeMfInfo by viewModel.threeMfInfo.collectAsState()
                    val parsedGcodeForDialog by viewModel.parsedGcode.collectAsState()
                    val canonical = remember(canonicalState, overrides) {
                        (canonicalState as? CanonicalLookup.Present)?.let { p ->
                            com.u1.slicer.data.applyOverridesToCanonical(
                                p.list,
                                overrides.mapValues { (_, ov) ->
                                    ov.color to ov.materialType
                                },
                            )
                        }
                    }
                    // Phase 2 (2026-04-28, post-v2.0.0-validation Bug #2):
                    // narrow the dialog to just the active plate's filaments.
                    // v2.0.0 systematic fix: post-slice perExtruderFilamentMm
                    // is the single source of truth (canonical fileIdx order,
                    // 0.0 for unused). computePlateFileIndices uses it to
                    // derive plateFileIndices = canonical fileIdx where
                    // mm > 0. Border Collie → [1, 2]; Buzz plate 1 → [0, 1, 5, 8].
                    //
                    // Returns a Pair<narrowedCanonical, plateFileIndices> so
                    // onConfirm can expand the dialog's plate-narrowed
                    // mapping back to canonical-fileIndex space for
                    // applyPrintTimeRemap (which keys by full canonical fileIdx).
                    val plateNarrowed: Pair<com.u1.slicer.data.CanonicalFilamentList, List<Int>>? =
                        remember(canonical, threeMfInfo, viewModel.recoveryPlateId, parsedGcodeForDialog, extruderPresets) {
                            val full = canonical ?: return@remember null
                            val perMm = parsedGcodeForDialog?.perExtruderFilamentMm
                            val plateFileIndices = computePlateFileIndices(
                                threeMfInfo,
                                viewModel.recoveryPlateId,
                                full.size,
                                perExtruderFilamentMm = perMm,
                            )
                            when {
                                // B121: computePlateFileIndices returns null when G-code has
                                // active canonical indices beyond the canonical list size
                                // (e.g. STL sliced with support/interface on a different
                                // extruder). Expand the canonical list with synthetic entries
                                // for those extra slots so the Filament Mapping dialog shows
                                // all active extruders, not just the model filament.
                                plateFileIndices == null && perMm != null -> {
                                    buildWideGcodeMapping(full, perMm, extruderPresets)
                                        ?: (full to (0 until full.size).toList())
                                }
                                plateFileIndices == null || plateFileIndices.size == full.size ->
                                    full to (0 until full.size).toList()
                                else -> {
                                    val filtered = plateFileIndices.mapNotNull { idx ->
                                        full.filaments.getOrNull(idx)
                                    }
                                    if (filtered.size == plateFileIndices.size) {
                                        full.copy(filaments = filtered) to plateFileIndices
                                    } else {
                                        full to (0 until full.size).toList()
                                    }
                                }
                            }
                        }
                    val activeNickname by printerViewModel.activeNickname.collectAsState()
                    val printerCount by printerViewModel.printerCount.collectAsState()
                    when (canonicalState) {
                        is CanonicalLookup.Loading -> {
                            // IO in flight; keep pendingMappingSend alive.
                            // Composition will re-fire when produceState
                            // updates. No Send fires here — the race that
                            // sent canonical G-code to the printer pre-
                            // revision is closed.
                        }
                        is CanonicalLookup.Present -> {
                            if (canonical != null && plateNarrowed != null) {
                                val (narrowedList, plateFileIndices) = plateNarrowed
                                when (pending.action) {
                                    PendingMappingSend.Action.UploadOnly -> {
                                        // Internal-memory wrong-nozzle fix (2026-06-03):
                                        // send-to-hold ships the CANONICAL body and lets the
                                        // printer's Filament Setup map it once. No in-app slot
                                        // picker — its picks would double-remap (the body is
                                        // already physical + the printer maps again → wrong
                                        // nozzle). See gcode.sendRemapForAction KDoc.
                                        com.u1.slicer.ui.UploadConfirmationDialog(
                                            canonicalList = narrowedList,
                                            plateFileIndices = plateFileIndices,
                                            modelName = viewModel.modelFileName.value,
                                            // Show the sliced-with material (slot preset wins
                                            // over file-declared), matching the Per-Extruder
                                            // summary — e.g. PETG, not the file's PLA.
                                            slicedMaterials = viewModel.sliceTimeMaterials(canonical, currentMapping),
                                            onConfirm = {
                                                val sourceFile = java.io.File(pending.gcodePath)
                                                val heldFile = java.io.File(
                                                    sourceFile.parentFile,
                                                    "${sourceFile.nameWithoutExtension}.remapped.${sourceFile.extension}"
                                                )
                                                pendingMappingSend = null
                                                navigateTab(Routes.PRINTER)
                                                printerViewModel.beginSendPreparing()
                                                sendActionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                    // Foreground service across the large-file
                                                    // copy so the freezer can't kill it once
                                                    // the app is backgrounded.
                                                    LongOpService.start(toastContext, "Preparing G-code")
                                                    val physical = try {
                                                        com.u1.slicer.gcode.applyPrintTimeRemap(
                                                            source = com.u1.slicer.gcode.CanonicalGcodePath.of(sourceFile),
                                                            output = com.u1.slicer.gcode.PhysicalGcodePath.of(heldFile),
                                                            colorMapping = com.u1.slicer.gcode.sendRemapForAction(
                                                                uploadOnly = true,
                                                                physicalMapping = emptyList(),
                                                            ),
                                                        )
                                                    } catch (ce: kotlinx.coroutines.CancellationException) {
                                                        throw ce
                                                    } catch (t: Throwable) {
                                                        withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main) {
                                                            printerViewModel.reportSendError("Couldn't prepare G-code: ${t.message ?: "unknown error"}")
                                                        }
                                                        return@launch
                                                    } finally {
                                                        LongOpService.stop(toastContext)
                                                    }
                                                    val modelName = viewModel.modelFileName.value
                                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                        printerViewModel.sendUploadOnly(physical, modelName)
                                                    }
                                                }
                                            },
                                            onDismiss = { pendingMappingSend = null },
                                        )
                                    }
                                    PendingMappingSend.Action.PrintAndUpload -> {
                                // Phase 2.5 final: slices produce canonical
                                // (file-filament-relative) T-indices. The
                                // dialog now shows only the plate's
                                // filaments; on confirm we expand the
                                // dialog's mapping back to full-canonical
                                // space (placing each plate-row's slot at
                                // the matching canonical fileIdx) so
                                // applyPrintTimeRemap rewrites the right
                                // T<n> values.
                                com.u1.slicer.ui.FilamentMappingDialog(
                                    canonicalList = narrowedList,
                                    extruderPresets = extruderPresets,
                                    initialMapping = currentMapping,
                                    plateFileIndices = plateFileIndices,
                                    filamentMaterialOverrides = overrides.mapValues { (_, ov) -> ov.materialType },
                                    sliceTimeColorMapping = currentMapping,
                                    // B128: feed the actual sliced materials (file-declared
                                    // when applicable) so the "Sliced as X" mismatch check
                                    // matches the slice instead of the slot preset.
                                    sliceTimeMaterials = viewModel.sliceTimeMaterials(canonical, currentMapping),
                                    activeNickname = activeNickname,
                                    showNicknameInTitle = printerCount > 1,
                                    onConfirm = { plateMapping ->
                                        val sourceFile = java.io.File(pending.gcodePath)
                                        val remappedFile = java.io.File(
                                            sourceFile.parentFile,
                                            "${sourceFile.nameWithoutExtension}.remapped.${sourceFile.extension}"
                                        )
                                        // Expand plate-narrowed mapping →
                                        // full canonical-wide mapping
                                        // (positioning each plate row's
                                        // slot at the matching canonical
                                        // fileIdx).
                                        // B121: for STL+support, plateFileIndices may contain
                                        // indices ≥ canonical.size (support/interface slots
                                        // beyond the model's 1-entry canonical list). Use
                                        // expandedSize so those entries aren't dropped.
                                        val canonicalSize = canonical.size
                                        val maxFileIdx = plateFileIndices.maxOrNull() ?: (canonicalSize - 1)
                                        val expandedSize = maxOf(canonicalSize, maxFileIdx + 1)
                                        val expanded = MutableList(expandedSize) { fileIdx -> fileIdx % 4 }
                                        plateFileIndices.forEachIndexed { posInPlate, fileIdx ->
                                            val slot = plateMapping.getOrNull(posInPlate)
                                            if (slot != null && fileIdx in 0 until expandedSize) {
                                                // Physical slot (0..3). The explicit if/else
                                                // avoids tripping the coerceIn grep guard in
                                                // HardcodedExtruderCapTest.
                                                expanded[fileIdx] = if (slot >= 0) slot else 0
                                            }
                                        }
                                        pendingMappingSend = null
                                        navigateTab(Routes.PRINTER)
                                        printerViewModel.beginSendPreparing()
                                        sendActionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            // Foreground service across the ~80 s
                                            // remap of a large G-code so the freezer
                                            // can't kill it once the user backgrounds
                                            // the app; the upload step starts its own.
                                            LongOpService.start(toastContext, "Preparing G-code")
                                            // Map & Print runs verbatim via the API, so its
                                            // body must already be in physical-slot space —
                                            // keep the physical remap. See sendRemapForAction.
                                            val sendMapping = com.u1.slicer.gcode.sendRemapForAction(
                                                uploadOnly = false,
                                                physicalMapping = expanded,
                                            )
                                            val physical = try {
                                                com.u1.slicer.gcode.applyPrintTimeRemap(
                                                    source = com.u1.slicer.gcode.CanonicalGcodePath.of(sourceFile),
                                                    output = com.u1.slicer.gcode.PhysicalGcodePath.of(remappedFile),
                                                    colorMapping = sendMapping,
                                                )
                                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                                throw ce
                                            } catch (t: Throwable) {
                                                withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main) {
                                                    printerViewModel.reportSendError("Couldn't prepare G-code: ${t.message ?: "unknown error"}")
                                                }
                                                return@launch
                                            } finally {
                                                LongOpService.stop(toastContext)
                                            }
                                            val modelName = viewModel.modelFileName.value
                                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                printerViewModel.sendAndPrint(physical, modelName)
                                            }
                                        }
                                    },
                                    onDismiss = { pendingMappingSend = null }
                                )
                                    }
                                }
                            }
                        }
                        is CanonicalLookup.Absent -> {
                            // Legacy / unrecognised file with no canonical
                            // list. Route through the resolver-driven helper
                            // so the export boundary stays consistent with
                            // Save/Share — even though the resolver returns
                            // null here (identity copy), going through the
                            // helper means a future change to the absent
                            // path lands in one place, not three.
                            val sourceFile = java.io.File(pending.gcodePath)
                            val exportedFile = java.io.File(
                                sourceFile.parentFile,
                                "${sourceFile.nameWithoutExtension}.remapped.${sourceFile.extension}"
                            )
                            pendingMappingSend = null
                            navigateTab(Routes.PRINTER)
                            printerViewModel.beginSendPreparing()
                            sendActionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                // Phase 2 B.1 — Absent canonical path
                                // means legacy/unrecognised file. Treat
                                // the source as already physical-slot
                                // (v1.6.13-era contract) and copy
                                // verbatim into the typed output.
                                // Foreground service across the copy so the
                                // freezer can't kill it (see canonical path).
                                LongOpService.start(toastContext, "Preparing G-code")
                                try {
                                    viewModel.prepareExportableGcodeWithMapping(
                                        sourceFile, exportedFile, mapping = null
                                    )
                                } catch (ce: kotlinx.coroutines.CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main) {
                                        printerViewModel.reportSendError("Couldn't prepare G-code: ${t.message ?: "unknown error"}")
                                    }
                                    return@launch
                                } finally {
                                    LongOpService.stop(toastContext)
                                }
                                val physical = com.u1.slicer.gcode.PhysicalGcodePath.of(exportedFile)
                                val modelName = viewModel.modelFileName.value
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    when (pending.action) {
                                        PendingMappingSend.Action.PrintAndUpload ->
                                            printerViewModel.sendAndPrint(physical, modelName)
                                        PendingMappingSend.Action.UploadOnly ->
                                            printerViewModel.sendUploadOnly(physical, modelName)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Phase 2.4 — captures a deferred Send action while the Filament mapping
 * dialog collects the user's slot picks. [action] is either
 * `Action.PrintAndUpload` (Send to Printer) or `Action.UploadOnly`.
 */
internal data class PendingMappingSend(
    val gcodePath: String,
    val action: Action,
) {
    enum class Action { PrintAndUpload, UploadOnly }
}

/**
 * Phase 2 (2026-04-28, post-adversarial-review) — three-state canonical
 * filament list lookup for the Send dialog. Distinguishes "IO in flight"
 * from "no canonical list" so a fast-tap user can't slip canonical-
 * fileIndex G-code past the mapping dialog while the lookup is loading.
 *
 * See `docs/superpowers/specs/2026-04-28-canonical-export-mapping-helper-design.md`
 * § "Send dialog three-state lookup".
 */
internal sealed class CanonicalLookup {
    object Loading : CanonicalLookup()
    object Absent : CanonicalLookup()
    data class Present(val list: com.u1.slicer.data.CanonicalFilamentList) : CanonicalLookup()
}

/**
 * B121 — when [computePlateFileIndices] returns null because the G-code used
 * canonical indices beyond [canonical]'s declared size (e.g. an STL sliced
 * with support/interface on a different extruder), this function synthesises
 * the missing [com.u1.slicer.data.FilamentEntry] rows so the Filament Mapping
 * dialog shows all active extruders, not just the model filament.
 *
 * Returns a [Pair] of the expanded [com.u1.slicer.data.CanonicalFilamentList]
 * and the active canonical indices, or null when [perExtruderFilamentMm] has
 * no active entries beyond [canonical].size (no expansion needed).
 */
internal fun buildWideGcodeMapping(
    canonical: com.u1.slicer.data.CanonicalFilamentList,
    perExtruderFilamentMm: List<Float>,
    extruderPresets: List<com.u1.slicer.data.ExtruderPreset>,
): Pair<com.u1.slicer.data.CanonicalFilamentList, List<Int>>? {
    val activeIndices = perExtruderFilamentMm.indices
        .filter { perExtruderFilamentMm[it] > 0f }
        .sorted()
    if (activeIndices.isEmpty() || activeIndices.none { it >= canonical.size }) return null
    val syntheticEntries = activeIndices.map { idx ->
        canonical.filaments.getOrNull(idx)
            ?: com.u1.slicer.data.FilamentEntry(
                fileIndex = idx,
                color = extruderPresets.getOrNull(idx)?.color ?: "#808080",
                materialType = extruderPresets.getOrNull(idx)?.materialType,
                source = com.u1.slicer.data.FilamentSource.SUPPORT_FILAMENT,
            )
    }
    return canonical.copy(filaments = syntheticEntries) to activeIndices
}

/**
 * Phase 2 (2026-04-28, post-v2.0.0-validation Bug #2) — derives the
 * 0-indexed canonical fileIndices that the active plate's filaments
 * occupy in the file-wide canonical filament list.
 *
 * Sources, in priority order:
 *   1. `plate.filamentIndices` (1-indexed Bambu format) → 0-indexed.
 *   2. `info.usedExtruderIndices` (1-indexed plate-narrowed extruder
 *      set, from native plate state) — fallback when the plate
 *      metadata is empty/legacy.
 *
 * Returns null when the active plate can't be determined (single-
 * plate file, no plate selected, etc.) — caller should fall back to
 * showing the full canonical list. Returns the file-wide list of
 * indices when there's no narrowing to apply.
 */
internal fun computePlateFileIndices(
    info: com.u1.slicer.bambu.ThreeMfInfo?,
    plateId: Int,
    canonicalSize: Int,
    perExtruderFilamentMm: List<Float>? = null,
): List<Int>? {
    if (canonicalSize <= 0) return null
    // v2.0.0 systematic fix — when post-slice `perExtruderFilamentMm` is
    // available, derive plateFileIndices from non-zero canonical entries.
    // The footer line is the slicer's authoritative output: canonical fileIdx
    // order, sized to canonical, 0.0 for unused. This is the SINGLE SOURCE
    // OF TRUTH post-slice and supersedes every pre-slice heuristic below when
    // it is the same width as the canonical model/material list.
    //
    // Examples:
    //   - Border Collie (single-plate SEMM, canonical=5): footer
    //     [0, X, X, 0, 0] → returns [1, 2]. Dialog shows 2 rows; chips
    //     label "Filament 2, Filament 3" via fileIdx.
    //   - Buzz plate 1 (multi-plate, canonical=11): footer
    //     [X, X, 0, 0, 0, X, 0, 0, X, 0, 0] → returns [0, 1, 5, 8]. Dialog
    //     shows 4 rows; chips label "Filament 1, 2, 6, 9".
    //   - slip_slide_spin plate 3 (multi-plate SEMM, canonical=10): footer
    //     non-zero at [0, 1, 2, 3] → returns [0, 1, 2, 3]. 4 rows / chips.
    //   - STL with support/interface filaments (canonical=1): footer can be
    //     wider than the synthetic one-filament model list, e.g. [PLA, PETG,
    //     PETG]. In that case null deliberately falls back to raw G-code slots.
    if (perExtruderFilamentMm != null) {
        val nonZero = perExtruderFilamentMm.indices
            .filter { perExtruderFilamentMm[it] > 0f }
        if (nonZero.any { it >= canonicalSize }) return null
        if (nonZero.isNotEmpty()) return nonZero
    }
    if (info == null) return null
    val detectedSize = info.detectedColors.size
    // Pre-slice fallback for SEMM/painted (single + multi-plate): detectedColors
    // is the most authoritative pre-slice narrowing signal. Plate metadata
    // (plate.filamentIndices) can undercount paint states.
    if (info.hasPaintData && detectedSize in 1..canonicalSize) {
        return (0 until detectedSize).toList()
    }
    // Pre-slice multi-plate non-painted: plate.filamentIndices set by
    // buildThreeMfInfoFromNative is authoritative for per-object Bambu
    // models (Dragon Scale and similar).
    val plate = if (plateId >= 0) info.plates.firstOrNull { it.plateId == plateId } else null
    if (plate != null && plate.filamentIndices.isNotEmpty()) {
        val zeroIndexed = plate.filamentIndices
            .map { it - 1 }
            .filter { it in 0 until canonicalSize }
            .distinct()
            .sorted()
        if (zeroIndexed.isNotEmpty()) return zeroIndexed
    }
    // Per-object multi-extruder (non-painted, single-plate): usedExtruderIndices
    // captures volume-extruder diversity directly (Button-for-S-trousers).
    val fromUsed = info.usedExtruderIndices
        .filter { it > 0 }
        .map { it - 1 }
        .filter { it in 0 until canonicalSize }
        .distinct()
        .sorted()
    if (fromUsed.isNotEmpty()) return fromUsed
    // Final fallback.
    if (detectedSize in 1..canonicalSize) {
        return (0 until detectedSize).toList()
    }
    return null
}

// Phase 2 (2026-04-28) — Group B: `computeDialogRewrite` was retired.
// Pre-Phase-2 the slice path baked the pre-dialog `colorMapping` into the
// G-code via `GcodeToolRemapper.remap`, so the on-disk T-indices were
// physical-slot space and the dialog's mapping had to be remapped via this
// helper. With the canonical contract the slicer emits T-indices in
// fileIndex space, the GcodeToolRemapper call was removed in `1e95c7d`,
// and the dialog's mapping is now the only translation needed —
// `applyPrintTimeRemap(mapping)` is correct directly. The helper had no
// production callers left and only its unit test
// (ComputeDialogRewriteTest) referenced it.

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
    onAddFileToBed: () -> Unit = {},
    onBrowseMakerWorld: () -> Unit = {},
    onNavigatePrepare: () -> Unit,
    onNavigatePreview: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigatePrinter: () -> Unit,
    onNavigateJobs: () -> Unit,
    onNavigateModelViewer: () -> Unit,
    onExportSanitized3mf: () -> Unit,
    onExportSanitizedEmbedded3mf: () -> Unit,
    onCopyModelDebugSummary: () -> Unit,
    sharedPreviewCameraState: com.u1.slicer.viewer.CameraViewState?,
    onSharedPreviewCameraStateChange: (com.u1.slicer.viewer.CameraViewState) -> Unit,
    onResetPreviewCamera: (() -> Unit)? = null,
    onNavigateAiPaint: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val config by viewModel.config.collectAsState()
    val slicingOverrides by viewModel.slicingOverrides.collectAsState()
    val plateType by viewModel.plateType.collectAsState()
    val context = LocalContext.current
    val modelInfo by viewModel.modelInfo.collectAsState()
    val showPlateSelector by viewModel.showPlateSelector.collectAsState()
    val multiPlatePlates by viewModel.multiPlatePlates.collectAsState()
    val currentModelName by viewModel.modelFileName.collectAsState()
    val currentPlateId by viewModel.currentPlateId.collectAsState()
    val showMultiColorDialog by viewModel.showMultiColorDialog.collectAsState()
    val colorMapping by viewModel.colorMapping.collectAsState()
    val threeMfInfo by viewModel.threeMfInfo.collectAsState()
    val filaments by viewModel.filaments.collectAsState(initial = emptyList())
    val extruderPresets by viewModel.extruderPresets.collectAsState()
    val copyCount by viewModel.copyCount.collectAsState()
    val copyBedWarning by viewModel.copyBedWarning.collectAsState()
    val modelScale by viewModel.modelScale.collectAsState()
    val modelRotation by viewModel.modelRotation.collectAsState()
    val loadTimeInstanceOffsets by viewModel.loadTimeInstanceOffsets.collectAsState()
    val nativeSliceStateDirty by viewModel.nativeSliceStateDirty.collectAsState()
    val objectBoundingBoxes by viewModel.objectBoundingBoxes.collectAsState()
    val hasMultipleDistinctObjects by viewModel.hasMultipleDistinctObjects.collectAsState()
    val modelAddVersion by viewModel.modelAddVersion.collectAsState()
    val multiObjectPositions by viewModel.multiObjectPositions.collectAsState()
    // B132c follow-up (v2.10.5): the bed-wide Scale/Rotation section is hidden
    // when an object is selected — the EditPanel takes over with per-object
    // scale + rotation + Copy. Surfacing both at once misled users into
    // thinking the bed-wide controls would only affect the selection.
    val topLevelSelection by viewModel.selection.collectAsState()
    val extruderColors by viewModel.activeExtruderColors.collectAsState()
    val layerToolOnly by viewModel.layerToolOnly.collectAsState()
    val sourceConfig by viewModel.sourceConfig.collectAsState()
    val canonicalFilamentList by viewModel.canonicalFilamentList.collectAsState()
    val resolvedFilamentColors by viewModel.resolvedFilamentColors.collectAsState()
    val meshAlignedFilamentColors by viewModel.meshAlignedFilamentColors.collectAsState()
    val canonicalFilamentColors by viewModel.canonicalFilamentColors.collectAsState()
    var captureViewer by remember { mutableStateOf<com.u1.slicer.viewer.ModelViewerView?>(null) }
    val pendingAddFile by viewModel.pendingAddFile.collectAsState()
    val sessionResumeOffer by viewModel.sessionResumeOffer.collectAsState()
    val silentRestoreInProgress by viewModel.silentRestoreInProgress.collectAsState()

    // Plate selector dialogs — mutually exclusive: only one can be shown at a time.
    // If the initial-load selector is open and the user also triggers an add-to-bed
    // on a multi-plate 3MF, we show the initial-load selector until it is dismissed
    // (the add-to-bed pending state is preserved and will show next).
    val addPending = pendingAddFile
    if (showPlateSelector && multiPlatePlates.isNotEmpty()) {
        com.u1.slicer.ui.PlateSelectDialog(
            plates = multiPlatePlates,
            onSelect = { viewModel.selectPlate(it) },
            onDismiss = { viewModel.dismissPlateSelector() },
            info = threeMfInfo
        )
    } else if (addPending != null && addPending.plates.isNotEmpty()) {
        // F85: plate selector dialog — "Add to bed" multi-plate 3MF
        com.u1.slicer.ui.PlateSelectDialog(
            plates = addPending.plates,
            onSelect = { viewModel.confirmAddPlate(it) },
            onDismiss = { viewModel.cancelPendingAdd() },
            info = null
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
                            "☕ Support development",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(BMAC_URL)
                                    )
                                )
                            }
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
                state is SlicerViewModel.SlicerState.Cancelling ||
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
                if (sessionResumeOffer != null && state is SlicerViewModel.SlicerState.Idle) {
                    SessionResumeBanner(
                        offer = sessionResumeOffer!!,
                        onAccept = { viewModel.acceptSessionResume() },
                        onDismiss = { viewModel.dismissSessionResume() },
                    )
                }
                if (silentRestoreInProgress) {
                    SilentRestoreIndicator()
                }
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
                            // B109 review: observe rotatedMeshSizeXY so this composable
                            // recomposes when the rotated preview mesh's AABB updates
                            // (typically ~300ms after the user rotates). On recomposition
                            // `getPlacementPositions()` re-evaluates with the refined
                            // bounds, snapping the auto-centered position to where the
                            // renderer actually draws the rotated model. Unused locally —
                            // the observation alone is what triggers recomposition.
                            @Suppress("UNUSED_VARIABLE")
                            val rotatedMeshSizeXY by viewModel.rotatedMeshSizeXY.collectAsState()
                            val positions = viewModel.getPlacementPositions()
                            val loadedInfo = info
                            // fix35: Smart Paint as a top-right overlay icon on the 3D viewer.
                            // Replaces the two inline TextButton/OutlinedButton blocks that
                            // previously appeared below PrintSetupSection. Both painted and
                            // single-colour models go through the same code path now (the
                            // pipeline branches internally based on colorMapping / paint data).
                            val smartPaintAvailable =
                                state is SlicerViewModel.SlicerState.ModelLoaded ||
                                state is SlicerViewModel.SlicerState.SliceComplete
                            val smartPaintCallback: (() -> Unit)? = if (smartPaintAvailable) {
                                {
                                    val container = (context.applicationContext as U1SlicerApplication).container
                                    val path = viewModel.currentModelPath
                                    if (path != null) {
                                        val cachedResult = (container.aiPaintViewModel.uiState.value as? com.u1.slicer.aipaint.AiPaintUiState.Result)?.state
                                        val isReedit = cachedResult != null &&
                                            com.u1.slicer.aipaint.AiPaintViewModel.isSamePainting(
                                                cachedResult.paintedModelPath, path
                                            )
                                        if (!isReedit) {
                                            container.aiPaintViewModel.runPipeline(
                                                path,
                                                viewModel.nativeLib,
                                                printerColours = viewModel.activeExtruderColors.value
                                                    .takeIf { it.isNotEmpty() },
                                                sourceDisplayName = viewModel.modelFileName.value,
                                            )
                                        }
                                        onNavigateAiPaint()
                                    }
                                }
                            } else null
                            val isReeditSmartPaint = remember(modelPath) {
                                val container = (context.applicationContext as U1SlicerApplication).container
                                val cachedResult = (container.aiPaintViewModel.uiState.value as? com.u1.slicer.aipaint.AiPaintUiState.Result)?.state
                                cachedResult != null &&
                                    com.u1.slicer.aipaint.AiPaintViewModel.isSamePainting(
                                        cachedResult.paintedModelPath, modelPath
                                    )
                            }
                            val f66Selection by viewModel.selection.collectAsState()
                            val f66Poses by viewModel.perObjectPoses.collectAsState()
                            val f66LoadTimePoses by viewModel.loadTimePoses.collectAsState()
                            val f66AnyRotationDirty = f66Poses.any { (k, v) ->
                                val b = f66LoadTimePoses[k] ?: com.u1.slicer.data.PerObjectPose()
                                v.rotXDeg != b.rotXDeg || v.rotYDeg != b.rotYDeg || v.rotZDeg != b.rotZDeg
                            }
                            val f66AnyScaleDirty = f66Poses.any { (k, v) ->
                                val b = f66LoadTimePoses[k] ?: com.u1.slicer.data.PerObjectPose()
                                v.scaleX != b.scaleX || v.scaleY != b.scaleY || v.scaleZ != b.scaleZ
                            }
                            InlineModelPreview(
                                modelFilePath = modelPath,
                                modelTriangleCount = loadedInfo?.triangleCount ?: 0,
                                selectedObjectIndex = f66Selection.objectIndex,
                                onObjectTapped = { idx -> viewModel.selectObject(idx) },
                                onEmptyTap = { viewModel.deselect() },
                                onAutoOrientAll = { viewModel.launchAutoOrientAll() },
                                onAutoArrangeAll = { viewModel.launchAutoArrangeAll() },
                                onResetAllRotations = if (f66AnyRotationDirty) {
                                    { viewModel.resetAllRotations() }
                                } else null,
                                onResetAllScales = if (f66AnyScaleDirty) {
                                    { viewModel.resetAllScales() }
                                } else null,
                                onFullScreen = if (modelPath.endsWith(".stl", ignoreCase = true))
                                    onNavigateModelViewer else ({}),
                                extruderColors = extruderColors,
                                extruderMap = viewModel.buildExtruderMap(),
                                colorMapping = colorMapping,
                                hasPaintData = threeMfInfo?.hasPaintData == true,
                                objectPositions = positions,
                                modelSizeX = loadedInfo?.sizeX ?: 0f,
                                modelSizeY = loadedInfo?.sizeY ?: 0f,
                                modelSizeZ = loadedInfo?.sizeZ ?: 0f,
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
                                resolvedFilamentColors = resolvedFilamentColors,
                                meshAlignedFilamentColors = meshAlignedFilamentColors,
                                cachedMesh = if (viewModel.cachedPrepareMeshPath == modelPath) viewModel.cachedPrepareMesh else null,
                                onMeshCached = { mesh ->
                                    viewModel.cachedPrepareMeshPath = modelPath
                                    viewModel.cachedPrepareMesh = mesh
                                    // B109 review: feed the mesh's rotated AABB to the
                                    // ViewModel so getPlacementPositions / setCopyCount
                                    // can use it for auto-center + bed-warning math
                                    // (matches what the renderer draws). Pre-scale —
                                    // the helper applies modelScale.
                                    viewModel.setRotatedMeshSize(
                                        mesh.maxX - mesh.minX,
                                        mesh.maxY - mesh.minY,
                                    )
                                    // F81: notify when a large preview mesh finishes
                                    // building so background users know Prepare is ready.
                                    val tris = (mesh.vertices.limit() / 10 / 3)
                                    viewModel.onPreparePreviewReady(tris)
                                },
                                loadTimeInstanceOffsets = loadTimeInstanceOffsets,
                                nativeSliceStateDirty = nativeSliceStateDirty,
                                perObjectSizes = if (hasMultipleDistinctObjects) objectBoundingBoxes else floatArrayOf(),
                                modelAddVersion = modelAddVersion,
                                multiObjectPositions = multiObjectPositions,
                                onSmartPaint = smartPaintCallback,
                                isReeditSmartPaint = isReeditSmartPaint,
                            )
                            if (showInfoDialog && loadedInfo != null) {
                                ModelInfoDialog(
                                    info = loadedInfo,
                                    threeMfInfo = threeMfInfo,
                                    exportArtifacts = viewModel.getExportableModelArtifacts(),
                                    config = config,
                                    onToggleWipeTower = { viewModel.togglePrimeTower() },
                                    onReassign = { viewModel.showMultiColorReassign() },
                                    onExportSanitized = onExportSanitized3mf,
                                    onExportEmbedded = onExportSanitizedEmbedded3mf,
                                    onCopyDebugSummary = onCopyModelDebugSummary,
                                    onDismiss = { showInfoDialog = false }
                                )
                            }
                        }
                        // F66 — Edit panel: bed-wide controls when nothing is
                        // selected, object-scoped controls (Auto-orient, Split,
                        // Reset rotation/scale, Parts panel) when an object is.
                        if (currentModelName.isNotEmpty()) {
                            com.u1.slicer.ui.EditPanel(
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                        }
                        // File/plate label row — always shown when a model is loaded
                        if (currentModelName.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (multiPlatePlates.isNotEmpty()) {
                                    AssistChip(
                                        onClick = { viewModel.reopenPlateSelector() },
                                        label = { Text("Change plate") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Layers,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                                BadgedBox(
                                    badge = {
                                        com.u1.slicer.ui.BetaPill()
                                    }
                                ) {
                                    AssistChip(
                                        onClick = onAddFileToBed,
                                        label = { Text("Add to bed") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Add,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                                val displayName = currentModelName
                                    .removeSuffix(".3mf")
                                    .removeSuffix(".stl")
                                    .removeSuffix(".obj")
                                    .removeSuffix(".step")
                                val plateLabel = if (currentPlateId > 0) " · Plate $currentPlateId" else ""
                                Text(
                                    text = "$displayName$plateLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        // Inline filament list + prime tower toggle
                        val filamentOverrides by viewModel.filamentOverrides.collectAsState()
                        // B128: per-filament (material, temp) resolved by the same
                        // function the slice uses, so the chip strip shows the file's
                        // declared materials and never diverges from the slice.
                        val filamentMaterials by viewModel.displayedFilamentMaterials.collectAsState()
                        PrintSetupSection(
                            detectedColors = threeMfInfo?.detectedColors ?: emptyList(),
                            colorMapping = colorMapping,
                            extruderPresets = extruderPresets,
                            filaments = filaments,
                            filamentMaterials = filamentMaterials,
                            extruderCount = config.extruderCount,
                            onMappingChange = { newMapping ->
                                viewModel.applyMultiColorAssignments(newMapping, extruderPresets, filaments)
                            },
                            onAutoMap = {
                                viewModel.reAutoMapColors(extruderPresets, filaments)
                            },
                            filamentOverrides = filamentOverrides,
                            onMaterialOverride = { idx, material ->
                                viewModel.setFilamentMaterialOverride(idx, material)
                            },
                            onColorOverride = { idx, color ->
                                viewModel.setFilamentColorOverride(idx, color)
                            },
                            // Task 3: Mixes subsection folded into this card.
                            mixSlotsContent = {
                                PrepareMixSlotsSectionContent(
                                    viewModel = viewModel,
                                    extruderPresets = extruderPresets,
                                )
                            },
                        )
                        // fix35: Smart Paint moved to a top-right overlay icon on InlineModelPreview
                        // (the painted-model inline TextButton previously here is gone).
                        // Scale & copies controls — hidden when an object is selected
                        // so the bed-wide controls don't compete with the per-object
                        // controls in the EditPanel below.
                        if (topLevelSelection.objectIndex == null) {
                            ScaleSection(
                                scale = modelScale,
                                onScaleChange = { viewModel.setModelScale(it) },
                                copyCount = copyCount,
                                onSetCopyCount = viewModel::setCopyCount,
                                copyBedWarning = copyBedWarning,
                                rotation = modelRotation,
                                onRotationChange = { viewModel.setModelRotation(it) },
                                loadTimeSize = modelInfo?.let { Triple(it.sizeX, it.sizeY, it.sizeZ) },
                                // B132c follow-up: hide whole-model Copies in
                                // multi-object mode — per-object Copy lives in the
                                // selection Edit panel instead.
                                multiObjectMode = hasMultipleDistinctObjects,
                            )
                        }
                        // Phase 2 §4 Step 7 / Task 3 UX consolidation: the single-colour
                        // Filament card (SingleColorFilamentRow) has been merged into
                        // PrintSetupSection above — that composable already handles the
                        // STL/single-filament case by synthesising a fallback from the
                        // first extruder preset. The separate Mix slots card
                        // (PrepareMixSlotsSection) is now a "Mixes" subsection inside
                        // PrintSetupSection so both live in ONE card.
                        // fix35: Smart Paint moved to the 3D-viewer overlay (top-right icon).
                        // Single-colour Smart Paint entry previously here is gone.
                        ConfigCard(
                            config, viewModel::updateConfig,
                            slicingOverrides = slicingOverrides,
                            onOverridesChange = { viewModel.saveSlicingOverrides(it) },
                            plateType = plateType,
                            onPlateTypeChange = { viewModel.setPlateType(it) },
                            bedTemp = config.bedTemp,
                            onBedTempChange = { viewModel.setBedTemp(it) },
                            sourceConfig = sourceConfig,
                            filamentCount = canonicalFilamentList?.size
                                ?: threeMfInfo?.detectedColors?.size?.takeIf { it > 0 },
                            extruderPresets = extruderPresets,
                            colorMapping = colorMapping,
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
    val context = LocalContext.current
    val parsedGcode by viewModel.parsedGcode.collectAsState()
    val extruderColors by viewModel.activeExtruderColors.collectAsState()
    val colorMapping by viewModel.colorMapping.collectAsState()
    val threeMfInfo by viewModel.threeMfInfo.collectAsState()
    val config by viewModel.config.collectAsState()
    val extruderPresets by viewModel.extruderPresets.collectAsState()
    val sliceStale by viewModel.sliceStale.collectAsState()
    val resolvedFilamentColors by viewModel.resolvedFilamentColors.collectAsState()
    val canonicalFilamentColors by viewModel.canonicalFilamentColors.collectAsState()
    val previewLayerRange by viewModel.previewLayerRange.collectAsState()
    // B142b: mix slices use physical-slot tool space — palette/summary must follow.
    val sliceMixToolSpace by viewModel.sliceMixToolSpace.collectAsState()
    val slotPaletteWithMixBlends by viewModel.slotPaletteWithMixBlends.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("U1 Slicer", fontWeight = FontWeight.Bold)
                        Text(
                            "☕ Support development",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(BMAC_URL)
                                    )
                                )
                            }
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
                is SlicerViewModel.SlicerState.Cancelling -> {
                    SlicingProgressCard(
                        progress = -1,
                        stage = "Cancelling\u2026",
                        onCancel = null
                    )
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
                        // B142b: mix-assigned slices emit physical-slot tools — no
                        // canonical→slot mapping applies and the palette is slot-space.
                        val gcodeColorMapping = if (isH2c || sliceMixToolSpace) null else colorMapping
                        InlineGcodePreview(
                            parsedGcode = parsedGcode!!,
                            extruderColors = extruderColors,
                            colorMapping = gcodeColorMapping,
                            slicerLayerCount = s.result.totalLayers,
                            onExpand = onNavigateGcodeViewer3D,
                            cameraState = sharedPreviewCameraState,
                            onCameraStateChange = onSharedPreviewCameraStateChange,
                            onResetView = onResetPreviewCamera,
                            // Bug 1 class sibling fix (post-v2.0.0-validation):
                            // gcode body emits T<canonical-fileIndex>; palette
                            // must be canonical-aligned so palette[T] resolves
                            // to the right file colour. Pre-fix used
                            // `resolvedFilamentColors` which is plate-narrowed
                            // and misindexed for multi-plate fileIdx > 0.
                            resolvedFilamentColors = if (sliceMixToolSpace) slotPaletteWithMixBlends
                            else canonicalFilamentColors
                                .takeIf { it.isNotEmpty() }
                                ?: resolvedFilamentColors,
                            // B129: same remembered layer range as the full-screen
                            // viewer, so the inline slider survives move/rotate too.
                            initialLayerRange = previewLayerRange,
                            onLayerRangeChange = { lo, hi -> viewModel.setPreviewLayerRange(lo, hi) },
                        )
                        }
                    }
                    val filamentOverrides by viewModel.filamentOverrides.collectAsState()
                    // Phase 2 (2026-04-28) — hoist canonical lookup off main
                    // thread. The synchronous variant froze on Buzz-class
                    // files. produceState shows a loading state (null) until
                    // the IO walk completes; SliceCompleteSummaryCard tolerates
                    // a null canonical (renders without override badges).
                    val canonicalForSummary by produceState<com.u1.slicer.data.CanonicalFilamentList?>(
                        initialValue = null, key1 = viewModel.currentModelPath
                    ) {
                        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            viewModel.getCanonicalFilamentList()
                        }
                    }
                    val threeMfInfoForSummary by viewModel.threeMfInfo.collectAsState()
                    val plateFileIndicesForSummary = remember(canonicalForSummary, threeMfInfoForSummary, viewModel.recoveryPlateId, parsedGcode) {
                        canonicalForSummary?.let {
                            computePlateFileIndices(
                                threeMfInfoForSummary,
                                viewModel.recoveryPlateId,
                                it.size,
                                perExtruderFilamentMm = parsedGcode?.perExtruderFilamentMm,
                            )
                        }
                    }
                    SliceCompleteSummaryCard(
                        result = s.result,
                        perExtruderFilamentMm = parsedGcode?.perExtruderFilamentMm ?: emptyList(),
                        wipeTowerFilamentMm = parsedGcode?.wipeTowerFilamentMm ?: 0f,
                        bedTemp = config.bedTemp,
                        extruderColors = extruderColors,
                        colorMapping = colorMapping,
                        extruderPresets = extruderPresets,
                        canonicalList = canonicalForSummary,
                        filamentOverrides = filamentOverrides,
                        plateFileIndices = plateFileIndicesForSummary,
                        mixToolSpacePalette = if (sliceMixToolSpace) slotPaletteWithMixBlends else emptyList(),
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
                if (sliceStale) {
                    StaleSliceBanner(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        onSliceAgain = {
                            viewModel.startSlicing()
                            onNavigatePreview()
                        }
                    )
                }
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
    sourceConfig: Map<String, Any>? = null,
    // Phase 2 §4 Step 7 — file-filament count (canonical list size).
    // Drives the support-filament dropdown's option range so the user
    // sees real filament numbers instead of slot indices. Null when no
    // model is loaded (settings screen).
    filamentCount: Int? = null,
    extruderPresets: List<com.u1.slicer.data.ExtruderPreset> = emptyList(),
    colorMapping: List<Int>? = null,
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
                    sourceConfig = sourceConfig,
                    filamentCount = filamentCount,
                    extruderPresets = extruderPresets,
                    colorMapping = colorMapping,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            InfoRow("Nozzle Diameter", "${config.nozzleDiameter} mm")
            // B117: removed the "Filament: PLA" InfoRow. It was sourced from
            // `config.filamentType` which is set only at file-load time and never
            // updates after a Prepare-screen material override — so it shows stale
            // info. The Filaments panel above is now the single source of truth.
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
                // Indeterminate ring — always spinning so user sees motion.
                // When cancelling (progress < 0), this is the only indicator.
                CircularProgressIndicator(
                    modifier = Modifier.size(80.dp),
                    color = if (progress < 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    strokeWidth = 6.dp
                )
                if (progress >= 0) {
                    CircularProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.size(80.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 6.dp
                    )
                }
            }
            if (progress >= 0) {
                Text("$progress%", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
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
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Print, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Map & Print",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                OutlinedButton(
                    onClick = onUploadOnly,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Upload Only",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
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
    extruderPresets: List<com.u1.slicer.data.ExtruderPreset> = emptyList(),
    canonicalList: com.u1.slicer.data.CanonicalFilamentList? = null,
    filamentOverrides: Map<Int, SlicerViewModel.FilamentOverride> = emptyMap(),
    /**
     * Phase 2 (2026-04-28, post-v2.0.0-validation Bug #3) — sorted-
     * ascending list of canonical fileIndices the active plate's
     * filaments occupy in the file-wide canonical list. When non-null
     * AND its size matches `perExtruderFilamentMm`, the row at
     * position `n` looks up `canonicalList[plateFileIndices[n]]`
     * instead of `canonicalList[n]`. Pre-fix the summary always
     * indexed positionally, which for a multi-plate file like Dragon
     * Scale plate 1 (using fileIdx 5+9 of 13) showed the colour for
     * canonical[0] + canonical[1] — wrong file filaments.
     */
    plateFileIndices: List<Int>? = null,
    /**
     * B142b (2026-06-10) — non-empty when the slice used physical-slot tool space
     * (a mix was assigned): entry `i` is tool `i`'s display colour (slot presets
     * E1..E4 + mix blends). Canonical/file lookups don't apply in that space.
     */
    mixToolSpacePalette: List<String> = emptyList(),
) {
    val displaySlots = remember(perExtruderFilamentMm, colorMapping) {
        buildPerExtruderDisplaySlots(perExtruderFilamentMm.size, colorMapping)
    }
    // v2.0.0 systematic fix — build the visible chip list from the canonical-
    // wide gcode-derived plateFileIndices when available; otherwise fall back
    // to per-extruder mm directly.
    //
    // visibleEntries contains (fileIdx, plateNarrowedPosition, mm) for each
    // chip to render. fileIdx drives label + canonical colour lookup;
    // plateNarrowedPosition picks into colorMapping when colorMapping is
    // plate-narrowed; mm is the displayed value.
    data class ChipEntry(val fileIdx: Int, val plateNarrowedPosition: Int, val mm: Float)
    val visibleEntries = remember(perExtruderFilamentMm, plateFileIndices) {
        when {
            plateFileIndices != null && plateFileIndices.isNotEmpty() -> {
                // plateFileIndices is already filtered to non-zero footer
                // entries by `computePlateFileIndices` (mm > 0 guard).
                plateFileIndices.mapIndexed { posInPlate, fileIdx ->
                    ChipEntry(fileIdx, posInPlate, perExtruderFilamentMm.getOrNull(fileIdx) ?: 0f)
                }
            }
            else -> {
                // H2 from post-Buzz code review (2026-04-30): when
                // plateFileIndices is null (non-Bambu / canonical load
                // failed), the GcodeParser preference for the canonical-wide
                // footer can include trailing zeros for declared-but-unused
                // filaments. Filter `mm > 0` here so the chip strip never
                // renders a "Filament N · 0 mm (0.0 g)" ghost entry.
                perExtruderFilamentMm.mapIndexedNotNull { i, mm ->
                    if (mm > 0f) ChipEntry(i, i, mm) else null
                }
            }
        }
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
                visibleEntries.chunked(2).forEachIndexed { rowIndex, rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { entry ->
                            val fileIdx = entry.fileIdx
                            val mm = entry.mm
                            val override = filamentOverrides[fileIdx]
                            // colorMapping shape varies: canonical-wide for
                            // single-plate (size = canonical), plate-narrowed
                            // for multi-plate (size = plateFileIndices.size).
                            // Lookup by fileIdx for canonical-wide, else by
                            // plateNarrowedPosition.
                            val slot = colorMapping
                                ?.let { cm ->
                                    if (cm.size > entry.plateNarrowedPosition && plateFileIndices != null && cm.size == plateFileIndices.size)
                                        cm[entry.plateNarrowedPosition]
                                    else cm.getOrNull(fileIdx)
                                }
                                ?: displaySlots.getOrElse(entry.plateNarrowedPosition) { fileIdx }
                            // B142b: mix-assigned slices use physical-slot tool space —
                            // tool i IS slot Ei; canonical/file lookups don't apply.
                            val canonicalEntry = if (mixToolSpacePalette.isEmpty())
                                canonicalList?.filaments?.getOrNull(fileIdx) else null
                            // Colour priority: slot-space palette (mix slices) → user
                            // override → canonical (file's declared, indexed by true
                            // canonical fileIdx) → mapped slot's preset colour.
                            val colorHex = mixToolSpacePalette.getOrNull(fileIdx)
                                ?: override?.color
                                ?: canonicalEntry?.color
                                ?: extruderColors.getOrNull(slot)?.takeIf { it.isNotBlank() }
                                ?: "#808080"
                            val color = try {
                                Color(android.graphics.Color.parseColor(colorHex))
                            } catch (_: Exception) {
                                Color.Gray
                            }
                            // Material priority: slot preset (mix slices, tool i = slot i)
                            // -> override -> mapped slot preset -> canonical.
                            val mappedMaterial = resolveExtruderMaterialType(
                                if (mixToolSpacePalette.isNotEmpty()) fileIdx else slot,
                                extruderPresets
                            ).takeIf { it.isNotBlank() }
                            val material = if (mixToolSpacePalette.isNotEmpty()) (mappedMaterial ?: "")
                            else override?.materialType
                                ?: mappedMaterial
                                ?: canonicalEntry?.materialType
                                ?: ""
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
                                    // Slot-space rows are physical extruders, not file filaments.
                                    val rowLabel = if (mixToolSpacePalette.isNotEmpty()) "E${fileIdx + 1}"
                                    else "Filament ${fileIdx + 1}"
                                    Text(
                                        if (material.isNotEmpty()) "$rowLabel · $material" else rowLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        "%.0f mm (%.1f g)".format(mm, mm * 0.0024053f * 1.24f),
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
                    "%.0f mm (%.1f g)".format(wipeTowerFilamentMm, wipeTowerFilamentMm * 0.0024053f * 1.24f),
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
    // Phase 2 §4 Step 6 — when colorMapping is missing for a multi-filament
    // file, return identity. Filament indices >= 4 fall through to the grey
    // fallback in the colour resolver downstream rather than being capped to
    // slot 3 (which would misrepresent them as sharing slot 3's loaded
    // colour).
    if (colorMapping.isNullOrEmpty()) return (0 until count).toList()

    val ordered = mutableListOf<Int>()
    colorMapping.forEach { slot ->
        if (slot in 0..3 && slot !in ordered) ordered += slot
    }
    for (slot in 0..3) {
        if (slot !in ordered) ordered += slot
    }
    while (ordered.size < count) {
        ordered += ordered.lastOrNull() ?: 0  // slot-space; pad with last
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
                    // Phase 2 §4 Step 6 — show every file filament's colour,
                    // not just the first 4. Multi-filament files (H2C, MMU)
                    // can have N > 4 colours all worth surfacing.
                    colors.forEach { hex ->
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
    // Painted/SEMM models (hasPaintData=true) are gated on the larger triangle budget
    // (MAX_KOTLIN_PREVIEW_TRIANGLES) before falling back to LargePreviewFallback — avoids
    // OOM on giant multi-colour models (F1 calendar).
    hasPaintData: Boolean = false,
    // Placement mode
    objectPositions: FloatArray? = null,
    modelSizeX: Float = 0f,
    modelSizeY: Float = 0f,
    modelSizeZ: Float = 0f,
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
    layerToolSegments: List<com.u1.slicer.bambu.LayerToolSegment>? = null,
    // Phase 2 §4 Step 7 (visual side) — per-file-filament resolved colours
    // (file colour overlaid by Prepare-screen overrides). When non-empty,
    // drives the 3D preview's recolor palette directly so the visual
    // agrees with the slicer's embedded filament_colour. Empty for non-
    // canonical / pre-load states; the slot-mapped fallback path runs.
    resolvedFilamentColors: List<String> = emptyList(),
    // Phase 2 (Approach C) — palette aligned to the mesh's extruder-index
    // space (sorted plate-used file-filament order for the per-vertex
    // recolor path; full canonical palette for the layer-tool Z-band
    // recolor path). When non-empty, takes priority over
    // `resolvedFilamentColors` for the recolor; chip strip / dialog still
    // read `resolvedFilamentColors` (file-fileIndex aligned). Empty for
    // STL / non-canonical paths; the slot-mapped fallback runs.
    meshAlignedFilamentColors: List<String> = emptyList(),
    // B78: snapshot of native instance offsets captured at loadModel time.
    // Used to restore the file's original plate position after prepareSlicer() has
    // clobbered native state; skipped when nativeSliceStateDirty is false so the
    // natural load state is preserved on first preview.
    loadTimeInstanceOffsets: FloatArray = floatArrayOf(135f, 135f),
    nativeSliceStateDirty: Boolean = false,
    // Per-object bounding boxes [sizeX0,sizeY0,sizeZ0, ...] for correct per-object hit-testing
    // when multiple distinct files have been added to the bed. Empty = use merged mesh AABB.
    perObjectSizes: FloatArray = floatArrayOf(),
    // Incremented when a model is added to the bed or multi-object positions change.
    // Used as a LaunchedEffect key so the prepare preview mesh re-fetches after an add or drag.
    modelAddVersion: Int = 0,
    // Current per-object positions [x0,y0,x1,y1,...] when multiple files are on the bed.
    // Re-applied inside the rotation LaunchedEffect so setObjectPositions survives the
    // nativeSliceStateDirty→setModelInstances reset that happens after a prior slice.
    multiObjectPositions: FloatArray? = null,
    // fix35: Smart Paint entry as a top-right overlay icon on the viewer. Null disables the
    // icon (no model loaded, large-preview fallback, or feature unavailable).
    onSmartPaint: (() -> Unit)? = null,
    isReeditSmartPaint: Boolean = false,
    // ---- F66 ----
    // Index of the tap-selected object (null = no selection). When non-null the renderer
    // applies its drag-highlight tint to that object.
    selectedObjectIndex: Int? = null,
    // Tap on an object surface: emits the owning object index (resolved from objectMeshRanges).
    // No-op if the renderer hasn't populated ranges yet (single-object STL).
    onObjectTapped: ((Int) -> Unit)? = null,
    // Tap on empty bed background: caller typically clears selection.
    onEmptyTap: (() -> Unit)? = null,
    // F66 — bed-wide actions surfaced in the top-right overlay's 3-dot menu so
    // they're discoverable without taking vertical space below the preview.
    // Reset entries should be null when there's nothing to reset; the menu
    // hides them entirely in that case.
    onAutoOrientAll: (() -> Unit)? = null,
    onAutoArrangeAll: (() -> Unit)? = null,
    onResetAllRotations: (() -> Unit)? = null,
    onResetAllScales: (() -> Unit)? = null,
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
    // Per-object vertex ranges for the current mesh (non-null when in multi-object mode).
    // Set alongside mesh in the rotation LaunchedEffect; passed to v.setMesh() below.
    var objectMeshRanges by remember { mutableStateOf<List<com.u1.slicer.viewer.ModelRenderer.ObjectMeshRange>?>(null) }
    val placementEnabled = placementConfig.objectPlacementEnabled

    // Mutable copies of positions for drag interaction
    val objPositions = remember(objectPositions) {
        objectPositions?.copyOf() ?: floatArrayOf()
    }
    var towerX by remember(wipeTowerX) { mutableFloatStateOf(wipeTowerX) }
    var towerY by remember(wipeTowerY) { mutableFloatStateOf(wipeTowerY) }

    // B109: compute the effective placement footprint after rotation+scale via the
    // pure helper [CopyArrangeCalculator.effectivePlacementFootprint]. The helper
    // prefers the live native preview-mesh AABB when present (matches what the
    // renderer draws at any rotation) and falls back to the box-rotation
    // approximation otherwise. See the helper's KDoc for the full rationale. Note
    // that both STL and 3MF go through the rotation effect at line ~2977, so once
    // a mesh has been fetched the AABB reflects rotation for both file types —
    // no file-type gate needed.
    val (effPlaceSizeX, effPlaceSizeY) = remember(
        mesh,
        modelSizeX, modelSizeY, modelSizeZ,
        modelScale, modelRotation,
    ) {
        val m = mesh
        com.u1.slicer.model.CopyArrangeCalculator.effectivePlacementFootprint(
            rotatedMeshSizeXY = m?.let { Pair(it.maxX - it.minX, it.maxY - it.minY) },
            loadTimeSizeX = modelSizeX,
            loadTimeSizeY = modelSizeY,
            loadTimeSizeZ = modelSizeZ,
            scaleX = modelScale.x,
            scaleY = modelScale.y,
            rotationXDeg = modelRotation.x,
            rotationYDeg = modelRotation.y,
            rotationZDeg = modelRotation.z,
        )
    }

    LaunchedEffect(modelFilePath, extruderMap, colorMapping?.size) {
        val requestId = parseRequestId + 1
        parseRequestId = requestId
        // B49: don't clear mesh/GL when a ViewModel cache exists and the native
        // rotation path handles the preview (3MF files).  The parse effect returns
        // null for 3MF — clearing the mesh here just kills the cached preview and
        // forces a slow re-fetch from native.
        // F73 fix: also don't clear mesh when we already have a displayed mesh (mesh != null)
        // and the native path is in use.  colorMapping?.size is a LaunchedEffect key so this
        // effect re-fires on every color-mapping change (e.g. after a plate switch completes
        // and loadNativeModel emits colorMapping size change).  Without this guard, the effect
        // clears mesh/viewerLoading that were already correctly set by the rotation effect,
        // leaving the "Preparing preview…" spinner forever because the rotation effect won't
        // re-fire (modelFilePath and modelRotation are both unchanged).
        val isNativePreviewPath = modelFilePath.endsWith(".3mf", ignoreCase = true)
        if ((cachedMesh == null && mesh == null) || !isNativePreviewPath) {
            viewerLoading = true
            mesh = null
            objectMeshRanges = null
            lastSetMesh = null
            viewerView?.clearMesh()
        }
        val parsedMesh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = java.io.File(modelFilePath)
                when {
                    modelFilePath.endsWith(".stl", ignoreCase = true) && perObjectSizes.size / 3 <= 1 ->
                        // In multi-object mode the rotation LaunchedEffect fetches the combined
                        // world-space mesh via getPreparePreviewMesh() — parsing only the first
                        // file here would show a stale single-object mesh before the full fetch
                        // completes, causing a visible "bottom-left" flash.
                        com.u1.slicer.viewer.StlParser.parse(file)
                    modelFilePath.endsWith(".3mf", ignoreCase = true) ->
                        // B46: all 3MF models use the native getPreparePreviewMesh() path
                        // via the rotation LaunchedEffect below.
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
    LaunchedEffect(mesh, viewerView, extruderColors, colorMapping, cameraState, layerToolOnly, layerToolSegments, resolvedFilamentColors, meshAlignedFilamentColors) {
        val m = mesh; val v = viewerView
        if (m != null && v != null) {
            // Only call setMesh when the mesh instance actually changed
            if (m !== lastSetMesh) {
                v.setMesh(m, objectMeshRanges)
                // F66: feed picking positions to the viewer's tap dispatcher in
                // the SAME coordinate space the renderer draws in (drawModelAt /
                // drawObjectRange translate the mesh into bed-space by way of
                // `instancePositions` and global `modelScale`). Passing raw
                // mesh vertices misses every triangle on a single-STL load
                // because the rendered geometry sits ~135mm out on the bed.
                v.setTrianglePickingPositions(
                    m.toWorldSpacePickingPositions(
                        objectMeshRanges = objectMeshRanges,
                        instancePositions = v.renderer.instancePositions,
                        modelScale = v.renderer.modelScale,
                    )
                )
                cameraState?.let { v.applyCameraState(it) }
                lastSetMesh = m
            }
            if (extruderColors.isNotEmpty()) {
                v.setExtruderColors(extruderColors)
            }
            // Phase 2 (Approach C) — prefer the mesh-aligned palette when the
            // ViewModel produces one. The mesh's extruder-index space depends
            // on the file shape (per-object vs paint-compacted vs layer-tool);
            // see [SlicerViewModel.meshAlignedFilamentColors] for the contract.
            // Fall back to `resolvedFilamentColors` (file-fileIndex aligned)
            // for the rare pre-canonical render — keeps tests asserting the
            // legacy alignment from breaking and is harmless when the two
            // happen to match.
            val canonicalSource = meshAlignedFilamentColors.takeIf { it.isNotEmpty() }
                ?: resolvedFilamentColors
            val canonicalPalette: List<FloatArray>? = canonicalSource
                .takeIf { it.isNotEmpty() }
                ?.map { hex -> SlicerViewModel.staticHexColorToFloatArray(hex) }

            // F46: Z-band recolour for layer-tool (Hueforge) models
            if (layerToolOnly && layerToolSegments != null && extruderColors.isNotEmpty() && colorMapping != null) {
                val palette = canonicalPalette
                    ?: colorMapping.map { slot -> SlicerViewModel.staticHexColorToFloatArray(extruderColors.getOrElse(slot) { "" }) }
                Log.i("InlineModelPreview", "recolorByZBands segments=${layerToolSegments.size} paletteSize=${palette.size} canonical=${canonicalPalette != null} meshAligned=${meshAlignedFilamentColors.size}")
                m.recolorByZBands(layerToolSegments, palette)
                v.refreshColors()  // upload recolorByZBands result to GPU without overwriting with recolor()
            } else if (m.hasPerVertexColor && (canonicalPalette != null || extruderColors.isNotEmpty())) {
                // Apply recolor when we have either a canonical-derived
                // palette OR a slot palette + (paint-data) mesh.
                val palette = canonicalPalette ?: if (colorMapping != null) {
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
                        "canonical=${canonicalPalette != null} " +
                        "meshAligned=${meshAlignedFilamentColors.size} " +
                        "hasMeshColors=${m.hasPerVertexColor}"
                )
                v.recolorMesh(palette)
            }
        } else if (v != null && extruderColors.isNotEmpty()) {
            // Mesh not ready yet but colors changed — just update instance colors
            v.setExtruderColors(extruderColors)
        }
    }

    // F66 — push the tap-selected object index into the renderer's highlight slot
    // AND the viewer's persistentSelectionIndex so the drag-cancel/drag-end paths
    // restore back to it instead of -1 after a drag finishes.
    LaunchedEffect(viewerView, selectedObjectIndex) {
        val v = viewerView ?: return@LaunchedEffect
        val idx = selectedObjectIndex ?: -1
        v.persistentSelectionIndex = idx
        v.renderer.highlightIndex = idx
        v.requestRender()
    }

    // Update renderer with model scale
    LaunchedEffect(viewerView, modelScale) {
        val v = viewerView ?: return@LaunchedEffect
        v.renderer.modelScale = floatArrayOf(modelScale.x, modelScale.y, modelScale.z)
        v.requestRender()
    }

    // Re-fetch preview mesh when rotation changes (all 3MF models, painted included).
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
    val previewPrepContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(modelRotation, modelFilePath, modelAddVersion) {
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

        // F90 follow-up (v2.7.1): push the foreground-service stage AFTER the 300ms
        // debounce. Putting it before the delay causes ForegroundServiceDidNotStartInTimeException
        // under rotation-slider drag — each cancelled-mid-debounce LaunchedEffect still
        // fires startForegroundService, and Android's per-call 5-second watchdog can't
        // be satisfied during the rapid cancel/restart churn. Pushing after the debounce
        // means a drag that gets cancelled mid-debounce never starts the service at all.
        // The paired stop() in `finally` covers both cancellation and exception paths.
        LongOpService.start(previewPrepContext, "Preparing preview")
        try {
        val newMesh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            NativeLibrary.previewMutex.withLock {
                try {
                    val lib = NativeLibrary()
                    lib.setModelRotation(rot.x, rot.y, rot.z)
                    // B72 + B73 + B78: only reset native scale and instances when a prior
                    // prepareSlicer() call has clobbered them.  On a fresh load the file's
                    // natural build-transform scale (e.g. the 0.6 applied to Shashibo plate 5)
                    // and original plate position must be preserved verbatim.
                    //
                    // B72: prepareSlicer() calls setModelInstances() with N grid positions for
                    // multi-copy slices; without the instance reset getPreparePreviewMesh()
                    // returns all N copies baked into world-space → shattered Prepare preview.
                    //
                    // B73: prepareSlicer() calls setModelScale(s) which permanently scales the
                    // native model geometry; getPreparePreviewMesh() then returns an already-
                    // scaled mesh; the GL renderer also applies modelScale = s via
                    // renderer.modelScale → double-scaled (s²) visual size on the Prepare screen.
                    // Resetting to scale=1.0 here ensures the native mesh is at full resolution;
                    // the GL renderer applies the user-chosen scale via renderer.modelScale.
                    //
                    // B78: setModelScale(1,1,1) is destructive — it overwrites the instance's
                    // scaling factor regardless of previous state. Calling it on a freshly
                    // loaded file wipes the file's baked build-transform scale, so the mesh
                    // emerges at raw size instead of its designed-for scale. Gate behind the
                    // dirty flag so fresh loads never trigger the destructive reset.
                    // Similarly, setModelInstances is gated — on a fresh load the natural
                    // build-transform offset is already correct, and calling setModelInstances
                    // re-derives the offset from meshBB.min, shifting the mesh off-centre.
                    if (nativeSliceStateDirty) {
                        lib.setModelScale(1f, 1f, 1f)
                        val multiPos = multiObjectPositions
                        if (perObjectSizes.size > 3 && multiPos != null) {
                            // Multi-object bed: set per-object positions directly.
                            // Do NOT call setModelInstances — it creates N copies of object 0,
                            // producing phantom duplicates when there are N distinct loaded objects.
                            lib.setObjectPositions(multiPos)
                        } else {
                            lib.setModelInstances(loadTimeInstanceOffsets)
                        }
                    }
                    val previewT0 = System.currentTimeMillis()
                    val raw = lib.getPreparePreviewMesh(NativePreviewMesh.MAX_DECIMATED_TRIANGLES)
                    // F95: tag the trailing negative/modifier-volume block so toMeshData carries
                    // the boundary into MeshData and the renderer draws it translucent. Read in
                    // the same critical section as the mesh build (static is valid post-build).
                    raw?.modifierBlockStartTriangle = lib.nativeGetPreviewModifierBlockStart()
                    val jniMs = System.currentTimeMillis() - previewT0
                    val meshT0 = System.currentTimeMillis()
                    val mesh = raw?.toMeshData()
                    val meshMs = System.currentTimeMillis() - meshT0
                    val previewMs = System.currentTimeMillis() - previewT0
                    android.util.Log.i("LoadTiming", "preview total=${previewMs}ms jni=${jniMs}ms toMeshData=${meshMs}ms vertexCount=${mesh?.vertexCount ?: 0} initial=$isInitialFetch")
                    mesh
                } catch (_: Throwable) {
                    null
                }
            }
        }
        if (newMesh != null) {
            // In multi-object mode, split the combined world-space mesh into per-object
            // vertex ranges so each object can be drawn independently (enables smooth drag).
            val multiPos = multiObjectPositions
            // B132c: tighten gate to require exact equality. The previous `>=`
            // tolerated a multiPos LARGER than perObjectSizes (e.g. an applied
            // placement for 5 objects while the model + bboxes track 3),
            // which made splitMeshByObjects index past sizes.length and crash
            // with ArrayIndexOutOfBoundsException.
            val splitResult = if (perObjectSizes.size / 3 > 1 && multiPos != null
                && multiPos.size == (perObjectSizes.size / 3) * 2
            ) {
                com.u1.slicer.viewer.ModelRenderer.splitMeshByObjects(newMesh, multiPos, perObjectSizes)
            } else null
            mesh = splitResult?.first ?: newMesh
            objectMeshRanges = splitResult?.second
            onMeshCached?.invoke(mesh!!)  // B49: save to ViewModel cache
            lastSetMesh = null  // force setMesh() on the GL thread
        }
        if (!isInitialFetch) viewerLoading = false
        } finally {
            LongOpService.stop(previewPrepContext)
        }
    }

    // Update renderer with placement data
    //
    // B109 follow-up (2026-05-17, GitHub #135 reopen): the v.onObjectMoved
    // lambda captures `effPlaceSizeX` / `effPlaceSizeY` as primitive Float
    // values at the moment this block runs. Those derive from `modelRotation`
    // via `remember(...modelRotation)` above, but if rotation isn't a key
    // here, the lambda keeps closing over the PRE-rotation values until some
    // other key (objPositions, towerX, …) changes. User rotates → footprint
    // recomputes → bed bounds NOT updated in the drag callback → drag still
    // clamped to load-time AABB. Add effPlaceSizeX/Y to the key list so the
    // callback re-captures on rotation.
    LaunchedEffect(
        viewerView, placementEnabled, objPositions,
        towerX, towerY, placementConfig.wipeTowerVisible,
        effPlaceSizeX, effPlaceSizeY,
        // B109 review: the wipe-tower drag clamp inside this lambda closes over
        // wipeTowerWidth/wipeTowerDepth (lines below). Without them as keys the
        // callback keeps a stale value if the user changes Prime Tower Width or
        // Depth mid-session — same class of bug as the earlier B109 reopen.
        wipeTowerWidth, wipeTowerDepth,
        // Multi-object: per-object footprints change when files are added/removed.
        perObjectSizes,
    ) {
        val v = viewerView ?: return@LaunchedEffect
        if (placementEnabled) {
            v.placementMode = true
            // Only reset the camera when placement positions are first assigned (not on every
            // drag update — setting pendingCameraReset on every recomposition would reset
            // azimuth/elevation/pan mid-drag, corrupting subsequent screenToBed calculations).
            val firstPlacement = v.renderer.instancePositions == null
            v.renderer.instancePositions = objPositions
            v.renderer.perObjectSizes = perObjectSizes.takeIf { it.isNotEmpty() }
            v.renderer.multiObjectMode = perObjectSizes.size / 3 > 1
            if (firstPlacement) v.renderer.pendingCameraReset = true
            if (placementConfig.wipeTowerVisible) {
                v.renderer.wipeTower = com.u1.slicer.viewer.ModelRenderer.WipeTowerInfo(
                    towerX, towerY, wipeTowerWidth, wipeTowerDepth
                )
            } else {
                v.renderer.wipeTower = null
            }
            val isMultiObjectBed = perObjectSizes.size / 3 > 1
            v.onObjectMoved = { index, dx, dy ->
                val count = objPositions.size / 2
                if (index < count) {
                    // Move object — use per-object footprint when available (multi-file bed),
                    // otherwise fall back to the single-model rotated+scaled footprint (B109).
                    val i = index
                    val hasPerObj = perObjectSizes.size / 3 == count
                    // perObjectSizes come from native getObjectBoundingBoxes() which uses
                    // get_matrix_no_offset() — includes instance scale — so they already
                    // reflect the user's chosen scale. Use them directly; do NOT multiply
                    // by modelScale again (double-apply).
                    val sizeX = if (hasPerObj) perObjectSizes[i * 3] else effPlaceSizeX
                    val sizeY = if (hasPerObj) perObjectSizes[i * 3 + 1] else effPlaceSizeY
                    objPositions[i * 2] = (objPositions[i * 2] + dx).coerceIn(0f, maxOf(0f, 270f - sizeX))
                    objPositions[i * 2 + 1] = (objPositions[i * 2 + 1] + dy).coerceIn(0f, maxOf(0f, 270f - sizeY))
                    v.renderer.instancePositions = objPositions.copyOf()
                    // Multi-object: the combined world-space mesh can't respond to per-object
                    // position updates during drag (mesh re-fetch is async). Batch position
                    // updates to drag-end via onDragEnded so the mesh only re-fetches once.
                    if (!isMultiObjectBed) {
                        onPositionsChanged?.invoke(objPositions.copyOf(), Pair(towerX, towerY))
                    }
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
            // Multi-object: fire one applyPlacementPositions call when drag ends so native
            // setObjectPositions + mesh re-fetch happen once instead of on every MOVE event.
            v.onDragEnded = if (isMultiObjectBed) {
                { onPositionsChanged?.invoke(objPositions.copyOf(), Pair(towerX, towerY)) }
            } else null
            v.requestRender()
        } else {
            v.placementMode = false
            v.renderer.instancePositions = null
            v.renderer.multiObjectMode = false
            v.onDragEnded = null
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
                            // F66: wire tap-to-select on the Prepare viewer. The existing
                            // dispatcher in ModelViewerView already filters pan/tilt/zoom
                            // before firing these callbacks, so no new gesture code needed.
                            //
                            // When the renderer has populated per-object ranges (F77 multi-file
                            // load), use them to identify which object owns the tapped triangle.
                            // Otherwise — the common single-file case where one ModelObject holds
                            // every volume — fall back to selecting object 0 so the user can
                            // reach Auto-orient / Split / Reset on the lone object.
                            view.onTriangleTapped = { triIdx ->
                                val cb = onObjectTapped
                                if (cb != null) {
                                    val ranges = view.renderer.objectMeshRanges
                                    if (ranges.isNullOrEmpty()) {
                                        cb(0)
                                    } else {
                                        val owner = ranges.indexOfFirst { r ->
                                            val triStart = r.vertexStart / 3
                                            val triEnd = (r.vertexStart + r.vertexCount) / 3
                                            triIdx in triStart until triEnd
                                        }
                                        cb(if (owner >= 0) owner else 0)
                                    }
                                }
                            }
                            view.onEmptyTap = { onEmptyTap?.invoke() }
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
                        // F66 — re-bind the tap callbacks on every recomposition.
                        // Without this, the view keeps the first-composition
                        // closures forever; any later state change in those
                        // lambdas (different ViewModel reference, different
                        // callback identity) would be silently lost.
                        view.onTriangleTapped = { triIdx ->
                            val cb = onObjectTapped
                            if (cb != null) {
                                val ranges = view.renderer.objectMeshRanges
                                if (ranges.isNullOrEmpty()) {
                                    cb(0)
                                } else {
                                    val owner = ranges.indexOfFirst { r ->
                                        val triStart = r.vertexStart / 3
                                        val triEnd = (r.vertexStart + r.vertexCount) / 3
                                        triIdx in triStart until triEnd
                                    }
                                    cb(if (owner >= 0) owner else 0)
                                }
                            }
                        }
                        view.onEmptyTap = { onEmptyTap?.invoke() }
                        // F66 — re-supply picking positions on every recomp,
                        // in WORLD SPACE so picking aligns with what's drawn.
                        // Re-runs even when the mesh ref didn't change, so a
                        // position-only update (drag commit, splitObject layout,
                        // setObjectPositions from another path) still refreshes
                        // the picker. See MeshData.toWorldSpacePickingPositions.
                        mesh?.let { m ->
                            view.setTrianglePickingPositions(
                                m.toWorldSpacePickingPositions(
                                    objectMeshRanges = view.renderer.objectMeshRanges,
                                    instancePositions = view.renderer.instancePositions,
                                    modelScale = view.renderer.modelScale,
                                )
                            )
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
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onSmartPaint != null) {
                    // fix35: Smart Paint entry. Top-right icon overlaid on the 3D viewer in
                    // place of the previous inline buttons. The 🪄 emoji icon doubles as a
                    // visual cue that this is the "Smart Paint" affordance. Re-edit mode keeps
                    // the same icon to avoid teaching the user two glyphs.
                    androidx.compose.material3.FilledIconButton(
                        onClick = onSmartPaint,
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.padding(end = 2.dp),
                    ) {
                        Text(
                            "🪄",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                // F66 — consolidated overflow menu: Model info + bed-wide
                // actions (Auto-orient all, Reset all rotations/scales). Reset
                // entries appear only when there's something to reset. The
                // standalone Info icon was removed in favour of this menu so
                // the overlay stays compact while keeping all actions one tap
                // away.
                if (onInfoClick != null || onAutoOrientAll != null || onAutoArrangeAll != null
                    || onResetAllRotations != null || onResetAllScales != null) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "Actions",
                                tint = Color.White.copy(alpha = 0.8f))
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            if (onInfoClick != null) {
                                DropdownMenuItem(
                                    text = { Text("Model info") },
                                    leadingIcon = { Icon(Icons.Default.Info, null) },
                                    onClick = { onInfoClick(); menuOpen = false },
                                )
                            }
                            if (onAutoOrientAll != null) {
                                DropdownMenuItem(
                                    text = { Text("Auto-orient all") },
                                    onClick = { onAutoOrientAll(); menuOpen = false },
                                )
                            }
                            if (onAutoArrangeAll != null) {
                                DropdownMenuItem(
                                    text = { Text("Auto-arrange all") },
                                    onClick = { onAutoArrangeAll(); menuOpen = false },
                                )
                            }
                            if (onResetAllRotations != null) {
                                DropdownMenuItem(
                                    text = { Text("Reset all rotations") },
                                    onClick = { onResetAllRotations(); menuOpen = false },
                                )
                            }
                            if (onResetAllScales != null) {
                                DropdownMenuItem(
                                    text = { Text("Reset all scales") },
                                    onClick = { onResetAllScales(); menuOpen = false },
                                )
                            }
                        }
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
                    modelSizeX = effPlaceSizeX,
                    modelSizeY = effPlaceSizeY,
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
    is SlicerViewModel.SlicerState.Cancelling -> cachedModelInfo
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
    exportArtifacts: ExportableModelArtifacts?,
    config: com.u1.slicer.data.SliceConfig,
    onToggleWipeTower: () -> Unit,
    onReassign: () -> Unit,
    onExportSanitized: () -> Unit,
    onExportEmbedded: () -> Unit,
    onCopyDebugSummary: () -> Unit,
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
            val infoScrollState = rememberScrollState()
            Column(
                modifier = Modifier.verticalScroll(infoScrollState),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InfoRow("Format", info.format.uppercase())
                InfoRow("Dimensions", info.dimensionString)
                InfoRow("Triangles", "%,d".format(info.triangleCount))
                InfoRow("Volumes", info.volumeCount.toString())
                InfoRow("Manifold", if (info.isManifold) "Yes" else "No")

                if (threeMfInfo != null && threeMfInfo.isBambu) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp),
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

                if (exportArtifacts != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Text(
                        "Export Pipeline Artifacts",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium
                    )
                    InfoRow("Selected Plate", exportArtifacts.selectedPlateId?.toString() ?: "None")
                    InfoRow(
                        "Sanitized for Orca",
                        exportArtifacts.fileFor(ExportArtifactKind.Sanitized3mf)?.name ?: "Unavailable"
                    )
                    InfoRow(
                        "Sanitized + U1 profile",
                        exportArtifacts.fileFor(ExportArtifactKind.SanitizedEmbedded3mf)?.name ?: "Unavailable"
                    )
                    OutlinedButton(
                        onClick = onExportSanitized,
                        enabled = exportArtifacts.fileFor(ExportArtifactKind.Sanitized3mf) != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Export Sanitized for Orca Compatibility")
                    }
                    OutlinedButton(
                        onClick = onExportEmbedded,
                        enabled = exportArtifacts.fileFor(ExportArtifactKind.SanitizedEmbedded3mf) != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Export Sanitized + U1 Profile Embedded")
                    }
                    OutlinedButton(
                        onClick = onCopyDebugSummary,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Copy Debug Summary")
                    }
                }

                if (config.extruderCount > 1) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp),
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
 * Phase 2.6a — Prepare-screen filament list.
 *
 * Mirrors desktop OrcaSlicer / Bambu Studio's Filament panel: each row
 * is one filament from the file's canonical list, showing colour and
 * material type. Slot mapping happens at Send time via the Filament
 * mapping dialog — no slot picker on Prepare.
 *
 * Currently read-only display. Phase 2.6b adds tappable rows that open
 * an edit dialog (colour picker + material-type dropdown) so users can
 * override per-filament before slicing — important when the loaded
 * spool differs from what the file specifies (e.g. printing a 3MF that
 * declares PLA in PETG → user overrides material so the slicer bakes
 * the right nozzle temp).
 *
 * The prime tower toggle stays — it's a slicing setting, not a slot
 * mapping concern.
 *
 * Hidden entirely for files with one filament and a single-extruder
 * config (no point showing one row with no controls).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSetupSection(
    detectedColors: List<String>,
    colorMapping: List<Int>?,
    extruderPresets: List<com.u1.slicer.data.ExtruderPreset>,
    filaments: List<com.u1.slicer.data.FilamentProfile>,
    // B128: per-canonical-filament (materialType, nozzleTemp) resolved by the
    // same slice-time resolver. When present for a row, it is authoritative for
    // the displayed material + temp so the chip strip matches the slice exactly.
    // Empty for STL / non-canonical files (falls back to slot-preset material).
    filamentMaterials: List<Pair<String, Int>> = emptyList(),
    extruderCount: Int,
    onMappingChange: (List<Int>) -> Unit,
    onAutoMap: (() -> Unit)? = null,
    filamentOverrides: Map<Int, SlicerViewModel.FilamentOverride> = emptyMap(),
    onMaterialOverride: (fileIndex: Int, materialType: String?) -> Unit = { _, _ -> },
    onColorOverride: (fileIndex: Int, color: String?) -> Unit = { _, _ -> },
    // Task 3 UX consolidation: optional "Mixes" subsection rendered inside this
    // card after the filament rows, so both live in one Card. The caller provides
    // the content lambda; null = no Mixes subsection (default, safe for previews).
    mixSlotsContent: (@Composable () -> Unit)? = null,
) {
    // Note: onMappingChange / onAutoMap kept in the signature because callers
    // still pass them (they wired the legacy slot-picker path). They're
    // unused by Phase 2.6a — the slot picker has moved to the Filament
    // mapping dialog at Send time. Will retire when callers update.
    @Suppress("UNUSED_PARAMETER", "UNUSED_VALUE")
    val _unused = onMappingChange to onAutoMap

    val isMultiColor = detectedColors.isNotEmpty() && colorMapping != null
    // B117: previously the panel returned early for non-canonical files — single-
    // colour 3MFs and STLs — leaving the user with no way to change the slice's
    // filament type from the file's declared default (typically PLA). DC15
    // reported this on a single-colour MakerWorld frog 3MF when they wanted to
    // print PETG. Now we always render at least one filament row, synthesising
    // a default from the first extruder preset when the file has no per-filament
    // metadata. The override flows through to the slicer via `_filamentOverrides[0]`
    // and the non-canonical override branch in `buildProfileOverrides`.
    if (extruderPresets.isEmpty()) return
    val effectiveDetectedColors: List<String> = if (detectedColors.isNotEmpty()) {
        detectedColors
    } else {
        val fallbackColor = extruderPresets.firstOrNull { it.index == 0 }?.color
            ?: extruderPresets.firstOrNull()?.color
            ?: "#FFFFFF"
        listOf(fallbackColor)
    }

    val mapping = remember(colorMapping, effectiveDetectedColors.size) {
        colorMapping ?: List(effectiveDetectedColors.size) { it }
    }
    var expanded by remember { mutableStateOf(true) }
    var editingMaterialFor by remember { mutableStateOf<Int?>(null) }
    var editingColorFor by remember { mutableStateOf<Int?>(null) }

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
                    Text(
                        if (isMultiColor) "Filaments (${detectedColors.size})"
                        else if (effectiveDetectedColors.size == 1) "Filament"
                        else "Print Setup",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Caption explaining where slot picking happens. For multi-colour
                    // files, mention the Map & Print step. For single-filament files,
                    // emphasise the override use case — DC15's exact request.
                    Text(
                        if (isMultiColor) "Slot mapping happens when you tap Map & Print →"
                        else "Tap the chip to change material if your loaded spool differs.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                    run {
                        effectiveDetectedColors.forEachIndexed { colorIdx, modelColor ->
                            // Material type: prefer user override, fall back to the
                            // auto-suggested slot's ExtruderPreset material.
                            val override = filamentOverrides[colorIdx]
                            val suggestedSlot = mapping.getOrElse(colorIdx) { 0 }
                            val suggestedPreset = extruderPresets.firstOrNull { it.index == suggestedSlot }
                                ?: extruderPresets.firstOrNull()
                            // B128: the resolved (material, temp) — computed by the
                            // same function the slice uses — is authoritative when
                            // present, so the chip shows the file's declared material
                            // for a declared multi-colour filament and the slice matches.
                            // Falls back to the slot-preset path for non-canonical
                            // files (STL / single-colour with no canonical entry).
                            val resolved = filamentMaterials.getOrNull(colorIdx)
                            val materialType = resolved?.first
                                ?: override?.materialType ?: suggestedPreset?.materialType ?: "PLA"
                            val profileId = suggestedPreset?.filamentProfileId
                            val profile = filaments.firstOrNull { it.id == profileId }
                            val isOverridden = override?.materialType != null
                            // Temp display: prefer the resolver's temp (matches slice).
                            // For non-canonical fallback: when material is overridden,
                            // use the material's default temp since the slot's linked
                            // profile is no longer the source of truth; otherwise prefer
                            // the linked filament profile's nozzleTemp.
                            val displayTemp = resolved?.second ?: if (isOverridden) {
                                com.u1.slicer.nozzleTempDefaultForMaterial(materialType)
                            } else {
                                profile?.nozzleTemp ?: com.u1.slicer.nozzleTempDefaultForMaterial(materialType)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Filament colour swatch — tap to override.
                                val effectiveColor = override?.color ?: modelColor
                                val colorIsOverridden = override?.color != null
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(com.u1.slicer.ui.parseHexColor(effectiveColor))
                                        .border(
                                            if (colorIsOverridden) 2.dp else 1.dp,
                                            if (colorIsOverridden)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.outline,
                                            androidx.compose.foundation.shape.CircleShape,
                                        )
                                        .clickable { editingColorFor = colorIdx }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Filament ${colorIdx + 1}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        effectiveColor.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    )
                                }
                                // Material type chip — tap to override.
                                Box {
                                    Surface(
                                        color = if (isOverridden)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.clickable { editingMaterialFor = colorIdx },
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        ) {
                                            Text(
                                                materialType,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Icon(
                                                Icons.Default.ArrowDropDown, null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = editingMaterialFor == colorIdx,
                                        onDismissRequest = { editingMaterialFor = null },
                                    ) {
                                        listOf("PLA", "PETG", "ABS", "TPU", "PA", "PC").forEach { m ->
                                            DropdownMenuItem(
                                                text = { Text(m) },
                                                onClick = {
                                                    onMaterialOverride(colorIdx, m)
                                                    editingMaterialFor = null
                                                },
                                            )
                                        }
                                        if (isOverridden) {
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text("Reset to default", style = MaterialTheme.typography.labelMedium) },
                                                onClick = {
                                                    onMaterialOverride(colorIdx, null)
                                                    editingMaterialFor = null
                                                },
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "${displayTemp}°C",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }

                    // (Prime-tower toggle removed from the filament list — it's a slicing
                    // setting, not a per-filament one. Toggle lives in the model-info dialog
                    // and Settings → slice overrides, consistent with every other setting.)

                    // Task 3 UX consolidation — Mixes subsection. When the caller
                    // provides a mix-slots content lambda, render it here with a
                    // subtle divider so it reads as a subsection of this card rather
                    // than a separate top-level card.
                    if (mixSlotsContent != null) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            Text(
                                "MIXES",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp,
                            )
                            Spacer(Modifier.width(6.dp))
                            com.u1.slicer.ui.BetaPill()
                        }
                        mixSlotsContent()
                    }
                }
            }
        }
    }

    // Phase 2.6c — colour edit dialog for the row whose swatch was tapped.
    editingColorFor?.let { idx ->
        val current = filamentOverrides[idx]?.color
            ?: detectedColors.getOrNull(idx)
            ?: "#FFFFFF"
        com.u1.slicer.ui.FilamentColorEditDialog(
            initialHex = current,
            onSave = { hex -> onColorOverride(idx, hex) },
            onDismiss = { editingColorFor = null },
            onReset = if (filamentOverrides[idx]?.color != null) {
                { onColorOverride(idx, null) }
            } else null,
        )
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
    copyBedWarning: String? = null,
    rotation: SlicerViewModel.ModelRotation = SlicerViewModel.ModelRotation(),
    onRotationChange: (SlicerViewModel.ModelRotation) -> Unit = {},
    // F83 (GitHub #136): load-time XYZ size in mm enables the absolute-mm
    // input mode. Null = no model loaded → mm mode is disabled.
    loadTimeSize: Triple<Float, Float, Float>? = null,
    // B132c follow-up (v2.10.4): in multi-object mode (post-split or
    // multi-file add) the global Copies control is meaningless — copies
    // are a single-model concept and don't multiply individual split
    // pieces. Hide the slider in this mode; per-object duplication
    // lives in the selection edit panel instead.
    multiObjectMode: Boolean = false,
) {
    var uniformMode by remember { mutableStateOf(true) }
    var uniformValue by remember(scale) { mutableFloatStateOf(scale.uniform) }
    var expanded by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }
    // F83: input-unit toggle — "%" (legacy) vs "mm" (absolute). Defaults to
    // percent so existing users keep the familiar control.
    var mmMode by remember { mutableStateOf(false) }

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
                    Text(
                        if (multiObjectMode) "Scale & Rotation" else "Scale, Copies & Rotation",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
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
                            // B132c follow-up: hide the "Copies: N" label in
                            // multi-object mode — per-object Copy lives in
                            // the object-scoped Edit panel.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!multiObjectMode) {
                                    Text("Copies: $copyCount", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // F83: unit toggle. mm only available with a loaded model.
                                if (loadTimeSize != null) {
                                    Text(
                                        "mm",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Switch(
                                        checked = mmMode,
                                        onCheckedChange = { mmMode = it }
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                                Text("Uniform", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Spacer(Modifier.width(4.dp))
                                Switch(checked = uniformMode, onCheckedChange = { uniformMode = it })
                            }
                        }
                        if (!multiObjectMode) {
                            Slider(
                                value = copyCount.toFloat(),
                                onValueChange = { v -> onSetCopyCount(v.toInt()) },
                                valueRange = 1f..16f,
                                steps = 14
                            )
                            if (copyBedWarning != null) {
                                Text(
                                    copyBedWarning,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        }
                        val focusManager = LocalFocusManager.current
                        // F83: mm-mode references the load-time axis sizes captured at file load.
                        val sizeX = loadTimeSize?.first ?: 0f
                        val sizeY = loadTimeSize?.second ?: 0f
                        val sizeZ = loadTimeSize?.third ?: 0f
                        val mmActive = mmMode && loadTimeSize != null
                        fun formatField(scaleFactor: Float, axisSize: Float): String =
                            if (mmActive) "%.1f".format(scaleFactor * axisSize)
                            else "%.0f".format(scaleFactor * 100)
                        // mm warning when scaled size exceeds the 270 mm bed on any axis.
                        val mmBedWarning = if (mmActive) listOfNotNull(
                            "X".takeIf { com.u1.slicer.model.ModelScaleConverter.exceedsBed(scale.x, sizeX) },
                            "Y".takeIf { com.u1.slicer.model.ModelScaleConverter.exceedsBed(scale.y, sizeY) },
                            "Z".takeIf { com.u1.slicer.model.ModelScaleConverter.exceedsBed(scale.z, sizeZ) }
                        ).takeIf { it.isNotEmpty() }?.let {
                            "Exceeds 270 mm bed on ${it.joinToString("/")}"
                        } else null
                        if (uniformMode) {
                            // Reference axis for uniform mm input is Z (height) — the most
                            // natural reference per GitHub #136.
                            val uniformAxisSize = if (mmActive) sizeZ else 1f
                            var uniformText by remember(uniformValue, mmActive) {
                                mutableStateOf(formatField(uniformValue, uniformAxisSize))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Scale", style = MaterialTheme.typography.labelMedium)
                                    if (mmActive) {
                                        Text(
                                            "= %.0f%%".format(uniformValue * 100),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    } else if (loadTimeSize != null) {
                                        Text(
                                            "= %.1f mm Z".format(uniformValue * sizeZ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = uniformText,
                                    onValueChange = { uniformText = it },
                                    suffix = { Text(if (mmActive) "mm" else "%") },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = {
                                        val parsed = uniformText.toFloatLenient()
                                        val v = if (mmActive && parsed != null) {
                                            com.u1.slicer.model.ModelScaleConverter
                                                .mmToScale(parsed, uniformAxisSize) ?: uniformValue
                                        } else {
                                            parsed?.div(100f)?.coerceIn(0.1f, 3f) ?: uniformValue
                                        }
                                        uniformValue = v
                                        uniformText = formatField(v, uniformAxisSize)
                                        onScaleChange(SlicerViewModel.ModelScale(v, v, v))
                                        focusManager.clearFocus()
                                    }),
                                    singleLine = true,
                                    modifier = Modifier.width(96.dp)
                                )
                            }
                            Slider(
                                value = uniformValue,
                                onValueChange = { v ->
                                    uniformValue = v
                                    uniformText = formatField(v, uniformAxisSize)
                                    onScaleChange(SlicerViewModel.ModelScale(v, v, v))
                                },
                                valueRange = 0.1f..3f
                            )
                        } else {
                            var xText by remember(scale.x, mmActive) {
                                mutableStateOf(formatField(scale.x, sizeX))
                            }
                            var yText by remember(scale.y, mmActive) {
                                mutableStateOf(formatField(scale.y, sizeY))
                            }
                            var zText by remember(scale.z, mmActive) {
                                mutableStateOf(formatField(scale.z, sizeZ))
                            }
                            // X
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("X", style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = xText,
                                    onValueChange = { xText = it },
                                    suffix = { Text(if (mmActive) "mm" else "%") },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = {
                                        val parsed = xText.toFloatLenient()
                                        val v = if (mmActive && parsed != null) {
                                            com.u1.slicer.model.ModelScaleConverter
                                                .mmToScale(parsed, sizeX) ?: scale.x
                                        } else {
                                            parsed?.div(100f)?.coerceIn(0.1f, 3f) ?: scale.x
                                        }
                                        xText = formatField(v, sizeX)
                                        onScaleChange(scale.copy(x = v))
                                        focusManager.clearFocus()
                                    }),
                                    singleLine = true,
                                    modifier = Modifier.width(96.dp)
                                )
                            }
                            Slider(
                                value = scale.x,
                                onValueChange = { nv ->
                                    xText = formatField(nv, sizeX)
                                    onScaleChange(scale.copy(x = nv))
                                },
                                valueRange = 0.1f..3f
                            )
                            // Y
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Y", style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = yText,
                                    onValueChange = { yText = it },
                                    suffix = { Text(if (mmActive) "mm" else "%") },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = {
                                        val parsed = yText.toFloatLenient()
                                        val v = if (mmActive && parsed != null) {
                                            com.u1.slicer.model.ModelScaleConverter
                                                .mmToScale(parsed, sizeY) ?: scale.y
                                        } else {
                                            parsed?.div(100f)?.coerceIn(0.1f, 3f) ?: scale.y
                                        }
                                        yText = formatField(v, sizeY)
                                        onScaleChange(scale.copy(y = v))
                                        focusManager.clearFocus()
                                    }),
                                    singleLine = true,
                                    modifier = Modifier.width(96.dp)
                                )
                            }
                            Slider(
                                value = scale.y,
                                onValueChange = { nv ->
                                    yText = formatField(nv, sizeY)
                                    onScaleChange(scale.copy(y = nv))
                                },
                                valueRange = 0.1f..3f
                            )
                            // Z
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Z", style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = zText,
                                    onValueChange = { zText = it },
                                    suffix = { Text(if (mmActive) "mm" else "%") },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = {
                                        val parsed = zText.toFloatLenient()
                                        val v = if (mmActive && parsed != null) {
                                            com.u1.slicer.model.ModelScaleConverter
                                                .mmToScale(parsed, sizeZ) ?: scale.z
                                        } else {
                                            parsed?.div(100f)?.coerceIn(0.1f, 3f) ?: scale.z
                                        }
                                        zText = formatField(v, sizeZ)
                                        onScaleChange(scale.copy(z = v))
                                        focusManager.clearFocus()
                                    }),
                                    singleLine = true,
                                    modifier = Modifier.width(96.dp)
                                )
                            }
                            Slider(
                                value = scale.z,
                                onValueChange = { nv ->
                                    zText = formatField(nv, sizeZ)
                                    onScaleChange(scale.copy(z = nv))
                                },
                                valueRange = 0.1f..3f
                            )
                        }
                        if (mmBedWarning != null) {
                            Text(
                                mmBedWarning,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
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

/**
 * M3 Phase A / Task 3 UX consolidation: Mixes subsection content for the
 * combined Filaments card. Renders the collapsible mix-slot list (project +
 * library) with an Add affordance, without its own outer Card/surface chrome —
 * it is always rendered inside PrintSetupSection's card column via the
 * `mixSlotsContent` slot.
 *
 * Renamed from `PrepareMixSlotsSection` (which was a standalone card) to
 * `PrepareMixSlotsSectionContent` (subsection content only).
 * AiPaintResultScreen's HighlightSlotPicker stays as-is — its mix-aware
 * migration is Phase B work.
 */
@Composable
fun PrepareMixSlotsSectionContent(
    viewModel: SlicerViewModel,
    extruderPresets: List<com.u1.slicer.data.ExtruderPreset>,
) {
    val projectMixes by viewModel.mixedFilamentManager.projectMixes.collectAsState()
    val libraryMixes by viewModel.mixedFilamentManager.libraryMixes.collectAsState()
    // Bug B — drive the "current" highlight + re-evaluate after each assignment.
    val perVolumeExtruders by viewModel.perVolumeExtruders.collectAsState()

    var expanded by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var editingRow by remember { mutableStateOf<com.u1.slicer.data.MixedFilamentRow?>(null) }

    // Bug B — 1-based slot id per mix, in the engine's activeOrder (numPhysical + idx + 1).
    // Tapping a mix row assigns the WHOLE MODEL to that mix via the proven per-volume-extruder
    // path (setModelFilament) so it bakes pre-slice AND the preview palette picks up the blend.
    val numPhysical = com.u1.slicer.aipaint.SegmentationCascade.TARGET_SLOTS
    val mixSlotIds = remember(projectMixes, libraryMixes) {
        com.u1.slicer.data.MixSlotOrdering.activeOrder(projectMixes, libraryMixes, numPhysical)
            .mapIndexed { idx, row -> row.id to (numPhysical + idx + 1) }
            .toMap()
    }
    // Recomputed after assignment so the tapped row shows "· current".
    val currentModelSlot = remember(perVolumeExtruders) { viewModel.aggregatedModelFilament() }

    val physicalColours = remember(extruderPresets) {
        (0..3).map { i ->
            val hex = extruderPresets.firstOrNull { it.index == i }?.color
                ?.takeIf { it.isNotBlank() }
                ?: com.u1.slicer.data.ExtruderPreset.DEFAULT_COLORS[i]
            com.u1.slicer.ui.parseHexColorOrDefault(hex)
        }
    }

    if (showDialog) {
        com.u1.slicer.ui.CreateMixSlotDialog(
            physicalFilamentColours = physicalColours,
            physicalFilamentLabels = listOf("E1", "E2", "E3", "E4"),
            editingRow = editingRow,
            onConfirmN = { components, weights, mode ->
                val edit = editingRow
                if (edit != null) {
                    viewModel.editMixN(edit.id, components, weights, mode)
                } else {
                    viewModel.createMixN(components, weights, mode)
                }
            },
            onDelete = editingRow?.let { row -> { viewModel.mixedFilamentManager.delete(row.id) } },
            onDismiss = {
                showDialog = false
                editingRow = null
            },
        )
    }

    // Library entries that aren't already shown in the project list — so the
    // user can see + edit their persistent mixes alongside project ones.
    val libraryOnly = libraryMixes.filter { lib -> projectMixes.none { it.id == lib.id } }
    val totalCount = projectMixes.size + libraryOnly.size

    // No outer Card — rendered as a subsection inside the Filaments card.
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Mix slots ($totalCount)",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
            IconButton(
                onClick = {
                    editingRow = null
                    showDialog = true
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add mix slot",
                    modifier = Modifier.size(18.dp),
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            if (totalCount == 0) {
                Text(
                    "No mix slots yet — tap + to create one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    projectMixes.forEach { row ->
                        val slotId = mixSlotIds[row.id]
                        PrepareMixSlotRow(
                            row = row,
                            physicalColours = physicalColours,
                            inLibrary = row.inLibrary,
                            isCurrent = slotId != null && slotId == currentModelSlot,
                            onAssign = slotId?.let { id -> { viewModel.setModelFilament(id) } },
                            onEdit = {
                                editingRow = row
                                showDialog = true
                            },
                            onToggleLibrary = {
                                if (row.inLibrary)
                                    viewModel.mixedFilamentManager.demoteFromLibrary(row.id)
                                else
                                    viewModel.mixedFilamentManager.promoteToLibrary(row.id)
                            },
                            onDelete = { viewModel.mixedFilamentManager.delete(row.id) },
                        )
                    }
                    if (libraryOnly.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Library (★)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        libraryOnly.forEach { row ->
                            val slotId = mixSlotIds[row.id]
                            PrepareMixSlotRow(
                                row = row,
                                physicalColours = physicalColours,
                                inLibrary = true,
                                isCurrent = slotId != null && slotId == currentModelSlot,
                                onAssign = slotId?.let { id -> { viewModel.setModelFilament(id) } },
                                onEdit = {
                                    editingRow = row
                                    showDialog = true
                                },
                                onToggleLibrary = {
                                    viewModel.mixedFilamentManager.demoteFromLibrary(row.id)
                                },
                                onDelete = { viewModel.mixedFilamentManager.delete(row.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrepareMixSlotRow(
    row: com.u1.slicer.data.MixedFilamentRow,
    physicalColours: List<Color>,
    inLibrary: Boolean,
    isCurrent: Boolean = false,
    // Bug B — tap assigns the whole model to this mix (set colour). Null when no model is
    // loaded; the row then falls back to opening the editor on tap (management-only).
    onAssign: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onToggleLibrary: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Bug B — tap = ASSIGN the model to this mix (set colour). Edit moved to the
            // pencil icon. Falls back to onEdit when nothing is loaded (no model to assign).
            .clickable { (onAssign ?: onEdit)() }
            .background(
                if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.u1.slicer.ui.MixedSlotSwatch(
            colours = row.components.map { physicalColours.getOrNull(it - 1) ?: Color.Gray },
            weights = row.weights,
            size = 32.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.label + if (isCurrent) " · current" else "",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                when (row.distributionMode) {
                    com.u1.slicer.data.MixedFilamentRow.MixDistributionMode.LAYER_CYCLE ->
                        "Layer alternation"
                    com.u1.slicer.data.MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS ->
                        "Same-layer dots"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        // Bug B — edit is now a secondary affordance (pencil), since tap assigns.
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit mix slot",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onToggleLibrary, modifier = Modifier.size(36.dp)) {
            Icon(
                if (inLibrary) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (inLibrary) "Remove from library" else "Promote to library",
                tint = if (inLibrary) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete mix slot",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// SingleColorFilamentRow removed (Task 3 UX consolidation): it was a duplicate
// standalone "Filament" card for STL/single-colour files. PrintSetupSection
// already handles the single-colour case by synthesising a fallback colour row
// from the first extruder preset when detectedColors is empty, so both STL and
// 3MF now share one Filaments card.

@Composable
fun InlineGcodePreview(
    parsedGcode: com.u1.slicer.gcode.ParsedGcode,
    extruderColors: List<String>,
    colorMapping: List<Int>? = null,
    slicerLayerCount: Int = 0,
    onExpand: () -> Unit,
    cameraState: com.u1.slicer.viewer.CameraViewState? = null,
    onCameraStateChange: ((com.u1.slicer.viewer.CameraViewState) -> Unit)? = null,
    onResetView: (() -> Unit)? = null,
    // Phase 2 §4 Step 7 (Preview side) — file's per-filament colours
    // (override-applied). When non-empty, drives the slot palette so the
    // G-code Preview shows the file's colours (matching the Prepare 3D
    // preview).
    resolvedFilamentColors: List<String> = emptyList(),
    // B129 (v2.9.1 follow-up) — the layer range remembered across navigation,
    // shared with the full-screen viewer via SlicerViewModel.previewLayerRange.
    // Null = show the full print (fresh slice). [onLayerRangeChange] persists
    // the user's slider position so moving/rotating the model (which leaves the
    // Preview tab) doesn't reset it to the top.
    initialLayerRange: Pair<Int, Int>? = null,
    onLayerRangeChange: (Int, Int) -> Unit = { _, _ -> },
) {
    var viewerView by remember { mutableStateOf<com.u1.slicer.viewer.GcodeViewerView?>(null) }
    var viewerLoading by remember(parsedGcode) { mutableStateOf(true) }
    var showTravel by remember { mutableStateOf(false) }
    val gcodeLayerCount = parsedGcode.layers.size
    // Use slicer's totalLayers for display (correct print layers), fall back to parsed count
    val displayLayerCount = if (slicerLayerCount > 0) slicerLayerCount else gcodeLayerCount
    // B129: seed from the saved range (preserved across navigation), clamped to
    // this slice's layer count. The inline viewer only exposes the top of the
    // range (min fixed at 0), so it reads/writes the max.
    var maxLayer by remember(parsedGcode) {
        mutableIntStateOf(
            com.u1.slicer.ui.resolveInitialLayerRange(initialLayerRange, gcodeLayerCount).second
        )
    }
    // B143: 1-based mapping so the slider's top position reads N/N, not N-1/N.
    val displayLayer =
        com.u1.slicer.ui.computeDisplayLayer(maxLayer, displayLayerCount, gcodeLayerCount)

    val previewColors = remember(extruderColors, colorMapping, resolvedFilamentColors) {
        normalizeGcodePreviewColors(extruderColors, colorMapping, resolvedFilamentColors)
    }

    LaunchedEffect(parsedGcode, previewColors, viewerView, cameraState) {
        val v = viewerView ?: return@LaunchedEffect
        viewerLoading = true
        if (previewColors.isNotEmpty()) {
            v.setExtruderColors(previewColors)
        }
        v.setGcode(parsedGcode)
        cameraState?.let { v.applyCameraState(it) }
        v.requestRender()
    }

    // B129: re-apply the remembered layer range AFTER the async G-code upload
    // finishes (viewerLoading flips false → totalLayers is known). Applying it
    // synchronously after setGcode raced the GL upload and crashed the renderer
    // (totalLayers still 0). A fresh slice seeds maxLayer to the top so this is a
    // no-op; a position restored across navigation re-applies here.
    LaunchedEffect(viewerLoading, maxLayer, gcodeLayerCount) {
        if (!viewerLoading && maxLayer in 0 until (gcodeLayerCount - 1)) {
            viewerView?.setLayerRange(0, maxLayer)
            viewerView?.requestRender()
        }
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
                                // B129: persist so the position survives leaving
                                // the Preview tab to move/rotate the model.
                                onLayerRangeChange(0, maxLayer)
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

/**
 * Build a palette for the G-code preview, indexed by the compact slicer
 * T-index emitted in the canonical-space G-code.
 *
 * Phase 2 (2026-04-28) — the slicer emits canonical (file-fileIndex space)
 * T-indices and the on-disk G-code is consumed AS-IS by the Preview
 * renderer. The user's slot mapping is only applied at Send time via
 * `applyPrintTimeRemap`. So the palette here must align to the slicer's
 * compact tool indices the renderer reads from `move.extruder`:
 *
 *   compactSlotOrder = colorMapping.distinct().sorted() (capped to 4 slots)
 *   normalized[c]    = slot preset for compactSlotOrder[c]
 *                       (fallback: canonical filament colour for the first
 *                        file filament that maps to that slot)
 *
 * The legacy slot-aware-slicer branches (semmColorPermutation +
 * slicerColorOrder cascades, useDirectSlots short-circuit, post-slice
 * GcodeToolRemapper) were retired with Group B because the slicer no
 * longer remaps tool indices at slice time.
 */
/**
 * Phase 2 (2026-04-28, post-adversarial-review) — produces the
 * extruder-colour palette consumed by the G-code 3D preview renderer.
 *
 * Pre-fix this always returned a 4-entry palette; the renderer clamps
 * `move.extruder` to the last palette index, so canonical files where
 * the slicer emits T4..T9+ in body collapsed all high-index tools onto
 * the last colour. The reviewer caught this on Buzz plate 9 (T9 + T10
 * in body) and on H2C benchy (T0..T6 active).
 *
 * The fix: when [resolvedFilamentColors] is non-empty (canonical-aware
 * path) the palette grows to canonical size. Each canonical fileIndex
 * gets its file-relative colour, and the renderer can paint T<n>
 * directly without clamping. When [resolvedFilamentColors] is empty
 * (legacy path, single-colour, STL), keep the 4-entry palette so
 * existing callers that expect that shape still work.
 */
internal fun normalizeGcodePreviewColors(
    extruderColors: List<String>,
    colorMapping: List<Int>?,
    resolvedFilamentColors: List<String> = emptyList(),
): List<String> {
    if (resolvedFilamentColors.isNotEmpty() || !colorMapping.isNullOrEmpty()) {
        // Phase 2 canonical path. Palette length grows with the canonical
        // filament list (or colorMapping size, whichever is larger) so
        // high-T canonical files (T4..T9+) don't visually collapse onto
        // the last entry the way the pre-fix 4-cap did.
        //
        // Phase 2 (2026-04-28, post-v2.0.0-validation user-visible
        // regression fix): the gcode body has T<fileIndex>, and the
        // user expects palette[fileIdx] to render in the FILE'S
        // filament colour (with user-applied Prepare overrides) — not
        // the physical slot's preset colour. Test on calib-cube + Dragon
        // + Button-S confirmed: showing slot preset hid the user's
        // Prepare customisations and made the gcode preview look
        // disconnected from the model preview.
        //
        // Resolution order for each canonical fileIndex i:
        //   1. `resolvedFilamentColors[i]` — the file's own filament
        //      colour, with any per-filament Prepare override already
        //      applied. Matches what Prepare's 3D model shows.
        //   2. `colorMapping[i]` slot preset — fallback when the file
        //      has no colour for fileIndex i (synthetic STL entries,
        //      blank-color edge cases).
        //   3. `extruderColors[i % 4]` — legacy default, keeps anything
        //      the slicer emits visible.
        //
        // Pre-revision tried slot-first; that was correct for the v1.6.13
        // T-indexed-by-slot world but inverted the contract once Phase 2
        // moved to canonical fileIndex space.
        val length = maxOf(
            resolvedFilamentColors.size,
            colorMapping?.size ?: 0,
            4,
        )
        return MutableList(length) { i ->
            val fileColor = resolvedFilamentColors.getOrNull(i)
            val slot = colorMapping?.getOrNull(i)
            val slotColor = slot?.takeIf { it in 0..3 }
                ?.let { extruderColors.getOrNull(it).orEmpty() }
            when {
                !fileColor.isNullOrBlank() -> fileColor
                !slotColor.isNullOrBlank() -> slotColor
                else -> extruderColors.getOrNull(i % 4).orEmpty()
            }
        }
    }
    // Legacy path: 4-slot palette indexed by physical slot. Used when
    // neither resolvedFilamentColors nor colorMapping is available
    // (single-colour STL, very early during load, etc.)
    return MutableList(4) { extruderColors.getOrNull(it).orEmpty() }
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

// =============================================================================
// Session Resume Banner (F89) — shown on launch when a previous session is
// recoverable. Tap Resume → ViewModel replays the saved state; tap × → clear.
// =============================================================================
@Composable
fun SessionResumeBanner(
    offer: SlicerViewModel.SessionResumeOffer,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val plateSuffix = offer.plateId?.let { " · plate $it" } ?: ""
        Text(
            "Resume ${offer.modelName}$plateSuffix?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAccept) {
            Text("Resume", color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss resume",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

// =============================================================================
// Silent Restore Indicator (F89) — shown on the Prepare tab while a silent
// background restore is in flight after a fast-path Resume. Tells the user
// "the model is reloading, don't tap Pick file".
// =============================================================================
@Composable
fun SilentRestoreIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Restoring model in the background…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

// =============================================================================
// Stale Slice Banner — shown when settings changed since last slice
// =============================================================================
@Composable
fun StaleSliceBanner(onSliceAgain: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "Settings changed \u2014 slice again",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onSliceAgain) {
            Text("Slice", color = MaterialTheme.colorScheme.primary)
        }
    }
}
