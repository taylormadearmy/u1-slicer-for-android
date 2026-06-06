package com.u1.slicer.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.printer.PrinterViewModel
import com.u1.slicer.ui.AiPaintResultScreen
import com.u1.slicer.ui.CreateMixSlotDialog
import com.u1.slicer.ui.FilamentScreen
import com.u1.slicer.ui.GcodeViewer3DScreen
import com.u1.slicer.ui.MakerWorldBrowserScreen
import com.u1.slicer.ui.ModelViewerScreen
import com.u1.slicer.ui.ProcessProfilesScreen

object Routes {
    const val PREPARE = "prepare"
    const val PREVIEW = "preview"
    const val SETTINGS = "settings"
    const val PRINTER = "printer"
    const val FILAMENTS = "filaments"
    const val PROCESS_PROFILES = "process_profiles"
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
            val extruderPresets by viewModel.extruderPresets.collectAsState()
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
                onBack = { navController.popBackStack() },
                // M3 Phase A: wire the mix-slot manager + presets so the
                // Filaments screen can render mix rows and open the dialog.
                mixedFilamentManager = viewModel.mixedFilamentManager,
                extruderPresets = extruderPresets,
            )
        }
        composable(Routes.JOBS) {
            jobsContent()
        }
        composable(Routes.PROCESS_PROFILES) {
            val cfg by viewModel.processProfilesConfig.collectAsState()
            ProcessProfilesScreen(
                profiles = cfg.profiles,
                activeId = cfg.activeId,
                onImport = { json, fallbackName -> viewModel.importProcessProfilesFromJson(json, fallbackName) },
                onSetActive = { viewModel.setActiveProcessProfile(it) },
                onRename = { id, name -> viewModel.renameProcessProfile(id, name) },
                onDelete = { viewModel.deleteProcessProfile(it) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.GCODE_VIEWER_3D) {
            val parsedGcode by viewModel.parsedGcode.collectAsState()
            val extruderColors by viewModel.activeExtruderColors.collectAsState()
            val colorMapping by viewModel.colorMapping.collectAsState()
            val threeMfInfo by viewModel.threeMfInfo.collectAsState()
            val slicerState by viewModel.state.collectAsState()
            val resolvedFilamentColors by viewModel.resolvedFilamentColors.collectAsState()
            val canonicalFilamentColors by viewModel.canonicalFilamentColors.collectAsState()
            val previewLayerRange by viewModel.previewLayerRange.collectAsState()
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
                    // B129: remember the layer-slider position across navigation
                    // (e.g. when the user leaves to move/rotate the model) so it
                    // doesn't reset to the top. Reset only happens on a new slice.
                    initialLayerRange = previewLayerRange,
                    onLayerRangeChange = { lo, hi -> viewModel.setPreviewLayerRange(lo, hi) },
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
            // M3-Phase-B/F1: wire the slot ceiling so mix slots (ids ≥ numPhysical)
            // are accepted by the three paint guards in AiPaintViewModel. SideEffect
            // runs synchronously each recomposition, keeping the lambda fresh whenever
            // the active mix set changes.
            // C-1: the Smart Paint surface is ALWAYS a fixed 4-physical-slot space
            // (SegmentationCascade.TARGET_SLOTS). Do NOT derive numPhysical from
            // slicerConfig.extruderCount — a freshly loaded single-colour STL has
            // extruderCount = 1, which would collapse the picker/palette to 1 chip,
            // reject E2/E3/E4 reassignment, and place mix virtual ids at 1+k (colliding
            // with physical slots in the always-4-physical painted file).
            val numPhysical = com.u1.slicer.aipaint.SegmentationCascade.TARGET_SLOTS
            SideEffect {
                aiVm.slotCeiling = {
                    numPhysical + viewModel.mixedFilamentManager.activeMixCount(numPhysical)
                }
            }
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
            // M3-Phase-B/F2: the painted-3MF writer appends one filament_colour entry per
            // active mix (in MixSlotOrdering order) so triangles painted to a mix slot resolve
            // to a real canonical filament instead of collapsing. Naive-blend the two
            // components' loaded colours for the display swatch. Declared after filamentColours
            // so the provider lambda can capture it.
            SideEffect {
                aiVm.mixDisplayColoursProvider = {
                    viewModel.mixedFilamentManager.activeOrder(numPhysical).map { row ->
                        val a = filamentColours.getOrNull(row.componentA - 1) ?: "#888888"
                        val b = filamentColours.getOrNull(row.componentB - 1) ?: "#888888"
                        com.u1.slicer.aipaint.ColourMatch.naiveBlendHex(a, b, row.mixBPercent)
                    }
                }
            }

            // B114: launch the painted-3MF serialization off the click handler so an
            // axolotl-sized model (880k tris taking 5-8s to write) doesn't hang the
            // main thread and trigger Android's ANR dialog.
            val finalizeScope = rememberCoroutineScope()
            val projectMixes by viewModel.mixedFilamentManager.projectMixes.collectAsState()
            val libraryMixes by viewModel.mixedFilamentManager.libraryMixes.collectAsState()

            // C1a (M3-Phase-B): mix-slot dialog state for per-region SectionedSlotPicker.
            var createMixOpen by remember { mutableStateOf(false) }
            var editMixRow by remember { mutableStateOf<com.u1.slicer.data.MixedFilamentRow?>(null) }

            if (createMixOpen || editMixRow != null) {
                val physicalColours = remember(extruderPresets) {
                    (0..3).map { i ->
                        val hex = extruderPresets.firstOrNull { it.index == i }?.color
                            ?.takeIf { it.isNotBlank() }
                            ?: com.u1.slicer.data.ExtruderPreset.DEFAULT_COLORS[i]
                        androidx.compose.ui.graphics.Color(
                            runCatching { android.graphics.Color.parseColor(hex) }
                                .getOrDefault(android.graphics.Color.GRAY)
                        )
                    }
                }
                CreateMixSlotDialog(
                    physicalFilamentColours = physicalColours,
                    physicalFilamentLabels = listOf("E1", "E2", "E3", "E4"),
                    editingRow = editMixRow,
                    onConfirm = { a, b, pct, mode ->
                        val editing = editMixRow
                        if (editing != null) {
                            viewModel.mixedFilamentManager.edit(editing.id, a, b, pct, mode)
                        } else {
                            viewModel.mixedFilamentManager.add(a, b, pct, mode)
                        }
                    },
                    onDelete = editMixRow?.let { row ->
                        { viewModel.mixedFilamentManager.delete(row.id) }
                    },
                    onDismiss = {
                        createMixOpen = false
                        editMixRow = null
                    },
                )
            }

            AiPaintResultScreen(
                uiState = uiState,
                filamentColours = filamentColours,
                projectMixes = projectMixes,
                libraryMixes = libraryMixes,
                numPhysical = numPhysical,
                onUsePainting = {
                    finalizeScope.launch {
                        val finalPath = aiVm.finalizePainting()
                        if (finalPath != null) {
                            // F88 follow-up: pass the original model name captured at pipeline
                            // launch so currentModelName stays "MyModel.3mf" instead of the
                            // cache file's auto-generated "ai_paint_<ts>.3mf" name.
                            val originalDisplayName = (uiState as? com.u1.slicer.aipaint.AiPaintUiState.Result)
                                ?.state?.sourceDisplayName
                            viewModel.loadModelFromFile(java.io.File(finalPath), preserveDisplayName = originalDisplayName)
                            navController.popBackStack(Routes.PREPARE, inclusive = false)
                        }
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
                onSetSlotColor = { slot, hex -> viewModel.setSlotColor(slot, hex) },
                onCreateMix = { createMixOpen = true },
                onEditMix = { row -> editMixRow = row },
            )
        }
    }
}
