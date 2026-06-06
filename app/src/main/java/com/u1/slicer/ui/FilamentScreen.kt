package com.u1.slicer.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentProfile
import com.u1.slicer.data.MixedFilamentManager
import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.util.toFloatLenient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilamentScreen(
    filaments: List<FilamentProfile>,
    onAdd: (FilamentProfile) -> Unit,
    onUpdate: (FilamentProfile) -> Unit,
    onDelete: (FilamentProfile) -> Unit,
    onApply: (FilamentProfile) -> Unit,
    onSetDefault: (FilamentProfile) -> Unit,
    onImport: (List<FilamentProfile>) -> Unit,
    onBack: () -> Unit,
    // M3 Phase A: optional Mix-slot affordances. Null when the caller doesn't
    // need them (e.g. unit-test harness); production passes the manager + the
    // extruder presets so we can build the physical filament chips inside the
    // CreateMixSlotDialog.
    mixedFilamentManager: MixedFilamentManager? = null,
    extruderPresets: List<ExtruderPreset> = emptyList(),
) {
    var showDialog by remember { mutableStateOf(false) }
    var editingFilament by remember { mutableStateOf<FilamentProfile?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // M3 Phase A: mix-slot dialog state. `editingMix` non-null = Edit mode.
    var showMixDialog by remember { mutableStateOf(false) }
    var editingMix by remember { mutableStateOf<MixedFilamentRow?>(null) }
    val projectMixes by (mixedFilamentManager?.projectMixes?.collectAsState()
        ?: remember { mutableStateOf(emptyList<MixedFilamentRow>()) })
    val libraryMixes by (mixedFilamentManager?.libraryMixes?.collectAsState()
        ?: remember { mutableStateOf(emptyList<MixedFilamentRow>()) })

    if (showMixDialog && mixedFilamentManager != null) {
        val physicalColours = remember(extruderPresets) {
            (0..3).map { i ->
                val hex = extruderPresets.firstOrNull { it.index == i }?.color
                    ?.takeIf { it.isNotBlank() }
                    ?: ExtruderPreset.DEFAULT_COLORS[i]
                parseHexColorOrDefault(hex)
            }
        }
        CreateMixSlotDialog(
            physicalFilamentColours = physicalColours,
            physicalFilamentLabels = listOf("E1", "E2", "E3", "E4"),
            editingRow = editingMix,
            onConfirm = { a, b, pct, mode ->
                val edit = editingMix
                if (edit != null) {
                    mixedFilamentManager.edit(edit.id, a, b, pct, mode)
                } else {
                    mixedFilamentManager.add(a, b, pct, mode)
                }
            },
            onDelete = editingMix?.let { row -> { mixedFilamentManager.delete(row.id) } },
            onDismiss = {
                showMixDialog = false
                editingMix = null
            },
        )
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: throw Exception("Could not read file")
            val profiles = parseFilamentJson(json, context)
            if (profiles.isEmpty()) throw Exception("No valid filament profiles found in file")
            onImport(profiles)
        } catch (e: Exception) {
            importError = e.message ?: "Import failed"
        }
    }

    if (showDialog) {
        FilamentEditDialog(
            filament = editingFilament,
            onSave = { profile ->
                if (editingFilament != null) onUpdate(profile) else onAdd(profile)
                showDialog = false
                editingFilament = null
            },
            onDismiss = {
                showDialog = false
                editingFilament = null
            }
        )
    }

    importError?.let { err ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Import Failed") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("OK") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Filament Library", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Import JSON")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, "Add filament")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (filaments.isEmpty()) {
                item("filaments-empty") {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    ) {
                        Text(
                            "No filament profiles yet",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Add profiles manually or import a JSON file",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                items(filaments, key = { it.id }) { filament ->
                    FilamentCard(
                        filament = filament,
                        onEdit = {
                            editingFilament = filament
                            showDialog = true
                        },
                        onDelete = { onDelete(filament) },
                        onApply = { onApply(filament) },
                        onSetDefault = { onSetDefault(filament) }
                    )
                }
            }

            // M3 Phase A: Mix Slots section. Always rendered when the manager is
            // wired, regardless of whether the library has any single-component
            // profiles — users can still create mixes from the 4 physical slots.
            if (mixedFilamentManager != null) {
                item("mix-slots-header") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Mix Slots",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // "+ Mix slot" row — affordance to open the dialog.
                item("mix-slot-add") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
                            editingMix = null
                            showMixDialog = true
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Add Mix slot",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                )
                                Text(
                                    "Two-component blend across physical slots",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }

                if (projectMixes.isEmpty() && libraryMixes.isEmpty()) {
                    item("mix-slots-empty") {
                        Text(
                            "No mix slots yet — tap Add to create one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                }

                if (projectMixes.isNotEmpty()) {
                    item("mix-slots-project-label") {
                        Text(
                            "This project",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                    itemsIndexed(projectMixes, key = { _, r -> "p-${r.id}" }) { _, row ->
                        MixSlotCard(
                            row = row,
                            physicalColours = (0..3).map { i ->
                                val hex = extruderPresets.firstOrNull { it.index == i }?.color
                                    ?.takeIf { it.isNotBlank() }
                                    ?: ExtruderPreset.DEFAULT_COLORS[i]
                                parseHexColorOrDefault(hex)
                            },
                            inLibrary = row.inLibrary,
                            onEdit = {
                                editingMix = row
                                showMixDialog = true
                            },
                            onDelete = { mixedFilamentManager.delete(row.id) },
                            onToggleLibrary = {
                                if (row.inLibrary) mixedFilamentManager.demoteFromLibrary(row.id)
                                else mixedFilamentManager.promoteToLibrary(row.id)
                            },
                        )
                    }
                }

                // Library-only mixes (rows the user promoted, then either deleted
                // from project — or that came from an earlier session).
                val libraryOnly = libraryMixes.filter { lib ->
                    projectMixes.none { it.id == lib.id }
                }
                if (libraryOnly.isNotEmpty()) {
                    item("mix-slots-library-label") {
                        Text(
                            "Library (★)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                    }
                    itemsIndexed(libraryOnly, key = { _, r -> "l-${r.id}" }) { _, row ->
                        MixSlotCard(
                            row = row,
                            physicalColours = (0..3).map { i ->
                                val hex = extruderPresets.firstOrNull { it.index == i }?.color
                                    ?.takeIf { it.isNotBlank() }
                                    ?: ExtruderPreset.DEFAULT_COLORS[i]
                                parseHexColorOrDefault(hex)
                            },
                            inLibrary = true,
                            onEdit = {
                                editingMix = row
                                showMixDialog = true
                            },
                            onDelete = { mixedFilamentManager.delete(row.id) },
                            onToggleLibrary = { mixedFilamentManager.demoteFromLibrary(row.id) },
                        )
                    }
                }

                // 2026-06-06 bugfix: the screen's FloatingActionButton floats over the
                // bottom-right of the LazyColumn and obscured the delete icon on the
                // last mix-slot row. Add a sentinel spacer so the last visible row
                // clears the FAB extent (~56dp + 16dp margin).
                item("mix-slots-fab-spacer") {
                    Spacer(Modifier.height(88.dp))
                }
            }
        }
    }
}

/**
 * One mix-slot row in the Filaments library. Shows the two-tone swatch,
 * auto-derived label (e.g. "E1+E3 @ 50%"), a star toggle (promote/demote),
 * Edit and Delete actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MixSlotCard(
    row: MixedFilamentRow,
    physicalColours: List<Color>,
    inLibrary: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleLibrary: () -> Unit,
) {
    val primary = physicalColours.getOrNull(row.componentA - 1) ?: Color.Gray
    val secondary = physicalColours.getOrNull(row.componentB - 1)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        onClick = onEdit,
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MixedSlotSwatch(
                primary = primary,
                secondary = secondary,
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    when (row.distributionMode) {
                        MixedFilamentRow.MixDistributionMode.LAYER_CYCLE -> "Layer alternation"
                        MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS -> "Same-layer dots"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onToggleLibrary) {
                Icon(
                    if (inLibrary) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (inLibrary) "Remove from library" else "Promote to library",
                    tint = if (inLibrary) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete mix slot",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Parses "#RRGGBB" or "#AARRGGBB" hex strings into a Compose [Color]. Returns
 * a mid-grey fallback when the input is malformed. Used by the Mix Slot UI
 * to convert ExtruderPreset.color hex strings to the Color values needed by
 * CreateMixSlotDialog + MixedSlotSwatch.
 */
internal fun parseHexColorOrDefault(hex: String, fallback: Color = Color(0xFF808080)): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        fallback
    }
}

@Composable
private fun FilamentCard(
    filament: FilamentProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onApply: () -> Unit,
    onSetDefault: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(filament.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        filament.material,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (filament.isDefault) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            "Default",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MiniStat("Nozzle", "${filament.nozzleTemp}\u00B0C")
                MiniStat("Bed", "${filament.bedTemp}\u00B0C")
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onApply) { Text("Apply") }
                if (!filament.isDefault) {
                    TextButton(onClick = onSetDefault) { Text("Set Default") }
                }
                TextButton(onClick = onEdit) { Text("Edit") }
                if (!filament.isDefault) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilamentEditDialog(
    filament: FilamentProfile?,
    onSave: (FilamentProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(filament?.name ?: "") }
    var material by remember { mutableStateOf(filament?.material ?: "PLA") }
    var nozzleTemp by remember { mutableStateOf(filament?.nozzleTemp?.toString() ?: "220") }
    var bedTemp by remember { mutableStateOf(filament?.bedTemp?.toString() ?: "55") }
    var retractLength by remember { mutableStateOf(filament?.retractLength?.toString() ?: "0.8") }
    var retractSpeed by remember { mutableStateOf(filament?.retractSpeed?.toInt()?.toString() ?: "45") }

    // F91: extended fields. Empty string == "use library default" (stored as null).
    var nozzleTempInitial by remember { mutableStateOf(filament?.nozzleTempInitialLayer?.toString() ?: "") }
    var bedTempInitial by remember { mutableStateOf(filament?.bedTempInitialLayer?.toString() ?: "") }
    var flowRatio by remember { mutableStateOf(filament?.flowRatio?.toString() ?: "") }
    var maxVolumetricSpeed by remember { mutableStateOf(filament?.maxVolumetricSpeed?.toString() ?: "") }
    var filamentCost by remember { mutableStateOf(filament?.filamentCost?.toString() ?: "") }
    var fanMinSpeed by remember { mutableStateOf(filament?.fanMinSpeed?.toString() ?: "") }
    var fanMaxSpeed by remember { mutableStateOf(filament?.fanMaxSpeed?.toString() ?: "") }
    var overhangFanSpeed by remember { mutableStateOf(filament?.overhangFanSpeed?.toString() ?: "") }
    var additionalCoolingFanSpeed by remember { mutableStateOf(filament?.additionalCoolingFanSpeed?.toString() ?: "") }
    var slowDownLayerTime by remember { mutableStateOf(filament?.slowDownLayerTime?.toString() ?: "") }
    var slowDownMinSpeed by remember { mutableStateOf(filament?.slowDownMinSpeed?.toString() ?: "") }
    var closeFanFirstLayers by remember { mutableStateOf(filament?.closeFanFirstLayers?.toString() ?: "") }
    var fullFanSpeedLayer by remember { mutableStateOf(filament?.fullFanSpeedLayer?.toString() ?: "") }
    var enablePressureAdvance by remember { mutableStateOf(filament?.enablePressureAdvance) }
    var pressureAdvance by remember { mutableStateOf(filament?.pressureAdvance?.toString() ?: "") }
    var filamentMinimalPurge by remember { mutableStateOf(filament?.filamentMinimalPurgeOnWipeTower?.toString() ?: "") }

    val materials = listOf("PLA", "PETG", "ABS", "TPU", "ASA", "PA", "PVA")
    var materialExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (filament != null) "Edit Filament" else "Add Filament") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(scrollState)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                ExposedDropdownMenuBox(
                    expanded = materialExpanded,
                    onExpandedChange = { materialExpanded = it }
                ) {
                    OutlinedTextField(
                        value = material,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Material") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = materialExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = materialExpanded,
                        onDismissRequest = { materialExpanded = false }
                    ) {
                        materials.forEach { mat ->
                            DropdownMenuItem(
                                text = { Text(mat) },
                                onClick = {
                                    material = mat
                                    materialExpanded = false
                                }
                            )
                        }
                    }
                }

                SectionLabel("Temperatures")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Nozzle \u00B0C", nozzleTemp, Modifier.weight(1f)) { nozzleTemp = it }
                    NumberField("Bed \u00B0C", bedTemp, Modifier.weight(1f)) { bedTemp = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionalNumberField("Nozzle initial \u00B0C", nozzleTempInitial, Modifier.weight(1f)) { nozzleTempInitial = it }
                    OptionalNumberField("Bed initial \u00B0C", bedTempInitial, Modifier.weight(1f)) { bedTempInitial = it }
                }

                SectionLabel("Retraction")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Retract (mm)", retractLength, Modifier.weight(1f)) { retractLength = it }
                    NumberField("Retract spd", retractSpeed, Modifier.weight(1f)) { retractSpeed = it }
                }

                SectionLabel("Flow & limits")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionalDecimalField("Flow ratio", flowRatio, Modifier.weight(1f)) { flowRatio = it }
                    OptionalDecimalField("Max vol. (mm\u00B3/s)", maxVolumetricSpeed, Modifier.weight(1f)) { maxVolumetricSpeed = it }
                }
                OptionalDecimalField("Cost (per kg)", filamentCost, Modifier.fillMaxWidth()) { filamentCost = it }

                SectionLabel("Cooling")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionalNumberField("Fan min %", fanMinSpeed, Modifier.weight(1f)) { fanMinSpeed = it }
                    OptionalNumberField("Fan max %", fanMaxSpeed, Modifier.weight(1f)) { fanMaxSpeed = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionalNumberField("Overhang fan %", overhangFanSpeed, Modifier.weight(1f)) { overhangFanSpeed = it }
                    OptionalNumberField("Aux cool fan %", additionalCoolingFanSpeed, Modifier.weight(1f)) { additionalCoolingFanSpeed = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionalDecimalField("Slowdown layer time (s)", slowDownLayerTime, Modifier.weight(1f)) { slowDownLayerTime = it }
                    OptionalDecimalField("Slowdown min spd", slowDownMinSpeed, Modifier.weight(1f)) { slowDownMinSpeed = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionalNumberField("Close fan first N", closeFanFirstLayers, Modifier.weight(1f)) { closeFanFirstLayers = it }
                    OptionalNumberField("Full fan layer", fullFanSpeedLayer, Modifier.weight(1f)) { fullFanSpeedLayer = it }
                }

                SectionLabel("Pressure advance")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enablePressureAdvance == true,
                        onCheckedChange = { enablePressureAdvance = if (it) true else null }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Enable", modifier = Modifier.weight(1f))
                }
                OptionalDecimalField("Pressure advance value", pressureAdvance, Modifier.fillMaxWidth()) { pressureAdvance = it }

                SectionLabel("Wipe tower")
                OptionalDecimalField("Minimal purge (mm\u00B3)", filamentMinimalPurge, Modifier.fillMaxWidth()) { filamentMinimalPurge = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val profile = (filament ?: FilamentProfile(
                        name = "", material = "", nozzleTemp = 0, bedTemp = 0,
                        retractLength = 0f, retractSpeed = 0f
                    )).copy(
                        name = name.ifBlank { "$material Filament" },
                        material = material,
                        nozzleTemp = nozzleTemp.toIntOrNull() ?: 220,
                        bedTemp = bedTemp.toIntOrNull() ?: 55,
                        retractLength = retractLength.toFloatLenient() ?: 0.8f,
                        retractSpeed = retractSpeed.toFloatLenient() ?: 45f,
                        nozzleTempInitialLayer = nozzleTempInitial.toIntOrNull(),
                        bedTempInitialLayer = bedTempInitial.toIntOrNull(),
                        flowRatio = flowRatio.toFloatLenient(),
                        maxVolumetricSpeed = maxVolumetricSpeed.toFloatLenient(),
                        filamentCost = filamentCost.toFloatLenient(),
                        fanMinSpeed = fanMinSpeed.toIntOrNull(),
                        fanMaxSpeed = fanMaxSpeed.toIntOrNull(),
                        overhangFanSpeed = overhangFanSpeed.toIntOrNull(),
                        additionalCoolingFanSpeed = additionalCoolingFanSpeed.toIntOrNull(),
                        slowDownLayerTime = slowDownLayerTime.toFloatLenient(),
                        slowDownMinSpeed = slowDownMinSpeed.toFloatLenient(),
                        closeFanFirstLayers = closeFanFirstLayers.toIntOrNull(),
                        fullFanSpeedLayer = fullFanSpeedLayer.toIntOrNull(),
                        enablePressureAdvance = enablePressureAdvance,
                        pressureAdvance = pressureAdvance.toFloatLenient(),
                        filamentMinimalPurgeOnWipeTower = filamentMinimalPurge.toFloatLenient(),
                        isDefault = filament?.isDefault ?: false,
                    )
                    onSave(profile)
                },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}

@Composable
private fun DecimalField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true
    )
}

@Composable
private fun OptionalNumberField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("default") },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}

@Composable
private fun OptionalDecimalField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("default") },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true
    )
}

/**
 * Parses filament profiles from JSON. Supports:
 *  1. Array of simple objects: [{ "name": "...", "material": "PLA", "nozzle_temp": 220, ... }]
 *  2. Wrapper object: { "filaments": [...] }
 *  3. Single Bambu/OrcaSlicer profile object: { "type": "filament", "name": "...", "nozzle_temperature": ["220"], ... }
 *  4. Array of Bambu/OrcaSlicer profile objects
 *
 * Simple keys accept camelCase or snake_case. Bambu/Orca keys use arrays — first non-nil value is used.
 */
internal fun parseFilamentJson(json: String, context: Context? = null): List<FilamentProfile> {
    val trimmed = json.trim()

    // Try array first
    val arr: JSONArray? = try { JSONArray(trimmed) } catch (_: JSONException) { null }
    if (arr != null) {
        val results = mutableListOf<FilamentProfile>()
        for (i in 0 until arr.length()) {
            val obj = try { arr.getJSONObject(i) } catch (_: Exception) { null } ?: continue
            parseOneFilamentObject(resolveInheritsIfPossible(obj, context))?.let { results += it }
        }
        return results
    }

    // Try single object
    val obj: JSONObject? = try { JSONObject(trimmed) } catch (_: JSONException) { null }
        ?: throw Exception("Could not parse file as JSON")

    obj!!

    // Wrapper object with "filaments" array
    if (obj.has("filaments")) {
        val inner = try { obj.getJSONArray("filaments") } catch (_: Exception) {
            throw Exception("\"filaments\" key must be an array")
        }
        val results = mutableListOf<FilamentProfile>()
        for (i in 0 until inner.length()) {
            val item = try { inner.getJSONObject(i) } catch (_: Exception) { null } ?: continue
            parseOneFilamentObject(resolveInheritsIfPossible(item, context))?.let { results += it }
        }
        return results
    }

    // Single Bambu/Orca/simple profile object
    return listOfNotNull(parseOneFilamentObject(resolveInheritsIfPossible(obj, context)))
}

/**
 * F91 follow-up: when [obj] is a Bambu-style profile with an `inherits` key, walk the
 * bundled Bambu profile chain to produce a flattened JSONObject. When [context] is null
 * (unit-test path) or no `inherits` key exists, returns [obj] unchanged.
 */
private fun resolveInheritsIfPossible(obj: JSONObject, context: Context?): JSONObject {
    if (context == null) return obj
    if (!obj.has("inherits")) return obj
    return com.u1.slicer.data.BambuProfileResolver.flatten(context, obj) ?: obj
}

/** Returns null if the object doesn't have enough data to form a profile. */
private fun parseOneFilamentObject(obj: JSONObject): FilamentProfile? {
    val name = obj.optString("name").trim()
    if (name.isBlank()) return null

    // Detect Bambu/OrcaSlicer format: has array-valued keys like nozzle_temperature
    val isBambu = obj.has("nozzle_temperature") || obj.has("filament_type") ||
                  obj.optString("type") == "filament"

    val material: String
    val nozzleTemp: Int
    val bedTemp: Int
    val retractLength: Float
    val retractSpeed: Float

    if (isBambu) {
        material = (extractBambuValue(obj, "filament_type")
            ?: inferMaterialFromName(name)).uppercase()

        nozzleTemp = (extractBambuValue(obj, "nozzle_temperature")
            ?.toIntOrNull() ?: materialNozzleDefault(material))
            .coerceIn(100, 400)

        // Bambu profiles store the actual print-bed target under per-plate-type keys.
        // U1 uses the textured/engineering plate equivalent (hot_plate). Prefer that;
        // fall back to legacy `bed_temperature`, then material default.
        bedTemp = (extractBambuValue(obj, "hot_plate_temp")
            ?: extractBambuValue(obj, "textured_plate_temp")
            ?: extractBambuValue(obj, "bed_temperature"))
            ?.toIntOrNull() ?: materialBedDefault(material)

        retractLength = (extractBambuValue(obj, "filament_retraction_length")
            ?.toFloatOrNull() ?: 0.8f).coerceIn(0f, 10f)

        retractSpeed = (extractBambuValue(obj, "filament_retraction_speed")
            ?.toFloatOrNull() ?: 45f).coerceIn(5f, 150f)
    } else {
        material = obj.optString("material").let { if (it.isBlank()) "PLA" else it }
        nozzleTemp = obj.optInt("nozzle_temp", obj.optInt("nozzleTemp", 220))
        bedTemp = obj.optInt("bed_temp", obj.optInt("bedTemp", 55))
        retractLength = obj.optDouble("retract_length", obj.optDouble("retractLength", 0.8)).toFloat()
        retractSpeed = obj.optDouble("retract_speed", obj.optDouble("retractSpeed", 45.0)).toFloat()
    }

    // F91: extended per-filament tuning fields. Optional — null when absent so library
    // entries imported from older JSON files keep behaving like before.
    val nozzleTempInitial = if (isBambu) extractBambuValue(obj, "nozzle_temperature_initial_layer")?.toIntOrNull()
        else obj.optInt("nozzleTempInitialLayer", obj.optInt("nozzle_temp_initial_layer", Int.MIN_VALUE))
            .let { if (it == Int.MIN_VALUE) null else it }
    val bedTempInitial = if (isBambu)
        (extractBambuValue(obj, "hot_plate_temp_initial_layer")
            ?: extractBambuValue(obj, "textured_plate_temp_initial_layer")
            ?: extractBambuValue(obj, "bed_temperature_initial_layer"))?.toIntOrNull()
        else obj.optInt("bedTempInitialLayer", Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) null else it }
    val flowRatio = if (isBambu) extractBambuValue(obj, "filament_flow_ratio")?.toFloatOrNull()
        else obj.optDouble("flowRatio", Double.NaN).let { if (it.isNaN()) null else it.toFloat() }
    val maxVolumetricSpeed = if (isBambu) extractBambuValue(obj, "filament_max_volumetric_speed")?.toFloatOrNull()
        else obj.optDouble("maxVolumetricSpeed", Double.NaN).let { if (it.isNaN()) null else it.toFloat() }
    val filamentCost = if (isBambu) extractBambuValue(obj, "filament_cost")?.toFloatOrNull()
        else obj.optDouble("filamentCost", Double.NaN).let { if (it.isNaN()) null else it.toFloat() }
    val fanMinSpeed = if (isBambu) extractBambuValue(obj, "fan_min_speed")?.toIntOrNull()
        else obj.optInt("fanMinSpeed", Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) null else it }
    val fanMaxSpeed = if (isBambu) extractBambuValue(obj, "fan_max_speed")?.toIntOrNull()
        else obj.optInt("fanMaxSpeed", Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) null else it }
    val overhangFanSpeed = if (isBambu) extractBambuValue(obj, "overhang_fan_speed")?.toIntOrNull()
        else obj.optInt("overhangFanSpeed", Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) null else it }
    val additionalCoolingFanSpeed = if (isBambu) extractBambuValue(obj, "additional_cooling_fan_speed")?.toIntOrNull()
        else obj.optInt("additionalCoolingFanSpeed", Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) null else it }
    val slowDownLayerTime = if (isBambu) extractBambuValue(obj, "slow_down_layer_time")?.toFloatOrNull()
        else obj.optDouble("slowDownLayerTime", Double.NaN).let { if (it.isNaN()) null else it.toFloat() }
    val slowDownMinSpeed = if (isBambu) extractBambuValue(obj, "slow_down_min_speed")?.toFloatOrNull()
        else obj.optDouble("slowDownMinSpeed", Double.NaN).let { if (it.isNaN()) null else it.toFloat() }
    val closeFanFirstLayers = if (isBambu) extractBambuValue(obj, "close_fan_the_first_x_layers")?.toIntOrNull()
        else obj.optInt("closeFanFirstLayers", Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) null else it }
    val fullFanSpeedLayer = if (isBambu) extractBambuValue(obj, "full_fan_speed_layer")?.toIntOrNull()
        else obj.optInt("fullFanSpeedLayer", Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) null else it }
    val enablePressureAdvance = if (isBambu) extractBambuValue(obj, "enable_pressure_advance")?.let { it == "1" || it.equals("true", ignoreCase = true) }
        else if (obj.has("enablePressureAdvance")) obj.optBoolean("enablePressureAdvance") else null
    val pressureAdvance = if (isBambu) extractBambuValue(obj, "pressure_advance")?.toFloatOrNull()
        else obj.optDouble("pressureAdvance", Double.NaN).let { if (it.isNaN()) null else it.toFloat() }
    val filamentMinimalPurgeOnWipeTower = if (isBambu) extractBambuValue(obj, "filament_minimal_purge_on_wipe_tower")?.toFloatOrNull()
        else obj.optDouble("filamentMinimalPurgeOnWipeTower", Double.NaN).let { if (it.isNaN()) null else it.toFloat() }

    // Density: Bambu profiles store as `filament_density`; simple format uses `density`.
    // Falls back to material-specific default (matches the FilamentProfile data class default).
    val density: Float = if (isBambu)
        extractBambuValue(obj, "filament_density")?.toFloatOrNull() ?: materialDensityDefault(material)
        else obj.optDouble("density", Double.NaN).let { if (it.isNaN()) materialDensityDefault(material) else it.toFloat() }

    // Colour: Bambu profiles sometimes carry `default_filament_colour`; simple format uses `color`.
    val color: String = if (isBambu)
        extractBambuValue(obj, "default_filament_colour") ?: extractBambuValue(obj, "filament_colour") ?: "#808080"
        else obj.optString("color", "#808080").ifBlank { "#808080" }

    return FilamentProfile(
        name = name,
        material = material,
        nozzleTemp = nozzleTemp,
        bedTemp = bedTemp,
        retractLength = retractLength,
        retractSpeed = retractSpeed,
        color = color,
        density = density,
        nozzleTempInitialLayer = nozzleTempInitial,
        bedTempInitialLayer = bedTempInitial,
        flowRatio = flowRatio,
        maxVolumetricSpeed = maxVolumetricSpeed,
        filamentCost = filamentCost,
        fanMinSpeed = fanMinSpeed,
        fanMaxSpeed = fanMaxSpeed,
        overhangFanSpeed = overhangFanSpeed,
        additionalCoolingFanSpeed = additionalCoolingFanSpeed,
        slowDownLayerTime = slowDownLayerTime,
        slowDownMinSpeed = slowDownMinSpeed,
        closeFanFirstLayers = closeFanFirstLayers,
        fullFanSpeedLayer = fullFanSpeedLayer,
        enablePressureAdvance = enablePressureAdvance,
        pressureAdvance = pressureAdvance,
        filamentMinimalPurgeOnWipeTower = filamentMinimalPurgeOnWipeTower,
    )
}

/**
 * Extracts the first non-nil string value from a key that may hold a string or a JSON array.
 * Returns null if the key is absent or all values are "nil".
 */
private fun extractBambuValue(obj: JSONObject, key: String): String? {
    if (!obj.has(key)) return null
    return when (val v = obj.opt(key)) {
        is JSONArray -> {
            for (i in 0 until v.length()) {
                val s = v.optString(i, "nil").trim()
                if (s.isNotEmpty() && s != "nil") return s
            }
            null
        }
        null -> null
        else -> v.toString().trim().takeIf { it.isNotEmpty() && it != "nil" }
    }
}

private fun inferMaterialFromName(name: String): String {
    val upper = name.uppercase()
    return when {
        "PETG" in upper -> "PETG"
        "PVA" in upper -> "PVA"
        "PLA" in upper -> "PLA"
        "ASA" in upper -> "ASA"
        "ABS" in upper -> "ABS"
        "TPU" in upper -> "TPU"
        "TPE" in upper -> "TPU"
        "PA" in upper || "NYLON" in upper -> "PA"
        "PC" in upper -> "PC"
        else -> "PLA"
    }
}

// Temperatures from Snapmaker/OrcaSlicer Generic profiles
private fun materialNozzleDefault(material: String) = when (material) {
    "PETG" -> 235; "ABS" -> 270; "ASA" -> 260; "PA" -> 260; "TPU" -> 225; "PVA" -> 210; else -> 220
}

private fun materialBedDefault(material: String) = when (material) {
    "PETG" -> 70; "ABS" -> 90; "ASA" -> 90; "PA" -> 100; "TPU" -> 60; "PVA" -> 65; else -> 55
}

private fun materialDensityDefault(material: String): Float = when (material) {
    "PETG" -> 1.27f; "ABS" -> 1.04f; "ASA" -> 1.07f; "PA" -> 1.14f
    "TPU" -> 1.21f; "PVA" -> 1.23f; "PC" -> 1.20f; else -> 1.24f  // PLA
}
