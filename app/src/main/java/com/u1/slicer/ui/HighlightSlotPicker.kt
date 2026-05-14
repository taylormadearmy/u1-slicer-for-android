package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

/**
 * Floating slot picker shown over the 3D viewer when a region is currently highlighted (i.e.
 * the user has tapped a row in the tree or a triangle on the model). Solves the fix34 feedback
 * — selecting a region had no obvious next action, so users couldn't tell how to change its
 * colour.
 *
 * Layout: a translucent dark pill at the bottom of the viewer containing 4 chunky slot
 * swatches + an × clear button. Tapping a swatch assigns the highlighted region to that slot
 * and clears the highlight; tapping × just clears.
 */
@Composable
fun HighlightSlotPicker(
    label: String,
    currentSlot: Int,
    slotPalette: List<Color>,
    onPickSlot: (slot: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
        slotPalette.forEachIndexed { slot, color ->
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
                    Text("✓", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
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
