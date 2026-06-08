package com.u1.slicer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

@OptIn(ExperimentalFoundationApi::class)
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
    canonicalCount: Int = numPhysical,
    activeMixes: List<MixedFilamentRow> = emptyList(),
    physicalColours: List<Color> = emptyList(),
    onCreateMix: () -> Unit = {},
    onEditMix: (MixedFilamentRow) -> Unit = {},
) {
    // fix #2 (M4): mix-slot ids start above the larger of the physical extruder count and the
    // canonical filament count, so a mix id never collides with a canonical file-filament index.
    // When canonicalCount <= numPhysical (the normal case) mixBase == numPhysical → no behaviour
    // change. Used for the mix-slot base, the "is this a mix slot" threshold, and the activeMixes
    // index — all three must agree.
    val mixBase = maxOf(numPhysical, canonicalCount)
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

        // fix35.1: row leading swatch shows the SLOT's colour (slotPalette[node.slot]) rather
        // than the node's own suggestedColour. Without this, reassigning a node to a different
        // slot wouldn't visibly change the row's swatch (user complaint: "tapping a region in
        // the list does not seem to change the colour"). Parents with mixed children fall back
        // to dominantSlot for the primary + a diagonal stripe of secondarySlot.
        // C2 (M3-Phase-B) / M4: leaf nodes whose slot >= mixBase are mix slots — render the
        // N-segment MixedSlotSwatch from the active mix's components/weights. Physical-slot leaves
        // and parents-with-mixed-children keep the two-tone (corner-stripe) path.
        val primarySlot = if (node.isLeaf) node.region.slot else node.dominantSlot()
        val primary = slotPalette.getOrNull(primarySlot)
            ?: remember(node) {
                val argb = runCatching { android.graphics.Color.parseColor(node.region.effectiveColour) }
                    .getOrDefault(android.graphics.Color.GRAY)
                Color(argb)
            }
        val isMixLeaf = node.isLeaf && primarySlot >= mixBase
        if (isMixLeaf) {
            // Mix leaf: render the full N-segment blend bar from the mix's components/weights.
            val mix = activeMixes.getOrNull(primarySlot - mixBase)
            MixedSlotSwatch(
                colours = (mix?.components ?: emptyList()).map {
                    physicalColours.getOrNull(it - 1) ?: Color.Gray
                },
                weights = mix?.weights ?: emptyList(),
                size = 32.dp,
                modifier = Modifier.clickable { onTapSwatch() },
            )
        } else {
            // Parent with mixed children → corner-stripe (no single percentage); plain physical
            // slot → solid. Both stay on the legacy 2-tone overload with a null fraction.
            val secondary = if (node.isLeaf) null
                else node.secondarySlot()?.let { slotPalette.getOrNull(it) }
            MixedSlotSwatch(
                primary = primary,
                secondary = secondary,
                size = 32.dp,
                secondaryFraction = null,
                modifier = Modifier.clickable { onTapSwatch() },
            )
        }
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(node.region.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${"%.0f".format(node.region.coverageFraction * 100)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Inline selector — same chips as the "Extruders →" row and the model-tap overlay, so a
        // mix created anywhere shows up here too: the physical slot chips, one two-tone chip per
        // active mix, and a "+" to create a new mix. Bounded with weight(1f) + horizontalScroll
        // so the chips never squeeze the label to zero width even with many mixes.
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slotPalette.take(numPhysical).forEachIndexed { slot, color ->
                val isActive = node.region.slot == slot
                Box(
                    Modifier
                        .size(if (isActive) 24.dp else 20.dp)
                        .background(color, MaterialTheme.shapes.small)
                        .clickable { onPickSlot(slot) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isActive) {
                        // fix38.3: contrasting tick — black on light slots, white on dark.
                        Text("✓", color = tickContrastColor(color), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            // One N-segment chip per active mix. Slot id = mixBase + index. Tapping assigns the
            // row to that mix; long-press opens the mix editor (#4/#5).
            activeMixes.forEachIndexed { idx, mix ->
                val slot = mixBase + idx
                val isActive = node.region.slot == slot
                val mColours = mix.components.map { physicalColours.getOrNull(it - 1) ?: Color.Gray }
                Box(
                    Modifier.size(if (isActive) 24.dp else 20.dp).combinedClickable(
                        onClick = { onPickSlot(slot) },
                        onLongClick = { activeMixes.getOrNull(idx)?.let { onEditMix(it) } },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    MixedSlotSwatch(
                        colours = mColours,
                        weights = mix.weights,
                        size = if (isActive) 24.dp else 20.dp,
                    )
                    if (isActive) {
                        Text(
                            "✓",
                            color = tickContrastColor(mColours.firstOrNull() ?: Color.Gray),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            // "+" create-mix chip.
            Box(
                Modifier
                    .size(20.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                    .clickable { onCreateMix() },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
