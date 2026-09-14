package com.u1.slicer.ui.machine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.HostType
import com.u1.slicer.machine.PrinterWizardStep
import com.u1.slicer.machine.PrinterWizardVariant
import com.u1.slicer.machine.PrinterWizardViewModel
import com.u1.slicer.machine.VendorCatalogClient
import kotlinx.coroutines.launch

/** Text-only Orca catalogue wizard: vendor, model, nozzle variant, then validation and save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterWizardScreen(
    viewModel: PrinterWizardViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var nickname by remember(state.selectedVariant) {
        mutableStateOf(state.selectedVariant?.profile?.displayName.orEmpty())
    }
    var address by remember(state.selectedVariant) { mutableStateOf("") }
    var apiKey by remember(state.selectedVariant) { mutableStateOf("") }
    var username by remember(state.selectedVariant) { mutableStateOf("") }
    var password by remember(state.selectedVariant) { mutableStateOf("") }
    var hostExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(wizardTitle(state.step)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Profiles come from OrcaSlicer ${VendorCatalogClient.ORCA_PROFILE_TAG}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.errorMessage?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                } else when (state.step) {
                    PrinterWizardStep.VENDOR -> WizardList(state.vendors) { vendor ->
                        scope.launch { viewModel.selectVendor(vendor) }
                    }
                    PrinterWizardStep.MODEL -> WizardList(state.models, { it.name }) { model ->
                        scope.launch { viewModel.selectModel(model) }
                    }
                    PrinterWizardStep.VARIANT -> WizardList(
                        values = state.variants,
                        label = ::printerWizardVariantLabel,
                        key = ::printerWizardVariantKey,
                        onClick = viewModel::selectVariant,
                    )
                    PrinterWizardStep.CONFIRM -> Column(
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val profile = state.selectedVariant?.profile
                        val hostType = state.connectionHostType
                        Text(profile?.displayName.orEmpty())
                        Text(
                            "${profile?.bedSizeX?.toInt()} x ${profile?.bedSizeY?.toInt()} x " +
                                "${profile?.maxPrintHeight?.toInt()} mm",
                        )
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            label = { Text("Printer name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ExposedDropdownMenuBox(
                            expanded = hostExpanded,
                            onExpandedChange = { hostExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = hostType?.let(::printerWizardHostLabel).orEmpty(),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Connection type") },
                                placeholder = { Text("Choose connection") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = hostExpanded)
                                },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                            )
                            DropdownMenu(
                                expanded = hostExpanded,
                                onDismissRequest = { hostExpanded = false },
                            ) {
                                PrinterWizardViewModel.SUPPORTED_HOST_TYPES.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(printerWizardHostLabel(option)) },
                                        onClick = {
                                            viewModel.selectHostType(option)
                                            hostExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        if (hostType == null) {
                            Text(
                                "This profile names a host service the app does not support. " +
                                    "Choose the connection service your printer uses.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            OutlinedTextField(
                                value = address,
                                onValueChange = { address = it },
                                label = { Text(printerWizardAddressLabel(hostType)) },
                                placeholder = { Text("http://192.168.1.50") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (hostType == HostType.OCTOPRINT || hostType == HostType.PRUSALINK) {
                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = { apiKey = it },
                                    label = { Text("API key (optional)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (hostType == HostType.PRUSALINK) {
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    label = { Text("Username (optional)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Password (optional)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Text(
                            "Add printer runs a one-layer validation slice before saving.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.step == PrinterWizardStep.CONFIRM) {
                Button(
                    enabled = !state.busy && state.connectionHostType != null,
                    onClick = {
                        scope.launch {
                            viewModel.confirm(nickname, address, apiKey, username, password)
                        }
                    },
                ) { Text("Validate and add") }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !state.busy,
                onClick = {
                    if (state.step == PrinterWizardStep.VENDOR) onDismiss() else viewModel.back()
                },
            ) { Text(if (state.step == PrinterWizardStep.VENDOR) "Cancel" else "Back") }
        },
    )
}

@Composable
private fun <T> WizardList(
    values: List<T>,
    label: (T) -> String = { it.toString() },
    key: (T) -> Any = { label(it) },
    onClick: (T) -> Unit,
) {
    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
        items(values, key = key) { item ->
            TextButton(onClick = { onClick(item) }, modifier = Modifier.fillMaxWidth()) {
                Text(label(item), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

internal fun printerWizardVariantLabel(variant: PrinterWizardVariant): String =
    variant.profile.displayName

internal fun printerWizardVariantKey(variant: PrinterWizardVariant): String =
    variant.sourcePath

internal fun printerWizardAddressLabel(hostType: HostType): String = when (hostType) {
    HostType.MOONRAKER -> "Moonraker address (optional)"
    HostType.OCTOPRINT -> "OctoPrint address (optional)"
    HostType.PRUSALINK -> "PrusaLink address (optional)"
    HostType.BAMBU_LAN -> "Printer address (optional)"
}

private fun printerWizardHostLabel(hostType: HostType): String = when (hostType) {
    HostType.MOONRAKER -> "Moonraker"
    HostType.OCTOPRINT -> "OctoPrint"
    HostType.PRUSALINK -> "PrusaLink"
    HostType.BAMBU_LAN -> "Bambu Lab (LAN)"
}

private fun wizardTitle(step: PrinterWizardStep): String = when (step) {
    PrinterWizardStep.VENDOR -> "Choose manufacturer"
    PrinterWizardStep.MODEL -> "Choose printer model"
    PrinterWizardStep.VARIANT -> "Choose nozzle"
    PrinterWizardStep.CONFIRM -> "Confirm printer"
}
