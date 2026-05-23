package com.u1.slicer.ui.printer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u1.slicer.data.Printer

/**
 * F78 Settings section: list of configured printers with edit/delete/add affordances.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintersSettingsCard(
    printers: List<Printer>,
    activeId: String?,
    onAdd: (nickname: String, url: String) -> Unit,
    onEdit: (id: String, nickname: String, url: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onTestConnection: suspend (url: String) -> String?,
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
                Text("Printers", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { addingNew = true }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
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
                        Text(printer.moonrakerUrl.ifBlank { "(no URL set)" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
            onSave = { nick, url -> onAdd(nick, url) },
            onTest = onTestConnection,
            onDismiss = { addingNew = false },
        )
    }
    editing?.let { existing ->
        PrinterEditDialog(
            existing = existing,
            onSave = { nick, url -> onEdit(existing.id, nick, url) },
            onTest = onTestConnection,
            onDismiss = { editing = null },
        )
    }
}
