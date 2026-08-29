package com.u1.slicer.ui.printer

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintersSettingsCard(
    printers: List<Printer>,
    activeId: String?,
    onAdd: (
        nickname: String,
        kind: PrinterKind,
        url: String,
        bambuIp: String,
        bambuAccessCode: String,
        bambuSerial: String,
        bambuModel: BambuModel,
    ) -> Unit,
    onEdit: (
        id: String,
        nickname: String,
        kind: PrinterKind,
        url: String,
        bambuIp: String,
        bambuAccessCode: String,
        bambuSerial: String,
        bambuModel: BambuModel,
    ) -> Unit,
    onDelete: (id: String) -> Unit,
    onTestConnection: suspend (
        kind: PrinterKind,
        url: String,
        bambuIp: String,
        bambuAccessCode: String,
        bambuSerial: String,
        bambuModel: BambuModel,
    ) -> String?,
    bambuEnabled: Boolean = false,
) {
    var editing by remember { mutableStateOf<Printer?>(null) }
    var addingNew by remember { mutableStateOf(false) }

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
                TextButton(onClick = { addingNew = true }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (bambuEnabled) "Add a Snapmaker U1 or a supported Bambu Lab printer. Bambu support is currently in beta."
                else "Add a Snapmaker U1. Enable Bambu beta support in Settings to add a supported Bambu Lab printer.",
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

    if (addingNew) {
        PrinterEditDialog(
            existing = null,
            onSave = { nick, kind, url, bambuIp, bambuAccessCode, bambuSerial, bambuModel ->
                onAdd(nick, kind, url, bambuIp, bambuAccessCode, bambuSerial, bambuModel)
            },
            onTest = onTestConnection,
            bambuEnabled = bambuEnabled,
            onDismiss = { addingNew = false },
        )
    }
    editing?.let { existing ->
        PrinterEditDialog(
            existing = existing,
            onSave = { nick, kind, url, bambuIp, bambuAccessCode, bambuSerial, bambuModel ->
                onEdit(existing.id, nick, kind, url, bambuIp, bambuAccessCode, bambuSerial, bambuModel)
            },
            onTest = onTestConnection,
            bambuEnabled = bambuEnabled,
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
