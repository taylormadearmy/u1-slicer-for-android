package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.u1.slicer.aipaint.AiRegionNode
import com.u1.slicer.aipaint.SegmentationSource
import com.u1.slicer.viewer.CameraViewState

/**
 * fix44: extracted from AiPaintResultScreen during the 1147-LOC file split. Pure helpers
 * (and one tiny composable) shared by AiPaintResultScreen and AiPaintViewer. All marked
 * `internal` so they're reachable across the two-file UI surface without leaking past the
 * package.
 */

/** Friendly label for the Painted/Regions toggle. */
internal fun viewLabelFor(source: SegmentationSource): String = when (source) {
    SegmentationSource.PAINT_STATE -> "🎨 Painted"
    SegmentationSource.VOLUME -> "🧩 Parts"
    SegmentationSource.OBJECT -> "📦 Objects"
    SegmentationSource.TRIANGLE_INDEX -> "🎯 Indices"
    SegmentationSource.TOPOLOGY,
    SegmentationSource.TOPOLOGY_RECURSIVE -> "🪨 Regions"
    SegmentationSource.Z_BAND -> "📏 Bands"
    SegmentationSource.BRUSH -> "✏️ Brush"
}

/** Compute a CameraViewState that frames the model nicely (target = model centroid,
 *  distance proportional to model diagonal). Without this the default camera fits the whole
 *  270×270 bed which makes small models look tiny and breaks tap precision. */
internal fun computeFitCameraState(positions: FloatArray): CameraViewState? {
    if (positions.isEmpty()) return null
    var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
    var i = 0
    while (i < positions.size) {
        val x = positions[i]; val y = positions[i + 1]; val z = positions[i + 2]
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        i += 3
    }
    val cx = (minX + maxX) / 2f
    val cy = (minY + maxY) / 2f
    val cz = (minZ + maxZ) / 2f
    val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
    val diagonal = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(50f)
    return CameraViewState(
        azimuth = -90.0,
        elevation = 35.0,
        distance = diagonal.toDouble() * 2.0,
        panX = 0.0,
        panY = 0.0,
        targetX = cx.toDouble(),
        targetY = cy.toDouble(),
        targetZ = cz.toDouble(),
    )
}

/** Walk the tree looking for a node with [id]. Returns null when no match. */
internal fun findNodeById(tree: List<AiRegionNode>, id: Int): AiRegionNode? {
    for (root in tree) {
        for ((node, _) in root.flatten()) {
            if (node.region.id == id) return node
        }
    }
    return null
}

/** Find the deepest leaf whose triangleIds contain [triangleId]. */
internal fun findLeafContainingTriangle(
    tree: List<AiRegionNode>,
    triangleId: Int,
): AiRegionNode? {
    var match: AiRegionNode? = null
    for (root in tree) {
        for ((node, _) in root.flatten()) {
            if (node.isLeaf && triangleId in node.triangleIds) match = node
        }
    }
    return match
}

/** Translate raw triangle positions onto the U1 bed (270×270 plate origin at corner). The
 *  bounding box is centred at (135, 135) in XY and the lowest Z lands at 0. Output array has
 *  the same length as input. */
internal fun recenterForBed(positions: FloatArray): FloatArray {
    if (positions.isEmpty()) return positions
    var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var i = 0
    while (i < positions.size) {
        val x = positions[i]; val y = positions[i + 1]; val z = positions[i + 2]
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z
        i += 3
    }
    val targetCx = 135f
    val targetCy = 135f
    val cx = (minX + maxX) / 2f
    val cy = (minY + maxY) / 2f
    val dx = targetCx - cx
    val dy = targetCy - cy
    val dz = -minZ
    val out = FloatArray(positions.size)
    var j = 0
    while (j < positions.size) {
        out[j]     = positions[j] + dx
        out[j + 1] = positions[j + 1] + dy
        out[j + 2] = positions[j + 2] + dz
        j += 3
    }
    return out
}

@Composable
internal fun AiNamingFailureChip(modelTried: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "AI naming unavailable — using default labels" +
                (modelTried?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}
