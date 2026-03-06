package com.u1.slicer.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.printer.PrinterViewModel
import com.u1.slicer.ui.FilamentScreen
import com.u1.slicer.ui.GcodeViewer3DScreen
import com.u1.slicer.ui.GcodeViewerScreen
import com.u1.slicer.ui.JobsScreen
import com.u1.slicer.ui.ModelViewerScreen
import com.u1.slicer.ui.PlacementViewerScreen
import com.u1.slicer.ui.PrinterScreen
import com.u1.slicer.ui.SettingsScreen

object Routes {
    const val SLICER = "slicer"
    const val SETTINGS = "settings"
    const val PRINTER = "printer"
    const val FILAMENTS = "filaments"
    const val JOBS = "jobs"
    const val GCODE_VIEWER = "gcode_viewer"
    const val GCODE_VIEWER_3D = "gcode_viewer_3d"
    const val MODEL_VIEWER = "model_viewer"
    const val PLACEMENT_VIEWER = "placement_viewer"
}

@Composable
fun U1NavGraph(
    navController: NavHostController,
    viewModel: SlicerViewModel,
    @Suppress("UNUSED_PARAMETER") onPickFile: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onSaveGcode: () -> Unit,
    slicerContent: @Composable () -> Unit
) {
    NavHost(navController = navController, startDestination = Routes.SLICER) {
        composable(Routes.SLICER) {
            slicerContent()
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.PRINTER) {
            val printerViewModel: PrinterViewModel = viewModel()
            PrinterScreen(
                viewModel = printerViewModel,
                onBack = { navController.popBackStack() }
            )
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
                onImport = { viewModel.importFilaments(it) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.JOBS) {
            val jobs by viewModel.sliceJobs.collectAsState(initial = emptyList())
            JobsScreen(
                jobs = jobs,
                onDelete = { viewModel.deleteJob(it) },
                onDeleteAll = { viewModel.deleteAllJobs() },
                onShare = { viewModel.shareJobGcode(it) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.GCODE_VIEWER) {
            val parsedGcode by viewModel.parsedGcode.collectAsState()
            if (parsedGcode != null) {
                GcodeViewerScreen(
                    parsedGcode = parsedGcode!!,
                    onBack = { navController.popBackStack() }
                )
            }
        }
        composable(Routes.GCODE_VIEWER_3D) {
            val parsedGcode by viewModel.parsedGcode.collectAsState()
            val extruderColors by viewModel.activeExtruderColors.collectAsState()
            if (parsedGcode != null) {
                GcodeViewer3DScreen(
                    parsedGcode = parsedGcode!!,
                    extruderColors = extruderColors,
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
        composable(Routes.PLACEMENT_VIEWER) {
            val config by viewModel.config.collectAsState()
            val copyCount by viewModel.copyCount.collectAsState()
            val modelInfo = (viewModel.state.collectAsState().value as? SlicerViewModel.SlicerState.ModelLoaded)?.info
            if (modelInfo != null) {
                PlacementViewerScreen(
                    modelInfo = modelInfo,
                    copyCount = copyCount,
                    wipeTowerEnabled = config.wipeTowerEnabled,
                    wipeTowerWidthMm = config.wipeTowerWidth,
                    initialObjectPositions = viewModel.getPlacementPositions(),
                    initialWipeTowerPos = Pair(config.wipeTowerX, config.wipeTowerY),
                    onApply = { positions, towerPos ->
                        viewModel.applyPlacementPositions(positions, towerPos)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
