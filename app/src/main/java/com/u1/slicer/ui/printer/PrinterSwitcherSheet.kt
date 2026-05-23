package com.u1.slicer.ui.printer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.Printer

/**
 * F78 bottom sheet. Lists all configured printers with a check next to the active one
 * and a one-line status hint per row ("Currently printing" if a print is in progress
 * on that printer at the moment the sheet opens).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterSwitcherSheet(
    printers: List<Printer>,
    activeId: String?,
    activePrintingFilename: String?,  // non-null if a print is running on the active printer
    onSelect: (Printer) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Switch printer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Switching only changes which printer the app is watching. " +
                "A running print continues on its physical printer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(12.dp))
            printers.forEach { printer ->
                val isActive = printer.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(printer); onDismiss() }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Print, null, modifier = Modifier.size(20.dp),
                        tint = if (isActive) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(printer.nickname, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            printer.moonrakerUrl.ifBlank { "(no URL set)" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        if (isActive && activePrintingFilename != null) {
                            Text(
                                "Currently printing: $activePrintingFilename",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    if (isActive) {
                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
