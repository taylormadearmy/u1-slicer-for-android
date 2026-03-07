package com.u1.slicer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.ThreeMfObject
import com.u1.slicer.bambu.ThreeMfPlate

private val plateColors = listOf(
    Color(0xFFE87A00),
    Color(0xFF4FC3F7),
    Color(0xFF66BB6A),
    Color(0xFFEF5350),
    Color(0xFFAB47BC),
    Color(0xFFFFCA28),
)

@Composable
fun PlateSelectDialog(
    plates: List<ThreeMfPlate>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    info: ThreeMfInfo? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Layers, null, tint = MaterialTheme.colorScheme.primary)
        },
        title = {
            Text("Select Plate", fontWeight = FontWeight.Bold)
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(plates) { plate ->
                    val plateIndex = plates.indexOf(plate)
                    val color = plateColors[plateIndex % plateColors.size]
                    val objectsOnPlate = info?.objects?.filter { obj ->
                        plate.objectIds.contains(obj.objectId)
                    } ?: emptyList()

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(plate.plateId) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        plate.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        "${plate.objectIds.size} object(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    if (objectsOnPlate.isNotEmpty()) {
                                        Text(
                                            "${objectsOnPlate.sumOf { it.triangles }} triangles",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                                Text(
                                    "#${plate.plateId}",
                                    color = color,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }

                            // Plate preview canvas
                            Spacer(Modifier.height(8.dp))
                            PlateThumbnail(
                                plate = plate,
                                allPlates = plates,
                                objects = info?.objects ?: emptyList(),
                                highlightColor = color,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PlateThumbnail(
    plate: ThreeMfPlate,
    allPlates: List<ThreeMfPlate>,
    @Suppress("UNUSED_PARAMETER") objects: List<ThreeMfObject>,
    highlightColor: Color,
    modifier: Modifier = Modifier
) {
    // Show extracted PNG thumbnail if available (Bambu 3MF plates)
    val bitmap = remember(plate.thumbnailBytes) {
        plate.thumbnailBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = plate.name,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
        return
    }

    Canvas(modifier = modifier) {
        val bedW = 270f
        val bedH = 270f
        val padding = 8f

        val availW = size.width - padding * 2
        val availH = size.height - padding * 2
        val scale = minOf(availW / bedW, availH / bedH)

        val offsetX = padding + (availW - bedW * scale) / 2
        val offsetY = padding + (availH - bedH * scale) / 2

        // Bed background — lighter for better contrast
        drawRect(
            color = Color(0xFF1E1E2E),
            topLeft = Offset(offsetX, offsetY),
            size = Size(bedW * scale, bedH * scale)
        )

        // Bed border
        drawRect(
            color = Color(0xFF555577),
            topLeft = Offset(offsetX, offsetY),
            size = Size(bedW * scale, bedH * scale),
            style = Stroke(width = 1.5f)
        )

        // Grid lines every 50mm
        val gridStep = 50f * scale
        var gx = gridStep
        while (gx < bedW * scale) {
            drawLine(
                Color(0xFF2A2A40),
                Offset(offsetX + gx, offsetY),
                Offset(offsetX + gx, offsetY + bedH * scale),
                strokeWidth = 0.5f
            )
            gx += gridStep
        }
        var gy = gridStep
        while (gy < bedH * scale) {
            drawLine(
                Color(0xFF2A2A40),
                Offset(offsetX, offsetY + gy),
                Offset(offsetX + bedW * scale, offsetY + gy),
                strokeWidth = 0.5f
            )
            gy += gridStep
        }

        // Draw other plates' objects as dim shapes
        for (otherPlate in allPlates) {
            if (otherPlate.plateId == plate.plateId) continue
            drawPlateObject(
                offsetX, offsetY, scale, bedH,
                otherPlate.translationX, otherPlate.translationY,
                Color(0xFF333355), 0.3f
            )
        }

        // Draw this plate's object(s) highlighted
        drawPlateObject(
            offsetX, offsetY, scale, bedH,
            plate.translationX, plate.translationY,
            highlightColor, 0.8f
        )
    }
}

private fun DrawScope.drawPlateObject(
    offsetX: Float, offsetY: Float, scale: Float, bedH: Float,
    tx: Float, ty: Float,
    color: Color, alpha: Float
) {
    // Estimate object size — use a default 50mm square for visibility
    val objSize = 50f * scale
    // Transform: tx,ty are in mm from bed origin. Y is flipped for Canvas (0 at top)
    val cx = offsetX + tx * scale
    val cy = offsetY + (bedH - ty) * scale

    val left = cx - objSize / 2
    val top = cy - objSize / 2

    // Filled rectangle
    drawRect(
        color = color.copy(alpha = alpha * 0.5f),
        topLeft = Offset(left, top),
        size = Size(objSize, objSize)
    )
    // Border
    drawRect(
        color = color.copy(alpha = alpha),
        topLeft = Offset(left, top),
        size = Size(objSize, objSize),
        style = Stroke(width = 2f)
    )
}
