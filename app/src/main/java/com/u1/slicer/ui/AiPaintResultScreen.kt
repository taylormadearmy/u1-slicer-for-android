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
    onUpdateRegionColour: (regionId: Int, hex: String) -> Unit = { _, _ -> }
) {
    var swapSheetRegion by remember { mutableStateOf<AiRegion?>(null) }
    var moveSheetComponent by remember { mutableStateOf<Int?>(null) }

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

                    // Live 3D viewer — replaces the previous static iso bitmap.
                    AiPaintViewer(
                        state = result,
                        onTriangleTapped = { triangleIdx ->
                            val comp = result.componentIds.getOrNull(triangleIdx) ?: return@AiPaintViewer
                            moveSheetComponent = comp
                            onHighlightComponent(comp)
                        },
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f)
                            .background(Color(0xFF111118))
                    )

                    Text(
                        "REGIONS — tap a colour swatch to change",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                    LazyColumn(Modifier.weight(1f)) {
                        items(result.regions) { region ->
                            RegionRow(
                                region = region,
                                onClick = { swapSheetRegion = region }
                            )
                        }
                    }

                    Text(
                        "Tip: tap a part of the 3D model to move it to a different region.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

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
    modifier: Modifier = Modifier
) {
    var viewerView by remember { mutableStateOf<ModelViewerView?>(null) }

    val mesh = remember(state.componentIds) {
        if (state.numComponents == 0 || state.componentIds.isEmpty()) null
        else AiPaintMeshBuilder.build(state.trianglePositions, state.componentIds)
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
                view.onTriangleTapped = onTriangleTapped
                mesh?.let { view.setMesh(it) }
                view.setTrianglePickingPositions(state.trianglePositions)
            }
        },
        modifier = modifier
    )

    LaunchedEffect(mesh, viewerView) {
        val v = viewerView ?: return@LaunchedEffect
        val m = mesh ?: return@LaunchedEffect
        v.setMesh(m)
        v.setTrianglePickingPositions(state.trianglePositions)
    }

    LaunchedEffect(viewerView, regionPalette, state.componentToRegion, state.highlightComponentId, state.numComponents) {
        val v = viewerView ?: return@LaunchedEffect
        if (state.numComponents == 0) return@LaunchedEffect
        val palette = AiPaintMeshBuilder.buildPalette(
            numComponents = state.numComponents,
            componentToRegion = state.componentToRegion,
            regionColours = regionPalette,
            highlightComponentId = state.highlightComponentId
        )
        v.recolorMesh(palette)
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
