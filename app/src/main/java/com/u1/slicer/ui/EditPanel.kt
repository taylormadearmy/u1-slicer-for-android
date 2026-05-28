package com.u1.slicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.data.PerObjectPose
import kotlinx.coroutines.launch

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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onAutoOrientAll, modifier = Modifier.fillMaxWidth()) {
            Text("Auto-orient all")
        }
        if (anyRotationDirty) {
            OutlinedButton(onClick = onResetAllRotations, modifier = Modifier.fillMaxWidth()) {
                Text("Reset all rotations")
            }
        }
        if (anyScaleDirty) {
            OutlinedButton(onClick = onResetAllScales, modifier = Modifier.fillMaxWidth()) {
                Text("Reset all scales")
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
    val pose = poses[objIdx] ?: PerObjectPose()
    val baseline = loadTime[objIdx] ?: PerObjectPose()
    val name = remember(objIdx) { viewModel.objectName(objIdx).ifBlank { "Object ${objIdx + 1}" } }
    val isSplittable = remember(objIdx) { viewModel.isObjectSplittable(objIdx) }
    val volumeCount = remember(objIdx) { viewModel.volumeCount(objIdx) }

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

        if (volumeCount > 1) {
            PartsPanel(objIdx = objIdx, viewModel = viewModel)
        }
    }
}
