package com.u1.slicer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.WipeTowerDepthEstimator

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
                SimplifiedPlacementBed(
                    modifier = Modifier.size(minOf(maxWidth, maxHeight)),
                    modelSizeX = modelInfo.sizeX,
                    modelSizeY = modelInfo.sizeY,
                    towerDepthMm = WipeTowerDepthEstimator.estimateDepth(modelInfo.sizeZ),
                    copyCount = copyCount,
                    wipeTowerEnabled = wipeTowerEnabled,
                    wipeTowerWidthMm = wipeTowerWidthMm,
                    objectX = objXState,
                    objectY = objYState,
                    towerX = towerX,
                    towerY = towerY,
                    draggingIdx = draggingIdx,
                    onDraggingIdxChange = { draggingIdx = it },
                    onObjectMove = { index, x, y ->
                        objXState[index] = snapMm(x)
                        objYState[index] = snapMm(y)
                    },
                    onTowerMove = { x, y ->
                        towerX = snapMm(x)
                        towerY = snapMm(y)
                    }
                )
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

@Composable
fun SimplifiedPlacementBed(
    modifier: Modifier = Modifier,
    modelSizeX: Float,
    modelSizeY: Float,
    towerDepthMm: Float,
    copyCount: Int,
    wipeTowerEnabled: Boolean,
    wipeTowerWidthMm: Float,
    objectX: List<Float>,
    objectY: List<Float>,
    towerX: Float,
    towerY: Float,
    draggingIdx: Int,
    onDraggingIdxChange: (Int) -> Unit,
    onObjectMove: (index: Int, x: Float, y: Float) -> Unit,
    onTowerMove: (x: Float, y: Float) -> Unit,
    overlayTitle: String? = null,
    overlayBody: String? = null,
    overlayActionLabel: String? = null,
    onOverlayAction: (() -> Unit)? = null
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val canvasSize = minOf(maxWidth, maxHeight)
        // towerDepthMm is passed in directly (estimated from model height by caller)

        Box(modifier = Modifier.size(canvasSize), contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(copyCount, wipeTowerEnabled, modelSizeX, modelSizeY, wipeTowerWidthMm, towerX, towerY) {
                        val ppm = size.width.toFloat() / BED_MM
                        val tW = wipeTowerWidthMm * ppm
                        val tH = towerDepthMm * ppm
                        val objW = modelSizeX * ppm
                        val objH = modelSizeY * ppm

                        detectDragGestures(
                            onDragStart = { offset ->
                                onDraggingIdxChange(-1)
                                if (wipeTowerEnabled) {
                                    val tx = towerX * ppm
                                    val ty = towerY * ppm
                                    if (offset.x in tx..(tx + tW) && offset.y in ty..(ty + tH)) {
                                        onDraggingIdxChange(copyCount)
                                        return@detectDragGestures
                                    }
                                }
                                for (i in 0 until copyCount) {
                                    val ox = objectX.getOrElse(i) { 0f } * ppm
                                    val oy = objectY.getOrElse(i) { 0f } * ppm
                                    if (offset.x in ox..(ox + objW) && offset.y in oy..(oy + objH)) {
                                        onDraggingIdxChange(i)
                                        break
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val ppmDrag = size.width.toFloat() / BED_MM
                                val dx = dragAmount.x / ppmDrag
                                val dy = dragAmount.y / ppmDrag
                                when {
                                    draggingIdx == copyCount && wipeTowerEnabled -> {
                                        onTowerMove(
                                            (towerX + dx).coerceIn(0f, BED_MM - wipeTowerWidthMm),
                                            (towerY + dy).coerceIn(0f, BED_MM - towerDepthMm)
                                        )
                                    }
                                    draggingIdx in 0 until copyCount -> {
                                        val i = draggingIdx
                                        onObjectMove(
                                            i,
                                            (objectX.getOrElse(i) { 0f } + dx).coerceIn(0f, maxOf(0f, BED_MM - modelSizeX)),
                                            (objectY.getOrElse(i) { 0f } + dy).coerceIn(0f, maxOf(0f, BED_MM - modelSizeY))
                                        )
                                    }
                                }
                            },
                            onDragEnd = { onDraggingIdxChange(-1) }
                        )
                    }
            ) {
                val ppm = size.width / BED_MM
                drawRect(Color(0xFF1A1A1A), size = size)
                drawBedGrid(ppm)
                drawRect(Color(0xFF555555), size = size, style = Stroke(2f))

                val objWpx = modelSizeX * ppm
                val objHpx = modelSizeY * ppm
                for (i in 0 until copyCount) {
                    val ox = objectX.getOrElse(i) { 0f } * ppm
                    val oy = objectY.getOrElse(i) { 0f } * ppm
                    val color = OBJECT_COLORS[i % OBJECT_COLORS.size]
                    val active = draggingIdx == i
                    drawRect(
                        color.copy(alpha = if (active) 0.85f else 0.55f),
                        topLeft = Offset(ox, oy),
                        size = Size(objWpx, objHpx)
                    )
                    drawRect(
                        if (active) Color.White else color,
                        topLeft = Offset(ox, oy),
                        size = Size(objWpx, objHpx),
                        style = Stroke(if (active) 3f else 1.5f)
                    )
                }

                if (wipeTowerEnabled) {
                    val tW = wipeTowerWidthMm * ppm
                    val tH = towerDepthMm * ppm
                    val tx = towerX * ppm
                    val ty = towerY * ppm
                    val active = draggingIdx == copyCount
                    drawRect(
                        WIPE_TOWER_COLOR.copy(alpha = if (active) 0.9f else 0.7f),
                        topLeft = Offset(tx, ty),
                        size = Size(tW, tH)
                    )
                    drawRect(
                        if (active) Color.White else WIPE_TOWER_COLOR,
                        topLeft = Offset(tx, ty),
                        size = Size(tW, tH),
                        style = Stroke(if (active) 3f else 1.5f)
                    )
                }
            }

            if (overlayTitle != null || overlayBody != null || (overlayActionLabel != null && onOverlayAction != null)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.64f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp)
                        .fillMaxWidth(0.92f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        overlayTitle?.let {
                            Text(
                                it,
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                        overlayBody?.let {
                            Text(
                                it,
                                color = Color.White.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                        if (overlayActionLabel != null && onOverlayAction != null) {
                            TextButton(onClick = onOverlayAction) {
                                Text(overlayActionLabel)
                            }
                        }
                    }
                }
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
