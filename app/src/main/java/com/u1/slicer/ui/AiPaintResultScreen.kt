package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.u1.slicer.aipaint.*

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
    projectMixes: List<com.u1.slicer.data.MixedFilamentRow> = emptyList(),
    libraryMixes: List<com.u1.slicer.data.MixedFilamentRow> = emptyList(),
    numPhysical: Int = com.u1.slicer.aipaint.SegmentationCascade.TARGET_SLOTS,
    onCreateMix: () -> Unit = {},
    onEditMix: (com.u1.slicer.data.MixedFilamentRow) -> Unit = {},
) {
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
                    // F3 (M3-Phase-B): palette extended beyond the 4 physical slots to include
                    // one naive-blend entry per active mix (in MixSlotOrdering order).
                    val activeMixes = remember(projectMixes, libraryMixes, numPhysical) {
                        com.u1.slicer.data.MixSlotOrdering.activeOrder(projectMixes, libraryMixes, numPhysical)
                    }
                    val slotPalette: List<Color> = remember(filamentColours, activeMixes, numPhysical) {
                        val physical = (0 until numPhysical).map { slot ->
                            val hex = filamentColours.getOrNull(slot) ?: "#888888"
                            Color(
                                runCatching { android.graphics.Color.parseColor(hex) }
                                    .getOrDefault(android.graphics.Color.GRAY)
                            )
                        }
                        val mixes = activeMixes.map { row ->
                            val a = filamentColours.getOrNull(row.componentA - 1) ?: "#888888"
                            val b = filamentColours.getOrNull(row.componentB - 1) ?: "#888888"
                            val hex = com.u1.slicer.aipaint.ColourMatch.naiveBlendHex(a, b, row.mixBPercent)
                            Color(
                                runCatching { android.graphics.Color.parseColor(hex) }
                                    .getOrDefault(android.graphics.Color.GRAY)
                            )
                        }
                        physical + mixes
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
                    // Live 3D viewer.
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
                            // user can actually see the model as they paint.
                            if (paintMode) onHighlightComponent(null)
                        },
                        onToggleLasso = {
                            lassoMode = !lassoMode
                            if (lassoMode) paintMode = false
                            lassoSelection = emptySet()
                            if (lassoMode) onHighlightComponent(null)
                        },
                        onBrushSizeChange = { brushPct = it },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(8.dp),
                    )

                    // fix35.1: floating slot picker overlay shown when a region is currently
                    // highlighted (tapped on the model or in the list). Gives the "what now?"
                    // action — tap a slot/mix to assign the highlighted region, "+" to create a
                    // mix, × to clear. This is the model-tap selector, visually distinct from the
                    // inline per-row chips.
                    if (highlightedNode != null) {
                        HighlightSlotPicker(
                            label = highlightedNode.region.label,
                            currentSlot = highlightedNode.region.slot,
                            physicalColours = slotPalette.take(numPhysical),
                            numPhysical = numPhysical,
                            mixes = activeMixes,
                            onPickSlot = { slot ->
                                // Don't clear the highlight after reassign — the user wants to
                                // iterate (tap red, see it, tap a mix, see that). The ×,
                                // different-region, and empty-tap paths handle clearing.
                                onSetSegmentSlot(highlightedNode.region.id, slot)
                            },
                            onCreateMix = onCreateMix,
                            onDismiss = { onHighlightComponent(null) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                        )
                    }

                    }  // end Box wrapper for viewer

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

                    // C5 (M3-Phase-B): honest print-cost banner when one or more regions are
                    // assigned to mix slots. No fabricated hour figure — the real time cost
                    // shows up in the post-slice time estimate.
                    val mixCount = mixRegionCount(result.leafRegions.map { it.slot }, numPhysical)
                    if (mixCount > 0) {
                        val regionWord = if (mixCount == 1) "region uses" else "regions use"
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "$mixCount $regionWord mix slots — this adds tool changes and print time.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }

                    // C4 (M3-Phase-B): when an imported coloured model had colours with no close
                    // palette match, nudge the user toward creating a mix. Phase B does not auto-
                    // create mixes — it falls each unmatched colour back to its closest slot.
                    if (result.unmatchedColourCount > 0) {
                        val n = result.unmatchedColourCount
                        Text(
                            "$n ${if (n == 1) "colour" else "colours"} had no close match — create a mix to improve them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }

                    // "Extruders →" palette row. Tap behaviour depends on the toolbar mode
                    // armed via ViewerToolbar:
                    //   • Select (default) → physical chip opens the colour editor; mix chip
                    //                        opens the mix editor.
                    //   • Paint / Lasso    → tapping a chip sets the active paint slot (tick).
                    // A trailing "+" creates a new mix.
                    SlotPaletteRow(
                        physicalColours = slotPalette.take(numPhysical),
                        mixes = activeMixes,
                        numPhysical = numPhysical,
                        paintMode = paintMode,
                        lassoMode = lassoMode,
                        activeSlot = paintActiveRegion,
                        hasLassoSelection = false,
                        onTapSlot = { slot ->
                            when {
                                paintMode || lassoMode -> paintActiveRegion = slot
                                slot >= numPhysical ->
                                    activeMixes.getOrNull(slot - numPhysical)?.let { onEditMix(it) }
                                else -> editSlotColour = slot
                            }
                        },
                        onCreateMix = onCreateMix,
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
                            // (so the model lights up the affected triangles in yellow). Reassign
                            // + highlight is one coherent action.
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
                        numPhysical = numPhysical,
                        activeMixes = activeMixes,
                        physicalColours = slotPalette.take(numPhysical),
                        onCreateMix = onCreateMix,
                        onEditMix = onEditMix,
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

    // fix44: removed dead `swapSheetRegion` / `moveSheetComponent` bottom sheets — their
    // state setters were never invoked from any composable, so the sheets never opened.
    // Carry-over from the pre-fix34 tree shape. Removed ~200 LOC of dead code (RegionRow,
    // ColourSwapSheet, MoveComponentSheet) along with these stub callbacks.

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
