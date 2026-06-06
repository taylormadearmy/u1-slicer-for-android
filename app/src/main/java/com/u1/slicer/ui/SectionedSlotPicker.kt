package com.u1.slicer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.MixedFilamentRow

/**
 * Slot-assignment picker with three labelled sections:
 *   PHYSICAL        — E1..E4 colour chips
 *   THIS PROJECT    — project-scoped mix slots + "+ Add" chip
 *   LIBRARY (★)     — library mixes whose components fit the current
 *                     numPhysicalFilaments (others hidden silently)
 *
 * Each chip emits its virtual slot ID via `onSelect`:
 *   Physical chip → index in 0..3 (matches AiRegion.slot today)
 *   Mix chip      → numPhysicalFilaments + position-in-combined-mix-list
 *
 * Tap to select. Long-press a mix chip to fire `onEditMix(row)`. "+ Add"
 * fires `onCreateMix()`.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SectionedSlotPicker(
    physicalColours: List<Color>,
    physicalLabels: List<String>,
    projectMixes: List<MixedFilamentRow>,
    libraryMixes: List<MixedFilamentRow>,        // already filtered to fit current numPhysicalFilaments
    selectedSlot: Int,
    onSelect: (slot: Int) -> Unit,
    onCreateMix: () -> Unit,
    onEditMix: (row: MixedFilamentRow) -> Unit,
) {
    val numPhysical = physicalColours.size

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // PHYSICAL
        SectionLabel("PHYSICAL")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            physicalColours.forEachIndexed { idx, colour ->
                PhysicalSlotChip(
                    colour = colour,
                    label = physicalLabels.getOrNull(idx) ?: "E${idx + 1}",
                    selected = selectedSlot == idx,
                    onClick = { onSelect(idx) },
                )
            }
        }

        // THIS PROJECT (always show section even if empty, so + Add is discoverable)
        SectionLabel("THIS PROJECT", trailing = "+ Add", onTrailingClick = onCreateMix)
        if (projectMixes.isEmpty()) {
            Text(
                "No mix slots yet — tap + Add to create one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                projectMixes.forEachIndexed { idx, row ->
                    MixSlotChip(
                        row = row,
                        physicalColours = physicalColours,
                        selected = selectedSlot == numPhysical + idx,
                        onClick = { onSelect(numPhysical + idx) },
                        onLongClick = { onEditMix(row) },
                    )
                }
            }
        }

        // LIBRARY (hidden when empty)
        if (libraryMixes.isNotEmpty()) {
            SectionLabel("LIBRARY ★")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                libraryMixes.forEachIndexed { idx, row ->
                    MixSlotChip(
                        row = row,
                        physicalColours = physicalColours,
                        selected = selectedSlot == numPhysical + projectMixes.size + idx,
                        onClick = { onSelect(numPhysical + projectMixes.size + idx) },
                        onLongClick = { onEditMix(row) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, trailing: String? = null, onTrailingClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) {
            TextButton(onClick = { onTrailingClick?.invoke() }) { Text(trailing) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhysicalSlotChip(colour: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(CircleShape),
        onClick = onClick,
        shape = CircleShape,
        color = colour,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Text(label)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MixSlotChip(
    row: MixedFilamentRow,
    physicalColours: List<Color>,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val primary = physicalColours.getOrNull(row.componentA - 1) ?: Color.Gray
    val secondary = physicalColours.getOrNull(row.componentB - 1)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        MixedSlotSwatch(
            primary = primary,
            secondary = secondary,
            size = 40.dp,
            modifier = Modifier
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        )
    }
}
