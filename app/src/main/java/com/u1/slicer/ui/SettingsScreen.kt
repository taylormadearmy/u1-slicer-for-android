package com.u1.slicer.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.u1.slicer.BMAC_URL
import com.u1.slicer.BuildConfig
import com.u1.slicer.GITHUB_URL
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.network.UpdateChecker
import kotlinx.coroutines.launch
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.SlicingOverrides
import com.u1.slicer.printer.PrinterViewModel

private sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Available(val version: String, val downloadUrl: String, val releaseUrl: String) : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SlicerViewModel,
    printerViewModel: PrinterViewModel? = null,
    onShareDiagnostics: () -> Unit = {},
    onNavigateFilaments: (() -> Unit)? = null,
    onNavigateProcessProfiles: (() -> Unit)? = null,
    onNavigatePrepare: () -> Unit = {},
    onNavigatePreview: () -> Unit = {},
    onNavigatePrinter: () -> Unit = {},
    onNavigateJobs: () -> Unit = {},
    onNavigateSettings: () -> Unit = {},
    onNavigateMakerWorldLogin: () -> Unit = {}
) {
    val config by viewModel.config.collectAsState()
    var nozzleTemp by remember(config) { mutableStateOf(config.nozzleTemp.toString()) }
    var bedTemp by remember(config) { mutableStateOf(config.bedTemp.toString()) }
    var printSpeed by remember(config) { mutableStateOf(config.printSpeed.toInt().toString()) }
    var travelSpeed by remember(config) { mutableStateOf(config.travelSpeed.toInt().toString()) }
    var firstLayerSpeed by remember(config) { mutableStateOf(config.firstLayerSpeed.toInt().toString()) }
    var retractLength by remember(config) { mutableStateOf("%.1f".format(config.retractLength)) }
    var retractSpeed by remember(config) { mutableStateOf(config.retractSpeed.toInt().toString()) }

    val currentOverrides by viewModel.slicingOverrides.collectAsState()
    var overrides by remember(currentOverrides) { mutableStateOf(currentOverrides) }
    val plateType by viewModel.plateType.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        viewModel.updateConfig {
                            it.copy(
                                nozzleTemp = nozzleTemp.toIntOrNull() ?: it.nozzleTemp,
                                bedTemp = bedTemp.toIntOrNull() ?: it.bedTemp,
                                printSpeed = printSpeed.toFloatOrNull() ?: it.printSpeed,
                                travelSpeed = travelSpeed.toFloatOrNull() ?: it.travelSpeed,
                                firstLayerSpeed = firstLayerSpeed.toFloatOrNull() ?: it.firstLayerSpeed,
                                retractLength = retractLength.toFloatOrNull() ?: it.retractLength,
                                retractSpeed = retractSpeed.toFloatOrNull() ?: it.retractSpeed
                            )
                        }
                        viewModel.saveConfig()
                        viewModel.saveSlicingOverrides(overrides)
                    }) {
                        Icon(Icons.Default.Save, "Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            com.u1.slicer.U1BottomNavBar(
                selectedTab = "settings",
                onNavigatePrepare = onNavigatePrepare,
                onNavigatePreview = onNavigatePreview,
                onNavigatePrinter = onNavigatePrinter,
                onNavigateJobs = onNavigateJobs,
                onNavigateSettings = onNavigateSettings
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
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
            // null = not downloading, 0f..1f = download in progress
            var downloadProgress by remember { mutableStateOf<Float?>(null) }
            SettingsSection("About") {
                // Version row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Version", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                // GitHub / Release notes row
                val releaseNotesUrl = (updateState as? UpdateCheckState.Available)?.releaseUrl
                val githubRowUrl = if (!releaseNotesUrl.isNullOrEmpty()) releaseNotesUrl else GITHUB_URL
                val githubRowLabel = if (!releaseNotesUrl.isNullOrEmpty()) "Release notes" else "GitHub"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(githubRowUrl))
                            )
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(githubRowLabel, style = MaterialTheme.typography.bodyMedium)
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = githubRowLabel,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                // Check for Updates row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = updateState !is UpdateCheckState.Checking) {
                            updateState = UpdateCheckState.Checking
                            scope.launch {
                                val result = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
                                updateState = result.fold(
                                    onSuccess = { release ->
                                        if (release != null) UpdateCheckState.Available(release.version, release.downloadUrl, release.releaseUrl)
                                        else UpdateCheckState.UpToDate
                                    },
                                    onFailure = { UpdateCheckState.Error(it.message ?: "Check failed") }
                                )
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Check for Updates", style = MaterialTheme.typography.bodyMedium)
                    when (val s = updateState) {
                        is UpdateCheckState.Idle -> Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Check for updates",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        is UpdateCheckState.Checking -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        is UpdateCheckState.UpToDate -> Text(
                            "Up to date",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        is UpdateCheckState.Available -> Text(
                            "v${s.version} available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        is UpdateCheckState.Error -> Text(
                            "Check failed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Download row (only shown when update is available)
                if (updateState is UpdateCheckState.Available) {
                    val available = updateState as UpdateCheckState.Available
                    val isDownloading = downloadProgress != null
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isDownloading) {
                                    scope.launch {
                                        downloadProgress = 0f
                                        try {
                                            val apkFile = File(context.cacheDir, "u1-slicer-update.apk")
                                            withContext(Dispatchers.IO) {
                                                val client = OkHttpClient.Builder()
                                                    .connectTimeout(30, TimeUnit.SECONDS)
                                                    .readTimeout(120, TimeUnit.SECONDS)
                                                    .build()
                                                val response = client.newCall(
                                                    Request.Builder().url(available.downloadUrl).build()
                                                ).execute()
                                                if (!response.isSuccessful) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(context, "Download failed: HTTP ${response.code}", Toast.LENGTH_LONG).show()
                                                        downloadProgress = null
                                                    }
                                                    return@withContext
                                                }
                                                val body = response.body
                                                if (body == null) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(context, "Download failed: empty response", Toast.LENGTH_LONG).show()
                                                        downloadProgress = null
                                                    }
                                                    return@withContext
                                                }
                                                val contentLength = body.contentLength()
                                                body.byteStream().use { input ->
                                                    apkFile.outputStream().use { output ->
                                                        val buffer = ByteArray(8192)
                                                        var bytesRead = 0L
                                                        var read: Int
                                                        while (input.read(buffer).also { read = it } != -1) {
                                                            output.write(buffer, 0, read)
                                                            bytesRead += read
                                                            if (contentLength > 0) {
                                                                val progress = bytesRead.toFloat() / contentLength
                                                                withContext(Dispatchers.Main) {
                                                                    downloadProgress = progress
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            if (downloadProgress != null) {
                                                // Download completed successfully — trigger install
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    apkFile
                                                )
                                                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(installIntent)
                                                downloadProgress = null
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                                            downloadProgress = null
                                        }
                                    }
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (isDownloading) "Downloading v${available.version}…" else "Download v${available.version}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (isDownloading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Download update",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        val progress = downloadProgress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                            )
                        }
                    }
                }

                // Buy Me a Coffee card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(BMAC_URL))
                            )
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFDD00)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("\u2615", fontSize = 22.sp)
                        Column {
                            Text(
                                "Buy Me a Coffee",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.Black
                            )
                            Text(
                                "buymeacoffee.com/taylormadearmy",
                                fontSize = 10.sp,
                                color = Color(0xFF555555)
                            )
                        }
                    }
                }
            }

            // ---- Printer ----
            SettingsSection("Printer") {
                InfoRow("Model", "Snapmaker U1")
                InfoRow("Build Volume", "270 x 270 x 270 mm")
                InfoRow("Extruders", "4")

                // F78: multi-printer list with add/edit/delete
                if (printerViewModel != null) {
                    val printerList by printerViewModel.printerList.collectAsState()
                    val activePrinterId by printerViewModel.activePrinterId.collectAsState()

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(Modifier.height(4.dp))

                    com.u1.slicer.ui.printer.PrintersSettingsCard(
                        printers = printerList,
                        activeId = activePrinterId,
                        onAdd = { nick, url -> printerViewModel.addPrinter(nick, url) },
                        onEdit = { id, nick, url -> printerViewModel.updatePrinter(id, nick, url) },
                        onDelete = { id -> printerViewModel.deletePrinter(id) },
                        onTestConnection = { url ->
                            val client = com.u1.slicer.network.MoonrakerClient().apply {
                                baseUrl = url
                            }
                            client.testConnection()
                        },
                    )
                }
            }

            // ---- MakerWorld ----
            var showMakerWorldModeInfo by remember { mutableStateOf(false) }
            if (showMakerWorldModeInfo) {
                MakerWorldModeDialog(onDismiss = { showMakerWorldModeInfo = false })
            }

            SettingsSection(
                title = "MakerWorld",
                trailing = {
                    IconButton(onClick = { showMakerWorldModeInfo = true }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "MakerWorld help",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                Text(
                    "Google sign-in is not supported inside the app browser. " +
                        "Best results come from opening MakerWorld in your browser, then " +
                        "downloading the 3MF/STL there or sharing the model link back to U1 Slicer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://makerworld.com/en"))
                            intent.addCategory(Intent.CATEGORY_BROWSABLE)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Open MakerWorld in Browser") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onNavigateMakerWorldLogin,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Browse MakerWorld in App") }
                }
            }

            // ---- Process Profiles (F87) ----
            if (onNavigateProcessProfiles != null) {
                val ppCfg by viewModel.processProfilesConfig.collectAsState()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateProcessProfiles() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Process Profiles", fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    "${ppCfg.profiles.size} imported" +
                                        (ppCfg.active?.let { " · Active: ${it.name}" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            }

            // ---- Filament Profiles ----
            if (onNavigateFilaments != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateFilaments() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Palette, null,
                                tint = MaterialTheme.colorScheme.primary)
                            Text("Filament Profiles", fontWeight = FontWeight.Bold,
                                fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            }

            // ---- Temperature ----
            SettingsSection("Temperature") {
                SettingsTextField("Nozzle Temp (\u00B0C)", nozzleTemp) { nozzleTemp = it }
                SettingsTextField("Bed Temp (\u00B0C)", bedTemp) { bedTemp = it }
            }

            // ---- Speed ----
            SettingsSection("Speed (mm/s)") {
                SettingsTextField("Print Speed", printSpeed) { printSpeed = it }
                SettingsTextField("Travel Speed", travelSpeed) { travelSpeed = it }
                SettingsTextField("First Layer Speed", firstLayerSpeed) { firstLayerSpeed = it }
            }

            // ---- Retraction ----
            SettingsSection("Retraction") {
                SettingsTextField("Retract Length (mm)", retractLength) { retractLength = it }
                SettingsTextField("Retract Speed (mm/s)", retractSpeed) { retractSpeed = it }
            }

            // ---- Slicing Overrides ----
            SettingsSection("Slicing Overrides") {
                Text(
                    "Override settings from loaded 3MF files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                SlicingOverridesAccordion(
                    overrides = overrides,
                    onOverridesChange = { overrides = it },
                    plateType = plateType,
                    onPlateTypeChange = { viewModel.setPlateType(it) },
                    bedTemp = config.bedTemp,
                    onBedTempChange = { viewModel.setBedTemp(it) },
                    sourceConfig = null
                )
            }

            // ---- Backup & Restore ----
            var backupStatus by remember { mutableStateOf<String?>(null) }

            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri: Uri? ->
                if (uri != null) {
                    viewModel.exportBackupAsync { json ->
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray())
                            backupStatus = "Settings exported"
                        }
                    }
                }
            }

            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri != null) {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    if (json != null) {
                        viewModel.importBackup(json) { hasPrinterUrl ->
                            if (hasPrinterUrl && printerViewModel != null) {
                                printerViewModel.testConnection()
                            }
                        }
                        backupStatus = "Settings imported"
                    }
                }
            }

            SettingsSection("Backup & Restore") {
                Button(
                    onClick = { exportLauncher.launch("u1-slicer-backup.json") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Export Settings") }

                OutlinedButton(
                    onClick = { importLauncher.launch("application/json") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Import Settings") }

                if (backupStatus != null) {
                    Text(
                        backupStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                OutlinedButton(
                    onClick = onShareDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Share Diagnostics") }

                OutlinedButton(
                    onClick = {
                        val count = viewModel.resetAppState()
                        backupStatus = if (count > 0) {
                            "Reset app state and cleared $count files/directories"
                        } else {
                            "Reset app state"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Reset App State") }

                Text(
                    "Clears generated files, cache, and recovery markers, then closes the app. Reopen it afterward if slicing fails after an update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            // ---- Smart Paint (renamed from AI Paint in fix34) ----
            SettingsSection("Smart Paint") {
                // F54: AI naming toggle. Off by default — pipeline uses deterministic segments
                // from the model's own structure (paint state / volumes / objects / topology /
                // height bands). When on, AI is also called to name and recolour the leaves.
                val aiNaming by viewModel.aiNamingEnabled.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI naming (experimental)",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Send rendered views to the AI for label + colour suggestions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = aiNaming,
                        onCheckedChange = { viewModel.saveAiNamingEnabled(it) },
                    )
                }
                Spacer(Modifier.height(8.dp))

                val aiProviderName by viewModel.aiPaintProvider.collectAsState()
                // Per-provider key — re-derived whenever the selected provider changes so each
                // provider keeps its own key independently.
                val keyFlow = remember(aiProviderName) { viewModel.aiPaintApiKeyFor(aiProviderName) }
                val aiApiKey by keyFlow.collectAsState(initial = "")
                val currentProvider = com.u1.slicer.aipaint.AiPaintProvider.fromId(aiProviderName)

                // Provider dropdown
                var providerExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = currentProvider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("AI provider (for naming)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false }
                    ) {
                        com.u1.slicer.aipaint.AiPaintProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.displayName) },
                                onClick = {
                                    providerExpanded = false
                                    // Only save the provider selection — NOT the key. The key
                                    // field auto-reloads that provider's stored key (blank if
                                    // none). Without this split, switching providers was
                                    // copying the previous provider's key into the new slot.
                                    viewModel.saveAiPaintProvider(provider.name)
                                }
                            )
                        }
                    }
                }

                // API key field — shown when the provider accepts a key (required or optional).
                if (currentProvider.acceptsKey) {
                    Spacer(Modifier.height(8.dp))
                    var keyVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = aiApiKey,
                        onValueChange = { newKey ->
                            viewModel.saveAiPaintKey(currentProvider.name, newKey)
                        },
                        label = {
                            Text(if (currentProvider.requiresKey) "API key" else "API key (optional)")
                        },
                        supportingText = if (!currentProvider.requiresKey) {
                            { Text("Leave blank to use the free public tier.") }
                        } else null,
                        visualTransformation = if (keyVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (keyVisible) "Hide key" else "Show key"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ---- About ----
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
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
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary)
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true
    )
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
