package com.u1.slicer.ui.printer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.Printer
import kotlinx.coroutines.launch

/**
 * F78 add/edit printer dialog. When [existing] is null this is an add flow; otherwise edit.
 * Test-connection runs on-demand via [onTest] which returns null on success or an error string.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterEditDialog(
    existing: Printer?,
    onSave: (nickname: String, url: String) -> Unit,
    onTest: suspend (url: String) -> String?,
    onDismiss: () -> Unit,
) {
    var nickname by remember { mutableStateOf(existing?.nickname ?: "") }
    var url by remember { mutableStateOf(existing?.moonrakerUrl ?: "") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add printer" else "Edit printer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nickname, onValueChange = { nickname = it },
                    label = { Text("Nickname") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it; testResult = null },
                    label = { Text("Moonraker URL") },
                    placeholder = { Text("http://192.168.1.50") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(
                        enabled = !testing && url.isNotBlank(),
                        onClick = {
                            testing = true; testResult = null
                            scope.launch {
                                testResult = onTest(url) ?: "OK"
                                testing = false
                            }
                        },
                    ) {
                        Text(if (testing) "Testing…" else "Test connection")
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
                enabled = url.isNotBlank() && nickname.isNotBlank(),
                onClick = { onSave(nickname, url); onDismiss() },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
