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
            val slicerState by viewModel.state.collectAsState()
            val slicerLayerCount = (slicerState as? com.u1.slicer.SlicerViewModel.SlicerState.SliceComplete)?.result?.totalLayers ?: 0
            if (parsedGcode != null) {
                GcodeViewer3DScreen(
                    parsedGcode = parsedGcode!!,
                    extruderColors = extruderColors,
                    slicerLayerCount = slicerLayerCount,
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
    }
}
