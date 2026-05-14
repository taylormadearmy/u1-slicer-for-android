package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.u1.slicer.aipaint.*
import com.u1.slicer.viewer.ModelViewerView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPaintResultScreen(
    uiState: AiPaintUiState,
    onUsePainting: (paintedPath: String) -> Unit,
    onRedo: () -> Unit,
    onBack: () -> Unit,
    onNavigateSettings: () -> Unit = {},
    filamentColours: List<String> = emptyList(),
    onMoveComponent: (componentId: Int, toRegion: Int) -> Unit = { _, _ -> },
    onHighlightComponent: (componentId: Int?) -> Unit = {},
    onUpdateRegionColour: (regionId: Int, hex: String) -> Unit = { _, _ -> },
    onPaintTriangles: (triangleIds: List<Int>, toRegion: Int) -> Unit = { _, _ -> },
    onBrushStrokeStart: () -> Unit = {},
    onUndo: () -> Unit = {},
    onSetSegmentSlot: (segmentId: Int, newSlot: Int) -> Unit = { _, _ -> },
    onCommitSelection: (triangleIds: List<Int>, toSlot: Int) -> Unit = { _, _ -> },
    onSwitchToAlternate: () -> Unit = {},
) {
    var swapSheetRegion by remember { mutableStateOf<AiRegion?>(null) }
    var moveSheetComponent by remember { mutableStateOf<Int?>(null) }
    // Which slot the HSV colour picker is open for (or null when closed). Driven by tapping the
    // leading swatch on a RegionRow — applies the new hex to the slot's canonical region so the
    // 3D viewer palette updates too.
    var editSlotColour by remember { mutableStateOf<Int?>(null) }
    var paintMode by remember { mutableStateOf(false) }
    var lassoMode by remember { mutableStateOf(false) }
    var paintActiveRegion by remember { mutableStateOf(0) }
    // Brush radius as a fraction of the model's bbox diagonal. 0 = single triangle.
    var brushPct by remember { mutableStateOf(0.03f) }
    // Lasso selection: per-triangle index set. Drag adds to it; tapping a slot chip commits.
    // Stored as an immutable Set and reassigned on each change so Compose actually recomposes
    // (mutating a MutableSet in place and re-setting the same reference is a no-op for state).
    var lassoSelection by remember { mutableStateOf<Set<Int>>(emptySet()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Paint") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            when (uiState) {
                is AiPaintUiState.Running -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Phase ${uiState.phase}/4", style = MaterialTheme.typography.labelMedium)
                            Text(uiState.phaseLabel, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                is AiPaintUiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
                            Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
                            Text(uiState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Button(onClick = onRedo) { Text("Try again") }
                            if (uiState.message.contains("Settings", ignoreCase = true)) {
                                TextButton(onClick = onNavigateSettings) { Text("Open Settings") }
                            }
                        }
                    }
                }

                is AiPaintUiState.Result -> {
                    val result = uiState.state

                    // Compute the model's bounding-box diagonal once per pipeline run; the brush
                    // radius slider expresses size as a fraction of that diagonal so behaviour
                    // is consistent regardless of model scale.
                    val modelDiagonal = remember(result.trianglePositions) {
                        if (result.trianglePositions.isEmpty()) 1f
                        else {
                            var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
                            var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
                            var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
                            val p = result.trianglePositions
                            var i = 0
                            while (i < p.size) {
                                val x = p[i]; val y = p[i + 1]; val z = p[i + 2]
                                if (x < minX) minX = x; if (x > maxX) maxX = x
                                if (y < minY) minY = y; if (y > maxY) maxY = y
                                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                                i += 3
                            }
                            val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
                            kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1f)
                        }
                    }
                    val brushRadiusWorld = brushPct * modelDiagonal

                    // fix35.2: slot palette comes from the user's loaded filament colours
                    // directly (passed in as filamentColours), NOT from whatever leaves are
                    // currently assigned to each slot. The leaves-grouped approach drifted
                    // because reassignments left some slots with no leaves on them — the
                    // picker would then show grey instead of the real filament colour.
                    val slotPalette: List<Color> = remember(filamentColours) {
                        (0 until com.u1.slicer.aipaint.SegmentationCascade.TARGET_SLOTS).map { slot ->
                            val hex = filamentColours.getOrNull(slot) ?: "#888888"
                            Color(
                                runCatching { android.graphics.Color.parseColor(hex) }
                                    .getOrDefault(android.graphics.Color.GRAY)
                            )
                        }
                    }
                    // fix38.1: float-array form of slotPalette for the GL renderer. Used by
                    // AiPaintViewer's recolor path so triangleRegions[t] (a slot byte 0..3)
                    // correctly indexes into the user's loaded filament colours, regardless of
                    // how many leaves are currently assigned to that slot or in what order.
                    val slotPaletteFloats: List<FloatArray> = remember(slotPalette) {
                        slotPalette.map { c ->
                            floatArrayOf(c.red, c.green, c.blue, c.alpha)
                        }
                    }

                    // Triangle set for the currently-highlighted tree node, derived from the
                    // node's stored triangleIds. Walks the tree once whenever the highlight or
                    // the tree changes. Empty when nothing is highlighted → renderer falls back
                    // to the natural slot palette.
                    val highlightedTriangles: Set<Int> = remember(result.tree, result.highlightComponentId, result.customSelections) {
                        val id = result.highlightComponentId ?: return@remember emptySet()
                        val withCustom = result.tree + listOfNotNull(
                            com.u1.slicer.aipaint.CustomSelections.buildGroup(result.customSelections)
                        )
                        findNodeById(withCustom, id)
                            ?.let { it.triangleIds.toHashSet() }
                            ?: emptySet()
                    }
                    val highlightedNode: com.u1.slicer.aipaint.AiRegionNode? = remember(result.tree, result.highlightComponentId, result.customSelections) {
                        val id = result.highlightComponentId ?: return@remember null
                        val withCustom = result.tree + listOfNotNull(
                            com.u1.slicer.aipaint.CustomSelections.buildGroup(result.customSelections)
                        )
                        findNodeById(withCustom, id)
                    }

                    // Live 3D viewer — wrapped in a Box so the HighlightSlotPicker overlay can
                    // float at the bottom of the viewer when a region is selected.
                    Box(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.42f),
                    ) {
                    AiPaintViewer(
                        state = result,
                        onTriangleTapped = { triangleIdx ->
                            // Find the deepest leaf whose triangleIds contain the tapped index
                            // and highlight it. The tree row also visually selects.
                            val withCustom = result.tree + listOfNotNull(
                                com.u1.slicer.aipaint.CustomSelections.buildGroup(result.customSelections)
                            )
                            val leaf = findLeafContainingTriangle(withCustom, triangleIdx)
                            if (leaf != null) {
                                onHighlightComponent(
                                    if (result.highlightComponentId == leaf.region.id) null else leaf.region.id
                                )
                            }
                        },
                        // fix35.2: tap empty viewer area = clear highlight.
                        onEmptyTap = { onHighlightComponent(null) },
                        slotPaletteFloats = slotPaletteFloats,
                        paintMode = paintMode || lassoMode,
                        brushRadiusWorld = brushRadiusWorld,
                        brushPct = brushPct,
                        activeRegion = paintActiveRegion,
                        onPaintTriangles = { tris, slot ->
                            if (lassoMode) {
                                // Lasso: union into a NEW set each call so Compose sees a state
                                // change and the yellow overlay updates mid-drag.
                                if (tris.isNotEmpty()) {
                                    lassoSelection = lassoSelection + tris
                                }
                            } else {
                                // Paint mode: apply immediately to active slot.
                                onPaintTriangles(tris, slot)
                            }
                        },
                        onBrushStrokeStart = {
                            if (!lassoMode) onBrushStrokeStart()
                        },
                        lassoSelection = lassoSelection,
                        highlightedTriangles = highlightedTriangles,
                        modifier = Modifier.fillMaxSize()
                            .background(Color(0xFF111118))
                    )

                    // fix35.1: floating slot picker overlay shown when a region is currently
                    // highlighted. Provides the "what now?" action — tap a slot to assign the
                    // highlighted region; × clears. Solves the user-feedback "Select a region
                    // on the 3D view — there is no way to change the colour".
                    if (highlightedNode != null) {
                        HighlightSlotPicker(
                            label = highlightedNode.region.label,
                            currentSlot = highlightedNode.region.slot,
                            slotPalette = slotPalette,
                            onPickSlot = { slot ->
                                // fix35.2: don't clear the highlight after reassign — the user
                                // wants to iterate (tap red, see it, tap blue, see that, etc).
                                // The X / different-region / empty-tap paths handle clearing.
                                onSetSegmentSlot(highlightedNode.region.id, slot)
                            },
                            onDismiss = { onHighlightComponent(null) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                        )
                    }
                    }  // end Box wrapper for viewer + overlay

                    // Mode toolbar — Paint and Lasso are mutually exclusive.
                    PaintModeBar(
                        paintMode = paintMode,
                        lassoMode = lassoMode,
                        regions = result.regions,
                        activeRegion = paintActiveRegion,
                        brushPct = brushPct,
                        onTogglePaintMode = {
                            paintMode = !paintMode
                            if (paintMode) lassoMode = false
                            lassoSelection = emptySet()
                        },
                        onToggleLassoMode = {
                            lassoMode = !lassoMode
                            if (lassoMode) paintMode = false
                            lassoSelection = emptySet()
                        },
                        onSelectRegion = { newSlot ->
                            if (lassoMode && lassoSelection.isNotEmpty()) {
                                // Tap a slot chip in Lasso mode = commit the selection.
                                onCommitSelection(lassoSelection.toList(), newSlot)
                                lassoSelection = emptySet()
                            } else {
                                paintActiveRegion = newSlot
                            }
                        },
                        onEditSlotColour = { slot -> editSlotColour = slot },
                        onBrushSizeChange = { brushPct = it },
                        onClearSelection = { lassoSelection = emptySet() },
                        selectionSize = lassoSelection.size,
                    )

                    Text(
                        when {
                            lassoMode && lassoSelection.isEmpty() ->
                                "LASSO — drag on the 3D model to highlight an area, then tap a slot colour"
                            lassoMode -> "LASSO — tap a slot colour to apply, or × to clear"
                            paintMode -> "PAINT — tap a slot colour, then drag to paint"
                            else -> "REGIONS — tap a slot swatch on the right to remap a region"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                    )
                    // Build the 4 slot palette colours from the cascade root's leaves, grouped
                    // slotPalette hoisted to above the AiPaintViewer (see line ~127).

                    // F54 — AI failure chip surfaces when AI naming was attempted but failed.
                    if (result.aiNamingFailed) {
                        AiNamingFailureChip(
                            modelTried = result.aiModelTried,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }

                    // fix38: Parts ⇄ Regions toggle when an alternate view is available.
                    if (result.alternateSource != null) {
                        val (partsSource, regionsSource) = when (result.source) {
                            com.u1.slicer.aipaint.SegmentationSource.TOPOLOGY,
                            com.u1.slicer.aipaint.SegmentationSource.TOPOLOGY_RECURSIVE ->
                                result.alternateSource to result.source
                            else -> result.source to result.alternateSource
                        }
                        val isPartsActive = result.source == partsSource
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = isPartsActive,
                                onClick = { if (!isPartsActive) onSwitchToAlternate() },
                                label = { Text(viewLabelFor(partsSource)) },
                            )
                            FilterChip(
                                selected = !isPartsActive,
                                onClick = { if (isPartsActive) onSwitchToAlternate() },
                                label = { Text(viewLabelFor(regionsSource)) },
                            )
                        }
                    }

                    val treeWithCustom = result.tree + listOfNotNull(
                        com.u1.slicer.aipaint.CustomSelections.buildGroup(result.customSelections)
                    )
                    AiPaintTree(
                        tree = treeWithCustom,
                        slotPalette = slotPalette,
                        onTapSwatch = { nodeId -> editSlotColour = nodeId },
                        onPickSlot = { path, slot ->
                            // fix35.2: tapping a slot chip on a row also highlights that row
                            // (so the model lights up the affected triangles in yellow + the
                            // overlay picker appears below the viewer). Reassign + highlight is
                            // one coherent action.
                            if (path.isNotEmpty()) {
                                val nodeId = path.last()
                                onSetSegmentSlot(nodeId, slot)
                                onHighlightComponent(nodeId)
                            }
                        },
                        onSelectNode = { nodeId ->
                            // Highlight the node's triangles on the 3D viewer so users see what
                            // they're about to operate on. Re-tapping the same row clears.
                            onHighlightComponent(
                                if (result.highlightComponentId == nodeId) null else nodeId
                            )
                        },
                        selectedNodeId = result.highlightComponentId,
                        modifier = Modifier.weight(1f),
                    )

                    if (!paintMode) {
                        Text(
                            "Tip: tap a part of the 3D model to move it to a different region.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    // fix37: compact button row. Undo + Re-run as icon-only buttons (saves
                    // horizontal space and is visually consistent), the primary action takes
                    // the remaining width so its label never wraps on narrow phones.
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onUndo,
                            enabled = result.canUndo,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            modifier = Modifier.size(width = 48.dp, height = 40.dp),
                        ) {
                            Text("↶", style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedButton(
                            onClick = onRedo,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            modifier = Modifier.size(width = 48.dp, height = 40.dp),
                        ) {
                            Icon(Icons.Default.Refresh, "Re-run AI",
                                modifier = Modifier.size(20.dp))
                        }
                        Button(
                            onClick = { onUsePainting(result.paintedModelPath) },
                            modifier = Modifier.weight(1f).height(40.dp),
                        ) {
                            Text("Use this painting →")
                        }
                    }
                }

                else -> {}
            }
        }
    }

    swapSheetRegion?.let { region ->
        ColourSwapSheet(
            region = region,
            filamentColours = filamentColours,
            onApply = { newColour ->
                onUpdateRegionColour(region.id, newColour)
                swapSheetRegion = null
            },
            onDismiss = { swapSheetRegion = null }
        )
    }

    moveSheetComponent?.let { componentId ->
        val regions = (uiState as? AiPaintUiState.Result)?.state?.regions ?: emptyList()
        val currentRegion = (uiState as? AiPaintUiState.Result)?.state
            ?.componentToRegion?.getOrNull(componentId)
        MoveComponentSheet(
            componentId = componentId,
            currentRegion = currentRegion,
            regions = regions,
            onMoveToSlot = { toSlot ->
                // componentId IS the segment index in the post-fix32 pipeline (numComponents =
                // TARGET_SEGMENTS, componentToRegion is identity). Reusing setSegmentSlot keeps
                // the slot reassignment in one code path.
                onSetSegmentSlot(componentId, toSlot)
                onHighlightComponent(null)
                moveSheetComponent = null
            },
            onDismiss = {
                onHighlightComponent(null)
                moveSheetComponent = null
            }
        )
    }

    editSlotColour?.let { slot ->
        val regions = (uiState as? AiPaintUiState.Result)?.state?.regions ?: emptyList()
        // The canonical region for a slot is the one whose id matches the slot number — that's
        // what the 3D viewer's palette pulls from (regions[0..3]).
        val canonical = regions.firstOrNull { it.id == slot } ?: regions.getOrNull(slot)
        if (canonical != null) {
            FilamentColorEditDialog(
                initialHex = canonical.effectiveColour,
                onSave = { hex ->
                    onUpdateRegionColour(canonical.id, hex)
                    editSlotColour = null
                },
                onDismiss = { editSlotColour = null },
            )
        } else {
            editSlotColour = null
        }
    }
}

@Composable
private fun AiPaintViewer(
    state: AiPaintResultState,
    onTriangleTapped: (Int) -> Unit,
    onEmptyTap: () -> Unit,
    paintMode: Boolean,
    brushRadiusWorld: Float,
    brushPct: Float,
    activeRegion: Int,
    onPaintTriangles: (List<Int>, Int) -> Unit,
    onBrushStrokeStart: () -> Unit,
    /** fix38.1: SLOT-indexed palette (size = TARGET_SLOTS = 4). The renderer applies
     *  palette[triangleRegions[t]] where triangleRegions[t] stores the slot byte 0..3. Previously
     *  AiPaintViewer built a LEAF-indexed palette from state.regions, which only happened to
     *  work when leaves were laid out in slot order. After any reassignment, palette[slot] would
     *  return the wrong leaf's effectiveColour — e.g. all-red leaves rendered blue. */
    slotPaletteFloats: List<FloatArray>,
    lassoSelection: Set<Int> = emptySet(),
    highlightedTriangles: Set<Int> = emptySet(),
    modifier: Modifier = Modifier,
) {
    var viewerView by remember { mutableStateOf<ModelViewerView?>(null) }
    // Latest brush touch position in viewer-local pixels, or null when the finger is up.
    var brushTouchPx by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var viewerSizePx by remember { mutableStateOf(IntSize.Zero) }

    // Recenter the mesh on the U1 build plate so it sits in frame. The pipeline retains
    // file-space coordinates (which may be far from the bed origin for raw STLs); the viewer
    // draws a 270×270 plate at (0..270, 0..270) and would render those models in the corner
    // or off-plate entirely. We translate XY so the bounding box is centred at (135, 135) and
    // Z so the bottom rests on the plate.
    val recenteredPositions = remember(state.trianglePositions) {
        recenterForBed(state.trianglePositions)
    }

    // The mesh is built ONCE per pipeline run. Subsequent paints mutate extruderIndices in
    // place via ModelViewerView.updateExtruderIndices — far cheaper than rebuilding the VBO.
    val mesh = remember(recenteredPositions) {
        if (state.triangleRegions.isEmpty()) null
        else AiPaintMeshBuilder.build(recenteredPositions, state.triangleRegions)
    }

    // fix38.1: palette is now SLOT-indexed (4 entries) supplied from the screen. The renderer
    // reads palette[triangleRegions[t]] where triangleRegions[t] is the slot byte 0..3.
    val regionPalette = slotPaletteFloats

    Box(modifier = modifier.onSizeChanged { viewerSizePx = it }) {
        AndroidView(
            factory = { ctx ->
                ModelViewerView(ctx).also { view ->
                    viewerView = view
                    mesh?.let { view.setMesh(it) }
                    view.setTrianglePickingPositions(recenteredPositions)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        // Brush ring overlay — drawn on top of the GL surface at the latest touch position.
        // Radius is brushPct * 85% of the smaller viewer dimension (mirroring the renderer's
        // 0.85 model-fill factor) so the on-screen circle matches the actual painted area.
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
    }

    // Wire up the tap callbacks every time the mode flips. Paint mode → onBrushPaint, default
    // mode → onTriangleTapped (for the move-component sheet).
    LaunchedEffect(viewerView, paintMode, activeRegion, brushRadiusWorld) {
        val v = viewerView ?: return@LaunchedEffect
        v.brushRadiusWorld = brushRadiusWorld
        if (paintMode) {
            v.onBrushPaint = { tris -> onPaintTriangles(tris, activeRegion) }
            v.onBrushStrokeStart = { onBrushStrokeStart() }
            v.onBrushTouchAt = { x, y ->
                brushTouchPx = if (x < 0f) null else x to y
            }
            v.onTriangleTapped = null
            v.onEmptyTap = null
        } else {
            v.onBrushPaint = null
            v.onBrushStrokeStart = null
            v.onBrushTouchAt = null
            v.onTriangleTapped = onTriangleTapped
            // fix35.2: tap on empty viewer background clears the highlight.
            v.onEmptyTap = onEmptyTap
            brushTouchPx = null
        }
    }

    LaunchedEffect(mesh, viewerView) {
        val v = viewerView ?: return@LaunchedEffect
        val m = mesh ?: return@LaunchedEffect
        v.setMesh(m)
        v.setTrianglePickingPositions(recenteredPositions)
    }

    // Whenever the per-triangle regions, palette, highlight target, or lasso selection changes:
    // update extruder indices in place and recolor. Three overlay modes:
    //   * tap-to-select highlight (from tree row or model tap): selected node's triangles
    //     yellow, rest dimmed.
    //   * lasso selection: selected triangles yellow, rest keep their slot colour.
    //   * none: render the natural slot palette.
    LaunchedEffect(viewerView, state.triangleRegions, highlightedTriangles, regionPalette, lassoSelection) {
        val v = viewerView ?: return@LaunchedEffect
        if (state.triangleRegions.isEmpty()) return@LaunchedEffect
        when {
            highlightedTriangles.isNotEmpty() -> {
                // fix35.3: highlight in the region's REAL slot colour (was yellow). The rest of
                // the model fades but keeps its colour identity — lerp 50% toward a medium dark
                // grey, which desaturates and darkens but preserves hue. Slot-indexed palette
                // doubles up: indices 0..3 = full slot colours (for highlighted triangles),
                // indices 4..7 = faded versions (for everything else).
                val slotCount = com.u1.slicer.aipaint.SegmentationCascade.TARGET_SLOTS
                val baseSlotColours = (0 until slotCount).map { i ->
                    regionPalette.getOrNull(i) ?: floatArrayOf(0.55f, 0.55f, 0.55f, 1f)
                }
                val fadedSlotColours = baseSlotColours.map { c ->
                    floatArrayOf(
                        c[0] * 0.5f + 0.10f,
                        c[1] * 0.5f + 0.10f,
                        c[2] * 0.5f + 0.10f,
                        c[3],
                    )
                }
                val overlay = ByteArray(state.triangleRegions.size) { i ->
                    val baseSlot = (state.triangleRegions[i].toInt() and 0xFF).coerceIn(0, slotCount - 1)
                    if (i in highlightedTriangles) baseSlot.toByte() else (baseSlot + slotCount).toByte()
                }
                v.updateExtruderIndices(overlay)
                v.recolorMesh(baseSlotColours + fadedSlotColours)
            }
            lassoSelection.isNotEmpty() -> {
                // Reserve a fresh palette index past whatever the regions need so we don't clash
                // with any existing slot colour. Selected triangles get that index; the rest keep
                // their current slot colour so the unselected paint stays visible.
                val highlightIdx = regionPalette.size.coerceAtMost(254)
                val overlay = ByteArray(state.triangleRegions.size) { i ->
                    if (i in lassoSelection) highlightIdx.toByte() else state.triangleRegions[i]
                }
                val extended = regionPalette + listOf(
                    floatArrayOf(1f, 0.92f, 0.20f, 1f) // lasso highlight = yellow
                )
                v.updateExtruderIndices(overlay)
                v.recolorMesh(extended)
            }
            else -> {
                v.updateExtruderIndices(state.triangleRegions)
                v.recolorMesh(AiPaintMeshBuilder.regionPalette(regionPalette))
            }
        }
    }
}

@Composable
private fun PaintModeBar(
    paintMode: Boolean,
    lassoMode: Boolean,
    regions: List<AiRegion>,
    activeRegion: Int,
    brushPct: Float,
    onTogglePaintMode: () -> Unit,
    onToggleLassoMode: () -> Unit,
    onSelectRegion: (Int) -> Unit,
    onEditSlotColour: (Int) -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onClearSelection: () -> Unit,
    selectionSize: Int,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        // Row 1 — the two mutually exclusive mode chips + (lasso only) clear button.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = paintMode,
                onClick = onTogglePaintMode,
                label = { Text(if (paintMode) "🖌 Painting" else "🖌 Paint") },
            )
            FilterChip(
                selected = lassoMode,
                onClick = onToggleLassoMode,
                label = {
                    Text(
                        when {
                            lassoMode && selectionSize > 0 -> "🪄 $selectionSize selected"
                            lassoMode -> "🪄 Lasso"
                            else -> "🪄 Lasso"
                        }
                    )
                },
            )
            if (lassoMode && selectionSize > 0) {
                IconButton(onClick = onClearSelection, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Clear selection")
                }
            }
        }
        // Row 2 — slot swatches. ALWAYS shown (not gated on paint/lasso mode) so users can
        // see and edit slot colours straight from the result screen. Tap behaviour depends on
        // current mode: paint=select-active, lasso=commit-selection, neither=open-picker.
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    lassoMode -> "Apply to →"
                    paintMode -> "Active →"
                    else -> "Slots →"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // One swatch per PHYSICAL filament slot (TARGET_SLOTS=4). Swatch shows the
            // canonical slot colour from regions[slot]. Tap = mode-dependent action; the ✎
            // hint reveals when neither mode is on so users discover the picker.
            val slotChips = regions.take(com.u1.slicer.aipaint.AiPaintViewModel.TARGET_SLOTS)
            slotChips.forEachIndexed { idx, region ->
                val argb = remember(region.effectiveColour) {
                    runCatching { android.graphics.Color.parseColor(region.effectiveColour) }
                        .getOrDefault(android.graphics.Color.GRAY)
                }
                val isActive = paintMode && idx == activeRegion
                Box(
                    Modifier
                        .size(if (isActive) 44.dp else 40.dp)
                        .background(Color(argb), MaterialTheme.shapes.small)
                        .clickable {
                            // Neither mode = colour picker. Paint mode = select active.
                            // Lasso mode = commit (handled by parent's onSelectRegion).
                            if (!paintMode && !lassoMode) onEditSlotColour(idx)
                            else onSelectRegion(idx)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isActive -> Text("✓", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        !paintMode && !lassoMode -> Text("✎", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        else -> {}
                    }
                }
            }
        }
        // Row 3 — brush size slider (only meaningful when painting / lassoing).
        if (paintMode || lassoMode) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Brush",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
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
                    modifier = Modifier.padding(start = 8.dp).widthIn(min = 36.dp)
                )
            }
        }
    }
}

@Composable
private fun RegionRow(
    region: AiRegion,
    slotColours: List<Int>,
    onSetSlot: (Int) -> Unit,
    onEditSlotColour: () -> Unit,
) {
    val partsLabel = if (region.componentIds.size == 1) "1 part" else "${region.componentIds.size} parts"
    ListItem(
        headlineContent = { Text(region.label) },
        supportingContent = {
            Text(
                "${"%.0f".format(region.coverageFraction * 100)}% of model · $partsLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            // Current slot's colour as a chunky swatch. Tap to open an HSV picker for that
            // slot's filament colour — updates the 3D viewer palette and downstream slicing.
            val argb = slotColours.getOrElse(region.slot) { android.graphics.Color.GRAY }
            Box(
                Modifier
                    .size(36.dp)
                    .background(Color(argb), shape = MaterialTheme.shapes.small)
                    .clickable { onEditSlotColour() },
                contentAlignment = Alignment.Center,
            ) {
                Text("✎", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        },
        trailingContent = {
            // 4 small swatches — tap one to remap this segment to that physical slot.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                slotColours.forEachIndexed { slot, argb ->
                    val isActive = slot == region.slot
                    Box(
                        Modifier
                            .size(if (isActive) 22.dp else 18.dp)
                            .background(Color(argb), MaterialTheme.shapes.small)
                            .clickable { onSetSlot(slot) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isActive) {
                            Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
    )
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColourSwapSheet(
    region: AiRegion,
    filamentColours: List<String>,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedColour by remember { mutableStateOf(region.effectiveColour) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).navigationBarsPadding()) {
            Text(region.label, style = MaterialTheme.typography.titleMedium)
            Text("AI suggestion: ${region.suggestedColour}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (filamentColours.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("YOUR LOADED FILAMENTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    filamentColours.forEach { hex ->
                        val argb = runCatching { android.graphics.Color.parseColor(hex) }
                            .getOrDefault(android.graphics.Color.GRAY)
                        Box(
                            Modifier.size(44.dp)
                                .background(Color(argb), MaterialTheme.shapes.small)
                                .clickable { selectedColour = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedColour == hex) {
                                Text("✓", color = Color.White,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = { onApply(selectedColour) }, modifier = Modifier.fillMaxWidth()) {
                Text("Apply to ${region.label}")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveComponentSheet(
    componentId: Int,
    currentRegion: Int?,
    regions: List<AiRegion>,
    onMoveToSlot: (slot: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentRegionObj = currentRegion?.let { regions.getOrNull(it) }
    val currentSlot = currentRegionObj?.slot
    val currentLabel = currentRegionObj?.label ?: "—"
    // The 4 physical filament slots and their canonical colours (from regions[0..3]).
    val slotColours = regions.take(com.u1.slicer.aipaint.AiPaintViewModel.TARGET_SLOTS).map { r ->
        runCatching { android.graphics.Color.parseColor(r.effectiveColour) }
            .getOrDefault(android.graphics.Color.GRAY)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp).navigationBarsPadding()) {
            Text(
                "Move \"$currentLabel\" to a different slot",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Currently in Slot ${(currentSlot ?: 0) + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "TAP A FILAMENT SLOT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                slotColours.forEachIndexed { slot, argb ->
                    val isCurrent = slot == currentSlot
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(if (isCurrent) 56.dp else 52.dp)
                                .background(Color(argb), MaterialTheme.shapes.medium)
                                .clickable(enabled = !isCurrent) { onMoveToSlot(slot) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isCurrent) {
                                Text("✓", color = Color.White, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Slot ${slot + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCurrent) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Translate raw triangle positions onto the U1 bed (270×270 plate origin at corner). The
 *  bounding box is centred at (135, 135) in XY and the lowest Z lands at 0. Output array has
 *  the same length as input. */
/** Friendly label for the Parts/Regions toggle. */
private fun viewLabelFor(source: com.u1.slicer.aipaint.SegmentationSource): String = when (source) {
    com.u1.slicer.aipaint.SegmentationSource.PAINT_STATE -> "🎨 Painted"
    com.u1.slicer.aipaint.SegmentationSource.VOLUME -> "🧩 Parts"
    com.u1.slicer.aipaint.SegmentationSource.OBJECT -> "📦 Objects"
    com.u1.slicer.aipaint.SegmentationSource.TRIANGLE_INDEX -> "🎯 Indices"
    com.u1.slicer.aipaint.SegmentationSource.TOPOLOGY,
    com.u1.slicer.aipaint.SegmentationSource.TOPOLOGY_RECURSIVE -> "🪨 Regions"
    com.u1.slicer.aipaint.SegmentationSource.Z_BAND -> "📏 Bands"
    com.u1.slicer.aipaint.SegmentationSource.BRUSH -> "✏️ Brush"
}

/** Walk the tree looking for a node with [id]. Returns null when no match. */
private fun findNodeById(tree: List<com.u1.slicer.aipaint.AiRegionNode>, id: Int): com.u1.slicer.aipaint.AiRegionNode? {
    for (root in tree) {
        for ((node, _) in root.flatten()) {
            if (node.region.id == id) return node
        }
    }
    return null
}

/** Find the deepest leaf whose triangleIds contain [triangleId]. Custom-selection rows take
 *  priority over cascade leaves so a triangle painted manually highlights the user's stroke,
 *  not the underlying segment it overpaints. */
private fun findLeafContainingTriangle(
    tree: List<com.u1.slicer.aipaint.AiRegionNode>,
    triangleId: Int,
): com.u1.slicer.aipaint.AiRegionNode? {
    var match: com.u1.slicer.aipaint.AiRegionNode? = null
    for (root in tree) {
        for ((node, _) in root.flatten()) {
            if (node.isLeaf && triangleId in node.triangleIds) match = node
        }
    }
    return match
}

private fun recenterForBed(positions: FloatArray): FloatArray {
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
private fun AiNamingFailureChip(modelTried: String?, modifier: Modifier = Modifier) {
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
