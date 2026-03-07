package com.u1.slicer.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.u1.slicer.data.FilamentProfile
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
    onImport: (List<FilamentProfile>) -> Unit,
    onBack: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var editingFilament by remember { mutableStateOf<FilamentProfile?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: throw Exception("Could not read file")
            val profiles = parseFilamentJson(json)
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
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }) {
                        Icon(Icons.Default.FolderOpen, "Import JSON")
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
        if (filaments.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filaments, key = { it.id }) { filament ->
                    FilamentCard(
                        filament = filament,
                        onEdit = {
                            editingFilament = filament
                            showDialog = true
                        },
                        onDelete = { onDelete(filament) },
                        onApply = { onApply(filament) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilamentCard(
    filament: FilamentProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onApply: () -> Unit
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
                MiniStat("Speed", "${filament.printSpeed.toInt()} mm/s")
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onApply) { Text("Apply") }
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
    var nozzleTemp by remember { mutableStateOf(filament?.nozzleTemp?.toString() ?: "210") }
    var bedTemp by remember { mutableStateOf(filament?.bedTemp?.toString() ?: "60") }
    var printSpeed by remember { mutableStateOf(filament?.printSpeed?.toInt()?.toString() ?: "60") }
    var retractLength by remember { mutableStateOf(filament?.retractLength?.toString() ?: "0.8") }
    var retractSpeed by remember { mutableStateOf(filament?.retractSpeed?.toInt()?.toString() ?: "45") }

    val materials = listOf("PLA", "PETG", "ABS", "TPU", "ASA", "PA", "PVA")
    var materialExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (filament != null) "Edit Filament" else "Add Filament") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nozzleTemp,
                        onValueChange = { nozzleTemp = it },
                        label = { Text("Nozzle \u00B0C") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = bedTemp,
                        onValueChange = { bedTemp = it },
                        label = { Text("Bed \u00B0C") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = printSpeed,
                    onValueChange = { printSpeed = it },
                    label = { Text("Print Speed (mm/s)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = retractLength,
                        onValueChange = { retractLength = it },
                        label = { Text("Retract (mm)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = retractSpeed,
                        onValueChange = { retractSpeed = it },
                        label = { Text("Retract spd") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val profile = (filament ?: FilamentProfile(
                        name = "", material = "", nozzleTemp = 0, bedTemp = 0,
                        printSpeed = 0f, retractLength = 0f, retractSpeed = 0f
                    )).copy(
                        name = name.ifBlank { "$material Filament" },
                        material = material,
                        nozzleTemp = nozzleTemp.toIntOrNull() ?: 210,
                        bedTemp = bedTemp.toIntOrNull() ?: 60,
                        printSpeed = printSpeed.toFloatOrNull() ?: 60f,
                        retractLength = retractLength.toFloatOrNull() ?: 0.8f,
                        retractSpeed = retractSpeed.toFloatOrNull() ?: 45f,
                        isDefault = false
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

/**
 * Parses filament profiles from JSON. Supports:
 *  1. Array of simple objects: [{ "name": "...", "material": "PLA", "nozzle_temp": 220, ... }]
 *  2. Wrapper object: { "filaments": [...] }
 *  3. Single Bambu/OrcaSlicer profile object: { "type": "filament", "name": "...", "nozzle_temperature": ["220"], ... }
 *  4. Array of Bambu/OrcaSlicer profile objects
 *
 * Simple keys accept camelCase or snake_case. Bambu/Orca keys use arrays — first non-nil value is used.
 */
internal fun parseFilamentJson(json: String): List<FilamentProfile> {
    val trimmed = json.trim()

    // Try array first
    val arr: JSONArray? = try { JSONArray(trimmed) } catch (_: JSONException) { null }
    if (arr != null) {
        val results = mutableListOf<FilamentProfile>()
        for (i in 0 until arr.length()) {
            val obj = try { arr.getJSONObject(i) } catch (_: Exception) { null } ?: continue
            parseOneFilamentObject(obj)?.let { results += it }
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
            parseOneFilamentObject(item)?.let { results += it }
        }
        return results
    }

    // Single Bambu/Orca/simple profile object
    return listOfNotNull(parseOneFilamentObject(obj))
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
    val printSpeed: Float
    val retractLength: Float
    val retractSpeed: Float

    if (isBambu) {
        material = (extractBambuValue(obj, "filament_type")
            ?: inferMaterialFromName(name)).uppercase()

        nozzleTemp = (extractBambuValue(obj, "nozzle_temperature")
            ?.toIntOrNull() ?: materialNozzleDefault(material))
            .coerceIn(100, 400)

        bedTemp = (extractBambuValue(obj, "bed_temperature")
            ?: extractBambuValue(obj, "cool_plate_temp")
            ?.toIntOrNull()?.let { null }  // cool_plate_temp is not bed temp
            .let { extractBambuValue(obj, "bed_temperature") })
            ?.toIntOrNull() ?: materialBedDefault(material)

        // Derive print speed from volumetric speed (mm³/s → mm/s at 0.4mm nozzle, 0.2mm layer)
        val volSpeed = extractBambuValue(obj, "filament_max_volumetric_speed")?.toDoubleOrNull()
        printSpeed = if (volSpeed != null && volSpeed > 0) {
            (volSpeed / 0.08).toFloat().coerceIn(30f, 500f)
        } else {
            materialSpeedDefault(material)
        }

        retractLength = (extractBambuValue(obj, "filament_retraction_length")
            ?.toFloatOrNull() ?: 0.8f).coerceIn(0f, 10f)

        retractSpeed = (extractBambuValue(obj, "filament_retraction_speed")
            ?.toFloatOrNull() ?: 45f).coerceIn(5f, 150f)
    } else {
        material = obj.optString("material").let { if (it.isBlank()) "PLA" else it }
        nozzleTemp = obj.optInt("nozzle_temp", obj.optInt("nozzleTemp", 210))
        bedTemp = obj.optInt("bed_temp", obj.optInt("bedTemp", 60))
        printSpeed = obj.optDouble("print_speed", obj.optDouble("printSpeed", 60.0)).toFloat()
        retractLength = obj.optDouble("retract_length", obj.optDouble("retractLength", 0.8)).toFloat()
        retractSpeed = obj.optDouble("retract_speed", obj.optDouble("retractSpeed", 45.0)).toFloat()
    }

    return FilamentProfile(
        name = name,
        material = material,
        nozzleTemp = nozzleTemp,
        bedTemp = bedTemp,
        printSpeed = printSpeed,
        retractLength = retractLength,
        retractSpeed = retractSpeed
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

private fun materialNozzleDefault(material: String) = when (material) {
    "PETG" -> 235; "ABS" -> 245; "ASA" -> 250; "PA" -> 260; "TPU" -> 220; "PVA" -> 200; else -> 210
}

private fun materialBedDefault(material: String) = when (material) {
    "PETG" -> 80; "ABS" -> 100; "ASA" -> 100; "PA" -> 80; "TPU" -> 50; "PVA" -> 50; else -> 60
}

private fun materialSpeedDefault(material: String) = when (material) {
    "TPU" -> 30f; "PA" -> 100f; "PVA" -> 30f; "ABS" -> 150f; "ASA" -> 150f; "PETG" -> 150f; else -> 200f
}
