package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.ImportedMixRecipeRowSummary
import com.u1.slicer.data.MixedFilamentDefinitionSource
import com.u1.slicer.data.MixedFilamentSliceSummary

@Composable
fun ImportedMixRecipeBanner(
    recipe: MixedFilamentSliceSummary,
    source: MixedFilamentDefinitionSource,
    onViewRecipe: () -> Unit,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeLabel = when (source) {
        MixedFilamentDefinitionSource.FILE_EMBEDDED -> "File mix active"
        MixedFilamentDefinitionSource.MANAGER_STATE -> "Project mix copy active"
        MixedFilamentDefinitionSource.NONE -> "No active mix"
    }
    val primaryLabel = when (source) {
        MixedFilamentDefinitionSource.FILE_EMBEDDED -> "Make copy"
        MixedFilamentDefinitionSource.MANAGER_STATE -> "Use file mix"
        MixedFilamentDefinitionSource.NONE -> "Make copy"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when (source) {
                            MixedFilamentDefinitionSource.FILE_EMBEDDED -> "Imported mix active"
                            MixedFilamentDefinitionSource.MANAGER_STATE -> "Project mix copy active"
                            MixedFilamentDefinitionSource.NONE -> "Mix active"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        activeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                AssistChip(
                    onClick = onViewRecipe,
                    label = { Text("${recipe.activeMixCount} mixes") },
                )
            }

            Text(
                when (source) {
                    MixedFilamentDefinitionSource.FILE_EMBEDDED ->
                        "Loaded from the 3MF. Make a project copy to edit."
                    MixedFilamentDefinitionSource.MANAGER_STATE ->
                        "Editing the project copy. Use file mix to go back."
                    MixedFilamentDefinitionSource.NONE ->
                        "No imported mix is active."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onViewRecipe) {
                    Text("View mix")
                }
                Button(onClick = onPrimaryAction) {
                    Text(primaryLabel)
                }
            }
        }
    }
}

@Composable
fun ImportedMixRecipeDetails(
    recipe: MixedFilamentSliceSummary,
    source: MixedFilamentDefinitionSource,
    extruderPresets: List<ExtruderPreset>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Source: ${source.name.lowercase().replace('_', ' ')}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            "${recipe.activeMixCount} mix${if (recipe.activeMixCount == 1) "" else "es"}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        recipe.rows.forEachIndexed { index, row ->
            ImportedMixRecipeRowCard(
                index = index,
                row = row,
                extruderPresets = extruderPresets,
            )
        }
    }
}

@Composable
private fun ImportedMixRecipeRowCard(
    index: Int,
    row: ImportedMixRecipeRowSummary,
    extruderPresets: List<ExtruderPreset>,
) {
    val componentColours = rememberRowComponentColours(row, extruderPresets)
    val previewLabel = row.componentIds.joinToString("+") { "E$it" }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MixedSlotSwatch(
                    colours = componentColours,
                    weights = row.weights,
                    size = 34.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${index + 1}. $previewLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        row.weights.joinToString("/") { "$it%" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                componentColours.forEachIndexed { componentIdx, colour ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(colour),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "E${row.componentIds.getOrNull(componentIdx) ?: componentIdx + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        )
                    }
                }
            }

            Text(
                "${row.distributionLabel} · ${row.topMixLabel}" +
                    " · ${if (row.fineTopLines) "Fine" else "No fine"}" +
                    " · ${if (row.ironingGlaze) "Iron" else "No iron"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            row.parseWarning?.let { warning ->
                Text(
                    warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun rememberRowComponentColours(
    row: ImportedMixRecipeRowSummary,
    extruderPresets: List<ExtruderPreset>,
): List<Color> = row.componentIds.mapIndexed { index, componentId ->
    val hex = extruderPresets.firstOrNull { it.index == componentId - 1 }?.color
        ?.takeIf { it.isNotBlank() }
        ?: ExtruderPreset.DEFAULT_COLORS.getOrNull(componentId - 1)
        ?: ExtruderPreset.DEFAULT_COLORS.getOrNull(index)
        ?: ExtruderPreset.DEFAULT_COLORS.first()
    parseHexColorOrDefault(hex)
}
