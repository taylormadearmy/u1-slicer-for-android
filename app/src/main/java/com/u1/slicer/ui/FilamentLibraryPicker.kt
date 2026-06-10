package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.u1.slicer.data.FilamentLibrary
import com.u1.slicer.data.FilamentLibraryEntry
import com.u1.slicer.data.LibraryState
import com.u1.slicer.data.buildImportPreview
import com.u1.slicer.data.hasImportableData

private val MATERIAL_FILTERS = listOf("PLA", "PETG", "ABS", "TPU", "ASA")

/**
 * Reusable OpenPrintTag filament-library picker. Lives inside host dialogs
 * (FilamentColorEditDialog / ExtruderSlotEditDialog "Library" tab) so it stays
 * compact and self-contained: no ViewModel or DataStore imports — favourites /
 * recents / state are passed in, actions are passed out.
 *
 * [onImport] is null when the host can't import profile data; when non-null and
 * the entry has importable fields, a "Use + import profile…" affordance opens
 * [FilamentImportPreviewDialog]. Confirming fires [onImport] only (the host
 * applies colour + material AND links the profile in that single callback).
 */
@Composable
fun FilamentLibraryPicker(
    state: LibraryState,
    favourites: List<String>,
    recents: List<String>,
    onToggleFavourite: (String) -> Unit,
    onPick: (FilamentLibraryEntry) -> Unit,
    onImport: ((FilamentLibraryEntry) -> Unit)?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is LibraryState.Loading -> {
            Box(
                modifier = modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is LibraryState.Failed -> {
            Column(
                modifier = modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }

        is LibraryState.Ready -> {
            LibraryReadyContent(
                library = state.library,
                favourites = favourites,
                recents = recents,
                onToggleFavourite = onToggleFavourite,
                onPick = onPick,
                onImport = onImport,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun LibraryReadyContent(
    library: FilamentLibrary,
    favourites: List<String>,
    recents: List<String>,
    onToggleFavourite: (String) -> Unit,
    onPick: (FilamentLibraryEntry) -> Unit,
    onImport: ((FilamentLibraryEntry) -> Unit)?,
    modifier: Modifier,
) {
    var query by remember { mutableStateOf("") }
    var materialFilter by remember { mutableStateOf<String?>(null) }
    var selectedSlug by remember { mutableStateOf<String?>(null) }
    var importTarget by remember { mutableStateOf<FilamentLibraryEntry?>(null) }

    val favSet = favourites.toSet()
    val results = remember(query, materialFilter, favourites, recents, library) {
        library.search(query, materialFilter, favourites.toSet(), recents)
    }

    // On a blank query the search() result is ordered favourites → recents → rest,
    // so we can partition it back into those groups for section headers.
    val blankQuery = query.isBlank()
    val recentSet = recents.toSet()
    val favGroup = if (blankQuery) results.filter { it.slug in favSet } else emptyList()
    val recentGroup = if (blankQuery) {
        results.filter { it.slug !in favSet && it.slug in recentSet }
    } else emptyList()
    val restGroup = if (blankQuery) {
        results.filter { it.slug !in favSet && it.slug !in recentSet }
    } else results

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search brand, name, material…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            // Narrow host dialogs can't fit all six chips — keep TPU/ASA reachable.
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            FilterChip(
                selected = materialFilter == null,
                onClick = { materialFilter = null },
                label = { Text("All") },
            )
            MATERIAL_FILTERS.forEach { m ->
                FilterChip(
                    selected = materialFilter == m,
                    onClick = { materialFilter = if (materialFilter == m) null else m },
                    label = { Text(m) },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            fun rowItems(group: List<FilamentLibraryEntry>) {
                items(group, key = { it.slug }) { entry ->
                    EntryRow(
                        entry = entry,
                        isFavourite = entry.slug in favSet,
                        isSelected = entry.slug == selectedSlug,
                        onToggleFavourite = onToggleFavourite,
                        onSelect = {
                            selectedSlug = if (selectedSlug == entry.slug) null else entry.slug
                        },
                        onPick = onPick,
                        onImport = onImport,
                        onRequestImport = { importTarget = it },
                    )
                }
            }

            if (blankQuery) {
                // Only show a header when its group is non-empty (search() ordering
                // guarantees favourites → recents → rest, so these stay grouped).
                if (favGroup.isNotEmpty()) {
                    item(key = "hdr-fav") { SectionHeader("FAVOURITES") }
                    rowItems(favGroup)
                }
                if (recentGroup.isNotEmpty()) {
                    item(key = "hdr-recent") { SectionHeader("RECENT") }
                    rowItems(recentGroup)
                }
                if (restGroup.isNotEmpty()) {
                    item(key = "hdr-all") { SectionHeader("ALL") }
                    rowItems(restGroup)
                }
            } else {
                rowItems(restGroup)
            }
        }

        Text(
            "OpenPrintTag database — ${library.snapshot.count} filaments, " +
                "snapshot ${library.snapshot.date} (MIT)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }

    importTarget?.let { target ->
        FilamentImportPreviewDialog(
            entry = target,
            onConfirm = {
                onImport?.invoke(target)
                importTarget = null
            },
            onDismiss = { importTarget = null },
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun EntryRow(
    entry: FilamentLibraryEntry,
    isFavourite: Boolean,
    isSelected: Boolean,
    onToggleFavourite: (String) -> Unit,
    onSelect: () -> Unit,
    onPick: (FilamentLibraryEntry) -> Unit,
    onImport: ((FilamentLibraryEntry) -> Unit)?,
    onRequestImport: (FilamentLibraryEntry) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Swatch(entry.hex)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    entry.material,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = { onToggleFavourite(entry.slug) }) {
                Icon(
                    if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isFavourite) "Remove favourite" else "Add favourite",
                    tint = if (isFavourite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }

        if (isSelected) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onImport != null && hasImportableData(entry)) {
                    // The long label yields width first so the Use button never wraps.
                    TextButton(
                        onClick = { onRequestImport(entry) },
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        Text("Use + import profile…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Button(onClick = { onPick(entry) }) { Text("Use", maxLines = 1) }
            }
        }
    }
}

@Composable
private fun Swatch(hex: String?) {
    if (hex == null) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(parseHexColorOrDefault(hex))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

/**
 * Field-by-field preview of exactly what an import would bring in. Lists the
 * rows from [buildImportPreview]; confirming fires [onConfirm] (the host's
 * single pick+import callback).
 */
@Composable
internal fun FilamentImportPreviewDialog(
    entry: FilamentLibraryEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(entry.slug) { buildImportPreview(entry) }
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Card(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Import profile data",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { row ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(row.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    row.value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            row.note?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = onConfirm) { Text("Import") }
                }
            }
        }
    }
}
