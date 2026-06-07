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
import com.u1.slicer.data.MixedFilamentRow

/** Returns black or white depending on which has more contrast against [color]. Uses the
 *  WCAG relative-luminance formula. Used for swatch ticks so they're visible on every slot. */
internal fun tickContrastColor(color: Color): Color {
    // sRGB luminance (no gamma — approximation is fine for tick contrast).
    val lum = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return if (lum > 0.55f) Color.Black else Color.White
}

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
    numPhysical: Int = 4,
    activeMixes: List<MixedFilamentRow> = emptyList(),
    physicalColours: List<Color> = emptyList(),
    onCreateMix: () -> Unit = {},
    onEditMix: (MixedFilamentRow) -> Unit = {},
) {
    val rowBg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    // Layout: identity line (chevron + swatch + label) on top, the mix-aware chip selector on
    // its own full-width line below. The chip row is horizontally scrollable and wide
    // (4 physical + mixes + "+"); placing it as a sibling of the weighted label Column on a
    // single Row squeezed the title to zero width and hid it (regression from UX Task 2).
    Column(
        modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable { onSelectRow() }
            .padding(start = (12 * depth).dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
      Row(
        modifier = Modifier
            .fillMaxWidth()
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

        // fix35.1: row leading swatch shows the SLOT's colour (slotPalette[node.slot]) rather
        // than the node's own suggestedColour. Without this, reassigning a node to a different
        // slot wouldn't visibly change the row's swatch (user complaint: "tapping a region in
        // the list does not seem to change the colour"). Parents with mixed children fall back
        // to dominantSlot for the primary + a diagonal stripe of secondarySlot.
        // C2 (M3-Phase-B): leaf nodes whose slot >= numPhysical are mix slots — render the
        // two-tone MixedSlotSwatch using componentA/B of the active mix, matching the pattern
        // used by SectionedSlotPicker.MixSlotChip. Physical-slot leaves keep the existing path.
        val primarySlot = if (node.isLeaf) node.region.slot else node.dominantSlot()
        val primary = slotPalette.getOrNull(primarySlot)
            ?: remember(node) {
                val argb = runCatching { android.graphics.Color.parseColor(node.region.effectiveColour) }
                    .getOrDefault(android.graphics.Color.GRAY)
                Color(argb)
            }
        val secondary = when {
            node.isLeaf && primarySlot >= numPhysical -> {
                // Mix slot leaf — secondary comes from the mix's componentB physical colour.
                val mix = activeMixes.getOrNull(primarySlot - numPhysical)
                mix?.let { physicalColours.getOrNull(it.componentB - 1) }
            }
            node.isLeaf -> null
            else -> node.secondarySlot()?.let { slotPalette.getOrNull(it) }
        }
        val swatchPrimary = when {
            node.isLeaf && primarySlot >= numPhysical -> {
                // Mix slot leaf — primary comes from the mix's componentA physical colour.
                val mix = activeMixes.getOrNull(primarySlot - numPhysical)
                mix?.let { physicalColours.getOrNull(it.componentA - 1) } ?: primary
            }
            else -> primary
        }
        MixedSlotSwatch(
            primary = swatchPrimary,
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
      }

      Spacer(Modifier.height(6.dp))

      // Mix-aware chip selector on its own full-width line, indented to align under the label
      // (past the chevron/spacer + swatch + gap). Horizontal scroll handles chip overflow.
      FilamentMixChipRow(
          physicalColours = physicalColours,
          physicalLabels = (1..numPhysical).map { "E$it" },
          mixes = activeMixes,
          selectedSlot = node.region.slot,
          onSelect = { slot -> onPickSlot(slot) },
          onCreateMix = onCreateMix,
          onEditMix = onEditMix,
          modifier = Modifier.padding(start = 42.dp),
      )
    }
}
