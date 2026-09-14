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
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.HostType
import com.u1.slicer.machine.PrinterWizardViewModel
import com.u1.slicer.ui.machine.PrinterWizardScreen
import com.u1.slicer.ui.documentDisplayName
import java.io.InputStream
import kotlinx.coroutines.launch

private enum class AddPrinterFlow { CHOOSE, U1, BAMBU, CATALOGUE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintersSettingsCard(
    printers: List<Printer>,
    activeId: String?,
    onAdd: (
        nickname: String,
        kind: PrinterKind,
        hostType: HostType,
        url: String,
        apiKey: String,
        username: String,
        password: String,
        bambuIp: String,
        bambuAccessCode: String,
        bambuSerial: String,
        bambuModel: BambuModel,
    ) -> Unit,
    onEdit: (
        id: String,
        nickname: String,
        kind: PrinterKind,
        hostType: HostType,
        url: String,
        apiKey: String,
        username: String,
        password: String,
        bambuIp: String,
        bambuAccessCode: String,
        bambuSerial: String,
        bambuModel: BambuModel,
    ) -> Unit,
    onDelete: (id: String) -> Unit,
    onTestConnection: suspend (
        kind: PrinterKind,
        hostType: HostType,
        url: String,
        apiKey: String,
        username: String,
        password: String,
        bambuIp: String,
        bambuAccessCode: String,
        bambuSerial: String,
        bambuModel: BambuModel,
    ) -> String?,
    createPrinterWizardViewModel: () -> PrinterWizardViewModel,
    onImportOrcaPrinter: suspend (InputStream, String) -> Unit,
    experimentalPrinterSupportAcknowledged: Boolean = false,
    onAcknowledgeExperimentalPrinterSupport: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Printer?>(null) }
    var addFlow by remember { mutableStateOf<AddPrinterFlow?>(null) }
    var pendingExperimentalChoice by remember { mutableStateOf<AddPrinterChoice?>(null) }
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
    val selectAddChoice: (AddPrinterChoice) -> Unit = { choice ->
        when (addPrinterNextStep(choice, experimentalPrinterSupportAcknowledged)) {
            AddPrinterNextStep.Proceed -> launchAddChoice(choice)
            AddPrinterNextStep.RequireAcknowledgement -> pendingExperimentalChoice = choice
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
                "Add a Snapmaker U1 or an experimental third-party printer.",
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
                Column {
                    TextButton(
                        onClick = { selectAddChoice(AddPrinterChoice.SNAPMAKER_U1) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Snapmaker U1", modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "Experimental printer support",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    TextButton(
                        onClick = { selectAddChoice(AddPrinterChoice.BAMBU_LAN) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Bambu Lab (LAN) · Experimental", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { selectAddChoice(AddPrinterChoice.ORCA_CATALOGUE) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Other printer from Orca catalogue · Experimental", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        enabled = !importing,
                        onClick = { selectAddChoice(AddPrinterChoice.ORCA_IMPORT) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Import .orca_printer bundle · Experimental", modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { addFlow = null }) { Text("Cancel") }
            },
        )
    }
    pendingExperimentalChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = { pendingExperimentalChoice = null },
            title = { Text("Experimental printer support") },
            text = {
                Text(
                    "Bambu connections and third-party Orca profiles are experimental. " +
                        "Some models, firmware, and printer features may not work as expected. " +
                        "Review the generated G-code and use upload-only testing before starting a print.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAcknowledgeExperimentalPrinterSupport()
                    pendingExperimentalChoice = null
                    launchAddChoice(choice)
                }) { Text("I understand, continue") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExperimentalChoice = null }) { Text("Cancel") }
            },
        )
    }
    if (addFlow == AddPrinterFlow.U1 || addFlow == AddPrinterFlow.BAMBU) {
        val initialKind = if (addFlow == AddPrinterFlow.BAMBU) PrinterKind.BAMBU_LAN else PrinterKind.MOONRAKER
        PrinterEditDialog(
            existing = null,
            onSave = { nick, kind, hostType, url, apiKey, username, password, bambuIp, bambuAccessCode, bambuSerial, bambuModel ->
                onAdd(nick, kind, hostType, url, apiKey, username, password, bambuIp, bambuAccessCode, bambuSerial, bambuModel)
            },
            onTest = onTestConnection,
            initialKind = initialKind,
            onDismiss = { addFlow = null },
        )
    }
    if (addFlow == AddPrinterFlow.CATALOGUE) {
        val wizard = remember { createPrinterWizardViewModel() }
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
            onSave = { nick, kind, hostType, url, apiKey, username, password, bambuIp, bambuAccessCode, bambuSerial, bambuModel ->
                onEdit(existing.id, nick, kind, hostType, url, apiKey, username, password, bambuIp, bambuAccessCode, bambuSerial, bambuModel)
            },
            onTest = onTestConnection,
            onDismiss = { editing = null },
        )
    }
}

internal fun buildPrinterSubtitle(printer: Printer): String = when (printer.kind) {
    PrinterKind.MOONRAKER -> printer.moonrakerUrl.ifBlank { "(no URL set)" }
    PrinterKind.BAMBU_LAN -> {
        val model = printer.bambu?.model?.name?.replace('_', ' ').orEmpty()
        val ip = printer.bambu?.ip.orEmpty()
        val details = listOf(model, ip).filter { it.isNotBlank() }.joinToString(" - ")
        if (details.isBlank()) "Bambu LAN" else details
    }
}
