package com.u1.slicer.ui.printer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.u1.slicer.data.Printer
import com.u1.slicer.data.U1NozzleConfiguration

@Composable
fun U1PrepareNozzleSettings(
    printer: Printer?,
    onSave: (Printer) -> Unit,
    onTest: suspend (Printer) -> String?,
    onNavigateSettings: () -> Unit,
) {
    var editing by remember(printer?.id) { mutableStateOf(false) }
    val nozzles = printer?.u1Nozzles ?: U1NozzleConfiguration()
    Text(
        "Nozzles: " + nozzles.diametersMm.mapIndexed { index, size -> "E${index + 1}: $size mm" }.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = { if (printer == null) onNavigateSettings() else editing = true }) {
        Text("Edit nozzles")
    }
    if (editing && printer != null) {
        PrinterEditDialog(printer, onSave = onSave, onTest = onTest, onDismiss = { editing = false })
    }
}
