package com.u1.slicer.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    filamentColours: List<String> = emptyList()
) {
    var swapSheetRegion by remember { mutableStateOf<AiRegion?>(null) }
    var localRegions by remember(uiState) {
        mutableStateOf((uiState as? AiPaintUiState.Result)?.state?.regions ?: emptyList())
    }

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

                    // Preview image (front shaded render from Phase 2)
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(0.42f)
                            .background(Color(0xFF111118))
                    ) {
                        result.previewBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Painted model preview",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Surface(
                            Modifier.align(Alignment.TopEnd).padding(8.dp),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text("4 regions", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Region list
                    Text(
                        "AI COLOUR SUGGESTIONS — tap to change",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                    LazyColumn(Modifier.weight(1f)) {
                        items(localRegions) { region ->
                            RegionRow(
                                region = region,
                                onClick = { swapSheetRegion = region }
                            )
                        }
                    }

                    // Bottom actions
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

    // Colour swap bottom sheet
    swapSheetRegion?.let { region ->
        ColourSwapSheet(
            region = region,
            filamentColours = filamentColours,
            onApply = { newColour ->
                localRegions = localRegions.map { r ->
                    if (r.id == region.id) r.copy(userColour = newColour) else r
                }
                swapSheetRegion = null
            },
            onDismiss = { swapSheetRegion = null }
        )
    }
}

@Composable
private fun RegionRow(region: AiRegion, onClick: () -> Unit) {
    val colour = remember(region.effectiveColour) {
        runCatching { android.graphics.Color.parseColor(region.effectiveColour) }
            .getOrDefault(android.graphics.Color.GRAY)
    }
    ListItem(
        headlineContent = { Text(region.label) },
        supportingContent = {
            Text(
                "${"%.0f".format(region.coverageFraction * 100)}% of model",
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
