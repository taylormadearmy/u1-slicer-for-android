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
    onSetSlotColor: (slotIndex: Int, hex: String) -> Unit = { _, _ -> },
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
                            android.util.Log.i(
                                "AiPaintTap",
                                "tap tri=$triangleIdx → leaf=${leaf?.region?.id} " +
                                    "label='${leaf?.region?.label}' slot=${leaf?.region?.slot} " +
                                    "size=${leaf?.triangleIds?.size} " +
                                    "source=${result.source.name}"
                            )
                            if (leaf != null) {
                                onHighlightComponent(
                                    if (result.highlightComponentId == leaf.region.id) null else leaf.region.id
                                )
                            }
                        },
                        // fix35.2: tap empty viewer area = clear highlight.
                        onEmptyTap = { onHighlightComponent(null) },
                        slotPaletteFloats = slotPaletteFloats,
                        paintMode = paintMode,
                        lassoMode = lassoMode,
                        brushRadiusWorld = brushRadiusWorld,
                        brushPct = brushPct,
                        activeRegion = paintActiveRegion,
                        onPaintTriangles = { tris, slot ->
                            // Paint mode applies immediately to active slot. Lasso is a
                            // separate channel via onLassoLoop — no per-tick painting.
                            onPaintTriangles(tris, slot)
                        },
                        onBrushStrokeStart = { onBrushStrokeStart() },
                        onLassoLoop = { tris ->
                            // fix42: polygon lasso UP → auto-commit the enclosed front-facing
                            // triangles to the currently-active slot. No intermediate selection
                            // step; the user already picked the colour by tapping a swatch
                            // before drawing the loop. Snapshot for undo first so a single
                            // lasso stroke is one undo step.
                            if (tris.isNotEmpty()) {
                                onBrushStrokeStart()
                                onCommitSelection(tris, paintActiveRegion)
                            }
                        },
                        lassoSelection = lassoSelection,
                        highlightedTriangles = highlightedTriangles,
                        modifier = Modifier.fillMaxSize()
                            .background(Color(0xFF111118))
                    )

                    // fix41: tool strip overlaid on the TOP of the viewer (replaces the
                    // PaintModeBar that previously sat below the viewer). Tools live with the
                    // model they act on, not with the regions list.
                    ViewerToolbar(
                        paintMode = paintMode,
                        lassoMode = lassoMode,
                        brushPct = brushPct,
                        selectionSize = lassoSelection.size,
                        onSelect = {
                            paintMode = false
                            lassoMode = false
                            lassoSelection = emptySet()
                            // Tapping Select also clears the region highlight — Select mode is
                            // for browsing, not for committing edits.
                            onHighlightComponent(null)
                        },
                        onTogglePaint = {
                            paintMode = !paintMode
                            if (paintMode) lassoMode = false
                            lassoSelection = emptySet()
                            // fix41.1: arming Paint clears the yellow region highlight so the
                            // user can actually see the model as they paint. The fade overlay
                            // would otherwise leave everything-but-the-selected-region dimmed
                            // throughout the paint stroke.
                            if (paintMode) onHighlightComponent(null)
                        },
                        onToggleLasso = {
                            lassoMode = !lassoMode
                            if (lassoMode) paintMode = false
                            lassoSelection = emptySet()
                            // fix41.1: same reason as Paint — clear the region highlight on arm
                            // so the lasso drag isn't obscured by the existing fade.
                            if (lassoMode) onHighlightComponent(null)
                        },
                        onBrushSizeChange = { brushPct = it },
                        onClearSelection = { lassoSelection = emptySet() },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(8.dp),
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

                    // fix41 panel order: primary view toggle → AI failure chip (if any) →
                    // slot palette row → tree → tip → bottom bar. Manual paint/lasso/brush
                    // moved INTO the viewer overlay (ViewerToolbar above).

                    // Painted ⇄ Regions toggle promoted to the top of the panel.
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
                                .padding(horizontal = 12.dp, vertical = 6.dp),
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

                    // F54 — AI failure chip surfaces when AI naming was attempted but failed.
                    if (result.aiNamingFailed) {
                        AiNamingFailureChip(
                            modelTried = result.aiModelTried,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }

                    // Consolidated slot palette row. Tap behaviour depends on the toolbar mode
                    // armed via ViewerToolbar:
                    //   • Select (default) → tap a swatch opens the colour picker for that slot
                    //   • Paint            → tap a swatch sets the active paint slot (tick mark)
                    //   • Lasso + items    → tap a swatch commits the selection to that slot
                    //   • Lasso + empty    → tap a swatch sets the active slot for next stroke
                    SlotPaletteRow(
                        slotPalette = slotPalette,
                        paintMode = paintMode,
                        lassoMode = lassoMode,
                        activeSlot = paintActiveRegion,
                        hasLassoSelection = false,
                        onTapSlot = { slot ->
                            when {
                                paintMode || lassoMode -> paintActiveRegion = slot
                                else -> editSlotColour = slot
                            }
                        },
                    )

                    val treeWithCustom = result.tree + listOfNotNull(
                        com.u1.slicer.aipaint.CustomSelections.buildGroup(result.customSelections)
                    )
                    AiPaintTree(
                        tree = treeWithCustom,
                        slotPalette = slotPalette,
                        onTapSwatch = { nodeSlot -> editSlotColour = nodeSlot },
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
        // fix38.4: tapping a slot swatch now edits the underlying EXTRUDER PRESET colour
        // (persisted via SlicerViewModel.setSlotColor). The change propagates to every
        // surface that reads filamentColours / extruderPresets — Smart Paint picker, tree
        // rows, Prepare filaments row, Map dialog — so the user only edits it once and the
        // change sticks for future loads.
        if (slot in 0..3) {
            val initialHex = filamentColours.getOrNull(slot)
                ?: com.u1.slicer.data.ExtruderPreset.DEFAULT_COLORS.getOrNull(slot)
                ?: "#888888"
            FilamentColorEditDialog(
                initialHex = initialHex,
                onSave = { hex ->
                    onSetSlotColor(slot, hex)
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
    lassoMode: Boolean,
    brushRadiusWorld: Float,
    brushPct: Float,
    activeRegion: Int,
    onPaintTriangles: (List<Int>, Int) -> Unit,
    onBrushStrokeStart: () -> Unit,
    onLassoLoop: (List<Int>) -> Unit,
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
    // fix42: live lasso path in view-local pixels — populated on every MOVE while lasso-
    // dragging, cleared on UP. Drawn as a yellow polyline overlay on top of the GL surface.
    var lassoPathPx by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var viewerSizePx by remember { mutableStateOf(IntSize.Zero) }

    // Recenter the mesh on the U1 build plate so it sits in frame. The pipeline retains
    // file-space coordinates (which may be far from the bed origin for raw STLs); the viewer
    // draws a 270×270 plate at (0..270, 0..270) and would render those models in the corner
    // or off-plate entirely. We translate XY so the bounding box is centred at (135, 135) and
    // Z so the bottom rests on the plate.
    val recenteredPositions = remember(state.trianglePositions) {
        recenterForBed(state.trianglePositions)
    }
    // fix38.5: compute a CameraViewState that fits the recentered mesh, then apply it after
    // setMesh. Default camera targets (135, 135, 0) with distance 500 — fits the whole 270mm
    // bed, which makes a small model (e.g. a 50mm goat) look tiny → tap precision is poor.
    // Fitting to the model's bounding diagonal puts the whole model on screen with margin.
    val fitCamera = remember(recenteredPositions) {
        computeFitCameraState(recenteredPositions)
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
                    fitCamera?.let { view.applyCameraState(it) }
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
        // fix42: live lasso polygon overlay — yellow polyline that follows the user's finger
        // while they draw the loop. Drawn as a closed Path with a faint fill so the enclosed
        // area is visible during the drag. Cleared on UP by ModelViewerView's path-empty emit.
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
    // (which doesn't change paintMode/activeRegion/brushRadiusWorld) left the GL view holding
    // a stale onTriangleTapped that referenced the previous tab's tree, so tap returned a
    // leaf id from the old tree and findNodeById on the new tree returned null → no
    // visible selection.
    val currentTapped by rememberUpdatedState(onTriangleTapped)
    val currentEmptyTap by rememberUpdatedState(onEmptyTap)
    val currentPaint by rememberUpdatedState(onPaintTriangles)
    val currentStrokeStart by rememberUpdatedState(onBrushStrokeStart)
    val currentLassoLoop by rememberUpdatedState(onLassoLoop)

    // Wire up the GL-view callbacks every time the mode flips. Three exclusive modes:
    //   paintMode → brush stroke (immediate paint per tick)
    //   lassoMode → polygon lasso (path captured, auto-commit on UP)
    //   neither   → tap-to-highlight
    LaunchedEffect(viewerView, paintMode, lassoMode, activeRegion, brushRadiusWorld) {
        val v = viewerView ?: return@LaunchedEffect
        v.brushRadiusWorld = brushRadiusWorld
        // Clear all callbacks first; subsequent block re-wires the active mode.
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
                v.onBrushPaint = { tris -> currentPaint(tris, activeRegion) }
                v.onBrushStrokeStart = { currentStrokeStart() }
                v.onBrushTouchAt = { x, y ->
                    brushTouchPx = if (x < 0f) null else x to y
                }
            }
            lassoMode -> {
                v.lassoMode = true
                v.onLassoLoop = { tris -> currentLassoLoop(tris) }
                v.onLassoPathUpdate = { path -> lassoPathPx = path }
                brushTouchPx = null
            }
            else -> {
                v.onTriangleTapped = { tri -> currentTapped(tri) }
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
                // fix38.2: stronger fade for more contrast. 25% original + tiny dark floor
                // keeps the colour identity readable but pushes everything dark enough that
                // the selected region pops.
                val fadedSlotColours = baseSlotColours.map { c ->
                    floatArrayOf(
                        c[0] * 0.25f + 0.04f,
                        c[1] * 0.25f + 0.04f,
                        c[2] * 0.25f + 0.04f,
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

/**
 * fix41 ViewerToolbar — overlay strip sat at the top of the 3D viewer. Holds the three
 * mode chips (Select / Paint / Lasso) and the brush-size slider when a manual mode is
 * armed. Placing this on the viewer (not in the regions panel) keeps the tools visually
 * grouped with the model they act on.
 */
@Composable
private fun ViewerToolbar(
    paintMode: Boolean,
    lassoMode: Boolean,
    brushPct: Float,
    selectionSize: Int,
    onSelect: () -> Unit,
    onTogglePaint: () -> Unit,
    onToggleLasso: () -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onClearSelection: () -> Unit,
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
            // fix42: brush slider only meaningful for Paint mode. Lasso draws a polygon, no
            // thickness involved, so no slider.
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

/**
 * fix41 SlotPaletteRow — consolidated "Extruders →" row that lives below the viewer.
 * Always visible. Tap behaviour is mode-aware (select/paint/lasso), driven by the parent
 * via [onTapSlot]. Visual indicator on the swatch shows what the next tap will do.
 */
@Composable
private fun SlotPaletteRow(
    slotPalette: List<Color>,
    paintMode: Boolean,
    lassoMode: Boolean,
    activeSlot: Int,
    hasLassoSelection: Boolean,
    onTapSlot: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            when {
                lassoMode && hasLassoSelection -> "Apply to →"
                paintMode || lassoMode -> "Active →"
                else -> "Extruders →"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        slotPalette.take(com.u1.slicer.aipaint.AiPaintViewModel.TARGET_SLOTS).forEachIndexed { idx, color ->
            val isActive = (paintMode || lassoMode) && idx == activeSlot && !hasLassoSelection
            Box(
                Modifier
                    .size(if (isActive) 44.dp else 40.dp)
                    .background(color, MaterialTheme.shapes.small)
                    .clickable { onTapSlot(idx) },
                contentAlignment = Alignment.Center,
            ) {
                val tickColor = tickContrastColor(color)
                when {
                    isActive -> Text("✓", color = tickColor, style = MaterialTheme.typography.labelLarge)
                    !paintMode && !lassoMode ->
                        Text("✎", color = tickColor, style = MaterialTheme.typography.labelMedium)
                    else -> {}
                }
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

/** fix38.5: compute a CameraViewState that frames the model nicely (target = model centroid,
 *  distance proportional to model diagonal). Without this the default camera fits the whole
 *  270×270 bed which makes small models look tiny and breaks tap precision. */
private fun computeFitCameraState(positions: FloatArray): com.u1.slicer.viewer.CameraViewState? {
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
    // Distance ≈ 2x diagonal for a 45° FOV — fits the model with ~20% margin.
    return com.u1.slicer.viewer.CameraViewState(
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
