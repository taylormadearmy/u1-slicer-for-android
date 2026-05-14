package com.u1.slicer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.u1.slicer.aipaint.AiRegionNode

/**
 * Top-level Compose surface for the segmentation tree. Flattens [tree] into a LazyColumn,
 * tracks per-node expand state, and bridges row callbacks to the screen-level handlers.
 *
 * Auto-rules:
 *   - leafCount ≤ 8 → start fully expanded
 *   - leafCount > 20 → start collapsed to depth 1 (root visible, children hidden)
 */
@Composable
fun AiPaintTree(
    tree: List<AiRegionNode>,
    slotPalette: List<Color>,
    onTapSwatch: (nodeId: Int) -> Unit,
    onPickSlot: (path: List<Int>, slot: Int) -> Unit,
    onSelectNode: (nodeId: Int) -> Unit,
    selectedNodeId: Int? = null,
    modifier: Modifier = Modifier,
) {
    val totalLeaves = remember(tree) { tree.sumOf { it.leafCount() } }
    val initialExpand = remember(tree, totalLeaves) {
        when {
            totalLeaves <= 8 -> ExpandPolicy.AllExpanded
            totalLeaves > 20 -> ExpandPolicy.Depth1
            else -> ExpandPolicy.AllExpanded
        }
    }
    val expanded = remember(tree, initialExpand) {
        mutableStateMapOf<Int, Boolean>().also {
            applyInitial(tree, initialExpand, it, depth = 0)
        }
    }
    val flat = remember(tree, expanded.toMap()) {
        tree.flatMap { root -> flattenVisible(root, expanded, 0, parentPath = emptyList()) }
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(flat, key = { (node, _, _) -> node.region.id }) { (node, depth, path) ->
            AiPaintTreeRow(
                node = node.copy(expanded = expanded[node.region.id] ?: true),
                depth = depth,
                onToggleExpand = { expanded[node.region.id] = !(expanded[node.region.id] ?: true) },
                onTapSwatch = { onTapSwatch(node.region.id) },
                onPickSlot = { slot -> onPickSlot(path, slot) },
                onSelectRow = { onSelectNode(node.region.id) },
                selected = selectedNodeId == node.region.id,
                slotPalette = slotPalette,
            )
            HorizontalDivider()
        }
    }
}

private enum class ExpandPolicy { AllExpanded, Depth1 }

private fun applyInitial(
    nodes: List<AiRegionNode>,
    policy: ExpandPolicy,
    out: MutableMap<Int, Boolean>,
    depth: Int,
) {
    nodes.forEach { node ->
        val shouldExpand = when (policy) {
            ExpandPolicy.AllExpanded -> true
            ExpandPolicy.Depth1 -> depth < 1
        }
        out[node.region.id] = shouldExpand
        applyInitial(node.children, policy, out, depth + 1)
    }
}

private fun flattenVisible(
    node: AiRegionNode,
    expanded: Map<Int, Boolean>,
    depth: Int,
    parentPath: List<Int>,
): List<Triple<AiRegionNode, Int, List<Int>>> {
    val path = parentPath + node.region.id
    val out = mutableListOf<Triple<AiRegionNode, Int, List<Int>>>()
    out += Triple(node, depth, path)
    if (expanded[node.region.id] != false) {
        node.children.forEach { c ->
            out += flattenVisible(c, expanded, depth + 1, path)
        }
    }
    return out
}
