package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
) {
    var swapSheetRegion by remember { mutableStateOf<AiRegion?>(null) }
    var moveSheetComponent by remember { mutableStateOf<Int?>(null) }
    var paintMode by remember { mutableStateOf(false) }
    var paintActiveRegion by remember { mutableStateOf(0) }
    // Brush radius as a fraction of the model's bbox diagonal. 0 = single triangle.
    var brushPct by remember { mutableStateOf(0.03f) }

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

                    if (result.usedAiFallback) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth().padding(8.dp)
                        ) {
                            Text(
                                "AI couldn't process this model. Used height-based fallback instead — open Settings → AI Paint and try Gemini or Claude for better results.",
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
                        paintMode = paintMode,
                        brushRadiusWorld = brushRadiusWorld,
                        activeRegion = paintActiveRegion,
                        onPaintTriangles = onPaintTriangles,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.42f)
                            .background(Color(0xFF111118))
                    )

                    // Paint mode toolbar — appears between viewer and region list.
                    PaintModeBar(
                        paintMode = paintMode,
                        regions = result.regions,
                        activeRegion = paintActiveRegion,
                        brushPct = brushPct,
                        onTogglePaintMode = { paintMode = !paintMode },
                        onSelectRegion = { paintActiveRegion = it },
                        onBrushSizeChange = { brushPct = it },
                    )

                    Text(
                        if (paintMode) "PAINT MODE — tap a part of the 3D model to paint it with the selected colour"
                        else "REGIONS — tap a colour swatch to change",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                    )
                    LazyColumn(Modifier.weight(1f)) {
                        items(result.regions) { region ->
                            RegionRow(
                                region = region,
                                onClick = { swapSheetRegion = region }
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
    activeRegion: Int,
    onPaintTriangles: (List<Int>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var viewerView by remember { mutableStateOf<ModelViewerView?>(null) }

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

    AndroidView(
        factory = { ctx ->
            ModelViewerView(ctx).also { view ->
                viewerView = view
                mesh?.let { view.setMesh(it) }
                view.setTrianglePickingPositions(state.trianglePositions)
            }
        },
        modifier = modifier
    )

    // Wire up the tap callbacks every time the mode flips. Paint mode → onBrushPaint, default
    // mode → onTriangleTapped (for the move-component sheet).
    LaunchedEffect(viewerView, paintMode, activeRegion, brushRadiusWorld) {
        val v = viewerView ?: return@LaunchedEffect
        v.brushRadiusWorld = brushRadiusWorld
        if (paintMode) {
            v.onBrushPaint = { tris -> onPaintTriangles(tris, activeRegion) }
            v.onTriangleTapped = null
        } else {
            v.onBrushPaint = null
            v.onTriangleTapped = onTriangleTapped
        }
    }

    LaunchedEffect(mesh, viewerView) {
        val v = viewerView ?: return@LaunchedEffect
        val m = mesh ?: return@LaunchedEffect
        v.setMesh(m)
        v.setTrianglePickingPositions(state.trianglePositions)
    }

    // Whenever the per-triangle regions or the palette changes, update the VBO indices in place
    // and trigger a recolor. No mesh rebuild → no perceptible lag.
    LaunchedEffect(viewerView, state.triangleRegions, regionPalette) {
        val v = viewerView ?: return@LaunchedEffect
        if (state.triangleRegions.isEmpty()) return@LaunchedEffect
        v.updateExtruderIndices(state.triangleRegions)
        v.recolorMesh(AiPaintMeshBuilder.regionPalette(regionPalette))
    }
}

@Composable
private fun PaintModeBar(
    paintMode: Boolean,
    regions: List<AiRegion>,
    activeRegion: Int,
    brushPct: Float,
    onTogglePaintMode: () -> Unit,
    onSelectRegion: (Int) -> Unit,
    onBrushSizeChange: (Float) -> Unit,
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
            if (paintMode) {
                Spacer(Modifier.width(4.dp))
                regions.forEachIndexed { idx, region ->
                    val argb = remember(region.effectiveColour) {
                        runCatching { android.graphics.Color.parseColor(region.effectiveColour) }
                            .getOrDefault(android.graphics.Color.GRAY)
                    }
                    val isActive = idx == activeRegion
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
        if (paintMode) {
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
private fun RegionRow(region: AiRegion, onClick: () -> Unit) {
    val colour = remember(region.effectiveColour) {
        runCatching { android.graphics.Color.parseColor(region.effectiveColour) }
            .getOrDefault(android.graphics.Color.GRAY)
    }
    val partsLabel = if (region.componentIds.size == 1) "1 part" else "${region.componentIds.size} parts"
    ListItem(
        headlineContent = { Text(region.label) },
        supportingContent = {
            Text(
                "${"%.0f".format(region.coverageFraction * 100)}% of model · $partsLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Box(
                Modifier.size(24.dp).background(Color(colour), shape = MaterialTheme.shapes.small)
            )
        },
        trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick)
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
