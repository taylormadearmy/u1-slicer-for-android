package com.u1.slicer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.MixedFilamentRow

/**
 * Horizontally-scrollable chip row combining physical colour slots, mix slots, and an "+ Add"
 * button. Intended as the shared slot-selector control for screens that let the user pick or
 * create a filament assignment.
 *
 * Slot-id invariant (Phase B):
 *   Physical chip  → slot = index in 0..(numPhysical-1)
 *   Mix chip       → slot = numPhysical + mixIndex
 *
 * Use [FilamentMixChipRow.physicalSlotId] and [FilamentMixChipRow.mixSlotId] to produce ids
 * consistently across callers.
 *
 * NOTE (2026-06-07): after the Smart Paint layout restore, the per-surface selectors inline
 * their own chips, so this composable currently has no caller. It is deliberately retained as
 * the slot-id-invariant single source of truth (the companion `object` below) and a ready-made
 * shared mix selector for the upcoming N-way mix work, rather than deleted and re-created. The
 * companion helpers are covered by FilamentMixChipRowTest.
 *
 * @param physicalColours  Ordered list of physical filament colours (E1..E4).
 * @param physicalLabels   Optional per-slot labels; falls back to "E1", "E2" … when shorter.
 * @param mixes            Active mix rows to render after the physical chips.
 * @param selectedSlot     Currently selected slot id, or -1 for no selection.
 * @param onSelect         Called with the slot id when the user taps any chip.
 * @param onCreateMix      Called when the user taps the "+" chip.
 * @param onEditMix        Called with the mix row when the user long-presses a mix chip.
 * @param modifier         Applied to the outer Row.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FilamentMixChipRow(
    physicalColours: List<Color>,
    physicalLabels: List<String> = emptyList(),
    mixes: List<MixedFilamentRow> = emptyList(),
    selectedSlot: Int = -1,
    onSelect: (slot: Int) -> Unit,
    onCreateMix: () -> Unit = {},
    onEditMix: (MixedFilamentRow) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val numPhysical = physicalColours.size

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Physical colour chips
        physicalColours.forEachIndexed { idx, colour ->
            val slotId = FilamentMixChipRow.physicalSlotId(idx)
            val selected = selectedSlot == slotId
            PhysicalChip(
                colour = colour,
                label = physicalLabels.getOrNull(idx) ?: "E${idx + 1}",
                selected = selected,
                onClick = { onSelect(slotId) },
            )
        }

        // Mix chips
        mixes.forEachIndexed { idx, row ->
            val slotId = FilamentMixChipRow.mixSlotId(idx, numPhysical)
            val selected = selectedSlot == slotId
            val primary = physicalColours.getOrNull(row.componentA - 1) ?: Color.Gray
            val secondary = physicalColours.getOrNull(row.componentB - 1)
            MixChip(
                primary = primary,
                secondary = secondary,
                secondaryFraction = row.mixBPercent / 100f,
                selected = selected,
                onClick = { onSelect(slotId) },
                onLongClick = { onEditMix(row) },
            )
        }

        // "+" add-mix chip
        AddMixChip(onClick = onCreateMix)
    }
}

// ---------------------------------------------------------------------------
// Private chip composables
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhysicalChip(
    colour: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(40.dp).clip(CircleShape),
        onClick = onClick,
        shape = CircleShape,
        color = colour,
        border = if (selected)
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (selected) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelMedium,
                    color = tickContrastColor(colour),
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tickContrastColor(colour),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MixChip(
    primary: Color,
    secondary: Color?,
    secondaryFraction: Float,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (selected)
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            )
            .background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        MixedSlotSwatch(
            primary = primary,
            secondary = secondary,
            size = 40.dp,
            secondaryFraction = secondaryFraction,
            modifier = Modifier
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        )
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelMedium,
                color = tickContrastColor(primary),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddMixChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Companion — pure slot-id helpers (single source of truth for the invariant)
// ---------------------------------------------------------------------------

object FilamentMixChipRow {
    /** Slot id for a physical chip at [index]. */
    fun physicalSlotId(index: Int): Int = index

    /** Slot id for a mix chip at [index] in the mix list, given [numPhysical] physical slots. */
    fun mixSlotId(index: Int, numPhysical: Int): Int = numPhysical + index
}
