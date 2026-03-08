package com.u1.slicer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u1.slicer.data.ModelInfo

private const val BED_MM = 270f
private val OBJECT_COLORS = listOf(
    Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFFFF9800),
    Color(0xFF9C27B0), Color(0xFFE91E63), Color(0xFF00BCD4)
)
private val WIPE_TOWER_COLOR = Color(0xFFFFC107)

/**
 * Pre-slice placement viewer: top-down 270x270 bed with draggable object copies
 * and an optional draggable wipe tower. User taps Apply to confirm positions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacementViewerScreen(
    modelInfo: ModelInfo,
    copyCount: Int,
    wipeTowerEnabled: Boolean,
    wipeTowerWidthMm: Float,
    initialObjectPositions: FloatArray,
    initialWipeTowerPos: Pair<Float, Float>,
    onApply: (FloatArray, Pair<Float, Float>) -> Unit,
    onBack: () -> Unit
) {
    val objXState = remember(initialObjectPositions, copyCount) {
        mutableStateListOf(*Array(copyCount) { i -> initialObjectPositions.getOrElse(i * 2) { 5f + i * 25f } })
    }
    val objYState = remember(initialObjectPositions, copyCount) {
        mutableStateListOf(*Array(copyCount) { i -> initialObjectPositions.getOrElse(i * 2 + 1) { 5f } })
    }

    var towerX by remember(initialWipeTowerPos) { mutableFloatStateOf(initialWipeTowerPos.first) }
    var towerY by remember(initialWipeTowerPos) { mutableFloatStateOf(initialWipeTowerPos.second) }
    var snapToGrid by remember { mutableStateOf(false) }
    var draggingIdx by remember { mutableIntStateOf(-1) }

    fun collectPositions(): FloatArray {
        val arr = FloatArray(copyCount * 2)
        for (i in 0 until copyCount) { arr[i * 2] = objXState[i]; arr[i * 2 + 1] = objYState[i] }
        return arr
    }

    fun snapMm(v: Float) = if (snapToGrid) (v / 5f).toInt() * 5f else v

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Arrange Objects") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { snapToGrid = !snapToGrid }) {
                        Icon(
                            Icons.Default.GridOn, "Snap to grid",
                            tint = if (snapToGrid) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { onApply(collectPositions(), Pair(towerX, towerY)) }) {
                        Icon(Icons.Default.CheckCircle, "Apply", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Drag objects to position \u2022 Apply to confirm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (snapToGrid) {
                    Text("Grid: 5mm", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                val canvasSize = minOf(maxWidth, maxHeight)
                // Prime tower is square: width × width (matches OrcaSlicer default shape)
                val towerDepthMm = wipeTowerWidthMm

                Canvas(
                    modifier = Modifier
                        .size(canvasSize)
                        .pointerInput(copyCount, wipeTowerEnabled, snapToGrid) {
                            val ppm = size.width.toFloat() / BED_MM
                            val tW = wipeTowerWidthMm * ppm
                            val tH = towerDepthMm * ppm
                            val objW = modelInfo.sizeX * ppm
                            val objH = modelInfo.sizeY * ppm

                            detectDragGestures(
                                onDragStart = { offset ->
                                    draggingIdx = -1
                                    // Check tower first so it can be selected even when overlapping objects
                                    if (wipeTowerEnabled) {
                                        val tx = towerX * ppm; val ty = towerY * ppm
                                        if (offset.x in tx..(tx + tW) && offset.y in ty..(ty + tH)) {
                                            draggingIdx = copyCount; return@detectDragGestures
                                        }
                                    }
                                    for (i in 0 until copyCount) {
                                        val ox = objXState[i] * ppm; val oy = objYState[i] * ppm
                                        if (offset.x in ox..(ox + objW) && offset.y in oy..(oy + objH)) {
                                            draggingIdx = i; break
                                        }
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val p = size.width.toFloat() / BED_MM
                                    val dx = dragAmount.x / p; val dy = dragAmount.y / p
                                    when {
                                        draggingIdx == copyCount && wipeTowerEnabled -> {
                                            towerX = snapMm((towerX + dx).coerceIn(0f, BED_MM - wipeTowerWidthMm))
                                            towerY = snapMm((towerY + dy).coerceIn(0f, BED_MM - towerDepthMm))
                                        }
                                        draggingIdx in 0 until copyCount -> {
                                            val i = draggingIdx
                                            objXState[i] = snapMm((objXState[i] + dx).coerceIn(0f, maxOf(0f, BED_MM - modelInfo.sizeX)))
                                            objYState[i] = snapMm((objYState[i] + dy).coerceIn(0f, maxOf(0f, BED_MM - modelInfo.sizeY)))
                                        }
                                    }
                                },
                                onDragEnd = { draggingIdx = -1 }
                            )
                        }
                ) {
                    val ppm = size.width / BED_MM
                    drawRect(Color(0xFF1A1A1A), size = size)
                    drawBedGrid(ppm)
                    drawRect(Color(0xFF555555), size = size, style = Stroke(2f))

                    // Draw objects first, then tower on top so it's always visible and selectable
                    val objWpx = modelInfo.sizeX * ppm; val objHpx = modelInfo.sizeY * ppm
                    for (i in 0 until copyCount) {
                        val ox = objXState[i] * ppm; val oy = objYState[i] * ppm
                        val color = OBJECT_COLORS[i % OBJECT_COLORS.size]
                        val active = draggingIdx == i
                        drawRect(color.copy(alpha = if (active) 0.85f else 0.55f),
                            topLeft = Offset(ox, oy), size = Size(objWpx, objHpx))
                        drawRect(if (active) Color.White else color,
                            topLeft = Offset(ox, oy), size = Size(objWpx, objHpx),
                            style = Stroke(if (active) 3f else 1.5f))
                    }

                    if (wipeTowerEnabled) {
                        val tW = wipeTowerWidthMm * ppm; val tH = towerDepthMm * ppm
                        val tx = towerX * ppm; val ty = towerY * ppm
                        val active = draggingIdx == copyCount
                        drawRect(WIPE_TOWER_COLOR.copy(alpha = if (active) 0.9f else 0.7f),
                            topLeft = Offset(tx, ty), size = Size(tW, tH))
                        drawRect(if (active) Color.White else WIPE_TOWER_COLOR,
                            topLeft = Offset(tx, ty), size = Size(tW, tH),
                            style = Stroke(if (active) 3f else 1.5f))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendDot(OBJECT_COLORS[0], "Object \u00d7$copyCount")
                if (wipeTowerEnabled) LegendDot(WIPE_TOWER_COLOR, "Wipe Tower")
                Spacer(Modifier.weight(1f))
                Text("270\u00d7270 mm", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                onClick = { onApply(collectPositions(), Pair(towerX, towerY)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.CheckCircle, null)
                Spacer(Modifier.width(8.dp))
                Text("Apply Positions", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun DrawScope.drawBedGrid(pxPerMm: Float) {
    var mm = 10f
    while (mm < BED_MM) {
        val major = mm % 50f == 0f
        val lineColor = if (major) Color(0xFF2D2D2D) else Color(0xFF232323)
        val strokeW = if (major) 1f else 0.5f
        val x = mm * pxPerMm; val y = mm * pxPerMm
        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeW)
        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeW)
        mm += 10f
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(12.dp), color = color, shape = RoundedCornerShape(2.dp)) {}
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
    }
}
