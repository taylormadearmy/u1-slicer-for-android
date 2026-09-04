package com.u1.slicer.ui.machine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.u1.slicer.machine.PrinterWizardStep
import com.u1.slicer.machine.PrinterWizardViewModel
import com.u1.slicer.machine.VendorCatalogClient
import kotlinx.coroutines.launch

/** Text-only Orca catalogue wizard: vendor, model, nozzle variant, then validation and save. */
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
    var moonrakerUrl by remember { mutableStateOf("") }

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
                        state.variants,
                        { "${it.profile.printerVariant} mm nozzle" },
                    ) { variant -> viewModel.selectVariant(variant) }
                    PrinterWizardStep.CONFIRM -> Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val profile = state.selectedVariant?.profile
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
                        OutlinedTextField(
                            value = moonrakerUrl,
                            onValueChange = { moonrakerUrl = it },
                            label = { Text("Moonraker URL (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                    enabled = !state.busy,
                    onClick = { scope.launch { viewModel.confirm(nickname, moonrakerUrl) } },
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
    onClick: (T) -> Unit,
) {
    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
        items(values, key = { label(it) }) { item ->
            TextButton(onClick = { onClick(item) }, modifier = Modifier.fillMaxWidth()) {
                Text(label(item), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun wizardTitle(step: PrinterWizardStep): String = when (step) {
    PrinterWizardStep.VENDOR -> "Choose manufacturer"
    PrinterWizardStep.MODEL -> "Choose printer model"
    PrinterWizardStep.VARIANT -> "Choose nozzle"
    PrinterWizardStep.CONFIRM -> "Confirm printer"
}
