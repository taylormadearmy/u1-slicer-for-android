package com.u1.slicer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.MixedFilamentRow

/**
 * Single-screen modal for creating or editing a mixed-filament slot.
 *
 * Visual choice "B" from the M3 Phase A design spec: two filament chips
 * for components A and B, a 0-100% ratio slider, a visible distribution-
 * mode toggle (LAYER_CYCLE vs SAME_LAYER_DOTS), live preview swatch,
 * and a print-cost subtitle. The Create button is disabled when A == B.
 *
 * When `editingRow` is non-null, the dialog is in Edit mode (title shifts,
 * Delete button appears, save callback is wired to edit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateMixSlotDialog(
    physicalFilamentColours: List<Color>,        // size 1..4
    physicalFilamentLabels: List<String>,        // size matches colours
    editingRow: MixedFilamentRow? = null,
    onConfirm: (componentA: Int, componentB: Int, mixBPercent: Int,
        distributionMode: MixedFilamentRow.MixDistributionMode) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var componentA by remember { mutableStateOf(editingRow?.componentA ?: 1) }
    var componentB by remember {
        mutableStateOf(editingRow?.componentB ?: 2.coerceAtMost(physicalFilamentColours.size))
    }
    var mixBPercent by remember { mutableStateOf(editingRow?.mixBPercent ?: 50) }
    var distributionMode by remember {
        mutableStateOf(editingRow?.distributionMode
            ?: MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
    }

    val sameComponentError = componentA == componentB
    val canConfirm = !sameComponentError
    val isEditing = editingRow != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit Mix Slot" else "Create Mix Slot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Component A picker
                Text("Component A", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    physicalFilamentColours.forEachIndexed { idx, c ->
                        val slot = idx + 1
                        FilamentChip(
                            colour = c,
                            label = physicalFilamentLabels.getOrNull(idx) ?: "E$slot",
                            selected = componentA == slot,
                            onClick = { componentA = slot },
                        )
                    }
                }
                // Component B picker
                Text("Component B", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    physicalFilamentColours.forEachIndexed { idx, c ->
                        val slot = idx + 1
                        FilamentChip(
                            colour = c,
                            label = physicalFilamentLabels.getOrNull(idx) ?: "E$slot",
                            selected = componentB == slot,
                            onClick = { componentB = slot },
                        )
                    }
                }
                if (sameComponentError) {
                    Text(
                        "Pick two different filaments.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Ratio slider
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Mix ratio", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "$mixBPercent% E$componentB",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Slider(
                    value = mixBPercent.toFloat(),
                    onValueChange = { mixBPercent = it.toInt() },
                    valueRange = 0f..100f,
                    steps = 99,
                )
                // Live preview swatch (uses existing MixedSlotSwatch)
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MixedSlotSwatch(
                        primary = physicalFilamentColours.getOrNull(componentA - 1) ?: Color.Gray,
                        secondary = physicalFilamentColours.getOrNull(componentB - 1),
                        size = 56.dp,
                    )
                }
                // Distribution mode toggle
                Text("Pattern", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DistributionChip(
                        label = "Layer alternation",
                        selected = distributionMode == MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                        onClick = { distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE },
                    )
                    DistributionChip(
                        label = "Same-layer dots",
                        selected = distributionMode == MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS,
                        onClick = { distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS },
                    )
                }
                // Print-cost tag (heuristic; refined later)
                Text(
                    "Mix slots add print time (each tool change ~ 5–10 s).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    onConfirm(componentA, componentB, mixBPercent, distributionMode)
                    onDismiss()
                },
            ) { Text(if (isEditing) "Save" else "Create") }
        },
        dismissButton = {
            if (isEditing && onDelete != null) {
                TextButton(
                    onClick = { onDelete(); onDismiss() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilamentChip(colour: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(CircleShape),
        onClick = onClick,
        shape = CircleShape,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        color = colour,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DistributionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, border),
        color = bg,
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
