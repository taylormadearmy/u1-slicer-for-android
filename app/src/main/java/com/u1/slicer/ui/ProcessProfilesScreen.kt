package com.u1.slicer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u1.slicer.data.ProcessProfile

/**
 * F87: list of imported OrcaSlicer process profiles.
 * - Import JSON via the action bar.
 * - Tap a profile to make it the active selection (slicing uses its keys).
 * - Long-press / Edit menu to rename or delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessProfilesScreen(
    profiles: List<ProcessProfile>,
    activeId: String?,
    onImport: (json: String, fallbackName: String) -> Unit,
    onSetActive: (String?) -> Unit,
    onRename: (id: String, name: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var importError by remember { mutableStateOf<String?>(null) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: throw Exception("Could not read file")
            // Derive a fallback name from the file (best effort).
            val displayName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                ?: "Imported profile"
            onImport(json, displayName)
        } catch (e: Exception) {
            importError = e.message ?: "Import failed"
        }
    }

    importError?.let { err ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Import failed") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } }
        )
    }

    renamingId?.let { id ->
        val profile = profiles.firstOrNull { it.id == id }
        if (profile != null) {
            RenameDialog(
                initialName = profile.name,
                onDismiss = { renamingId = null },
                onConfirm = { newName ->
                    onRename(id, newName)
                    renamingId = null
                }
            )
        } else {
            renamingId = null
        }
    }

    deletingId?.let { id ->
        val profile = profiles.firstOrNull { it.id == id }
        if (profile != null) {
            AlertDialog(
                onDismissRequest = { deletingId = null },
                title = { Text("Delete profile?") },
                text = { Text("Remove \"${profile.name}\" from the library?") },
                confirmButton = {
                    TextButton(onClick = {
                        onDelete(id)
                        deletingId = null
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } }
            )
        } else {
            deletingId = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Process Profiles", fontWeight = FontWeight.Bold) },
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
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No process profiles imported",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap the folder icon to import an OrcaSlicer .orca_process / .json file",
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
                item {
                    UseFileRow(active = activeId == null, onClick = { onSetActive(null) })
                }
                items(profiles, key = { it.id }) { profile ->
                    ProcessProfileCard(
                        profile = profile,
                        isActive = profile.id == activeId,
                        onTap = { onSetActive(profile.id) },
                        onRename = { renamingId = profile.id },
                        onDelete = { deletingId = profile.id }
                    )
                }
            }
        }
    }
}

@Composable
private fun UseFileRow(active: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = active, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Use file's process settings", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Slicer uses the file's embedded profile or Snapmaker defaults",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ProcessProfileCard(
    profile: ProcessProfile,
    isActive: Boolean,
    onTap: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isActive, onClick = onTap)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    val layer = profile.keys["layer_height"]?.toString()
                    val walls = profile.keys["wall_loops"]?.toString()
                    val infill = profile.keys["sparse_infill_density"]?.toString()
                    val summary = listOfNotNull(
                        layer?.let { "Layer $it mm" },
                        walls?.let { "${it} walls" },
                        infill?.let { "$it infill" }
                    ).joinToString(" · ")
                    if (summary.isNotEmpty()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        "${profile.keys.size} keys",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename profile") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotEmpty()
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

