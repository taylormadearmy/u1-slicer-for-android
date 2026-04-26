package com.u1.slicer.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.printer.PrinterViewModel
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
            val semmColorPermutation by viewModel.semmColorPermutationFlow.collectAsState()
            val slicerColorOrder by viewModel.slicerColorOrder.collectAsState()
            val gcodeUsesPhysicalSlots by viewModel.gcodeUsesPhysicalSlots.collectAsState()
            val resolvedFilamentColors by viewModel.resolvedFilamentColors.collectAsState()
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
                    semmColorPermutation = semmColorPermutation,
                    slicerColorOrder = slicerColorOrder,
                    slicerLayerCount = slicerLayerCount,
                    useDirectSlots = gcodeUsesPhysicalSlots,
                    resolvedFilamentColors = resolvedFilamentColors,
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
    }
}
