package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
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
    onToggleZBands: () -> Unit = {},
    onSetSegmentSlot: (segmentId: Int, newSlot: Int) -> Unit = { _, _ -> },
    onCommitSelection: (triangleIds: List<Int>, toSlot: Int) -> Unit = { _, _ -> },
) {
    var swapSheetRegion by remember { mutableStateOf<AiRegion?>(null) }
    var moveSheetComponent by remember { mutableStateOf<Int?>(null) }
    var paintMode by remember { mutableStateOf(false) }
    var lassoMode by remember { mutableStateOf(false) }
    var paintActiveRegion by remember { mutableStateOf(0) }
    // Brush radius as a fraction of the model's bbox diagonal. 0 = single triangle.
    var brushPct by remember { mutableStateOf(0.03f) }
    // Lasso selection: per-triangle bitmask set. Drag adds to this; tapping a slot chip commits.
    val lassoSelection = remember { mutableStateOf<MutableSet<Int>>(linkedSetOf()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Paint") },
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

                    // Toggle chip — only shown when both snapshots exist (AI succeeded AND
                    // Z-band was precomputed). Lets the user A/B compare without re-running.
                    val canToggle = result.aiTriangleRegions != null && result.zBandTriangleRegions != null
                    if (canToggle) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = !result.showingZBands,
                                onClick = { if (result.showingZBands) onToggleZBands() },
                                label = { Text("🤖 AI result") },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            FilterChip(
                                selected = result.showingZBands,
                                onClick = { if (!result.showingZBands) onToggleZBands() },
                                label = { Text("📏 Height-based") },
                            )
                        }
                    }

                    if (result.usedAiFallback) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth().padding(8.dp)
                        ) {
                            Text(
                                result.fallbackReason.ifEmpty {
                                    "AI couldn't process this model. Used height-based fallback instead."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

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

                    // Live 3D viewer — replaces the previous static iso bitmap.
                    AiPaintViewer(
                        state = result,
                        onTriangleTapped = { triangleIdx ->
                            val comp = result.componentIds.getOrNull(triangleIdx) ?: return@AiPaintViewer
                            moveSheetComponent = comp
                            onHighlightComponent(comp)
                        },
                        paintMode = paintMode || lassoMode,
                        brushRadiusWorld = brushRadiusWorld,
                        brushPct = brushPct,
                        activeRegion = paintActiveRegion,
                        onPaintTriangles = { tris, slot ->
                            if (lassoMode) {
                                // Lasso: add to the screen-level selection set; commit deferred
                                // until the user taps a slot chip below the model.
                                val current = lassoSelection.value
                                current.addAll(tris)
                                lassoSelection.value = current
                            } else {
                                // Paint mode: apply immediately to active slot.
                                onPaintTriangles(tris, slot)
                            }
                        },
                        onBrushStrokeStart = {
                            if (!lassoMode) onBrushStrokeStart()
                        },
                        lassoSelection = lassoSelection.value,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.42f)
                            .background(Color(0xFF111118))
                    )

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
                            lassoSelection.value = linkedSetOf()
                        },
                        onToggleLassoMode = {
                            lassoMode = !lassoMode
                            if (lassoMode) paintMode = false
                            lassoSelection.value = linkedSetOf()
                        },
                        onSelectRegion = { newSlot ->
                            if (lassoMode && lassoSelection.value.isNotEmpty()) {
                                // Tap a slot chip in Lasso mode = commit the selection.
                                onCommitSelection(lassoSelection.value.toList(), newSlot)
                                lassoSelection.value = linkedSetOf()
                            } else {
                                paintActiveRegion = newSlot
                            }
                        },
                        onBrushSizeChange = { brushPct = it },
                        onClearSelection = { lassoSelection.value = linkedSetOf() },
                        selectionSize = lassoSelection.value.size,
                    )

                    Text(
                        when {
                            lassoMode && lassoSelection.value.isEmpty() ->
                                "LASSO — drag on the 3D model to highlight an area, then tap a slot colour"
                            lassoMode -> "LASSO — tap a slot colour to apply, or × to clear"
                            paintMode -> "PAINT — tap a slot colour, then drag to paint"
                            else -> "REGIONS — tap a slot swatch on the right to remap a region"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                    )
                    // Pre-compute the 4 slot ARGB colours from regions[0..TARGET_SLOTS-1] so we
                    // don't reparse them on every row recomposition.
                    val slotColours = remember(result.regions) {
                        result.regions.take(com.u1.slicer.aipaint.AiPaintViewModel.TARGET_SLOTS)
                            .map { r ->
                                runCatching { android.graphics.Color.parseColor(r.effectiveColour) }
                                    .getOrDefault(android.graphics.Color.GRAY)
                            }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(result.regions) { region ->
                            RegionRow(
                                region = region,
                                slotColours = slotColours,
                                onSetSlot = { newSlot -> onSetSegmentSlot(region.id, newSlot) },
                            )
                        }
                    }

                    if (!paintMode) {
                        Text(
                            "Tip: tap a part of the 3D model to move it to a different region.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onUndo,
                            enabled = result.canUndo,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("↶ Undo")
                        }
                        OutlinedButton(onClick = onRedo, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Redo")
                        }
                        Button(
                            onClick = { onUsePainting(result.paintedModelPath) },
                            modifier = Modifier.weight(2f)
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
            onMove = { toRegion ->
                onMoveComponent(componentId, toRegion)
                onHighlightComponent(null)
                moveSheetComponent = null
            },
            onDismiss = {
                onHighlightComponent(null)
                moveSheetComponent = null
            }
        )
    }
}

@Composable
private fun AiPaintViewer(
    state: AiPaintResultState,
    onTriangleTapped: (Int) -> Unit,
    paintMode: Boolean,
    brushRadiusWorld: Float,
    brushPct: Float,
    activeRegion: Int,
    onPaintTriangles: (List<Int>, Int) -> Unit,
    onBrushStrokeStart: () -> Unit,
    lassoSelection: Set<Int> = emptySet(),
    modifier: Modifier = Modifier,
) {
    var viewerView by remember { mutableStateOf<ModelViewerView?>(null) }
    // Latest brush touch position in viewer-local pixels, or null when the finger is up.
    var brushTouchPx by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var viewerSizePx by remember { mutableStateOf(IntSize.Zero) }

    // The mesh is built ONCE per pipeline run. Subsequent paints mutate extruderIndices in
    // place via ModelViewerView.updateExtruderIndices — far cheaper than rebuilding the VBO.
    val mesh = remember(state.trianglePositions) {
        if (state.triangleRegions.isEmpty()) null
        else AiPaintMeshBuilder.build(state.trianglePositions, state.triangleRegions)
    }

    val regionPalette = remember(state.regions) {
        state.regions.map { r ->
            val argb = runCatching { android.graphics.Color.parseColor(r.effectiveColour) }
                .getOrDefault(android.graphics.Color.GRAY)
            floatArrayOf(
                android.graphics.Color.red(argb)   / 255f,
                android.graphics.Color.green(argb) / 255f,
                android.graphics.Color.blue(argb)  / 255f,
                1f
            )
        }
    }

    Box(modifier = modifier.onSizeChanged { viewerSizePx = it }) {
        AndroidView(
            factory = { ctx ->
                ModelViewerView(ctx).also { view ->
                    viewerView = view
                    mesh?.let { view.setMesh(it) }
                    view.setTrianglePickingPositions(state.trianglePositions)
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
        } else {
            v.onBrushPaint = null
            v.onBrushStrokeStart = null
            v.onBrushTouchAt = null
            v.onTriangleTapped = onTriangleTapped
            brushTouchPx = null
        }
    }

    LaunchedEffect(mesh, viewerView) {
        val v = viewerView ?: return@LaunchedEffect
        val m = mesh ?: return@LaunchedEffect
        v.setMesh(m)
        v.setTrianglePickingPositions(state.trianglePositions)
    }

    // Whenever the per-triangle regions, palette, highlight target, or lasso selection changes:
    // update extruder indices in place and recolor. Two overlay modes:
    //   * tap-to-move highlight: selected component yellow, rest dimmed.
    //   * lasso selection: selected triangles yellow, rest keep their slot colour.
    LaunchedEffect(viewerView, state.triangleRegions, state.componentIds, state.highlightComponentId, regionPalette, lassoSelection) {
        val v = viewerView ?: return@LaunchedEffect
        if (state.triangleRegions.isEmpty()) return@LaunchedEffect
        val highlight = state.highlightComponentId
        when {
            highlight != null && state.componentIds.size == state.triangleRegions.size -> {
                // Indices: 4 = bright highlight, 5 = dimmed background.
                val overlay = ByteArray(state.triangleRegions.size) { i ->
                    if (state.componentIds[i] == highlight) 4.toByte() else 5.toByte()
                }
                val extended = regionPalette + listOf(
                    floatArrayOf(1f, 0.92f, 0.20f, 1f),   // highlight = yellow
                    floatArrayOf(0.20f, 0.20f, 0.22f, 1f) // dim = near-black grey
                )
                v.updateExtruderIndices(overlay)
                v.recolorMesh(extended)
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
    onBrushSizeChange: (Float) -> Unit,
    onClearSelection: () -> Unit,
    selectionSize: Int,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = paintMode,
                onClick = onTogglePaintMode,
                label = { Text(if (paintMode) "🖌 Painting" else "🖌 Paint by tap") },
            )
            FilterChip(
                selected = lassoMode,
                onClick = onToggleLassoMode,
                label = {
                    Text(
                        when {
                            lassoMode && selectionSize > 0 -> "🪄 $selectionSize selected"
                            lassoMode -> "🪄 Lasso (drag to select)"
                            else -> "🪄 Lasso"
                        }
                    )
                },
            )
            if (lassoMode && selectionSize > 0) {
                IconButton(onClick = onClearSelection) {
                    Icon(Icons.Default.Close, contentDescription = "Clear selection")
                }
            }
            if (paintMode || lassoMode) {
                Spacer(Modifier.width(4.dp))
                // Chips represent PHYSICAL extruder slots (TARGET_SLOTS=4), not AI segments.
                // With round-robin slot folding, regions[0..3] carry the canonical slot
                // colours — iterate just those to render one chip per physical filament.
                // In Paint mode: tap = set active region for next stroke.
                // In Lasso mode: tap = commit current selection to that slot.
                val slotChips = regions.take(com.u1.slicer.aipaint.AiPaintViewModel.TARGET_SLOTS)
                slotChips.forEachIndexed { idx, region ->
                    val argb = remember(region.effectiveColour) {
                        runCatching { android.graphics.Color.parseColor(region.effectiveColour) }
                            .getOrDefault(android.graphics.Color.GRAY)
                    }
                    val isActive = paintMode && idx == activeRegion
                    Box(
                        Modifier
                            .size(if (isActive) 36.dp else 28.dp)
                            .background(Color(argb), MaterialTheme.shapes.small)
                            .clickable { onSelectRegion(idx) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isActive) {
                            Text("✓", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
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
            // Current slot's colour as a chunky swatch — visual cue for "what colour this
            // segment is currently mapped to".
            val argb = slotColours.getOrElse(region.slot) { android.graphics.Color.GRAY }
            Box(
                Modifier.size(28.dp).background(Color(argb), shape = MaterialTheme.shapes.small)
            )
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
    onMove: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val currentLabel = currentRegion?.let { regions.getOrNull(it)?.label } ?: "—"
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).navigationBarsPadding()) {
            Text("Move part #${componentId + 1}", style = MaterialTheme.typography.titleMedium)
            Text("Currently in: $currentLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            regions.forEachIndexed { idx, region ->
                if (idx == currentRegion) return@forEachIndexed
                val colour = runCatching { android.graphics.Color.parseColor(region.effectiveColour) }
                    .getOrDefault(android.graphics.Color.GRAY)
                ListItem(
                    headlineContent = { Text("Move to ${region.label}") },
                    leadingContent = {
                        Box(Modifier.size(24.dp).background(Color(colour), MaterialTheme.shapes.small))
                    },
                    modifier = Modifier.clickable { onMove(idx) }
                )
                HorizontalDivider()
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}
