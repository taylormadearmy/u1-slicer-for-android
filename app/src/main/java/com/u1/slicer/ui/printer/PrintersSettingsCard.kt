package com.u1.slicer.ui.printer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.HostType
import com.u1.slicer.machine.PrinterWizardViewModel
import com.u1.slicer.ui.machine.PrinterWizardScreen
import com.u1.slicer.ui.documentDisplayName
import java.io.InputStream
import kotlinx.coroutines.launch

private enum class AddPrinterFlow { CHOOSE, U1, BAMBU, CC1, CATALOGUE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintersSettingsCard(
    printers: List<Printer>,
    activeId: String?,
    onAdd: (Printer) -> Unit,
    onEdit: (Printer) -> Unit,
    onDelete: (id: String) -> Unit,
    onTestConnection: suspend (Printer) -> String?,
    bambuEnabled: Boolean = false,
    createPrinterWizardViewModel: (() -> PrinterWizardViewModel)? = null,
    onImportOrcaPrinter: suspend (InputStream, String) -> Unit = { _, _ -> error("Profile import is unavailable") },
    experimentalPrinterSupportEnabled: Boolean = bambuEnabled,
    onExperimentalPrinterSupportEnabledChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Printer?>(null) }
    var addFlow by remember { mutableStateOf<AddPrinterFlow?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            try {
                val name = context.documentDisplayName(uri) ?: "profile.orca_printer"
                context.contentResolver.openInputStream(uri)?.use { input ->
                    onImportOrcaPrinter(input, name)
                } ?: error("Could not read Orca profile bundle.")
                addFlow = null
            } catch (error: Exception) {
                importError = error.message ?: "Orca profile import failed."
            } finally {
                importing = false
            }
        }
    }
    val launchAddChoice: (AddPrinterChoice) -> Unit = { choice ->
        when (choice) {
            AddPrinterChoice.SNAPMAKER_U1 -> addFlow = AddPrinterFlow.U1
            AddPrinterChoice.BAMBU_LAN -> addFlow = AddPrinterFlow.BAMBU
            AddPrinterChoice.ELEGOO_CC1 -> addFlow = AddPrinterFlow.CC1
            AddPrinterChoice.ORCA_CATALOGUE -> addFlow = AddPrinterFlow.CATALOGUE
            AddPrinterChoice.ORCA_IMPORT -> {
                addFlow = null
                importLauncher.launch(arrayOf(
                    "application/zip",
                    "application/octet-stream",
                    "*/*",
                ))
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Printers",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { addFlow = AddPrinterFlow.CHOOSE }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Choose your machine and its connection protocol. Other machines require an Orca machine profile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(8.dp))
            printers.forEach { printer ->
                val isActive = printer.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = printer }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            printer.nickname + (if (isActive) "  (active)" else ""),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            buildPrinterSubtitle(printer),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(onClick = { editing = printer }) {
                        Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { onDelete(printer.id) }) {
                        Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(20.dp))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            }
        }
    }

    if (addFlow == AddPrinterFlow.CHOOSE) {
        AlertDialog(
            onDismissRequest = { addFlow = null },
            title = { Text("Add printer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddPrinterChoiceButton(
                        title = "Snapmaker U1",
                        supportingText = "Recommended for this app",
                        onClick = { launchAddChoice(AddPrinterChoice.SNAPMAKER_U1) },
                    )
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Experimental printer support",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "Show Bambu LAN and other Orca printer profiles.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = experimentalPrinterSupportEnabled,
                            onCheckedChange = onExperimentalPrinterSupportEnabledChange,
                        )
                    }
                    if (experimentalPrinterSupportEnabled) {
                        Text(
                            "Third-party printer support is experimental. Review generated G-code " +
                                "and use upload-only testing first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        visibleAddPrinterChoices(experimentalEnabled = true)
                            .drop(1)
                            .forEach { choice ->
                                when (choice) {
                                    AddPrinterChoice.SNAPMAKER_U1 -> Unit
                                    AddPrinterChoice.BAMBU_LAN -> AddPrinterChoiceButton(
                                        title = "Bambu Lab (LAN)",
                                        supportingText = "Experimental local-network connection",
                                        onClick = { launchAddChoice(AddPrinterChoice.BAMBU_LAN) },
                                    )
                                    AddPrinterChoice.ELEGOO_CC1 -> AddPrinterChoiceButton(
                                        title = "Elegoo Centauri Carbon 1 (beta)",
                                        supportingText = "Experimental CC1 connection and built-in machine profile",
                                        onClick = { launchAddChoice(AddPrinterChoice.ELEGOO_CC1) },
                                    )
                                    AddPrinterChoice.ORCA_CATALOGUE -> AddPrinterChoiceButton(
                                        title = "Other printer from Orca catalogue",
                                        supportingText = "Experimental profiles for supported printers",
                                        onClick = { launchAddChoice(AddPrinterChoice.ORCA_CATALOGUE) },
                                    )
                                    AddPrinterChoice.ORCA_IMPORT -> AddPrinterChoiceButton(
                                        title = "Import .orca_printer bundle",
                                        supportingText = "Experimental profile import",
                                        enabled = !importing,
                                        onClick = { launchAddChoice(AddPrinterChoice.ORCA_IMPORT) },
                                    )
                                }
                            }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { addFlow = null }) { Text("Cancel") }
            },
        )
    }
    if (addFlow == AddPrinterFlow.U1 || addFlow == AddPrinterFlow.BAMBU || addFlow == AddPrinterFlow.CC1) {
        val initialKind = when (addFlow) {
            AddPrinterFlow.BAMBU -> PrinterKind.BAMBU_LAN
            AddPrinterFlow.CC1 -> PrinterKind.ELEGOO_CC1
            else -> PrinterKind.MOONRAKER
        }
        PrinterEditDialog(
            existing = null,
            onSave = onAdd,
            onTest = onTestConnection,
            initialKind = initialKind,
            bambuEnabled = experimentalPrinterSupportEnabled,
            onDismiss = { addFlow = null },
        )
    }
    if (addFlow == AddPrinterFlow.CATALOGUE) {
        val wizard = remember { requireNotNull(createPrinterWizardViewModel)() }
        PrinterWizardScreen(viewModel = wizard, onDismiss = { addFlow = null })
    }
    importError?.let { error ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Import failed") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("OK") }
            },
        )
    }
    editing?.let { existing ->
        PrinterEditDialog(
            existing = existing,
            onSave = onEdit,
            onTest = onTestConnection,
            bambuEnabled = experimentalPrinterSupportEnabled,
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun AddPrinterChoiceButton(
    title: String,
    supportingText: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

internal fun buildPrinterSubtitle(printer: Printer): String = when (printer.kind) {
    PrinterKind.MOONRAKER -> printer.moonrakerUrl.ifBlank { "(no URL set)" }
    PrinterKind.ELEGOO_CC1 -> "Centauri Carbon 1 - ${printer.host?.url.orEmpty()}"
    PrinterKind.OCTOPRINT -> "OctoPrint - ${printer.host?.url.orEmpty()}"
    PrinterKind.BAMBU_LAN -> {
        val model = printer.bambu?.model?.name?.replace('_', ' ').orEmpty()
        val ip = printer.bambu?.ip.orEmpty()
        val details = listOf(model, ip).filter { it.isNotBlank() }.joinToString(" - ")
        if (details.isBlank()) "Bambu LAN" else details
    }
}
