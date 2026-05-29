package com.u1.slicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.data.PerObjectPose
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * F66 — The Edit panel that appears beneath the inline 3D preview on the
 * Prepare screen. Has two visual states:
 *  - Nothing selected (default): bed-wide controls. Auto-Orient All + Reset
 *    all rotations/scales.
 *  - Object selected: object-scoped controls. Auto-Orient (this object),
 *    Split to Objects, Split to Parts, Reset rotation/scale, Delete, plus
 *    the Parts panel when the selected object has >1 volume.
 */
@Composable
fun EditPanel(
    viewModel: SlicerViewModel,
    modifier: Modifier = Modifier,
) {
    val selection by viewModel.selection.collectAsState()
    val poses by viewModel.perObjectPoses.collectAsState()
    val loadTime by viewModel.loadTimePoses.collectAsState()
    val scope = rememberCoroutineScope()

    val sel = selection.objectIndex
    if (sel == null) {
        BedWideEditSection(
            anyRotationDirty = poses.any { (k, v) ->
                val baseline = loadTime[k] ?: PerObjectPose()
                v.rotXDeg != baseline.rotXDeg || v.rotYDeg != baseline.rotYDeg ||
                    v.rotZDeg != baseline.rotZDeg
            },
            anyScaleDirty = poses.any { (k, v) ->
                val baseline = loadTime[k] ?: PerObjectPose()
                v.scaleX != baseline.scaleX || v.scaleY != baseline.scaleY ||
                    v.scaleZ != baseline.scaleZ
            },
            onAutoOrientAll = { scope.launch { viewModel.autoOrientAll() } },
            onResetAllRotations = { viewModel.resetAllRotations() },
            onResetAllScales = { viewModel.resetAllScales() },
            modifier = modifier,
        )
    } else {
        ObjectScopedEditSection(
            objIdx = sel,
            viewModel = viewModel,
            scope = scope,
            modifier = modifier,
        )
    }
}

@Composable
private fun BedWideEditSection(
    anyRotationDirty: Boolean,
    anyScaleDirty: Boolean,
    onAutoOrientAll: () -> Unit,
    onResetAllRotations: () -> Unit,
    onResetAllScales: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bed-wide actions are infrequent — "Auto-orient all" is a once-per-load
    // affordance and the Reset buttons only appear post-edit. Collapse them
    // into a single trailing 3-dot menu so the panel stays compact for the
    // common "no selection, doing nothing" state, and so the Reset entries
    // appear only when there's actually something to reset.
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Tap an object to edit",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Bed actions")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Auto-orient all") },
                    onClick = { onAutoOrientAll(); menuOpen = false },
                )
                if (anyRotationDirty) {
                    DropdownMenuItem(
                        text = { Text("Reset all rotations") },
                        onClick = { onResetAllRotations(); menuOpen = false },
                    )
                }
                if (anyScaleDirty) {
                    DropdownMenuItem(
                        text = { Text("Reset all scales") },
                        onClick = { onResetAllScales(); menuOpen = false },
                    )
                }
            }
        }
    }
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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Selected: $name",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            IconButton(onClick = { viewModel.deselect() }) {
                Icon(Icons.Default.Close, contentDescription = "Deselect")
            }
        }

        // Per-object rotation dials (X / Y / Z). Range 0..360 degrees with 1-degree
        // snap on release for readability; sub-degree precision isn't useful at the
        // small touch-screen size.
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
        // Per-object scale — uniform / non-uniform toggle, % / mm modes,
        // matching the bed-wide ScaleSection in MainActivity for parity.
        ObjectScaleControl(objIdx = objIdx, viewModel = viewModel)

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
        if (volumeCount > 1) {
            FilledTonalButton(
                onClick = {
                    // Split the first splittable volume in this object. If none, no-op.
                    val firstSplittable = (0 until volumeCount).firstOrNull {
                        viewModel.isVolumeSplittable(objIdx, it)
                    }
                    if (firstSplittable != null) viewModel.splitVolume(objIdx, firstSplittable)
                },
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
    val display = if (mmActive) "%.1f mm".format(scale * axisSize)
                  else "${(scale * 100).roundToInt()} %"
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
            onValueChange = onScaleChange,
            valueRange = 0.1f..4f,
            modifier = Modifier.weight(1f),
        )
        Text(
            display,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
