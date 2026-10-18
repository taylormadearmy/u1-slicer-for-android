package com.u1.slicer.ui.printer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.MachineKind
import com.u1.slicer.data.PrintHostConfig
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.U1NozzleConfiguration
import com.u1.slicer.data.HostType
import com.u1.slicer.data.defaultHostType
import com.u1.slicer.data.defaultMachineKind
import com.u1.slicer.data.defaultExtruderPresets
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.printer.normalizePrintHostUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun PrinterEditDialog(
    existing: Printer?,
    onSave: (Printer) -> Unit,
    onTest: suspend (Printer) -> String?,
    onDismiss: () -> Unit,
    initialKind: PrinterKind = existing?.kind ?: PrinterKind.MOONRAKER,
    bambuEnabled: Boolean = false,
    experimentalPrinterSupportEnabled: Boolean = bambuEnabled,
    initialHostType: HostType = existing?.hostType
        ?: defaultHostType(initialKind),
) {
    var nickname by remember { mutableStateOf(existing?.nickname.orEmpty()) }
    var u1Nozzles by remember { mutableStateOf(existing?.u1Nozzles ?: U1NozzleConfiguration()) }
    var kind by remember { mutableStateOf(existing?.kind ?: initialKind) }
    var hostType by remember { mutableStateOf(initialHostType) }
    var machine by remember { mutableStateOf(existing?.machine ?: defaultMachineKind(initialKind)) }
    var profile by remember { mutableStateOf(existing?.machineProfile) }
    var url by remember { mutableStateOf(existing?.host?.url ?: existing?.moonrakerUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(existing?.host?.apiKey ?: existing?.apiKey.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var ip by remember { mutableStateOf(existing?.bambu?.ip.orEmpty()) }
    var accessCode by remember { mutableStateOf(existing?.bambu?.accessCode.orEmpty()) }
    var serial by remember { mutableStateOf(existing?.bambu?.serial.orEmpty()) }
    var model by remember { mutableStateOf(existing?.bambu?.model ?: BambuModel.P1S) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draftId = remember { existing?.id ?: UUID.randomUUID().toString() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                profile = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val chars = CharArray(1_048_577)
                        var count = 0
                        while (count < chars.size) {
                            val read = reader.read(chars, count, chars.size - count)
                            if (read < 0) break
                            count += read
                        }
                        require(count <= 1_048_576) { "Machine profile must be smaller than 1 MiB" }
                        String(chars, 0, count)
                    } ?: error("Cannot read profile")
                    GenericMachineProfile.fromJson(text)
                }
                machine = if (profile?.gcodeFlavor == "klipper") MachineKind.GENERIC_KLIPPER else MachineKind.GENERIC_MARLIN
                result = null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                result = error.message ?: "Cannot import machine profile"
            }
        }
    }
    fun draft() = Printer(
        id = draftId,
        nickname = nickname.trim().ifBlank { existing?.nickname ?: "Printer" },
        kind = kind,
        hostType = hostType,
        apiKey = if (kind == PrinterKind.MOONRAKER) apiKey.trim().takeIf { it.isNotBlank() } else existing?.apiKey,
        username = username.trim().takeIf { it.isNotBlank() },
        password = password.takeIf { it.isNotBlank() },
        moonrakerUrl = if (kind == PrinterKind.MOONRAKER) normalizePrintHostUrl(url, hostType) else "",
        bambu = if (kind == PrinterKind.BAMBU_LAN) BambuConfig(ip.trim(), accessCode.trim(), serial.trim().uppercase(java.util.Locale.ROOT), model) else null,
        machine = machine,
        machineProfile = profile.takeIf { machine == MachineKind.GENERIC_KLIPPER || machine == MachineKind.GENERIC_MARLIN },
        machineProfileId = existing?.machineProfileId.takeIf { existing?.machineProfile == profile },
        host = if (kind == PrinterKind.ELEGOO_CC1 || kind == PrinterKind.OCTOPRINT) PrintHostConfig(url.trim(), if (kind == PrinterKind.OCTOPRINT) apiKey.trim() else "") else null,
        extruderPresets = existing?.extruderPresets ?: if (machine == MachineKind.SNAPMAKER_U1) defaultExtruderPresets() else emptyList(),
        u1Nozzles = u1Nozzles,
        selectedWebcamUid = existing?.selectedWebcamUid.takeIf { kind == PrinterKind.MOONRAKER && existing?.moonrakerUrl == com.u1.slicer.network.MoonrakerClient.normalizeUrl(url) },
    )
    val addressValid = if (kind == PrinterKind.BAMBU_LAN) ip.isNotBlank() && accessCode.isNotBlank() && serial.isNotBlank() else url.isNotBlank()
    val canSaveWithoutAddress = canSaveOfflineU1(existing, machine, url)
    val allowExperimentalChoices = existing != null || experimentalPrinterSupportEnabled
    val hostTypes = if (initialKind == PrinterKind.BAMBU_LAN) listOf(HostType.BAMBU_LAN)
        else if (allowExperimentalChoices) listOf(HostType.MOONRAKER, HostType.OCTOPRINT, HostType.PRUSALINK)
        else listOf(HostType.MOONRAKER)
    val connectionKinds = when {
        initialKind == PrinterKind.BAMBU_LAN -> listOf(PrinterKind.BAMBU_LAN)
        initialKind == PrinterKind.ELEGOO_CC1 -> listOf(PrinterKind.ELEGOO_CC1)
        !allowExperimentalChoices -> listOf(PrinterKind.MOONRAKER)
        else -> PrinterKind.entries.filter { it != PrinterKind.BAMBU_LAN }
    }
    val machineKinds = if (allowExperimentalChoices) listOf(MachineKind.SNAPMAKER_U1, MachineKind.GENERIC_KLIPPER, MachineKind.GENERIC_MARLIN)
        else listOf(MachineKind.SNAPMAKER_U1)
    val machineValid = (machine != MachineKind.GENERIC_KLIPPER && machine != MachineKind.GENERIC_MARLIN) || profile != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add printer" else "Edit printer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(nickname, { nickname = it }, label = { Text("Nickname") }, singleLine = true)
                Text("Connects to your printer using the selected host service.", style = MaterialTheme.typography.bodySmall)
                PrinterChoice("Connection", kind, connectionKinds, { it.connectionLabel() }, canChangePrinterKind(existing)) { selected ->
                    kind = selected
                    hostType = when (selected) {
                        PrinterKind.BAMBU_LAN -> HostType.BAMBU_LAN
                        PrinterKind.ELEGOO_CC1 -> HostType.ELEGOO_CC1
                        PrinterKind.OCTOPRINT -> HostType.OCTOPRINT
                        PrinterKind.MOONRAKER -> HostType.MOONRAKER
                    }
                    machine = when (selected) {
                        PrinterKind.BAMBU_LAN -> MachineKind.BAMBU
                        PrinterKind.ELEGOO_CC1 -> MachineKind.ELEGOO_CENTAURI_CARBON
                        PrinterKind.OCTOPRINT -> MachineKind.GENERIC_KLIPPER
                        else -> if (machine == MachineKind.SNAPMAKER_U1 || machine == MachineKind.GENERIC_KLIPPER) machine else MachineKind.GENERIC_KLIPPER
                    }
                    result = null
                }
                if (!canChangePrinterKind(existing)) Text("Add a new printer to change its connection type.", style = MaterialTheme.typography.bodySmall)
                if (kind == PrinterKind.MOONRAKER && machine != MachineKind.SNAPMAKER_U1) {
                    PrinterChoice("Host type", hostType, hostTypes, { it.name }) { hostType = it; result = null }
                }
                if (kind == PrinterKind.MOONRAKER) {
                    PrinterChoice("Machine", machine, machineKinds, {
                        when (it) {
                            MachineKind.SNAPMAKER_U1 -> "Snapmaker U1"
                            MachineKind.GENERIC_MARLIN -> "Generic Marlin"
                            else -> "Generic Klipper"
                        }
                    }) { machine = it; result = null }
                }
                if (machine == MachineKind.SNAPMAKER_U1) {
                    Text("Installed nozzles", style = MaterialTheme.typography.titleSmall)
                    repeat(4) { slot ->
                        PrinterChoice("E${slot + 1} nozzle", u1Nozzles.diameterMm(slot),
                            U1NozzleConfiguration.SUPPORTED_DIAMETERS_MM, { "$it mm" }) {
                            u1Nozzles = u1Nozzles.withDiameter(slot, it)
                        }
                    }
                }
                if (machine == MachineKind.GENERIC_KLIPPER || machine == MachineKind.GENERIC_MARLIN) {
                    Text(profile?.name ?: "Import a resolved Orca machine JSON profile, or a JSON array containing the profile and its parents. Supported profiles use Klipper or Marlin with up to four independent nozzles.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import machine profile") }
                    Text("Start and end templates may call macros that must exist on your printer. Check the profile before starting a print.", style = MaterialTheme.typography.bodySmall)
                }
                if (kind == PrinterKind.ELEGOO_CC1) Text("Centauri Carbon 1 support is beta and has not been validated on hardware. Carbon 2 is not supported.", style = MaterialTheme.typography.bodySmall)
                if (kind == PrinterKind.BAMBU_LAN) {
                    PrinterChoice("Bambu model", model, BambuModel.entries, { it.name.replace('_', ' ') }) { model = it; result = null }
                    OutlinedTextField(ip, { ip = it; result = null }, label = { Text("Printer IP") }, singleLine = true)
                    OutlinedTextField(accessCode, { accessCode = it; result = null }, label = { Text("Access code") }, singleLine = true)
                    OutlinedTextField(serial, { serial = it; result = null }, label = { Text("Serial") }, singleLine = true)
                } else {
                    OutlinedTextField(url, { url = it; result = null }, label = { Text(if (kind == PrinterKind.ELEGOO_CC1) "Printer IP or hostname" else "Printer URL") }, singleLine = true)
                    if (hostType == HostType.OCTOPRINT || hostType == HostType.PRUSALINK) OutlinedTextField(apiKey, { apiKey = it; result = null }, label = { Text("API key (optional)") }, singleLine = true)
                    if (hostType == HostType.PRUSALINK) {
                        OutlinedTextField(username, { username = it; result = null }, label = { Text("Username (optional)") }, singleLine = true)
                        OutlinedTextField(password, { password = it; result = null }, label = { Text("Password (optional)") }, singleLine = true)
                    }
                }
                TextButton(enabled = !testing && addressValid && machineValid, onClick = {
                    testing = true
                    scope.launch {
                        try { result = onTest(draft()) ?: "OK" }
                        catch (error: Exception) { if (error is CancellationException) throw error; result = error.message ?: "Connection failed" }
                        finally { testing = false }
                    }
                }) { Text(if (testing) "Testing..." else "Test connection") }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = (addressValid || canSaveWithoutAddress) && machineValid && !testing, onClick = {
            try {
                onSave(draft())
                onDismiss()
            } catch (error: IllegalArgumentException) {
                result = error.message ?: "Invalid printer settings"
            }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PrinterChoice(label: String, selected: T, choices: List<T>, text: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { if (enabled) expanded = it }) {
        OutlinedTextField(text(selected), {}, enabled = enabled, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor())
        ExposedDropdownMenu(expanded, { expanded = false }) {
            choices.forEach { item -> DropdownMenuItem(text = { Text(text(item)) }, onClick = { onSelect(item); expanded = false }) }
        }
    }
}

internal fun PrinterKind.connectionLabel(): String = when (this) {
    PrinterKind.MOONRAKER -> "Moonraker"
    PrinterKind.BAMBU_LAN -> "Bambu LAN"
    PrinterKind.ELEGOO_CC1 -> "Elegoo Centauri Carbon 1 (beta)"
    PrinterKind.OCTOPRINT -> "OctoPrint"
}

internal fun canChangePrinterKind(existing: Printer?): Boolean = existing == null

internal fun canSaveOfflineU1(existing: Printer?, machine: MachineKind, url: String): Boolean =
    existing != null && machine == MachineKind.SNAPMAKER_U1 && url.isBlank()
