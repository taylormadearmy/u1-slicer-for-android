package com.u1.slicer.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.printer.PrinterViewModel
import com.u1.slicer.ui.AiPaintResultScreen
import com.u1.slicer.ui.FilamentScreen
import com.u1.slicer.ui.GcodeViewer3DScreen
import com.u1.slicer.ui.MakerWorldBrowserScreen
import com.u1.slicer.ui.ModelViewerScreen

object Routes {
    const val PREPARE = "prepare"
    const val PREVIEW = "preview"
    const val SETTINGS = "settings"
    const val PRINTER = "printer"
    const val FILAMENTS = "filaments"
    const val JOBS = "jobs"
    const val GCODE_VIEWER_3D = "gcode_viewer_3d"
    const val MODEL_VIEWER = "model_viewer"
    const val MAKERWORLD_BROWSER = "makerworld_browser"
    const val AI_PAINT = "ai_paint"
}

@Composable
fun U1NavGraph(
    navController: NavHostController,
    viewModel: SlicerViewModel,
    @Suppress("UNUSED_PARAMETER") printerViewModel: PrinterViewModel,
    @Suppress("UNUSED_PARAMETER") onPickFile: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onSaveGcode: () -> Unit,
    prepareContent: @Composable () -> Unit,
    previewContent: @Composable () -> Unit,
    printerContent: @Composable () -> Unit,
    jobsContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit
) {
    NavHost(navController = navController, startDestination = Routes.PREPARE) {
        composable(Routes.PREPARE) {
            prepareContent()
        }
        composable(Routes.PREVIEW) {
            previewContent()
        }
        composable(Routes.SETTINGS) {
            settingsContent()
        }
        composable(Routes.PRINTER) {
            printerContent()
        }
        composable(Routes.FILAMENTS) {
            val filaments by viewModel.filaments.collectAsState(initial = emptyList())
            FilamentScreen(
                filaments = filaments,
                onAdd = { viewModel.addFilament(it) },
                onUpdate = { viewModel.updateFilament(it) },
                onDelete = { viewModel.deleteFilament(it) },
                onApply = {
                    viewModel.applyFilament(it)
                    navController.popBackStack()
                },
                onSetDefault = { viewModel.setDefaultFilament(it) },
                onImport = { viewModel.importFilaments(it) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.JOBS) {
            jobsContent()
        }
        composable(Routes.GCODE_VIEWER_3D) {
            val parsedGcode by viewModel.parsedGcode.collectAsState()
            val extruderColors by viewModel.activeExtruderColors.collectAsState()
            val colorMapping by viewModel.colorMapping.collectAsState()
            val threeMfInfo by viewModel.threeMfInfo.collectAsState()
            val slicerState by viewModel.state.collectAsState()
            val resolvedFilamentColors by viewModel.resolvedFilamentColors.collectAsState()
            val canonicalFilamentColors by viewModel.canonicalFilamentColors.collectAsState()
            val slicerLayerCount = (slicerState as? com.u1.slicer.SlicerViewModel.SlicerState.SliceComplete)?.result?.totalLayers ?: 0
            if (parsedGcode != null) {
                // B48: H2C models (>4 model colours) — slicer's T0-T3 are physical
                // slot indices. Don't pass model→slot colorMapping to G-code preview.
                // Normal painted models (<=4 colours) still need the mapping.
                val isH2c = threeMfInfo?.hasPaintData == true &&
                    (colorMapping?.distinct()?.size ?: 0) >= 4 &&
                    (colorMapping?.size ?: 0) > (colorMapping?.distinct()?.size ?: 0)
                val gcodeColorMapping = if (isH2c) null else colorMapping
                GcodeViewer3DScreen(
                    parsedGcode = parsedGcode!!,
                    extruderColors = extruderColors,
                    colorMapping = gcodeColorMapping,
                    slicerLayerCount = slicerLayerCount,
                    // Bug 1 class sibling fix (post-v2.0.0-validation):
                    // gcode body emits T<canonical-fileIndex>; palette
                    // must be canonical-aligned. Pre-fix used
                    // resolvedFilamentColors (plate-narrowed) which
                    // misindexed for multi-plate Bambu fileIdx > 0.
                    resolvedFilamentColors = canonicalFilamentColors
                        .takeIf { it.isNotEmpty() }
                        ?: resolvedFilamentColors,
                    onBack = { navController.popBackStack() }
                )
            }
        }
        composable(Routes.MODEL_VIEWER) {
            val modelPath = viewModel.currentModelPath
            if (modelPath != null) {
                ModelViewerScreen(
                    modelFilePath = modelPath,
                    onBack = { navController.popBackStack() }
                )
            }
        }
        composable(Routes.MAKERWORLD_BROWSER) {
            MakerWorldBrowserScreen(
                viewModel = viewModel,
                onModelDownloaded = {
                    navController.popBackStack(Routes.PREPARE, inclusive = false)
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.AI_PAINT) {
            val container = (navController.context.applicationContext as U1SlicerApplication).container
            val aiVm = container.aiPaintViewModel
            val uiState by aiVm.uiState.collectAsState()
            // fix37: build the full 4-slot palette from the user's extruder presets directly.
            // viewModel.activeExtruderColors is filtered by `usedSlots` (which slots the loaded
            // model actually uses) so on a single-colour plate it leaves the other 3 slots
            // blank — making the Smart Paint picker show grey for slots the user can still
            // pick. The presets always have all 4, regardless of model.
            val extruderPresets by viewModel.extruderPresets.collectAsState()
            val filamentColours = remember(extruderPresets) {
                (0..3).map { i ->
                    extruderPresets.firstOrNull { it.index == i }?.color
                        ?.takeIf { it.isNotBlank() }
                        ?: com.u1.slicer.data.ExtruderPreset.DEFAULT_COLORS[i]
                }
            }

            AiPaintResultScreen(
                uiState = uiState,
                filamentColours = filamentColours,
                onUsePainting = {
                    // The painted 3MF is written ON DEMAND at this point — every paint stroke
                    // up to now has been an in-memory update only, so the result screen stays
                    // snappy. Block briefly while we serialise the final mesh and then load it.
                    val finalPath = aiVm.finalizePainting()
                    if (finalPath != null) {
                        viewModel.loadModelFromFile(java.io.File(finalPath))
                        navController.popBackStack(Routes.PREPARE, inclusive = false)
                    }
                },
                onRedo = {
                    val sourcePath = viewModel.currentModelPath ?: return@AiPaintResultScreen
                    aiVm.redo(sourcePath, viewModel.nativeLib)
                },
                onBack = {
                    aiVm.reset()
                    navController.popBackStack()
                },
                onNavigateSettings = { navController.navigate(Routes.SETTINGS) },
                onMoveComponent = { componentId, toRegion -> aiVm.moveComponent(componentId, toRegion) },
                onHighlightComponent = { componentId -> aiVm.highlightComponent(componentId) },
                onUpdateRegionColour = { regionId, hex -> aiVm.updateRegionColour(regionId, hex) },
                onPaintTriangles = { triIds, toRegion -> aiVm.paintTriangles(triIds, toRegion) },
                onBrushStrokeStart = { aiVm.beginUndoCheckpoint() },
                onUndo = { aiVm.undo() },
                onSetSegmentSlot = { segmentId, newSlot -> aiVm.setSegmentSlot(segmentId, newSlot) },
                onCommitSelection = { triIds, toSlot -> aiVm.commitSelection(triIds, toSlot) },
                onSwitchToAlternate = { aiVm.switchToAlternate() },
            )
        }
    }
}
