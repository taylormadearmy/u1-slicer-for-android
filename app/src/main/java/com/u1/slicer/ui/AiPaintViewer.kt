package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.u1.slicer.aipaint.AiPaintMeshBuilder
import com.u1.slicer.aipaint.AiPaintResultState
import com.u1.slicer.aipaint.AiPaintViewModel
import com.u1.slicer.viewer.ModelViewerView

/**
 * fix44: extracted from AiPaintResultScreen during the 1147-LOC file split. Owns the 3D
 * viewer panel — the GLSurfaceView wrapper, the tool strip overlay, and the slot palette
 * row below the viewer. The screen file is now responsible only for Scaffold + state +
 * tree wiring; everything visual that interacts with the model lives here.
 */

@Composable
internal fun AiPaintViewer(
    state: AiPaintResultState,
    onTriangleTapped: (Int) -> Unit,
    onEmptyTap: () -> Unit,
    paintMode: Boolean,
    lassoMode: Boolean,
    brushRadiusWorld: Float,
    brushPct: Float,
    activeRegion: Int,
    onPaintTriangles: (List<Int>, Int) -> Unit,
    onBrushStrokeStart: () -> Unit,
    onLassoLoop: (List<Int>) -> Unit,
    /** fix38.1 / M3-B: SLOT-indexed EXTENDED palette. Entries 0..3 are the physical
     *  slots (E1-E4); entries 4..(3+nMix) are the active mix-slot blend colours appended
     *  in MixSlotOrdering order. The renderer applies palette[triangleRegions[t]] where
     *  triangleRegions[t] stores the slot byte 0..(3+nMix) — physical bytes 0..3 and mix
     *  bytes >= 4. */
    slotPaletteFloats: List<FloatArray>,
    lassoSelection: Set<Int> = emptySet(),
    highlightedTriangles: Set<Int> = emptySet(),
    modifier: Modifier = Modifier,
) {
    var viewerView by remember { mutableStateOf<ModelViewerView?>(null) }
    // Latest brush touch position in viewer-local pixels, or null when the finger is up.
    var brushTouchPx by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    // fix42: live lasso path in view-local pixels — populated on every MOVE while lasso-
    // dragging, cleared on UP. Drawn as a yellow polyline overlay on top of the GL surface.
    var lassoPathPx by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var viewerSizePx by remember { mutableStateOf(IntSize.Zero) }

    // B115: when the cascade ran on a subsampled mesh (axolotl, korok, colored_benchy etc.),
    // render the FULL original mesh in the viewer — not the 29k-triangle subsampled scaffold
    // that looks like a sparse cloud of dots on a phone-sized viewport. The subsampled mesh
    // is still used for cascade lookups (leaf.triangleIds reference subsampled indices); we
    // translate at the tap/paint/lasso callbacks below so the viewmodel keeps operating in
    // subsampled space.
    val isSubsampled = state.cascadeStride > 1 && state.sourceTrianglePositions.isNotEmpty()
    val displayPositions = if (isSubsampled) state.sourceTrianglePositions else state.trianglePositions
    val recenteredPositions = remember(displayPositions) {
        recenterForBed(displayPositions)
    }
    val fitCamera = remember(recenteredPositions) {
        computeFitCameraState(recenteredPositions)
    }

    // Broadcast subsampled per-triangle slot ids to the full mesh by tile (each subsampled
    // tri covers `stride` source tris). Result: every triangle in displayPositions has the
    // slot of its representative subsampled triangle.
    val displayRegions: ByteArray = remember(state.triangleRegions, state.cascadeStride, displayPositions.size) {
        if (!isSubsampled) {
            state.triangleRegions
        } else {
            val originalCount = displayPositions.size / 9
            val sub = state.triangleRegions
            val stride = state.cascadeStride
            ByteArray(originalCount) { t ->
                val s = (t / stride).coerceAtMost(sub.size - 1).coerceAtLeast(0)
                if (sub.isEmpty()) 0 else sub[s]
            }
        }
    }

    // The mesh is built ONCE per pipeline run, keyed on geometry only. Subsequent paints and
    // colour changes mutate extruderIndices in place via the recolor LaunchedEffect below
    // (updateExtruderIndices / recolorMesh) — far cheaper than rebuilding the VBO, and crucially
    // camera-safe: rebuilding the mesh re-fires LaunchedEffect(mesh), which re-applies fitCamera
    // and snaps the viewer back to the default orientation. `displayRegions` is read here only to
    // size the initial extruderIndices for the full mesh (B115); it must NOT be a remember key,
    // or every paint stroke would rebuild the VBO and reset the user's view.
    val mesh = remember(recenteredPositions) {
        if (displayRegions.isEmpty()) null
        else AiPaintMeshBuilder.build(recenteredPositions, displayRegions)
    }

    val regionPalette = slotPaletteFloats

    Box(modifier = modifier.onSizeChanged { viewerSizePx = it }) {
        AndroidView(
            factory = { ctx ->
                ModelViewerView(ctx).also { view ->
                    viewerView = view
                    mesh?.let { view.setMesh(it) }
                    view.setTrianglePickingPositions(recenteredPositions)
                    fitCamera?.let { view.applyCameraState(it) }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        // Brush ring overlay — drawn on top of the GL surface at the latest touch position.
        val touch = brushTouchPx
        if (paintMode && touch != null && viewerSizePx.width > 0) {
            val px = touch.first
            val py = touch.second
            val ringRadius = brushPct * 0.85f * minOf(viewerSizePx.width, viewerSizePx.height)
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = ringRadius.coerceAtLeast(4f),
                    center = androidx.compose.ui.geometry.Offset(px, py),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                )
            }
        }
        // fix42: live lasso polygon overlay — yellow polyline that follows the user's finger.
        val lassoPts = lassoPathPx
        if (lassoMode && lassoPts.size >= 2) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(lassoPts.first().first, lassoPts.first().second)
                    for (i in 1 until lassoPts.size) lineTo(lassoPts[i].first, lassoPts[i].second)
                    close()
                }
                drawPath(
                    path = path,
                    color = Color(0xFFFFC107).copy(alpha = 0.18f),
                    style = androidx.compose.ui.graphics.drawscope.Fill,
                )
                drawPath(
                    path = path,
                    color = Color(0xFFFFC107),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f),
                )
            }
        }
    }

    // fix40.6: rememberUpdatedState wraps the parent lambdas so the LaunchedEffect's
    // assignment always sees the LATEST closure — without this, swapping Painted ↔ Regions
    // (which doesn't change paintMode/activeRegion/brushRadiusWorld) left the GL view
    // holding a stale onTriangleTapped that referenced the previous tab's tree.
    val currentTapped by rememberUpdatedState(onTriangleTapped)
    val currentEmptyTap by rememberUpdatedState(onEmptyTap)
    val currentPaint by rememberUpdatedState(onPaintTriangles)
    val currentStrokeStart by rememberUpdatedState(onBrushStrokeStart)
    val currentLassoLoop by rememberUpdatedState(onLassoLoop)

    // B115: when rendering the full mesh on a subsampled cascade, the picker returns
    // original-space triangle ids (0..originalCount-1) but `currentTapped`/`currentPaint`/
    // `currentLassoLoop` expect ids in subsampled space (so leaf.triangleIds lookups work).
    // Translate at the callback boundary: original_id → subsampled_id = original_id / stride.
    // De-dup so a brush stroke that covers `stride` originals → one subsampled id.
    val mapTri: (Int) -> Int = if (isSubsampled) {
        val stride = state.cascadeStride
        val maxSub = state.triangleRegions.size - 1;
        { orig -> (orig / stride).coerceIn(0, maxSub) }
    } else { it -> it }
    val mapTris: (List<Int>) -> List<Int> = if (isSubsampled) {
        ({ tris -> tris.map(mapTri).distinct() })
    } else { it -> it }

    // Wire up the GL-view callbacks every time the mode flips. Three exclusive modes:
    //   paintMode → brush stroke (immediate paint per tick)
    //   lassoMode → polygon lasso (path captured, auto-commit on UP)
    //   neither   → tap-to-highlight
    LaunchedEffect(viewerView, paintMode, lassoMode, activeRegion, brushRadiusWorld) {
        val v = viewerView ?: return@LaunchedEffect
        v.brushRadiusWorld = brushRadiusWorld
        v.onBrushPaint = null
        v.onBrushStrokeStart = null
        v.onBrushTouchAt = null
        v.onTriangleTapped = null
        v.onEmptyTap = null
        v.lassoMode = false
        v.onLassoLoop = null
        v.onLassoPathUpdate = null
        when {
            paintMode -> {
                v.onBrushPaint = { tris -> currentPaint(mapTris(tris), activeRegion) }
                v.onBrushStrokeStart = { currentStrokeStart() }
                v.onBrushTouchAt = { x, y ->
                    brushTouchPx = if (x < 0f) null else x to y
                }
            }
            lassoMode -> {
                v.lassoMode = true
                v.onLassoLoop = { tris -> currentLassoLoop(mapTris(tris)) }
                v.onLassoPathUpdate = { path -> lassoPathPx = path }
                brushTouchPx = null
            }
            else -> {
                v.onTriangleTapped = { tri -> currentTapped(mapTri(tri)) }
                v.onEmptyTap = { currentEmptyTap() }
                brushTouchPx = null
                lassoPathPx = emptyList()
            }
        }
    }

    LaunchedEffect(mesh, viewerView) {
        val v = viewerView ?: return@LaunchedEffect
        val m = mesh ?: return@LaunchedEffect
        v.setMesh(m)
        v.setTrianglePickingPositions(recenteredPositions)
        fitCamera?.let { v.applyCameraState(it) }
    }

    // Three overlay modes:
    //   * tap-to-select highlight: selected node's triangles in slot colour, rest dimmed.
    //   * lasso selection: selected triangles yellow, rest keep their slot colour.
    //   * none: render the natural slot palette.
    //
    // B115: overlays are built over `displayRegions` (length = display mesh tri count)
    // not `state.triangleRegions` (length = subsampled count). For the membership tests,
    // a display-space triangle is "highlighted" when its subsampled-space representative
    // (`i / stride`) is in the parent's highlightedTriangles set.
    LaunchedEffect(viewerView, displayRegions, highlightedTriangles, regionPalette, lassoSelection) {
        val v = viewerView ?: return@LaunchedEffect
        if (displayRegions.isEmpty()) return@LaunchedEffect
        val strideForMembership = if (isSubsampled) state.cascadeStride else 1
        val maxSub = (state.triangleRegions.size - 1).coerceAtLeast(0)
        fun displayToSubsampled(i: Int): Int = (i / strideForMembership).coerceIn(0, maxSub)
        when {
            highlightedTriangles.isNotEmpty() -> {
                // I-1: build the base palette over the FULL extended palette (physical
                // slots 0..3 PLUS one entry per active mix at byte >= 4), mirroring the
                // non-highlight branch (`AiPaintMeshBuilder.regionPalette(regionPalette)`).
                // The previous code clamped to TARGET_SLOTS (4) and built only 4 entries,
                // so during a highlight a mix-slot triangle (byte >= 4) rendered as E4's
                // colour instead of its blend. Using the full palette and offsetting the
                // faded copy by the palette size keeps mix bytes pointing at their real
                // blend (highlighted) or its faded variant (dimmed).
                val baseSlotColours = AiPaintMeshBuilder.regionPalette(regionPalette)
                val paletteSize = baseSlotColours.size
                val fadedSlotColours = baseSlotColours.map { c ->
                    floatArrayOf(
                        c[0] * 0.25f + 0.04f,
                        c[1] * 0.25f + 0.04f,
                        c[2] * 0.25f + 0.04f,
                        c[3],
                    )
                }
                val overlay = ByteArray(displayRegions.size) { i ->
                    val baseSlot = (displayRegions[i].toInt() and 0xFF).coerceIn(0, paletteSize - 1)
                    if (displayToSubsampled(i) in highlightedTriangles) baseSlot.toByte()
                    else (baseSlot + paletteSize).toByte()
                }
                v.updateExtruderIndices(overlay)
                v.recolorMesh(baseSlotColours + fadedSlotColours)
            }
            lassoSelection.isNotEmpty() -> {
                val highlightIdx = regionPalette.size.coerceAtMost(254)
                val overlay = ByteArray(displayRegions.size) { i ->
                    if (displayToSubsampled(i) in lassoSelection) highlightIdx.toByte()
                    else displayRegions[i]
                }
                val extended = regionPalette + listOf(
                    floatArrayOf(1f, 0.92f, 0.20f, 1f) // lasso highlight = yellow
                )
                v.updateExtruderIndices(overlay)
                v.recolorMesh(extended)
            }
            else -> {
                v.updateExtruderIndices(displayRegions)
                v.recolorMesh(AiPaintMeshBuilder.regionPalette(regionPalette))
            }
        }
    }
}

/**
 * fix41 ViewerToolbar — overlay strip at the top of the 3D viewer. Holds Select / Paint /
 * Lasso chips plus a brush-size slider when Paint is armed. Tools live on the viewer they
 * act on, not jumbled with the regions list below.
 */
@Composable
internal fun ViewerToolbar(
    paintMode: Boolean,
    lassoMode: Boolean,
    brushPct: Float,
    onSelect: () -> Unit,
    onTogglePaint: () -> Unit,
    onToggleLasso: () -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSelectActive = !paintMode && !lassoMode
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color(0xCC181A20))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = isSelectActive,
                onClick = onSelect,
                label = { Text("→ Select") },
            )
            FilterChip(
                selected = paintMode,
                onClick = onTogglePaint,
                label = { Text("🖌 Paint") },
            )
            FilterChip(
                selected = lassoMode,
                onClick = onToggleLasso,
                label = { Text("🪄 Lasso") },
            )
        }
        if (paintMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Brush",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Slider(
                    value = brushPct,
                    onValueChange = onBrushSizeChange,
                    valueRange = 0f..0.25f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${"%.1f".format(brushPct * 100)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).widthIn(min = 36.dp)
                )
            }
        }
    }
}
