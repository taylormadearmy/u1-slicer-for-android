package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u1.slicer.SlicerViewModel

/**
 * F66 — Unified filament panel for the selected object. Always shows a
 * **whole-object** filament row at the top (colour swatch + material chip)
 * so the user can re-paint the entire object with one tap. When the object
 * has more than one volume, a "Parts (N)" expander reveals per-part rows
 * for finer-grained control. Earlier shape ("Parts ▼" only, collapsed by
 * default) hid the per-part feature; this shape makes both levels
 * discoverable without requiring the user to expand anything first.
 *
 *   ┌────────────────────────────────────────────────┐
 *   │  ● white          Object 1               PETG  │   <-- whole-object
 *   └────────────────────────────────────────────────┘
 *   Parts (3) ▼                                       <-- expander (multi)
 *     ┌──────────────────────────────────────────────┐
 *     │ ● white   Part 1                       PETG  │
 *     │ ● red     Part 2                       PETG  │
 *     │ ● black   Part 3                       PETG  │
 *     └──────────────────────────────────────────────┘
 *
 * The whole-object row's chip shows "Mixed" when the object's parts use
 * different slots; tapping it opens the picker with no highlight and
 * picking a slot re-paints every part to that slot.
 */
@Composable
fun ObjectFilamentPanel(
    objIdx: Int,
    viewModel: SlicerViewModel,
    modifier: Modifier = Modifier,
) {
    val modelVersion by viewModel.modelAddVersion.collectAsState()
    val volumeCount = remember(objIdx, modelVersion) { viewModel.volumeCount(objIdx) }
    if (volumeCount <= 0) return

    Column(modifier.fillMaxWidth()) {
        // Whole-object row — always visible.
        WholeObjectFilamentRow(
            objIdx = objIdx,
            viewModel = viewModel,
            modifier = Modifier.fillMaxWidth(),
        )

        // Per-part rows — only when the object has multiple volumes.
        if (volumeCount > 1) {
            var expanded by remember(objIdx) { mutableStateOf(false) }
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                // F66 review-2026-05-30 P2: Material icons match bed-wide expand
                // patterns (Compose ExpandLess/More) and stay readable for
                // screen readers.
                Text("Parts ($volumeCount)", modifier = Modifier.padding(end = 4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse parts list" else "Expand parts list",
                )
            }
            if (expanded) {
                val perVolume by viewModel.perVolumeExtruders.collectAsState()
                for (v in 0 until volumeCount) {
                    val key = "$objIdx:$v"
                    val slot = perVolume[key]
                        ?: viewModel.volumeExtruder(objIdx, v).coerceAtLeast(1)
                    val partName = remember(objIdx, v, modelVersion) {
                        viewModel.volumeName(objIdx, v).ifBlank { "Part ${v + 1}" }
                    }
                    PartFilamentRow(
                        partName = partName,
                        currentSlot = slot,
                        viewModel = viewModel,
                        onSelectSlot = { newSlot -> viewModel.setVolumeExtruder(objIdx, v, newSlot) },
                        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun WholeObjectFilamentRow(
    objIdx: Int,
    viewModel: SlicerViewModel,
    modifier: Modifier = Modifier,
) {
    // collectAsState on _perVolumeExtruders so the aggregated row refreshes
    // when ANY part changes slot — not just when the whole object is reassigned.
    val perVolume by viewModel.perVolumeExtruders.collectAsState()
    val modelVersion by viewModel.modelAddVersion.collectAsState()
    val aggregated = remember(objIdx, modelVersion, perVolume) {
        viewModel.aggregatedObjectFilament(objIdx)
    }
    val objectName = remember(objIdx, modelVersion) {
        viewModel.objectName(objIdx).ifBlank { "Object ${objIdx + 1}" }
    }
    PartFilamentRow(
        partName = objectName,
        currentSlot = aggregated ?: 0,                     // 0 → "Mixed" display
        viewModel = viewModel,
        onSelectSlot = { newSlot -> viewModel.setObjectFilament(objIdx, newSlot) },
        modifier = modifier,
    )
}

/**
 * One filament row — used both for the whole-object aggregated row and for
 * each per-part row inside the expander. `currentSlot == 0` is a sentinel
 * meaning "mixed across parts": the swatch dims and the material chip reads
 * "Mixed" so the user sees that no single colour applies.
 */
@Composable
private fun PartFilamentRow(
    partName: String,
    currentSlot: Int,                  // 1..N for a real slot, 0 = mixed sentinel
    viewModel: SlicerViewModel,
    onSelectSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMixed = currentSlot <= 0
    val (color, material, _) = if (isMixed) {
        Triple(Color(0xFF888888), "Mixed", "—")
    } else {
        resolveFilamentChip(currentSlot, viewModel)
    }
    var picking by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable { picking = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Text(
            partName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        AssistChip(onClick = { picking = true }, label = { Text(material) })
    }

    if (picking) {
        FilamentChooserDialog(
            currentSlot = currentSlot,
            viewModel = viewModel,
            onPick = { newSlot ->
                onSelectSlot(newSlot)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/** Resolve the (colour, material, label) tuple for a given filament index, choosing
 *  between the file's canonical filament list (for 3MFs), the printer's slot
 *  presets (for STLs), and the active mixes (slot ids above the mix base).
 *  Returns a 1-based slot label. */
@Composable
private fun resolveFilamentChip(
    slot: Int,                                  // 1-based; caller must guarantee > 0
    viewModel: SlicerViewModel,
): Triple<Color, String, String> {
    val canonical by viewModel.canonicalFilamentList.collectAsState()
    val resolvedColors by viewModel.resolvedFilamentColors.collectAsState()
    val displayedMaterials by viewModel.displayedFilamentMaterials.collectAsState()
    val activeColors by viewModel.activeExtruderColors.collectAsState()
    val presets by viewModel.extruderPresets.collectAsState()
    val projectMixes by viewModel.mixedFilamentManager.projectMixes.collectAsState()
    val libraryMixes by viewModel.mixedFilamentManager.libraryMixes.collectAsState()

    val numPhysical = com.u1.slicer.aipaint.SegmentationCascade.TARGET_SLOTS
    val canonicalSize = canonical?.size ?: 0

    // B142 (2026-06-10): a mix-assigned part rendered a grey swatch + "PLA" here
    // because mix slot ids (> mixBase) fell through to the slot-preset branch.
    // Resolve them to the mix's blend colour over the printer's loaded presets.
    val mixBase = maxOf(numPhysical, canonicalSize)
    if (slot > mixBase) {
        val mix = com.u1.slicer.data.MixSlotOrdering
            .activeOrder(projectMixes, libraryMixes, numPhysical)
            .getOrNull(slot - mixBase - 1)
        if (mix != null) {
            val blend = com.u1.slicer.aipaint.FilamentMixPredictor.predict(
                mix.components.map { c ->
                    presets.firstOrNull { it.index == c - 1 }?.color?.takeIf { it.isNotBlank() }
                        ?: com.u1.slicer.data.ExtruderPreset.DEFAULT_COLORS[(c - 1).mod(4)]
                },
                mix.weights,
            )
            return Triple(parseHexColor(blend), "Mix", mix.label.ifBlank { "Mix" })
        }
    }

    val useFile = canonical != null && slot - 1 < canonicalSize
    return if (useFile) {
        val idx = slot - 1
        val hex = resolvedColors.getOrNull(idx) ?: ""
        val mat = displayedMaterials.getOrNull(idx)?.first ?: "PLA"
        Triple(parseHexColor(hex), mat, "Filament $slot")
    } else {
        val hex = activeColors.getOrNull(slot - 1) ?: ""
        // `slot` is 1-based, preset.index is 0-based (pre-existing off-by-one fixed here).
        val mat = presets.firstOrNull { it.index == slot - 1 }?.materialType ?: "PLA"
        Triple(parseHexColor(hex), mat, "Filament $slot")
    }
}

/**
 * Issue #3 — the per-part / whole-object "Assign to filament" dialog, made
 * mix-aware. After the physical filament rows it lists the active mixes
 * (two-tone swatch) and a "+ Add mix" affordance that opens
 * [CreateMixSlotDialog].
 *
 * Slot-id contract for [onPick] (1-based, matching
 * [SlicerViewModel.setVolumeExtruder] / the native `vol->config["extruder"]`):
 *   - physical filament `n` → slot `n` (1..numPhysical)
 *   - mix at ordered index `idx` → slot `numPhysical + idx + 1` (5, 6, …)
 *
 * Assigning a mix bakes that mix id into the object/volume's `extruder`
 * metadata PRE-slice; the OrcaSlicer engine then resolves it as a virtual
 * filament and alternates the two component tools per layer (the proven blend
 * path — see MixSlotObjectAssignDiagnosticTest). The recipe + physical base
 * are guaranteed at slice time by [SlicerViewModel.startSlicing].
 */
@Composable
private fun FilamentChooserDialog(
    currentSlot: Int,
    viewModel: SlicerViewModel,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val canonical by viewModel.canonicalFilamentList.collectAsState()
    val rowCount = canonical?.size ?: 4
    // Mix slot ids are offset by the physical filament base (U1 = 4). Use the
    // same base the recipe serialization / FilamentMixChipRow use so the id a
    // part is assigned lines up with the engine's virtual-filament order.
    val numPhysical = com.u1.slicer.aipaint.SegmentationCascade.TARGET_SLOTS

    val projectMixes by viewModel.mixedFilamentManager.projectMixes.collectAsState()
    val libraryMixes by viewModel.mixedFilamentManager.libraryMixes.collectAsState()
    val mixes = remember(projectMixes, libraryMixes) {
        com.u1.slicer.data.MixSlotOrdering.activeOrder(projectMixes, libraryMixes, numPhysical)
    }

    val extruderPresets by viewModel.extruderPresets.collectAsState()
    // Mixes blend PHYSICAL extruders (E1..E4), so mix swatches and the
    // Create-Mix component palette must show what's loaded in the printer
    // (slot presets) — never the model's file-declared filament colours.
    // (2026-06-10 sanity test: a CMYW-loaded printer showed red/black mix
    // swatches because this block preferred resolvedFilamentColors.)
    val physicalColours = remember(numPhysical, extruderPresets) {
        (1..numPhysical).map { slot ->
            val hex = extruderPresets.firstOrNull { it.index == slot - 1 }?.color
                ?.takeIf { it.isNotBlank() }
                ?: com.u1.slicer.data.ExtruderPreset.DEFAULT_COLORS[(slot - 1) % 4]
            parseHexColor(hex)
        }
    }
    val physicalLabels = remember(numPhysical) { (1..numPhysical).map { "E$it" } }

    var creatingMix by remember { mutableStateOf(false) }
    var editingMix by remember { mutableStateOf<com.u1.slicer.data.MixedFilamentRow?>(null) }

    if (creatingMix) {
        CreateMixSlotDialog(
            physicalFilamentColours = physicalColours,
            physicalFilamentLabels = physicalLabels,
            editingRow = editingMix,
            onConfirmN = { components, weights, mode ->
                val edit = editingMix
                if (edit != null) viewModel.editMixN(edit.id, components, weights, mode)
                else viewModel.createMixN(components, weights, mode)
            },
            onDelete = editingMix?.let { row -> { viewModel.mixedFilamentManager.delete(row.id) } },
            onDismiss = { creatingMix = false; editingMix = null },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign to filament") },
        text = {
            // verticalScroll so the mix rows + "+ Add mix" (rendered AFTER the physical
            // filament rows) stay reachable when the combined list exceeds the dialog's
            // height — otherwise they're clipped below the fold and look "missing"
            // (mirrors ModelInfoDialogScrollTest's guard for the same dialog-clipping class).
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (canonical != null)
                        "Pick which file-declared filament to apply. Slot mapping happens at Send →"
                    else
                        "Slot mapping happens when you tap Send →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (slot in 1..rowCount) {
                    val (swatch, mat, label) = resolveFilamentChip(slot, viewModel)
                    // currentSlot == 0 is the "mixed" sentinel — no row marked current.
                    val isCurrent = slot == currentSlot
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                            )
                            .clickable { onPick(slot) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                label + if (isCurrent) " · current" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                mat,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                // ── Mix slots ───────────────────────────────────────────────
                // 1-based mix slot id = mixBase + idx + 1 (matches
                // setVolumeExtruder's native extruder contract). Picking a mix
                // bakes that id into the object/volume extruder → real blend.
                // M4/#2: base is max(numPhysical, canonicalCount) so mix ids
                // cannot collide with canonical filament slots when the 3MF
                // has more than TARGET_SLOTS (4) canonical filaments.
                // No-op when canonicalCount <= numPhysical.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                ) {
                    Text("Mix slots", style = MaterialTheme.typography.labelMedium)
                    BetaPill()
                }
                val mixBase = maxOf(numPhysical, canonical?.size ?: numPhysical)
                mixes.forEachIndexed { idx, mix ->
                    val mixSlot1Based = mixBase + idx + 1
                    val isCurrent = mixSlot1Based == currentSlot
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                            )
                            .clickable { onPick(mixSlot1Based) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MixedSlotSwatch(
                            colours = mix.components.map { physicalColours.getOrNull(it - 1) ?: androidx.compose.ui.graphics.Color.Gray },
                            weights = mix.weights,
                            size = 24.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                mix.label.ifBlank { "Mix ${idx + 1}" } +
                                    if (isCurrent) " · current" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "Mix E${mix.componentA}+E${mix.componentB} · ${mix.mixBPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                // ── + Add mix ───────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable { editingMix = null; creatingMix = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier.size(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "+",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Add mix",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
