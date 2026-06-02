package com.u1.slicer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.data.PerObjectPose
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * F66 — The Edit panel that appears beneath the inline 3D preview on the
 * Prepare screen.
 *
 *  - Nothing selected: panel is hidden entirely. Bed-wide actions
 *    (Auto-orient all + Reset all) now live in the preview's top-right
 *    overflow menu so they're accessible at all times without taking
 *    vertical space below the preview.
 *  - Object selected: object-scoped controls. Auto-Orient (this object),
 *    Split to Objects, Split to Parts, Reset rotation/scale, Delete, plus
 *    the unified ObjectFilamentPanel for whole-object + per-part colour.
 */
@Composable
fun EditPanel(
    viewModel: SlicerViewModel,
    modifier: Modifier = Modifier,
) {
    val selection by viewModel.selection.collectAsState()
    val scope = rememberCoroutineScope()

    val sel = selection.objectIndex ?: return
    ObjectScopedEditSection(
        objIdx = sel,
        viewModel = viewModel,
        scope = scope,
        modifier = modifier,
    )
}

@Composable
private fun ObjectScopedEditSection(
    objIdx: Int,
    viewModel: SlicerViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val poses by viewModel.perObjectPoses.collectAsState()
    val loadTime by viewModel.loadTimePoses.collectAsState()
    val modelVersion by viewModel.modelAddVersion.collectAsState()
    val pose = poses[objIdx] ?: PerObjectPose()
    val baseline = loadTime[objIdx] ?: PerObjectPose()
    // F66 — re-key on modelAddVersion (bumped by every structural mutation
    // and every per-object pose change) so a split that changes object 5's
    // name / splittable-ness / volume count refreshes the panel instead of
    // showing the pre-split values.
    val name = remember(objIdx, modelVersion) {
        viewModel.objectName(objIdx).ifBlank { "Object ${objIdx + 1}" }
    }
    val isSplittable = remember(objIdx, modelVersion) { viewModel.isObjectSplittable(objIdx) }
    val volumeCount = remember(objIdx, modelVersion) { viewModel.volumeCount(objIdx) }

    val rotationDirty = pose.rotXDeg != baseline.rotXDeg ||
        pose.rotYDeg != baseline.rotYDeg || pose.rotZDeg != baseline.rotZDeg
    val scaleDirty = pose.scaleX != baseline.scaleX ||
        pose.scaleY != baseline.scaleY || pose.scaleZ != baseline.scaleZ

    // v2.10.6: wrap the per-object edit section in an outlined Card with a
    // primary-coloured border so it's visually obvious that THESE controls
    // act on the selection only — distinct from the bed-wide controls
    // (which are now hidden when an object is selected). Without this,
    // users found the per-object panel ambiguous with the bed-wide one.
    Card(
        modifier = modifier,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            2.dp, MaterialTheme.colorScheme.primary,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.OpenWith,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Editing this part",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            IconButton(onClick = { viewModel.deselect() }) {
                Icon(Icons.Default.Close, contentDescription = "Deselect")
            }
        }

        // Per-object Scale + Copies + Rotation in the same Card+Tabs shape
        // as the bed-wide ScaleSection in MainActivity. v2.10.6: Copies now
        // lives inside the Scale tab (matching bed-wide layout) instead of
        // being a separate Card below.
        ObjectTransformCard(objIdx = objIdx, viewModel = viewModel, pose = pose)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { scope.launch { viewModel.autoOrientObject(objIdx) } },
                modifier = Modifier.weight(1f),
            ) { Text("Auto-orient") }
            FilledTonalButton(
                onClick = { viewModel.splitObject(objIdx) },
                enabled = isSplittable,
                modifier = Modifier.weight(1f),
            ) { Text("Split to Objects") }
        }
        // F66 2026-05-31: "Split to Parts" must appear ALSO when there's a
        // single volume whose mesh has multiple disconnected islands —
        // OrcaSlicer's desktop "Split → To Parts" covers that case explicitly,
        // and files like chain-20-links.stl (20 disconnected chain links in one
        // STL volume) or Jumping+frog.3mf Object 1 are the canonical examples.
        // Previously the button was hidden whenever volumeCount == 1, denying
        // those splits even though native is_splittable() returns true.
        val firstVolumeSplittable = remember(objIdx, volumeCount) {
            volumeCount >= 1 && viewModel.isVolumeSplittable(objIdx, 0)
        }
        val anyVolumeSplittable = remember(objIdx, volumeCount) {
            (0 until volumeCount).any { viewModel.isVolumeSplittable(objIdx, it) }
        }
        if (volumeCount > 1 || firstVolumeSplittable) {
            FilledTonalButton(
                onClick = {
                    // For single-volume files, route the click directly to
                    // volume 0. For multi-volume, pick the first volume that
                    // can be split (typical case: only some volumes are
                    // splittable; others are single-island parts).
                    val target = (0 until volumeCount).firstOrNull {
                        viewModel.isVolumeSplittable(objIdx, it)
                    }
                    if (target != null) viewModel.splitVolume(objIdx, target)
                },
                enabled = anyVolumeSplittable,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Split to Parts") }
        }
        if (rotationDirty) {
            OutlinedButton(
                onClick = { viewModel.resetObjectRotation(objIdx) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset rotation") }
        }
        if (scaleDirty) {
            OutlinedButton(
                onClick = { viewModel.resetObjectScale(objIdx) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset scale") }
        }

        if (volumeCount >= 1) {
            // F66 — unified filament panel. Always shows a whole-object row
            // (works for single-volume too); for multi-volume objects also
            // exposes a "Parts (N) ▼" expander for per-part control.
            Text(
                "Filament",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            ObjectFilamentPanel(
                objIdx = objIdx,
                viewModel = viewModel,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    } // close outer Card
}

/**
 * F66 — per-object Scale + Rotation in a Card with TabRow, matching the
 * bed-wide `ScaleSection` in MainActivity (`Scale, Copies & Rotation`).
 * Title omits "Copies" since copies are a whole-model concept.
 *
 *  ┌─────────────────────────────────────────────────────────┐
 *  │  ⊕  Scale & Rotation                                 ⌃  │
 *  │  ─────────────────────────────────────────────────────  │
 *  │     [ Scale ]    [ Rotation ]                           │
 *  │     <tab body — see ObjectScaleControl / RotationTab>   │
 *  └─────────────────────────────────────────────────────────┘
 */
@Composable
private fun ObjectTransformCard(
    objIdx: Int,
    viewModel: SlicerViewModel,
    pose: PerObjectPose,
) {
    var expanded by remember(objIdx) { mutableStateOf(true) }
    var selectedTab by remember(objIdx) { mutableIntStateOf(0) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.OpenWith,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Scale, Copies & Rotation",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Scale") },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Rotation") },
                        )
                    }
                    if (selectedTab == 0) {
                        // v2.10.8 — per-object Copies. Slider value tracks the
                        // user's target while dragging; duplicates are created
                        // only on release (onValueChangeFinished). The earlier
                        // v2.10.4/5/6/7 version fired duplicateObject() on
                        // every onValueChange tick, which raced with the
                        // rotation LaunchedEffect's getPreparePreviewMesh fetch
                        // and SIGSEGV'd in the native code (concurrent model
                        // mutation while the preview was being built).
                        // Slider state is local to the current selection.
                        var perObjectCopyTarget by remember(objIdx) { mutableIntStateOf(1) }
                        var perObjectCopyApplied by remember(objIdx) { mutableIntStateOf(1) }
                        Text(
                            "Copies of this part: $perObjectCopyTarget",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = perObjectCopyTarget.toFloat(),
                            onValueChange = { v ->
                                // Local UI state only — no native side-effects
                                // during the drag.
                                perObjectCopyTarget = v.toInt().coerceIn(1, 16)
                            },
                            onValueChangeFinished = {
                                // Commit on release: add (target - applied)
                                // duplicates. Decreasing is a no-op (select +
                                // delete to remove individual copies).
                                if (perObjectCopyTarget > perObjectCopyApplied) {
                                    repeat(perObjectCopyTarget - perObjectCopyApplied) {
                                        viewModel.duplicateObject(objIdx)
                                    }
                                    perObjectCopyApplied = perObjectCopyTarget
                                } else {
                                    // Snap the slider back if user dragged left
                                    // (decreasing not supported yet).
                                    perObjectCopyTarget = perObjectCopyApplied
                                }
                            },
                            valueRange = 1f..16f,
                            steps = 14,
                        )
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        ObjectScaleControl(objIdx = objIdx, viewModel = viewModel)
                    } else {
                        ObjectRotationControl(objIdx = objIdx, viewModel = viewModel, pose = pose)
                    }
                }
            }
        }
    }
}

@Composable
private fun ObjectRotationControl(
    objIdx: Int,
    viewModel: SlicerViewModel,
    pose: PerObjectPose,
) {
    AxisSlider(
        label = "Rotate X",
        value = pose.rotXDeg,
        range = 0f..360f,
        onChange = { viewModel.setObjectRotation(objIdx, it, pose.rotYDeg, pose.rotZDeg) },
    )
    AxisSlider(
        label = "Rotate Y",
        value = pose.rotYDeg,
        range = 0f..360f,
        onChange = { viewModel.setObjectRotation(objIdx, pose.rotXDeg, it, pose.rotZDeg) },
    )
    AxisSlider(
        label = "Rotate Z",
        value = pose.rotZDeg,
        range = 0f..360f,
        onChange = { viewModel.setObjectRotation(objIdx, pose.rotXDeg, pose.rotYDeg, it) },
    )
}

@Composable
private fun AxisSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 4.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${value.roundToInt()}°",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * F66 — per-object scale UX. Mirrors the bed-wide `ScaleSection` in
 * MainActivity for visual + functional parity: uniform / non-uniform toggle,
 * percent / mm toggle, per-axis input.
 *
 *  ┌─────────────────────────────────────────────────────────┐
 *  │ Scale            mm [○─]   Uniform [─●]                 │
 *  │ ─────────────────────────────────────────────────────── │
 *  │   100 %       (one row when Uniform=on)                 │
 *  │                                                         │
 *  │   X: 100 %    Y: 100 %    Z: 100 %  (when Uniform=off)  │
 *  └─────────────────────────────────────────────────────────┘
 */
@Composable
private fun ObjectScaleControl(
    objIdx: Int,
    viewModel: com.u1.slicer.SlicerViewModel,
) {
    val poses by viewModel.perObjectPoses.collectAsState()
    val loadTimeBoxes by viewModel.loadTimeObjectBoundingBoxes.collectAsState()
    val pose = poses[objIdx] ?: com.u1.slicer.data.PerObjectPose()

    var uniformMode by remember(objIdx) { mutableStateOf(true) }
    var mmMode by remember(objIdx) { mutableStateOf(false) }

    // Load-time per-axis size = the "100%" reference for mm mode. Falls back
    // to current pose-derived size if load-time boxes haven't been captured
    // (e.g. snapshotLoadTimePoses hasn't run yet).
    val sizeX = loadTimeBoxes.getOrNull(objIdx * 3) ?: 0f
    val sizeY = loadTimeBoxes.getOrNull(objIdx * 3 + 1) ?: 0f
    val sizeZ = loadTimeBoxes.getOrNull(objIdx * 3 + 2) ?: 0f
    val mmAvailable = sizeX > 0f && sizeY > 0f && sizeZ > 0f
    val mmActive = mmMode && mmAvailable

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Scale",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (mmAvailable) {
            Text("mm", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            androidx.compose.material3.Switch(
                checked = mmMode,
                onCheckedChange = { mmMode = it },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Text("Uniform", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        androidx.compose.material3.Switch(
            checked = uniformMode,
            onCheckedChange = { uniformMode = it },
            modifier = Modifier.padding(start = 4.dp),
        )
    }

    if (uniformMode) {
        ScaleAxisRow(
            label = "",
            scale = pose.scaleX,
            axisSize = sizeX,
            mmActive = mmActive,
            onScaleChange = { newScale -> viewModel.setObjectScale(objIdx, newScale, newScale, newScale) },
        )
    } else {
        ScaleAxisRow(
            label = "X",
            scale = pose.scaleX,
            axisSize = sizeX,
            mmActive = mmActive,
            onScaleChange = { newScale -> viewModel.setObjectScale(objIdx, newScale, pose.scaleY, pose.scaleZ) },
        )
        ScaleAxisRow(
            label = "Y",
            scale = pose.scaleY,
            axisSize = sizeY,
            mmActive = mmActive,
            onScaleChange = { newScale -> viewModel.setObjectScale(objIdx, pose.scaleX, newScale, pose.scaleZ) },
        )
        ScaleAxisRow(
            label = "Z",
            scale = pose.scaleZ,
            axisSize = sizeZ,
            mmActive = mmActive,
            onScaleChange = { newScale -> viewModel.setObjectScale(objIdx, pose.scaleX, pose.scaleY, newScale) },
        )
    }
}

@Composable
private fun ScaleAxisRow(
    label: String,
    scale: Float,
    axisSize: Float,                   // load-time size for this axis, mm
    mmActive: Boolean,
    onScaleChange: (Float) -> Unit,
) {
    // v2.10.9: parity with bed-wide ScaleSection — replaces the read-only Text
    // display with an editable OutlinedTextField. Edit-on-Done parses % or mm
    // and applies it; slider drag updates the field live.
    val formatField: (Float) -> String = { v ->
        if (mmActive) "%.1f".format(v * axisSize)
        else "%.0f".format(v * 100f)
    }
    var fieldText by remember(scale, mmActive) {
        mutableStateOf(formatField(scale))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 4.dp))
        }
        Slider(
            value = scale.coerceIn(0.1f, 4f),
            onValueChange = {
                onScaleChange(it)
                fieldText = formatField(it)
            },
            valueRange = 0.1f..4f,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.OutlinedTextField(
            value = fieldText,
            onValueChange = { fieldText = it },
            suffix = { Text(if (mmActive) "mm" else "%") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                val parsed = fieldText.toFloatOrNull()
                if (parsed != null) {
                    val newScale = if (mmActive && axisSize > 0f) parsed / axisSize
                                   else parsed / 100f
                    onScaleChange(newScale.coerceIn(0.1f, 4f))
                }
            }),
            modifier = Modifier.width(96.dp),
        )
    }
}
