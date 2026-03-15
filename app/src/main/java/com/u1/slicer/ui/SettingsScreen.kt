package com.u1.slicer.ui

import android.net.Uri
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.u1.slicer.BuildConfig
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.data.OverrideMode
import com.u1.slicer.data.OverrideValue
import com.u1.slicer.data.SlicingOverrides
import com.u1.slicer.printer.PrinterViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SlicerViewModel,
    printerViewModel: PrinterViewModel? = null,
    onShareDiagnostics: () -> Unit = {},
    onNavigateFilaments: (() -> Unit)? = null,
    onNavigatePrepare: () -> Unit = {},
    onNavigatePreview: () -> Unit = {},
    onNavigatePrinter: () -> Unit = {},
    onNavigateJobs: () -> Unit = {},
    onNavigateSettings: () -> Unit = {}
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
            // ---- Printer (top) ----
            SettingsSection("Printer") {
                InfoRow("Model", "Snapmaker U1")
                InfoRow("Build Volume", "270 x 270 x 270 mm")
                InfoRow("Extruders", "4")
                InfoRow("App Version", "v${BuildConfig.VERSION_NAME}")

                // Connection sub-section
                if (printerViewModel != null) {
                    val printerUrl by printerViewModel.printerUrl.collectAsState()
                    val connectionState by printerViewModel.connectionState.collectAsState()
                    var urlInput by remember(printerUrl) { mutableStateOf(printerUrl) }
                    var showConnection by remember { mutableStateOf(false) }

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showConnection = !showConnection },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Connection", fontWeight = FontWeight.Medium)
                            // Status indicator dot
                            when (connectionState) {
                                is PrinterViewModel.ConnectionState.Connected ->
                                    Icon(Icons.Default.CheckCircle, null,
                                        tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
                                is PrinterViewModel.ConnectionState.Failed ->
                                    Icon(Icons.Default.Error, null,
                                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                else -> {}
                            }
                        }
                        Icon(
                            if (showConnection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            "Toggle connection settings"
                        )
                    }

                    if (showConnection) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("Printer URL") },
                            placeholder = { Text("192.168.1.100") },
                            supportingText = { Text("http:// and port 7125 added automatically") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                printerViewModel.updateUrl(urlInput)
                                printerViewModel.testConnection()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Connect") }

                        when (connectionState) {
                            is PrinterViewModel.ConnectionState.Testing -> {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp)
                                    Text("Testing connection\u2026",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            is PrinterViewModel.ConnectionState.Connected -> {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.CheckCircle, null,
                                        tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                                    Text("Connected",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF4CAF50))
                                }
                            }
                            is PrinterViewModel.ConnectionState.Failed -> {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Error, null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp))
                                    Text((connectionState as PrinterViewModel.ConnectionState.Failed).reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }

            // ---- MakerWorld Cookies ----
            val cookiesEnabled by viewModel.makerWorldCookiesEnabled.collectAsState()
            val makerWorldCookies by viewModel.makerWorldCookies.collectAsState()
            var cookieInput by remember { mutableStateOf("") }
            val hasCookies = makerWorldCookies.isNotBlank()

            SettingsSection("MakerWorld") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Send cookies with URL downloads",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = cookiesEnabled,
                        onCheckedChange = { viewModel.saveMakerWorldCookiesEnabled(it) }
                    )
                }
                if (cookiesEnabled) {
                    Text(
                        if (hasCookies) "Session cookies configured"
                        else "Add cookies for downloads requiring login",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasCookies) androidx.compose.ui.graphics.Color(0xFF4CAF50)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    OutlinedTextField(
                        value = cookieInput,
                        onValueChange = { cookieInput = it },
                        label = { Text("MakerWorld Cookies") },
                        placeholder = { Text("Paste cookies from browser") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (cookieInput.isNotBlank()) {
                                    viewModel.saveMakerWorldCookies(cookieInput)
                                    cookieInput = ""
                                }
                            },
                            enabled = cookieInput.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Save Cookies") }
                        if (hasCookies) {
                            OutlinedButton(
                                onClick = { viewModel.saveMakerWorldCookies("") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Clear") }
                        }
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

                OverrideRow(
                    label = "Layer Height",
                    override = overrides.layerHeight,
                    defaultHint = "0.2 mm",
                    onModeChange = { mode -> overrides = overrides.copy(layerHeight = overrides.layerHeight.copy(mode = mode)) },
                    valueContent = {
                        OverrideFloatField(
                            value = overrides.layerHeight.value ?: 0.2f,
                            suffix = "mm",
                            onValueChange = { overrides = overrides.copy(layerHeight = OverrideValue(OverrideMode.OVERRIDE, it)) }
                        )
                    }
                )

                OverrideRow(
                    label = "Infill Density",
                    override = overrides.infillDensity,
                    defaultHint = "15%",
                    onModeChange = { mode -> overrides = overrides.copy(infillDensity = overrides.infillDensity.copy(mode = mode)) },
                    valueContent = {
                        OverrideFloatField(
                            value = (overrides.infillDensity.value ?: 0.15f) * 100f,
                            suffix = "%",
                            onValueChange = { overrides = overrides.copy(infillDensity = OverrideValue(OverrideMode.OVERRIDE, it / 100f)) }
                        )
                    }
                )

                OverrideRow(
                    label = "Wall Count",
                    override = overrides.wallCount,
                    defaultHint = "2",
                    onModeChange = { mode -> overrides = overrides.copy(wallCount = overrides.wallCount.copy(mode = mode)) },
                    valueContent = {
                        OverrideIntField(
                            value = overrides.wallCount.value ?: 2,
                            onValueChange = { overrides = overrides.copy(wallCount = OverrideValue(OverrideMode.OVERRIDE, it)) }
                        )
                    }
                )

                OverrideRow(
                    label = "Infill Pattern",
                    override = overrides.infillPattern,
                    defaultHint = "gyroid",
                    onModeChange = { mode -> overrides = overrides.copy(infillPattern = overrides.infillPattern.copy(mode = mode)) },
                    valueContent = {
                        val patterns = listOf("gyroid", "grid", "lines", "honeycomb", "cubic", "triangles", "rectilinear")
                        OverrideDropdown(
                            value = overrides.infillPattern.value ?: "gyroid",
                            options = patterns,
                            onValueChange = { overrides = overrides.copy(infillPattern = OverrideValue(OverrideMode.OVERRIDE, it)) }
                        )
                    }
                )

                OverrideRow(
                    label = "Supports",
                    override = overrides.supports,
                    defaultHint = "Off",
                    onModeChange = { mode -> overrides = overrides.copy(supports = overrides.supports.copy(mode = mode)) },
                    valueContent = {
                        OverrideToggle(
                            value = overrides.supports.value ?: false,
                            onValueChange = { overrides = overrides.copy(supports = OverrideValue(OverrideMode.OVERRIDE, it)) }
                        )
                    }
                )

                // Expanded support settings (only show when supports are overridden to enabled)
                if (overrides.supports.mode == OverrideMode.OVERRIDE && overrides.supports.value == true) {
                    OverrideRow(
                        label = "  Support Type",
                        override = overrides.supportType,
                        defaultHint = "Normal (Auto)",
                        onModeChange = { mode -> overrides = overrides.copy(supportType = overrides.supportType.copy(mode = mode)) },
                        valueContent = {
                            val types = listOf("normal(auto)" to "Normal", "tree(auto)" to "Tree")
                            OverrideDropdown(
                                value = overrides.supportType.value ?: "normal(auto)",
                                options = types.map { it.first },
                                labels = types.map { it.second },
                                onValueChange = { overrides = overrides.copy(supportType = OverrideValue(OverrideMode.OVERRIDE, it)) }
                            )
                        }
                    )

                    OverrideRow(
                        label = "  Threshold Angle",
                        override = overrides.supportAngle,
                        defaultHint = "30\u00B0",
                        onModeChange = { mode -> overrides = overrides.copy(supportAngle = overrides.supportAngle.copy(mode = mode)) },
                        valueContent = {
                            OverrideIntField(
                                value = overrides.supportAngle.value ?: 30,
                                suffix = "\u00B0",
                                onValueChange = { overrides = overrides.copy(supportAngle = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0, 90))) }
                            )
                        }
                    )

                    OverrideRow(
                        label = "  Build Plate Only",
                        override = overrides.supportBuildPlateOnly,
                        defaultHint = "Off",
                        onModeChange = { mode -> overrides = overrides.copy(supportBuildPlateOnly = overrides.supportBuildPlateOnly.copy(mode = mode)) },
                        valueContent = {
                            OverrideToggle(
                                value = overrides.supportBuildPlateOnly.value ?: false,
                                onValueChange = { overrides = overrides.copy(supportBuildPlateOnly = OverrideValue(OverrideMode.OVERRIDE, it)) }
                            )
                        }
                    )

                    OverrideRow(
                        label = "  Support Pattern",
                        override = overrides.supportPattern,
                        defaultHint = "Default",
                        onModeChange = { mode -> overrides = overrides.copy(supportPattern = overrides.supportPattern.copy(mode = mode)) },
                        valueContent = {
                            val patterns = listOf("default", "rectilinear", "rectilinear_grid", "honeycomb", "lightning")
                            OverrideDropdown(
                                value = overrides.supportPattern.value ?: "default",
                                options = patterns,
                                onValueChange = { overrides = overrides.copy(supportPattern = OverrideValue(OverrideMode.OVERRIDE, it)) }
                            )
                        }
                    )

                    OverrideRow(
                        label = "  Pattern Spacing",
                        override = overrides.supportPatternSpacing,
                        defaultHint = "2.5 mm",
                        onModeChange = { mode -> overrides = overrides.copy(supportPatternSpacing = overrides.supportPatternSpacing.copy(mode = mode)) },
                        valueContent = {
                            OverrideFloatField(
                                value = overrides.supportPatternSpacing.value ?: 2.5f,
                                suffix = "mm",
                                onValueChange = { overrides = overrides.copy(supportPatternSpacing = OverrideValue(OverrideMode.OVERRIDE, it)) }
                            )
                        }
                    )

                    OverrideRow(
                        label = "  Interface Top Layers",
                        override = overrides.supportInterfaceTopLayers,
                        defaultHint = "3",
                        onModeChange = { mode -> overrides = overrides.copy(supportInterfaceTopLayers = overrides.supportInterfaceTopLayers.copy(mode = mode)) },
                        valueContent = {
                            OverrideIntField(
                                value = overrides.supportInterfaceTopLayers.value ?: 3,
                                onValueChange = { overrides = overrides.copy(supportInterfaceTopLayers = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0, 10))) }
                            )
                        }
                    )

                    OverrideRow(
                        label = "  Interface Bottom Layers",
                        override = overrides.supportInterfaceBottomLayers,
                        defaultHint = "0",
                        onModeChange = { mode -> overrides = overrides.copy(supportInterfaceBottomLayers = overrides.supportInterfaceBottomLayers.copy(mode = mode)) },
                        valueContent = {
                            OverrideIntField(
                                value = overrides.supportInterfaceBottomLayers.value ?: 0,
                                onValueChange = { overrides = overrides.copy(supportInterfaceBottomLayers = OverrideValue(OverrideMode.OVERRIDE, it.coerceIn(0, 10))) }
                            )
                        }
                    )

                    OverrideRow(
                        label = "  Support Extruder",
                        override = overrides.supportFilament,
                        defaultHint = "Default",
                        onModeChange = { mode -> overrides = overrides.copy(supportFilament = overrides.supportFilament.copy(mode = mode)) },
                        valueContent = {
                            val options = listOf(0 to "Default") + (1..4).map { it to "Extruder $it" }
                            OverrideDropdown(
                                value = (overrides.supportFilament.value ?: 0).toString(),
                                options = options.map { it.first.toString() },
                                labels = options.map { it.second },
                                onValueChange = { overrides = overrides.copy(supportFilament = OverrideValue(OverrideMode.OVERRIDE, it.toIntOrNull() ?: 0)) }
                            )
                        }
                    )

                    OverrideRow(
                        label = "  Interface Extruder",
                        override = overrides.supportInterfaceFilament,
                        defaultHint = "Default",
                        onModeChange = { mode -> overrides = overrides.copy(supportInterfaceFilament = overrides.supportInterfaceFilament.copy(mode = mode)) },
                        valueContent = {
                            val options = listOf(0 to "Default") + (1..4).map { it to "Extruder $it" }
                            OverrideDropdown(
                                value = (overrides.supportInterfaceFilament.value ?: 0).toString(),
                                options = options.map { it.first.toString() },
                                labels = options.map { it.second },
                                onValueChange = { overrides = overrides.copy(supportInterfaceFilament = OverrideValue(OverrideMode.OVERRIDE, it.toIntOrNull() ?: 0)) }
                            )
                        }
                    )
                }

                OverrideRow(
                    label = "Brim Width",
                    override = overrides.brimWidth,
                    defaultHint = "0 mm",
                    onModeChange = { mode -> overrides = overrides.copy(brimWidth = overrides.brimWidth.copy(mode = mode)) },
                    valueContent = {
                        OverrideFloatField(
                            value = overrides.brimWidth.value ?: 0f,
                            suffix = "mm",
                            onValueChange = { overrides = overrides.copy(brimWidth = OverrideValue(OverrideMode.OVERRIDE, it)) }
                        )
                    }
                )

                OverrideRow(
                    label = "Skirt Loops",
                    override = overrides.skirtLoops,
                    defaultHint = "0",
                    onModeChange = { mode -> overrides = overrides.copy(skirtLoops = overrides.skirtLoops.copy(mode = mode)) },
                    valueContent = {
                        OverrideIntField(
                            value = overrides.skirtLoops.value ?: 0,
                            onValueChange = { overrides = overrides.copy(skirtLoops = OverrideValue(OverrideMode.OVERRIDE, it)) }
                        )
                    }
                )

                OverrideRow(
                    label = "Bed Temp",
                    override = overrides.bedTemp,
                    defaultHint = "60\u00B0C",
                    onModeChange = { mode -> overrides = overrides.copy(bedTemp = overrides.bedTemp.copy(mode = mode)) },
                    valueContent = {
                        OverrideIntField(
                            value = overrides.bedTemp.value ?: 60,
                            suffix = "\u00B0C",
                            onValueChange = { overrides = overrides.copy(bedTemp = OverrideValue(OverrideMode.OVERRIDE, it)) }
                        )
                    }
                )

                OverrideRow(
                    label = "Prime Tower",
                    override = overrides.primeTower,
                    defaultHint = "Off",
                    onModeChange = { mode -> overrides = overrides.copy(primeTower = overrides.primeTower.copy(mode = mode)) },
                    valueContent = {
                        OverrideToggle(
                            value = overrides.primeTower.value ?: false,
                            onValueChange = { overrides = overrides.copy(primeTower = OverrideValue(OverrideMode.OVERRIDE, it)) }
                        )
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Prime Tower Detail Overrides
                Text("Prime Tower Settings",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)

                OverrideRow(
                    label = "Prime Volume",
                    override = overrides.primeVolume,
                    defaultHint = "45",
                    onModeChange = { mode -> overrides = overrides.copy(primeVolume = overrides.primeVolume.copy(mode = mode)) },
                    valueContent = {
                        OverrideIntField(
                            value = overrides.primeVolume.value ?: 45,
                            onValueChange = { overrides = overrides.copy(primeVolume = OverrideValue(OverrideMode.OVERRIDE, it)) }
                        )
                    }
                )

                OverrideRow(
                    label = "Tower Brim Width",
                    override = overrides.primeTowerBrimWidth,
                    defaultHint = "3 mm",
                    onModeChange = { mode -> overrides = overrides.copy(primeTowerBrimWidth = overrides.primeTowerBrimWidth.copy(mode = mode)) },
                    valueContent = {
                        OverrideFloatField(
                            value = overrides.primeTowerBrimWidth.value ?: 3f,
                            suffix = "mm",
                            onValueChange = { overrides = overrides.copy(primeTowerBrimWidth = OverrideValue(OverrideMode.OVERRIDE, it)) }
                        )
                    }
                )

                OverrideRow(
                    label = "Brim Chamfer",
                    override = overrides.primeTowerBrimChamfer,
                    defaultHint = "On",
                    onModeChange = { mode -> overrides = overrides.copy(primeTowerBrimChamfer = overrides.primeTowerBrimChamfer.copy(mode = mode)) },
                    valueContent = {
                        OverrideToggle(
                            value = overrides.primeTowerBrimChamfer.value ?: true,
                            onValueChange = { overrides = overrides.copy(primeTowerBrimChamfer = OverrideValue(OverrideMode.OVERRIDE, it)) }
                        )
                    }
                )

                OverrideRow(
                    label = "Chamfer Max Width",
                    override = overrides.primeTowerChamferMaxWidth,
                    defaultHint = "5 mm",
                    onModeChange = { mode -> overrides = overrides.copy(primeTowerChamferMaxWidth = overrides.primeTowerChamferMaxWidth.copy(mode = mode)) },
                    valueContent = {
                        OverrideFloatField(
                            value = overrides.primeTowerChamferMaxWidth.value ?: 5f,
                            suffix = "mm",
                            onValueChange = { overrides = overrides.copy(primeTowerChamferMaxWidth = OverrideValue(OverrideMode.OVERRIDE, it)) }
                        )
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Flow Calibration", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = overrides.flowCalibration,
                        onCheckedChange = { overrides = overrides.copy(flowCalibration = it) }
                    )
                }
            }

            // ---- Backup & Restore ----
            val context = LocalContext.current
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
                        val count = viewModel.clearCacheFiles()
                        backupStatus = if (count > 0) "Cleared $count cached files" else "No cached files to clear"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Clear Cache") }

                Text(
                    "Deletes cached 3MF and G-code files. Use if slicing fails after an update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> OverrideRow(
    label: String,
    override: OverrideValue<T>,
    defaultHint: String,
    onModeChange: (OverrideMode) -> Unit,
    valueContent: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (override.mode == OverrideMode.ORCA_DEFAULT) {
                Text(
                    defaultHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        val modes = OverrideMode.entries
        val modeLabels = listOf("File", "Orca", "Override")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = override.mode == mode,
                    onClick = { onModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size)
                ) {
                    Text(modeLabels[index], style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (override.mode == OverrideMode.OVERRIDE) {
            valueContent()
        }
    }
}

@Composable
private fun OverrideFloatField(
    value: Float,
    suffix: String = "",
    onValueChange: (Float) -> Unit
) {
    var text by remember(value) { mutableStateOf(if (value == value.toInt().toFloat()) value.toInt().toString() else "%.2f".format(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toFloatOrNull()?.let { f -> onValueChange(f) }
        },
        suffix = if (suffix.isNotEmpty()) {{ Text(suffix) }} else null,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true
    )
}

@Composable
private fun OverrideIntField(
    value: Int,
    suffix: String = "",
    onValueChange: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let { i -> onValueChange(i) }
        },
        suffix = if (suffix.isNotEmpty()) {{ Text(suffix) }} else null,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverrideDropdown(
    value: String,
    options: List<String>,
    labels: List<String> = options,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = labels.getOrElse(options.indexOf(value)) { value }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { idx, option ->
                DropdownMenuItem(
                    text = { Text(labels.getOrElse(idx) { option }) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun OverrideToggle(value: Boolean, onValueChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (value) "Enabled" else "Disabled",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = value, onCheckedChange = onValueChange)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
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
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary)
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
