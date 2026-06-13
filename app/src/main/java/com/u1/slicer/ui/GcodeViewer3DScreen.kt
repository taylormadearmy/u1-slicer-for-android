package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.u1.slicer.normalizeGcodePreviewColors
import com.u1.slicer.gcode.ParsedGcode
import com.u1.slicer.viewer.GcodeViewerView
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GcodeViewer3DScreen(
    parsedGcode: ParsedGcode,
    extruderColors: List<String> = emptyList(),
    colorMapping: List<Int>? = null,
    slicerLayerCount: Int = 0,
    // Phase 2 §4 Step 7 (Preview side) — file-derived per-filament colours
    // (override-applied). When non-empty, drives the slot palette so the
    // standalone G-code 3D viewer shows file colours matching Prepare.
    resolvedFilamentColors: List<String> = emptyList(),
    // B129: the layer range remembered across navigation. Hoisted to the
    // ViewModel so moving/rotating the model (which leaves this screen) doesn't
    // reset the slider to the top. Null means "show the full print" (a fresh
    // slice). [onLayerRangeChange] persists the user's slider position back.
    initialLayerRange: Pair<Int, Int>? = null,
    onLayerRangeChange: (Int, Int) -> Unit = { _, _ -> },
    onBack: () -> Unit
) {
    val gcodeLayerCount = parsedGcode.layers.size
    val displayLayerCount = if (slicerLayerCount > 0) slicerLayerCount else gcodeLayerCount
    var viewerView by remember { mutableStateOf<GcodeViewerView?>(null) }
    // B129: seed the slider from the saved range (preserved across navigation),
    // clamped to the current layer count. A fresh composition on re-entry reads
    // the latest saved value the ViewModel handed us.
    val initialRange = remember(parsedGcode) { resolveInitialLayerRange(initialLayerRange, gcodeLayerCount) }
    var minLayer by remember(parsedGcode) { mutableIntStateOf(initialRange.first) }
    var maxLayer by remember(parsedGcode) { mutableIntStateOf(initialRange.second) }
    var isLoading by remember { mutableStateOf(true) }
    var showTravel by remember { mutableStateOf(false) }
    var featureColorMode by remember { mutableStateOf(false) }

    // Set colors + upload gcode atomically on the GL thread.
    // suspendCancellableCoroutine waits for the GL work to finish before
    // clearing the loading indicator.
    val previewColors = remember(extruderColors, colorMapping, resolvedFilamentColors) {
        normalizeGcodePreviewColors(extruderColors, colorMapping, resolvedFilamentColors)
    }

    LaunchedEffect(parsedGcode, previewColors, viewerView) {
        val v = viewerView ?: return@LaunchedEffect
        isLoading = true
        val colors = previewColors
        val gcode = parsedGcode
        suspendCancellableCoroutine { cont ->
            v.queueEvent {
                if (colors.isNotEmpty()) v.renderer.setExtruderColors(colors)
                v.renderer.uploadGcode(gcode)
                // Post back to main thread to resume the coroutine
                v.post { if (cont.isActive) cont.resume(Unit) }
            }
            v.requestRender()
        }
        isLoading = false
        // B129: uploadGcode() resets the renderer's layer window to the full
        // print. Re-apply the remembered range so a slider position restored
        // after navigation (move/rotate the model) actually takes effect
        // instead of snapping back to the top.
        if (minLayer > 0 || maxLayer < gcodeLayerCount - 1) {
            v.setLayerRange(minLayer, maxLayer)
            v.requestRender()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("3D G-code View", fontWeight = FontWeight.Bold)
                        val minZ = parsedGcode.layers.getOrNull(minLayer)?.z ?: 0f
                        val maxZ = parsedGcode.layers.getOrNull(maxLayer)?.z ?: 0f
                        val zInfo = if (minLayer > 0) "%.1f–%.1fmm".format(minZ, maxZ) else "%.1fmm".format(maxZ)
                        Text(
                            "$displayLayerCount layers  $zInfo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        featureColorMode = !featureColorMode
                        viewerView?.setFeatureColorMode(featureColorMode)
                    }) {
                        Icon(
                            Icons.Default.Palette,
                            "Toggle feature-type colors",
                            tint = if (featureColorMode) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        showTravel = !showTravel
                        viewerView?.setShowTravel(showTravel)
                    }) {
                        Icon(
                            if (showTravel) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            "Toggle travel moves",
                            tint = if (showTravel) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        val v = viewerView ?: return@IconButton
                        v.renderer.camera.apply {
                            setTarget(135.0, 135.0, (parsedGcode.layers.lastOrNull()?.z?.div(2) ?: 0f).toDouble())
                            distance = 400.0
                            elevation = 35.0
                            azimuth = -45.0
                            panX = 0.0
                            panY = 0.0
                        }
                        v.requestRender()
                    }) {
                        Icon(Icons.Default.FitScreen, "Reset view")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // GL view + loading overlay
            Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        GcodeViewerView(ctx).also { view ->
                            viewerView = view
                            // Don't upload gcode here — the LaunchedEffect handles both
                            // colors and gcode atomically to avoid a race where VBOs are
                            // built with default colors before the real colors are set.
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                "Building preview…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Vertical layer range slider on the right
            if (gcodeLayerCount > 1) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(52.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top label: display layer count
                    Text(
                        "$displayLayerCount",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Vertical range slider (rotated horizontal RangeSlider)
                    BoxWithConstraints(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val sliderLength = maxHeight
                        RangeSlider(
                            value = minLayer.toFloat()..maxLayer.toFloat(),
                            onValueChange = { range ->
                                minLayer = range.start.roundToInt()
                                maxLayer = range.endInclusive.roundToInt()
                                viewerView?.setLayerRange(minLayer, maxLayer)
                                // B129: persist to the ViewModel so the position
                                // survives leaving the preview to move/rotate.
                                onLayerRangeChange(minLayer, maxLayer)
                            },
                            valueRange = 0f..(gcodeLayerCount - 1).toFloat(),
                            modifier = Modifier
                                .requiredWidth(sliderLength)
                                .graphicsLayer { rotationZ = -90f }
                        )
                    }
                    // Bottom label
                    Text(
                        "1",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * B129 — resolve the slider's initial [minLayer, maxLayer] from the range
 * remembered in the ViewModel.
 *
 *   - `saved == null` → the full print `0..layerCount-1` (a fresh slice has no
 *     remembered position).
 *   - `saved != null` → the remembered range, clamped to the current layer
 *     count so a re-slice with fewer layers can't leave the window past the end.
 *
 * Pure and Android-free so the persist-vs-reset contract is unit-testable
 * (see `GcodeLayerRangeTest`).
 */
internal fun resolveInitialLayerRange(saved: Pair<Int, Int>?, layerCount: Int): Pair<Int, Int> {
    val maxIdx = (layerCount - 1).coerceAtLeast(0)
    if (saved == null) return 0 to maxIdx
    val lo = saved.first.coerceIn(0, maxIdx)
    val hi = saved.second.coerceIn(lo, maxIdx)
    return lo to hi
}

/**
 * B143 — map the 0-based slider position to the 1-based "Layer N/M" label.
 *
 * The slider indexes parsed G-code layers (`0..gcodeLayerCount-1`) while the
 * label uses the slicer's 1-based layer numbering ([displayLayerCount] can
 * differ from [gcodeLayerCount] when the slicer reports a different total).
 * Scaling the 0-based index directly could never reach [displayLayerCount],
 * so the slider's top position showed "Layer 121/122" and the final layer
 * appeared unreachable. Scale the 1-based position instead so the extremes
 * map to exactly 1 and [displayLayerCount].
 *
 * Pure and Android-free so the mapping is unit-testable (see
 * `GcodeLayerRangeTest`).
 */
internal fun computeDisplayLayer(maxLayer: Int, displayLayerCount: Int, gcodeLayerCount: Int): Int {
    if (gcodeLayerCount <= 0 || displayLayerCount <= 0) return 1
    return (((maxLayer + 1).toLong() * displayLayerCount) / gcodeLayerCount)
        .toInt()
        .coerceIn(1, displayLayerCount)
}
