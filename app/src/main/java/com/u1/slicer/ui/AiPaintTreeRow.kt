package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.u1.slicer.aipaint.AiRegionNode

@Composable
fun AiPaintTreeRow(
    node: AiRegionNode,
    depth: Int,
    onToggleExpand: () -> Unit,
    onTapSwatch: () -> Unit,
    onPickSlot: (slot: Int) -> Unit,
    onSelectRow: () -> Unit,
    slotPalette: List<Color>,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val rowBg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable { onSelectRow() }
            .padding(start = (12 * depth).dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chevron — only on parents with children.
        if (node.children.isNotEmpty()) {
            Icon(
                imageVector = if (node.expanded) Icons.Default.KeyboardArrowDown
                              else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onToggleExpand() },
            )
            Spacer(Modifier.width(6.dp))
        } else {
            Spacer(Modifier.width(26.dp))
        }

        val primary = remember(node) {
            val argb = runCatching { android.graphics.Color.parseColor(node.region.effectiveColour) }
                .getOrDefault(android.graphics.Color.GRAY)
            Color(argb)
        }
        val secondary = if (node.isLeaf) null else node.secondarySlot()?.let { slotPalette.getOrNull(it) }
        MixedSlotSwatch(
            primary = primary,
            secondary = secondary,
            size = 32.dp,
            modifier = Modifier.clickable { onTapSwatch() },
        )
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(node.region.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${"%.0f".format(node.region.coverageFraction * 100)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            slotPalette.forEachIndexed { slot, color ->
                val isActive = node.region.slot == slot
                Box(
                    Modifier
                        .size(if (isActive) 24.dp else 20.dp)
                        .background(color, MaterialTheme.shapes.small)
                        .clickable { onPickSlot(slot) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isActive) {
                        Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
