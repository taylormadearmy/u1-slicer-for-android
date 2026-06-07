package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.MixedFilamentRow

/**
 * Floating slot picker shown over the 3D viewer when a region is currently highlighted (i.e.
 * the user has tapped a row in the tree or a triangle on the model). Solves the fix34 feedback
 * — selecting a region had no obvious next action, so users couldn't tell how to change its
 * colour.
 *
 * Layout: a translucent dark pill at the bottom of the viewer containing the physical slot
 * swatches, one two-tone swatch per active mix, a "+" to create a new mix, and an × clear
 * button. Tapping a swatch assigns the highlighted region to that slot; × clears the highlight.
 *
 * Slot-id invariant (Phase B): physical chip → slot = index; mix chip → slot = numPhysical + i.
 */
@Composable
fun HighlightSlotPicker(
    label: String,
    currentSlot: Int,
    physicalColours: List<Color>,
    onPickSlot: (slot: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    numPhysical: Int = physicalColours.size,
    mixes: List<MixedFilamentRow> = emptyList(),
    onCreateMix: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(
                "Move to slot:",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 1,
            )
        }
        // Physical slot swatches.
        physicalColours.forEachIndexed { slot, color ->
            val isCurrent = slot == currentSlot
            Box(
                Modifier
                    .size(if (isCurrent) 36.dp else 40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(color)
                    .clickable(enabled = !isCurrent) { onPickSlot(slot) },
                contentAlignment = Alignment.Center,
            ) {
                if (isCurrent) {
                    // fix38.3: contrasting tick (was white — invisible on white slot).
                    Text("✓", color = tickContrastColor(color), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        // Mix swatches (two-tone). Slot id = numPhysical + index.
        mixes.forEachIndexed { idx, mix ->
            val slot = numPhysical + idx
            val isCurrent = slot == currentSlot
            val primary = physicalColours.getOrNull(mix.componentA - 1) ?: Color.Gray
            val secondary = physicalColours.getOrNull(mix.componentB - 1)
            Box(
                Modifier.size(if (isCurrent) 36.dp else 40.dp).clip(MaterialTheme.shapes.small)
                    .clickable(enabled = !isCurrent) { onPickSlot(slot) },
                contentAlignment = Alignment.Center,
            ) {
                MixedSlotSwatch(
                    primary = primary,
                    secondary = secondary,
                    size = if (isCurrent) 36.dp else 40.dp,
                    secondaryFraction = mix.mixBPercent / 100f,
                )
                if (isCurrent) {
                    Text("✓", color = tickContrastColor(primary), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        // "+" create-mix chip.
        Box(
            Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small)
                .border(1.5.dp, Color.White.copy(alpha = 0.7f), MaterialTheme.shapes.small)
                .clickable { onCreateMix() },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Clear selection",
                tint = Color.White,
            )
        }
    }
}
