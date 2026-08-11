package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.network.NozzleSide

/** Printer-slot topology projected into the send UI. */
data class BambuSlotNozzleRoute(
    val side: NozzleSide = NozzleSide.UNKNOWN,
    val switchable: Boolean = false,
)

internal fun normalizeH2DNozzleAssignments(
    assignments: List<Int>,
    filamentCount: Int,
): List<Int> = List(filamentCount.coerceAtLeast(0)) { index ->
    assignments.getOrNull(index)?.takeIf { it in 0..2 } ?: 0
}

internal fun updateH2DNozzleAssignment(
    assignments: List<Int>,
    filamentCount: Int,
    filamentIndex: Int,
    assignment: Int,
): List<Int> {
    require(assignment in 0..2) {
        "H2D nozzle assignment must be 0 (auto), 1 (left), or 2 (right)"
    }
    require(filamentIndex in 0 until filamentCount) {
        "H2D filament index $filamentIndex is outside 0 until $filamentCount"
    }
    return normalizeH2DNozzleAssignments(assignments, filamentCount).toMutableList().also {
        it[filamentIndex] = assignment
    }
}

internal fun h2dAssignmentNozzleSide(assignment: Int): NozzleSide = when (assignment) {
    1 -> NozzleSide.LEFT
    2 -> NozzleSide.RIGHT
    else -> NozzleSide.UNKNOWN
}

internal fun nozzleSideLabel(side: NozzleSide): String = when (side) {
    NozzleSide.LEFT -> "Left nozzle"
    NozzleSide.RIGHT -> "Right nozzle"
    NozzleSide.UNKNOWN -> "Nozzle side unknown"
}

internal fun isBambuSlotCompatibleWithNozzle(
    requiredSide: NozzleSide,
    route: BambuSlotNozzleRoute?,
): Boolean = requiredSide == NozzleSide.UNKNOWN ||
    route == null ||
    route.side == NozzleSide.UNKNOWN ||
    route.switchable ||
    route.side == requiredSide

/**
 * Colour-matches within the required nozzle's tray group when topology is
 * known. Unknown topology deliberately falls back to the legacy all-slot
 * suggestion so older firmware remains usable.
 */
internal fun autoSuggestSideAwareMapping(
    canonicalList: CanonicalFilamentList,
    extruderPresets: List<ExtruderPreset>,
    requiredNozzleSides: List<NozzleSide>,
    slotNozzleRoutes: Map<Int, BambuSlotNozzleRoute>,
): List<Int> = canonicalList.filaments.mapIndexed { index, entry ->
    val requiredSide = requiredNozzleSides.getOrElse(index) { NozzleSide.UNKNOWN }
    val compatible = extruderPresets.filter { preset ->
        isBambuSlotCompatibleWithNozzle(requiredSide, slotNozzleRoutes[preset.index])
    }
    compatible.ifEmpty { extruderPresets }
        .minByOrNull { preset -> h2dUiColorDistance(entry.color, preset.color) }
        ?.index
        ?: 0
}

/** Android-free RGB distance so JVM policy tests exercise the real suggestion. */
internal fun h2dUiColorDistance(first: String, second: String): Long {
    fun parse(hex: String): Triple<Int, Int, Int>? {
        val rgb = hex.trim().removePrefix("#").take(6)
        if (rgb.length != 6) return null
        val value = rgb.toLongOrNull(16) ?: return null
        return Triple(
            ((value shr 16) and 0xFF).toInt(),
            ((value shr 8) and 0xFF).toInt(),
            (value and 0xFF).toInt(),
        )
    }
    val a = parse(first) ?: return Long.MAX_VALUE
    val b = parse(second) ?: return Long.MAX_VALUE
    val dr = (a.first - b.first).toLong()
    val dg = (a.second - b.second).toLong()
    val db = (a.third - b.third).toLong()
    return dr * dr + dg * dg + db * db
}

@Composable
internal fun H2DNozzleAssignmentCard(
    filamentColors: List<String>,
    filamentMaterials: List<String>,
    assignments: List<Int>,
    onAssignmentsChange: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filamentCount = maxOf(filamentColors.size, filamentMaterials.size, 1)
    val normalized = normalizeH2DNozzleAssignments(assignments, filamentCount)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "H2D nozzle assignment",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Choose a physical nozzle for each filament. Auto picks a nozzle that can reach every toolpath; an impossible manual choice stops project generation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            repeat(filamentCount) { index ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(filamentColors.getOrElse(index) { "#FFFFFF" }))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Filament ${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            filamentMaterials.getOrNull(index).orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(0 to "Auto", 1 to "Left", 2 to "Right").forEach { (value, label) ->
                            FilterChip(
                                selected = normalized[index] == value,
                                onClick = {
                                    onAssignmentsChange(
                                        updateH2DNozzleAssignment(
                                            assignments = normalized,
                                            filamentCount = filamentCount,
                                            filamentIndex = index,
                                            assignment = value,
                                        ),
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
