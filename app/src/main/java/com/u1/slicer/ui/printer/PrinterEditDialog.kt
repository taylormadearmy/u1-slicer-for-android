package com.u1.slicer.ui.printer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterEditDialog(
    existing: Printer?,
    onSave: (
        nickname: String,
        kind: PrinterKind,
        url: String,
        bambuIp: String,
        bambuAccessCode: String,
        bambuSerial: String,
        bambuModel: BambuModel,
    ) -> Unit,
    onTest: suspend (
        kind: PrinterKind,
        url: String,
        bambuIp: String,
        bambuAccessCode: String,
        bambuSerial: String,
        bambuModel: BambuModel,
    ) -> String?,
    onDismiss: () -> Unit,
    bambuEnabled: Boolean = false,
) {
    var nickname by remember { mutableStateOf(existing?.nickname ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: PrinterKind.MOONRAKER) }
    var url by remember { mutableStateOf(existing?.moonrakerUrl ?: "") }
    var bambuIp by remember { mutableStateOf(existing?.bambu?.ip ?: "") }
    var bambuAccessCode by remember { mutableStateOf(existing?.bambu?.accessCode ?: "") }
    var bambuSerial by remember { mutableStateOf(existing?.bambu?.serial ?: "") }
    var bambuModel by remember { mutableStateOf(existing?.bambu?.model ?: BambuModel.P1S) }
    var modelExpanded by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canChangeKind = canChangePrinterKind(existing)

    val canSave = when (kind) {
        PrinterKind.MOONRAKER -> url.isNotBlank()
        PrinterKind.BAMBU_LAN -> bambuIp.isNotBlank() && bambuAccessCode.isNotBlank() && bambuSerial.isNotBlank()
    }
    val canTest = when (kind) {
        PrinterKind.MOONRAKER -> url.isNotBlank()
        PrinterKind.BAMBU_LAN -> bambuIp.isNotBlank() && bambuAccessCode.isNotBlank() && bambuSerial.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add printer" else "Edit printer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("Nickname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = kind == PrinterKind.MOONRAKER,
                        enabled = canChangeKind,
                        onClick = { kind = PrinterKind.MOONRAKER; testResult = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text("Moonraker")
                    }
                    SegmentedButton(
                        selected = kind == PrinterKind.BAMBU_LAN,
                        enabled = canChangeKind && bambuEnabled,
                        onClick = { kind = PrinterKind.BAMBU_LAN; testResult = null },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(if (bambuEnabled) "Bambu LAN" else "Bambu (off)")
                    }
                }

                if (!canChangeKind) {
                    Text(
                        "Provider type is fixed after creation. Add a new printer to try another connection type without changing your stable setup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }

                if (kind == PrinterKind.BAMBU_LAN) {
                    Text(
                        "Use the printer's LAN-only access code and serial number. Upload and print support depends on printer firmware.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }

                if (kind == PrinterKind.MOONRAKER) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; testResult = null },
                        label = { Text("Moonraker URL") },
                        placeholder = { Text("http://192.168.1.50") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = bambuModel.name.replace('_', ' '),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Bambu model") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        DropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false },
                        ) {
                            BambuModel.entries.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.name.replace('_', ' ')) },
                                    onClick = {
                                        bambuModel = model
                                        modelExpanded = false
                                        testResult = null
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = bambuIp,
                        onValueChange = { bambuIp = it; testResult = null },
                        label = { Text("Printer IP") },
                        placeholder = { Text("192.168.1.88") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = bambuAccessCode,
                        onValueChange = { bambuAccessCode = it; testResult = null },
                        label = { Text("Access code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = bambuSerial,
                        onValueChange = { bambuSerial = it; testResult = null },
                        label = { Text("Serial") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row {
                    TextButton(
                        enabled = !testing && canTest,
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = onTest(
                                    kind,
                                    url,
                                    bambuIp,
                                    bambuAccessCode,
                                    bambuSerial,
                                    bambuModel,
                                ) ?: "OK"
                                testing = false
                            }
                        },
                    ) {
                        Text(if (testing) "Testing..." else "Test connection")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (testResult != null) {
                        Text(
                            testResult!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (testResult == "OK") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        nickname,
                        kind,
                        url,
                        bambuIp,
                        bambuAccessCode,
                        bambuSerial,
                        bambuModel,
                    )
                    onDismiss()
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun canChangePrinterKind(existing: Printer?): Boolean = existing == null
