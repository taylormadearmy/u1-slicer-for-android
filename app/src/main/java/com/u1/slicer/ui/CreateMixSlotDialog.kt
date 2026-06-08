package com.u1.slicer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.MixWeights
import com.u1.slicer.data.MixedFilamentRow
import kotlin.math.abs

/** Remove the component at [index] (no-op below 3 components) and renormalize weights. */
fun removeMixComponent(components: List<Int>, weights: List<Int>, index: Int): Pair<List<Int>, List<Int>> {
    if (components.size <= 2) return components to weights
    val c = components.filterIndexed { i, _ -> i != index }
    val w = MixWeights.remove(weights, index)
    return c to w
}

/**
 * Single-screen modal for creating or editing an N-component mixed-filament slot.
 *
 * The editor is component-list driven: a proportional weight bar at the top
 * (draggable dividers rebalance neighbouring weights), one row per component
 * with a filament chip (tap to cycle to the next unused physical filament), a
 * tap-to-type percent field, and a remove (✕) button when there are more than
 * two components. A "+ Add colour" button appends an evenly-rebalanced
 * component up to the physical-filament count (max 4). All weight arithmetic
 * flows through [MixWeights]; the UI only maps pixels↔percent.
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
    onConfirmN: (components: List<Int>, weights: List<Int>,
        distributionMode: MixedFilamentRow.MixDistributionMode) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val maxComponents = minOf(4, physicalFilamentColours.size)
    var components by remember {
        mutableStateOf(editingRow?.components ?: listOf(1, 2.coerceAtMost(physicalFilamentColours.size)))
    }
    var weights by remember { mutableStateOf(editingRow?.weights ?: MixWeights.even(components.size)) }
    // Only LAYER_CYCLE is functional; an edited legacy row keeps its stored mode (harmless —
    // the engine treats the dead SameLayerPointillisme as LayerCycle). No UI toggle anymore.
    val distributionMode = editingRow?.distributionMode ?: MixedFilamentRow.MixDistributionMode.LAYER_CYCLE
    var typingIndex by remember { mutableStateOf(-1) }
    var typingText by remember { mutableStateOf("") }
    val isEditing = editingRow != null
    val canConfirm = components.distinct().size == components.size && components.size >= 2

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isEditing) "Edit Mix" else "Create Mix")
                Spacer(Modifier.width(6.dp))
                BetaPill()
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MixWeightBar(
                    colours = components.map { physicalFilamentColours.getOrNull(it - 1) ?: Color.Gray },
                    weights = weights,
                    onDragDivider = { leftIndex, newLeft ->
                        weights = MixWeights.rebalanceAfterDrag(weights, leftIndex, newLeft)
                    },
                )
                components.forEachIndexed { idx, slot ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilamentChip(
                            colour = physicalFilamentColours.getOrNull(slot - 1) ?: Color.Gray,
                            label = physicalFilamentLabels.getOrNull(slot - 1) ?: "E$slot",
                            selected = false,
                            onClick = {
                                val used = components.toSet()
                                val all = (1..physicalFilamentColours.size)
                                val next = all.firstOrNull { it > slot && it !in used }
                                    ?: all.firstOrNull { it !in used }
                                    ?: slot  // nowhere to go (all slots in use)
                                if (next != slot) {
                                    components = components.toMutableList().also { it[idx] = next }
                                }
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        if (typingIndex == idx) {
                            OutlinedTextField(
                                value = typingText,
                                onValueChange = { typingText = it.filter(Char::isDigit).take(3) },
                                singleLine = true,
                                modifier = Modifier.width(72.dp),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                ),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                                    weights = MixWeights.rebalanceAfterType(
                                        weights, idx, typingText.toIntOrNull() ?: weights[idx],
                                    )
                                    typingIndex = -1
                                }),
                            )
                        } else {
                            TextButton(onClick = {
                                typingIndex = idx; typingText = weights[idx].toString()
                            }) { Text("${weights[idx]}%") }
                        }
                        if (components.size > 2) {
                            TextButton(onClick = {
                                val (c, w) = removeMixComponent(components, weights, idx)
                                components = c; weights = w; typingIndex = -1
                            }) { Text("✕") }
                        }
                    }
                }
                if (components.size < maxComponents) {
                    TextButton(onClick = {
                        val used = components.toSet()
                        val next = (1..physicalFilamentColours.size).firstOrNull { it !in used }
                            ?: return@TextButton
                        weights = MixWeights.addEven(weights); components = components + next
                    }) { Text("+ Add colour") }
                }
                // NOTE: no distribution-mode toggle. The only functional mode is layer
                // alternation (LAYER_CYCLE). "Same-layer dots" (SameLayerPointillisme) is NOT
                // implemented in the Snapmaker Orca engine — its generators are #if 0-disabled
                // and the engine auto-converts the mode to LayerCycle/Simple before slicing
                // (PrintApply.cpp: "Deprecated: same-layer pointillism is disabled and will be
                // removed"). Offering it would be a no-op toggle, so it's omitted. The enum
                // value is retained only for backward-compatible deserialization of old saves.
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirmN(components, weights, distributionMode); onDismiss() },
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

/**
 * Proportional weight bar. Renders one coloured segment per component sized by
 * its weight fraction, with draggable divider handles between adjacent
 * segments. Dragging a divider moves budget between the two segments it
 * separates via [MixWeights.rebalanceAfterDrag]; this composable only converts
 * pixel positions to percent and picks which divider was grabbed.
 */
@Composable
private fun MixWeightBar(
    colours: List<Color>,
    weights: List<Int>,
    onDragDivider: (leftIndex: Int, newLeftPercent: Int) -> Unit,
) {
    val dividerColour = MaterialTheme.colorScheme.surface
    // Remembered across drag callbacks: which divider (boundary k between
    // segment k and k+1) the gesture grabbed, and the canvas width in pixels.
    var grabbedLeftIndex by remember { mutableStateOf(-1) }
    var canvasWidthPx by remember { mutableStateOf(0f) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(weights.size) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat()
                        canvasWidthPx = w
                        if (w <= 0f || weights.size < 2) {
                            grabbedLeftIndex = -1
                            return@detectHorizontalDragGestures
                        }
                        // Cumulative boundary positions (fractions) excluding 0 and 1.
                        val total = weights.sum().coerceAtLeast(1).toFloat()
                        var acc = 0f
                        var best = -1
                        var bestDist = Float.MAX_VALUE
                        for (k in 0 until weights.size - 1) {
                            acc += weights[k] / total
                            val boundaryX = acc * w
                            val d = abs(boundaryX - offset.x)
                            if (d < bestDist) { bestDist = d; best = k }
                        }
                        grabbedLeftIndex = best
                    },
                    onHorizontalDrag = { change, _ ->
                        val w = canvasWidthPx
                        val k = grabbedLeftIndex
                        if (w <= 0f || k < 0 || k >= weights.size - 1) return@detectHorizontalDragGestures
                        // The dragged divider is the right edge of segment k. The
                        // sum of weights left of (and including) k below the divider
                        // is fixed except for segment k itself; rebalanceAfterDrag
                        // interprets newLeft as the new value for segment k, moving
                        // budget to/from segment k+1. Compute segment k's new value
                        // from the divider x relative to the start of segment k.
                        val total = weights.sum().coerceAtLeast(1).toFloat()
                        var leftOfK = 0f
                        for (i in 0 until k) leftOfK += weights[i] / total
                        val leftOfKpx = leftOfK * w
                        val pairFrac = (weights[k] + weights[k + 1]) / total
                        val pairWidthPx = pairFrac * w
                        if (pairWidthPx <= 0f) return@detectHorizontalDragGestures
                        val withinPair = (change.position.x - leftOfKpx).coerceIn(0f, pairWidthPx)
                        val pairSum = weights[k] + weights[k + 1]
                        val newLeft = (withinPair / pairWidthPx * pairSum).toInt()
                        onDragDivider(k, newLeft)
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        canvasWidthPx = w
        mixSegmentOffsets(weights).forEachIndexed { i, (off, frac) ->
            drawRect(
                color = colours.getOrElse(i) { Color.Gray },
                topLeft = Offset(w * off, 0f),
                size = Size(w * frac, h),
            )
        }
        // Divider handles between adjacent segments.
        var acc = 0f
        val total = weights.sum().coerceAtLeast(1).toFloat()
        for (k in 0 until weights.size - 1) {
            acc += weights[k] / total
            val x = acc * w
            drawRect(
                color = dividerColour,
                topLeft = Offset(x - 1.5f, 0f),
                size = Size(3f, h),
            )
        }
    }
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

