package com.u1.slicer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.ExtruderPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class PreparePreviewViewModelTest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun dragonPlate3_selectPlate_keepsThreeVisiblePrepareColours_andSliceOutput() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("Dragon Scale infinity.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("plate selector visible") {
                viewModel.showPlateSelector.value
            }

            viewModel.selectPlate(3)

            waitUntil("plate 3 loaded with color mapping", timeoutMs = 90_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }

            val info = viewModel.threeMfInfo.value
            val mapping = viewModel.colorMapping.value

            assertNotNull("Plate 3 info should be available after selection", info)
            assertNotNull("Plate 3 color mapping should be available after selection", mapping)

            info!!
            mapping!!

            assertTrue(
                "Dragon plate 3 should keep at least 3 detected colors in Prepare state, got ${info.detectedColors}",
                info.detectedColors.size >= 3
            )
            assertTrue(
                "Dragon plate 3 should keep at least 3 visible slots in Prepare state, got $mapping",
                mapping.distinct().size >= 3
            )
            assertTrue(
                "Expected at least 3 non-blank active extruder colors, got ${viewModel.activeExtruderColors.value}",
                viewModel.activeExtruderColors.value.count { it.isNotBlank() } >= 3
            )

            val preview = NativeLibrary().getPreparePreviewMesh()
            assertNotNull("Native prepare preview mesh should be available after Dragon plate 3 load", preview)
            preview!!
            val distinctIndices = preview.extruderIndices.map { it.toInt() and 0xFF }.toSet().sorted()
            assertTrue(
                "Dragon plate 3 native preview indices should preserve at least 3 colors, got $distinctIndices",
                distinctIndices.size >= 3
            )
            assertTrue(
                "Dragon plate 3 native preview indices must be compact 0..N-1 for Android recolor, got $distinctIndices",
                distinctIndices == distinctIndices.indices.toList()
            )

            viewModel.startSlicing()

            waitUntil("dragon plate 3 slice complete", timeoutMs = 120_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.SliceComplete
            }

            val state = viewModel.state.value as SlicerViewModel.SlicerState.SliceComplete
            val gcode = File(state.result.gcodePath).readText()
            val toolChangeRegex = Regex("""(?m)^T([0-3])\b""")
            val toolLines = toolChangeRegex.findAll(gcode).map { it.groupValues[1].toInt() }.toList()
            val usedTools = toolLines.toSet().size

            assertTrue(
                "Dragon plate 3 slice should keep at least 3 extruders/tools in output, got $usedTools",
                usedTools >= 3
            )
            assertTrue(
                "Dragon plate 3 slice should contain explicit tool change commands, got ${toolLines.take(12)}",
                toolLines.isNotEmpty()
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    @Test
    fun slipSlidePlate3_selectPlate_keepsFourVisiblePrepareColours() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("slip slide spin fidget.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("plate selector visible") {
                viewModel.showPlateSelector.value
            }

            viewModel.selectPlate(3)

            waitUntil("slip slide plate 3 loaded with color mapping", timeoutMs = 90_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }

            val info = viewModel.threeMfInfo.value
            val mapping = viewModel.colorMapping.value

            assertNotNull("Slip/slide plate 3 info should be available", info)
            assertNotNull("Slip/slide plate 3 color mapping should be available", mapping)

            info!!
            mapping!!

            assertTrue(
                "Slip/slide plate 3 should keep at least 4 detected colours in Prepare state, got ${info.detectedColors}",
                info.detectedColors.size >= 4
            )
            assertTrue(
                "Slip/slide plate 3 should keep at least 4 visible slots in Prepare state, got $mapping",
                mapping.distinct().size >= 4
            )
            assertTrue(
                "Slip/slide plate 3 should expose at least 4 non-blank active extruder colours, got ${viewModel.activeExtruderColors.value}",
                viewModel.activeExtruderColors.value.count { it.isNotBlank() } >= 4
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    @Test
    fun sButtons_plate1_selectPlate_keepsFourVisiblePrepareColours() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("Button-for-S-trousers.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("S-Buttons plate selector visible") {
                viewModel.showPlateSelector.value
            }

            viewModel.selectPlate(1)

            waitUntil("S-Buttons plate 1 loaded with color mapping", timeoutMs = 90_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }

            val info = viewModel.threeMfInfo.value
            val mapping = viewModel.colorMapping.value

            assertNotNull("Plate 1 info should be available after selection", info)
            assertNotNull("Plate 1 color mapping should be available after selection", mapping)

            info!!
            mapping!!

            assertTrue(
                "S-Buttons plate 1 should detect at least 4 colours in Prepare state, got ${info.detectedColors}",
                info.detectedColors.size >= 4
            )
            assertTrue(
                "S-Buttons plate 1 colorMapping should map to at least 4 distinct extruder slots, got $mapping",
                mapping.distinct().size >= 4
            )
            assertTrue(
                "S-Buttons plate 1 should expose at least 4 non-blank active extruder colours, got ${viewModel.activeExtruderColors.value}",
                viewModel.activeExtruderColors.value.count { it.isNotBlank() } >= 4
            )

            // Verify the native prepare mesh has 4 distinct extruder indices and recoloring
            // with the real mapping produces 4 distinct RGBA clusters (no colour clamping).
            val preview = NativeLibrary().getPreparePreviewMesh()
            assertNotNull("Native prepare preview mesh should be available after S-Buttons plate 1 load", preview)
            preview!!
            val mesh = preview.toMeshData()
            assertNotNull("MeshData conversion should succeed", mesh)
            mesh!!

            val distinctNativeIndices = mesh.extruderIndices!!.map { it.toInt() and 0xFF }.toSet()
            assertTrue(
                "Native mesh should have at least 4 distinct extruder indices for S-Buttons, got $distinctNativeIndices",
                distinctNativeIndices.size >= 4
            )

            val colors = viewModel.activeExtruderColors.value
            val palette = mapping.map { slot ->
                SlicerViewModel.staticHexColorToFloatArray(colors.getOrElse(slot) { "" })
            }
            mesh.recolor(palette)

            // Count distinct RGBA values in the vertex buffer — if pink (slot 3) is clamped
            // to blue (slot 2), we'd see only 3 distinct colours even though the mesh has 4 indices.
            val distinctRgba = mutableSetOf<Int>()
            val buf = mesh.vertices
            val triCount = mesh.vertexCount / 3
            for (tri in 0 until triCount) {
                val base = tri * 3 * com.u1.slicer.viewer.MeshData.FLOATS_PER_VERTEX
                val r = (buf.get(base + 6) * 255).toInt()
                val g = (buf.get(base + 7) * 255).toInt()
                val b = (buf.get(base + 8) * 255).toInt()
                distinctRgba.add((r shl 16) or (g shl 8) or b)
                if (distinctRgba.size >= 4) break
            }
            assertTrue(
                "S-Buttons plate 1 recolored mesh should have at least 4 distinct RGBA values (not 3 due to clamping), got ${distinctRgba.size}",
                distinctRgba.size >= 4
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    /**
     * B86: S-Buttons Prepare preview shows 3 colours instead of 4 (pink/E4 appears white).
     *
     * Root cause hypothesis: when the user has E2=white AND E4=pink, the auto colour-mapping
     * via findClosestExtruder (which runs at plate-load time using the current extruderPresets)
     * can produce a non-identity colorMapping where colorMapping[3]=1 (the 4th model colour
     * maps to slot 1 = E2 = white).  The mesh's extruder-index-3 triangles then get
     * palette[3]=activeExtruderColors[1]=white instead of activeExtruderColors[3]=pink.
     *
     * This test injects user-like presets before creating the ViewModel so DataStore has them
     * when loadNativeModel runs findClosestExtruder.  It asserts that activeExtruderColors
     * contains 4 DISTINCT colours (not just 4 non-blank entries), and that the recoloured
     * mesh vertex buffer has 4 distinct RGBA clusters.
     *
     * Red: should FAIL because E4 gets DEFAULT_COLORS[3]="#FFFFFF" (white = same as E2),
     * showing only 3 visually distinct colours.
     */
    @Test
    fun sButtons_plate1_withUserLikePresetsWhiteE2PinkE4_showsFourDistinctColors() {
        val application = targetContext.applicationContext as U1SlicerApplication

        // Save user-like presets: E2=white (matches what user reported), E4=pink.
        // If E4 falls back to DEFAULT_COLORS[3]="#FFFFFF" it will equal E2 and only
        // 3 distinct preview colours will be visible — that is the B86 bug.
        val userPresets = listOf(
            ExtruderPreset(index = 0, color = "#FFD700"),  // E1: yellow
            ExtruderPreset(index = 1, color = "#FFFFFF"),  // E2: white
            ExtruderPreset(index = 2, color = "#0000FF"),  // E3: blue
            ExtruderPreset(index = 3, color = "#FF69B4"),  // E4: pink
        )
        runBlocking {
            application.container.settingsRepository.saveExtruderPresets(userPresets)
        }

        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("Button-for-S-trousers.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("S-Buttons plate selector visible") {
                viewModel.showPlateSelector.value
            }

            viewModel.selectPlate(1)

            waitUntil("S-Buttons plate 1 loaded with color mapping", timeoutMs = 90_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }

            // Wait for extruderPresets to reflect the user presets we saved, and for
            // refreshMappedPreviewColors to propagate them into activeExtruderColors.
            waitUntil("extruderPresets should have E4=pink from DataStore", timeoutMs = 5_000L) {
                viewModel.extruderPresets.value.firstOrNull { it.index == 3 }?.color == "#FF69B4"
            }
            Thread.sleep(300) // allow refreshMappedPreviewColors coroutine to complete

            val colors = viewModel.activeExtruderColors.value
            val mapping = viewModel.colorMapping.value!!

            // B86: with E2=white and E4=pink, all 4 active colours must be DISTINCT.
            // If E4 was incorrectly set to DEFAULT_COLORS[3]="#FFFFFF" it would equal E2.
            val nonBlankColors = colors.filter { it.isNotBlank() }
            val distinctColors = nonBlankColors.distinct()
            assertTrue(
                "B86: S-Buttons with user presets [yellow,white,blue,pink] must have 4 distinct " +
                    "active extruder colours, got colors=$colors mapping=$mapping",
                distinctColors.size >= 4
            )

            // Also verify the palette produced for the Compose recolor has 4 distinct RGBA values
            // when applied to the native mesh. This catches colorMapping-level remapping bugs where
            // palette[3]=activeExtruderColors[mapping[3]]=white even though activeExtruderColors[3]=pink.
            val preview = NativeLibrary().getPreparePreviewMesh()
            assertNotNull("Native prepare preview mesh should be available", preview)
            preview!!
            val mesh = preview.toMeshData()!!

            val palette = mapping.map { slot ->
                SlicerViewModel.staticHexColorToFloatArray(colors.getOrElse(slot) { "" })
            }
            mesh.recolor(palette)

            val distinctRgba = mutableSetOf<Int>()
            val buf = mesh.vertices
            val triCount = mesh.vertexCount / 3
            for (tri in 0 until triCount) {
                val base = tri * 3 * com.u1.slicer.viewer.MeshData.FLOATS_PER_VERTEX
                val r = (buf.get(base + 6) * 255).toInt()
                val g = (buf.get(base + 7) * 255).toInt()
                val b = (buf.get(base + 8) * 255).toInt()
                distinctRgba.add((r shl 16) or (g shl 8) or b)
                if (distinctRgba.size >= 4) break
            }
            assertTrue(
                "B86: recolored mesh must show 4 distinct RGBA values with [yellow,white,blue,pink] presets " +
                    "(E4/pink must not collapse to E2/white). " +
                    "mapping=$mapping colors=$colors distinctRgba=$distinctRgba",
                distinctRgba.size >= 4
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
            runBlocking {
                application.container.settingsRepository.saveExtruderPresets(
                    com.u1.slicer.data.defaultExtruderPresets()
                )
            }
        }
    }

    /**
     * Follow-up diagnostic for the Skywing plate-1 hang observed during B87
     * investigation. Loads Skywing and triggers plate 1 selection, asserts the
     * native model load completes within a generous timeout and the Prepare
     * preview mesh is produced.
     *
     * The hang manifested after `restructurePlateFile: no multi-color components,
     * skipping` with no further SlicerVM log activity — either embedProfile hung
     * or loadNativeModel deadlocked. This test either reproduces the hang (fail
     * with timeout) or proves it's been resolved by an unrelated change.
     */
    @Test
    fun skywingDragon_selectPlate1_loadCompletes() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("skywing-seawing-silkwing.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("skywing plate selector or ModelLoaded", timeoutMs = 240_000L) {
                viewModel.showPlateSelector.value ||
                    viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            val s = viewModel.state.value
            if (s is SlicerViewModel.SlicerState.Error) {
                throw AssertionError("skywing load failed: ${s.message}")
            }
            assertTrue("skywing should show plate selector", viewModel.showPlateSelector.value)

            val startNanos = System.nanoTime()
            viewModel.selectPlate(1)

            waitUntil("skywing plate 1 ModelLoaded (colorMapping may be null)", timeoutMs = 360_000L) {
                val st = viewModel.state.value
                st is SlicerViewModel.SlicerState.ModelLoaded ||
                    st is SlicerViewModel.SlicerState.Error
            }
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L

            val st = viewModel.state.value
            if (st is SlicerViewModel.SlicerState.Error) {
                throw AssertionError("skywing plate 1 selectPlate errored: ${st.message}")
            }
            assertTrue(
                "skywing plate 1 must complete within 360s; took ${elapsedMs}ms",
                elapsedMs < 360_000L
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    /**
     * B88: Plate-switch on Buzz Lightyear multi-plate 3MF leaves Prepare preview
     * showing the previous plate's colour(s) instead of the new plate's palette.
     *
     * User report (DC15 on v1.6.7): switching to plate 9 shows a single colour that
     * matches the previously viewed plate, while the post-slice G-code viewer shows
     * a different single colour that matches the true plate 9 palette.
     *
     * This test loads plate 1, then plate 9, and asserts for plate 9:
     *   1. `threeMfInfo.detectedColors` for plate 9 is populated.
     *   2. `colorMapping` is consistent with plate 9's detected colours — i.e. its
     *      size equals `detectedColors.size` (fresh derivation, not stale slice).
     *   3. `activeExtruderColors` matches plate 9's colour palette.
     *   4. The recoloured Prepare preview mesh uses plate 9's palette — the set of
     *      distinct RGBA values matches the distinct mapping slots.
     *
     * Red: should FAIL on v1.6.7 where plate 9 inherits plate 1's state.
     */
    @Test
    fun buzzLightyear_plateSwitch_preparePreviewReflectsCurrentPlatePalette() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("Buzz_Multipart_3MF_Bambu.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            // Buzz 3MF is ~73MB — parsing + plate enumeration may take >90s on device.
            waitUntil("buzz plate selector visible or error", timeoutMs = 240_000L) {
                viewModel.showPlateSelector.value ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            val loadState = viewModel.state.value
            if (loadState is SlicerViewModel.SlicerState.Error) {
                throw AssertionError("Buzz 3MF load failed: ${loadState.message}")
            }

            // ----- Plate 1: establish a known palette / mapping -----
            viewModel.selectPlate(1)
            waitUntil("buzz plate 1 loaded", timeoutMs = 120_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }
            Thread.sleep(400) // let refreshMappedPreviewColors settle

            val plate1Info = viewModel.threeMfInfo.value!!
            val plate1Mapping = viewModel.colorMapping.value!!
            val plate1Colors = viewModel.activeExtruderColors.value.toList()
            val plate1DetectedColors = plate1Info.detectedColors.toList()

            // ----- Plate 9: the reported-broken plate -----
            viewModel.selectPlate(9)
            waitUntil("buzz plate 9 loaded", timeoutMs = 120_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }
            Thread.sleep(400)

            val plate9Info = viewModel.threeMfInfo.value!!
            val plate9Mapping = viewModel.colorMapping.value!!
            val plate9Colors = viewModel.activeExtruderColors.value.toList()
            val plate9DetectedColors = plate9Info.detectedColors.toList()
            val plate9PreviewForDiag = NativeLibrary().getPreparePreviewMesh()
            val plate9MeshIndices = plate9PreviewForDiag
                ?.extruderIndices
                ?.map { it.toInt() and 0xFF }
                ?.groupingBy { it }
                ?.eachCount()
            val plate9ObjExtruderMap = plate9Info.objectExtruderMap
            val plate9Plate = plate9Info.plates.firstOrNull { it.plateId == 9 }
            val plate9PlateFilamentIndices = plate9Plate?.filamentIndices
            val plate9PlateLayerToolColors = plate9Plate?.layerToolColors
            val plate9PlateObjectIds = plate9Plate?.objectIds

            val diag = """
                |B88 plate-switch state:
                |  plate1 detectedColors=${plate1DetectedColors}
                |  plate1 colorMapping=${plate1Mapping}
                |  plate1 activeExtruderColors=${plate1Colors}
                |  plate9 detectedColors=${plate9DetectedColors}
                |  plate9 colorMapping=${plate9Mapping}
                |  plate9 activeExtruderColors=${plate9Colors}
                |  plate9 native mesh extruderIndex distribution=${plate9MeshIndices}
                |  plate9 objectExtruderMap=${plate9ObjExtruderMap}
                |  plate9 plate.objectIds=${plate9PlateObjectIds}
                |  plate9 plate.filamentIndices=${plate9PlateFilamentIndices}
                |  plate9 plate.layerToolColors=${plate9PlateLayerToolColors}
                |  plate9 hasPaintData=${plate9Info.hasPaintData}
                |  plate9 hasMultiExtruderAssignments=${plate9Info.hasMultiExtruderAssignments}
                |  plate9 detectedExtruderCount=${plate9Info.detectedExtruderCount}
            """.trimMargin()

            // Invariant 1: plate 9's detected colours exist.
            assertTrue(
                "plate 9 must expose detectedColors\n$diag",
                plate9DetectedColors.isNotEmpty()
            )

            // Invariant 1b (B90): plate 9 objects use extruder=10 and paint state 8.
            // Before the fix, `parseForPlateSelection` synthesized (1..visualCount) =
            // (1..2) as effectiveExtruders, dropping filament 10 (white) and filament 8
            // (peach) and substituting filaments 1 (black) and 2 (blue) instead.
            // After the fix, detectedColors must include filament 10 (#FFFFFF) and
            // filament 8 (#FFD6C1).
            val expectedPlate9Colors = setOf("#FFFFFF", "#FFD6C1")
            assertTrue(
                "plate 9 detectedColors must include both filament 10 (#FFFFFF white, " +
                    "object extruder) and filament 8 (#FFD6C1 peach, paint state 8). " +
                    "Got ${plate9DetectedColors}\n$diag",
                plate9DetectedColors.toSet().containsAll(expectedPlate9Colors)
            )

            // Invariant 2: colorMapping size matches detectedColors size (stale detection).
            // If plate 9 inherited plate 1's mapping, size will equal plate 1 palette — not plate 9.
            assertEquals(
                "plate 9 colorMapping size must match plate 9 detectedColors size\n$diag",
                plate9DetectedColors.size,
                plate9Mapping.size
            )

            // Invariant 3: at least one of detectedColors, mapping, or activeExtruderColors
            // actually changed between the two plates (for different palettes) — otherwise
            // plate 9 is rendering with stale plate-1 state.
            val differsFromPlate1 =
                plate9DetectedColors != plate1DetectedColors ||
                    plate9Mapping != plate1Mapping ||
                    plate9Colors != plate1Colors
            assertTrue(
                "plate 9 state must differ from plate 1 state — stale leak suspected\n$diag",
                differsFromPlate1
            )

            // Invariant 4: preview mesh distinct RGBA count should match plate 9's
            // expected distinct slot count when recoloured with plate 9's palette.
            val preview = NativeLibrary().getPreparePreviewMesh()
            assertNotNull("plate 9 preview mesh required", preview)
            val mesh = preview!!.toMeshData()!!
            val palette = plate9Mapping.map { slot ->
                SlicerViewModel.staticHexColorToFloatArray(plate9Colors.getOrElse(slot) { "" })
            }
            mesh.recolor(palette)

            val distinctRgba = mutableSetOf<Int>()
            val buf = mesh.vertices
            val triCount = mesh.vertexCount / 3
            for (tri in 0 until triCount) {
                val base = tri * 3 * com.u1.slicer.viewer.MeshData.FLOATS_PER_VERTEX
                val r = (buf.get(base + 6) * 255).toInt()
                val g = (buf.get(base + 7) * 255).toInt()
                val b = (buf.get(base + 8) * 255).toInt()
                distinctRgba.add((r shl 16) or (g shl 8) or b)
                if (distinctRgba.size >= 8) break
            }
            val expectedDistinctSlots = plate9Mapping.distinct().size
            assertEquals(
                "plate 9 preview mesh distinct RGBA count must match plate 9 mapping's distinct slots\n" +
                    "$diag\n  distinctRgba=${distinctRgba.size} expected=$expectedDistinctSlots",
                expectedDistinctSlots,
                distinctRgba.size
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    @Test
    fun flippy_layerToolOnly_hasSegments_andRecolorByZBandsProducesMultipleColours() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("flippy+flappy+mini.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("flippy plate selector visible") {
                viewModel.showPlateSelector.value
            }

            viewModel.selectPlate(4)

            waitUntil("flippy loaded with layer-tool mapping", timeoutMs = 60_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.layerToolOnly.value
            }

            val info = viewModel.threeMfInfo.value
            assertNotNull("threeMfInfo should be non-null after flippy load", info)
            info!!

            assertTrue("flippy should have hasLayerToolChanges=true", info.hasLayerToolChanges)
            assertNotNull("flippy layerToolSegments should survive merge into threeMfInfo", info.layerToolSegments)
            assertTrue(
                "flippy layerToolSegments should be non-empty, got ${info.layerToolSegments?.size}",
                (info.layerToolSegments?.size ?: 0) >= 2
            )

            assertTrue("layerToolOnly StateFlow should be true for flippy", viewModel.layerToolOnly.value)

            val mapping = viewModel.colorMapping.value
            val colors = viewModel.activeExtruderColors.value
            assertNotNull("colorMapping should be non-null for flippy", mapping)
            assertTrue("activeExtruderColors should be non-empty for flippy", colors.isNotEmpty())

            // Verify recolorByZBands would produce distinct colours on the native preview
            val lib = NativeLibrary()
            assertTrue(lib.loadModel(modelFile.absolutePath))
            val preview = lib.getPreparePreviewMesh()
            assertNotNull("Native preview mesh should be available for flippy", preview)
            preview!!
            val meshData = preview.toMeshData()
            assertNotNull("MeshData conversion should succeed", meshData)
            meshData!!

            // Build a deterministic 2-colour palette: extruder 1 (base) = red, extruder 2 = green
            // so we can assert distinct colours regardless of user filament presets.
            val testPalette = listOf(
                floatArrayOf(1f, 0f, 0f, 1f),  // palette[0]: red (extruderBambu=1, base)
                floatArrayOf(0f, 1f, 0f, 1f)   // palette[1]: green (extruderBambu=2)
            )

            meshData.recolorByZBands(info.layerToolSegments!!, testPalette)

            val buf = meshData.vertices
            val triCount = meshData.vertexCount / 3
            // Check G channel: red has G=0, green has G=1 — should see both
            val gValues = mutableSetOf<Float>()
            for (tri in 0 until triCount) {
                val gOffset = tri * 3 * 10 + 7  // G is at offset 7 per vertex
                gValues.add(buf.get(gOffset))
                if (gValues.size >= 2) break
            }
            assertTrue(
                "recolorByZBands through full ViewModel path should produce at least 2 distinct G values for flippy (red base + green band), got $gValues",
                gValues.size >= 2
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    /**
     * B47: colorMapping must be set to non-null BEFORE state transitions to ModelLoaded for a
     * multi-colour 3MF. Previously, _colorMapping was emitted after _state = ModelLoaded in
     * loadNativeModel, creating a window where the combined LaunchedEffect could fire with
     * mesh=non-null but colorMapping=null, causing one colour to be missing on first load.
     *
     * Uses Dispatchers.Unconfined so StateFlow collect callbacks run inline on the emitting
     * thread — this faithfully captures the true emission sequence rather than a scheduler-
     * dependent snapshot.
     */
    @Test
    fun multiColorFile_colorMapping_isSetBeforeStateBecomesModelLoaded() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("calib-cube-10-dual-colour-merged.3mf")

        val events = Channel<Pair<String, Any?>>(Channel.UNLIMITED)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val stateJob = viewModel.state
            .onEach { events.trySend("state" to it) }
            .launchIn(scope)
        val mappingJob = viewModel.colorMapping
            .onEach { events.trySend("mapping" to it) }
            .launchIn(scope)

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("model loaded with non-null colorMapping") {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }

            stateJob.cancel()
            mappingJob.cancel()

            val recorded = mutableListOf<Pair<String, Any?>>()
            while (true) {
                recorded.add(events.tryReceive().getOrNull() ?: break)
            }

            val firstModelLoadedIdx = recorded.indexOfFirst { (key, value) ->
                key == "state" && value is SlicerViewModel.SlicerState.ModelLoaded
            }
            assertTrue("Expected to see a ModelLoaded event", firstModelLoadedIdx >= 0)

            val colorMappingNonNullBeforeModelLoaded = recorded
                .take(firstModelLoadedIdx)
                .any { (key, value) -> key == "mapping" && value != null }

            val eventSummary = recorded.joinToString {
                "${it.first}=${it.second?.let { v ->
                    if (v is List<*>) "List(${v.size})" else v::class.simpleName
                } ?: "null"}"
            }
            assertTrue(
                "B47: colorMapping must be non-null before state becomes ModelLoaded. Events: $eventSummary",
                colorMappingNonNullBeforeModelLoaded
            )
        } finally {
            stateJob.cancel()
            mappingJob.cancel()
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    @Test
    fun dragonScale_importViaUri_matchesDirectFilePipelineArtifacts() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val fileViewModel = SlicerViewModel(application)
        val uriViewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("Dragon Scale infinity.3mf")

        try {
            fileViewModel.loadModelFromFile(modelFile)

            waitUntil("direct-file import reaches prepared multi-plate state", timeoutMs = 90_000L) {
                fileViewModel.showPlateSelector.value &&
                    fileViewModel.threeMfInfo.value != null &&
                    fileViewModel.currentModelPath != null
            }

            val fileInfo = fileViewModel.threeMfInfo.value!!
            val fileSourceConfig = fileViewModel.sourceConfig.value
            val fileEntryNames = zipEntryNames(File(fileViewModel.currentModelPath!!))
            val fileProjectSettingsCount = zipEntryCount(
                File(fileViewModel.currentModelPath!!),
                "Metadata/project_settings.config"
            )
            val fileShowPlateSelector = fileViewModel.showPlateSelector.value

            fileViewModel.clearModel()
            uriViewModel.loadModel(Uri.fromFile(modelFile))

            waitUntil("uri import reaches prepared multi-plate state", timeoutMs = 90_000L) {
                uriViewModel.showPlateSelector.value &&
                    uriViewModel.threeMfInfo.value != null &&
                    uriViewModel.currentModelPath != null
            }

            val uriInfo = uriViewModel.threeMfInfo.value!!
            val uriSourceConfig = uriViewModel.sourceConfig.value
            val uriEntryNames = zipEntryNames(File(uriViewModel.currentModelPath!!))
            val uriProjectSettingsCount = zipEntryCount(
                File(uriViewModel.currentModelPath!!),
                "Metadata/project_settings.config"
            )
            val uriShowPlateSelector = uriViewModel.showPlateSelector.value

            assertTrue("Dragon Scale should be Bambu in direct-file path", fileInfo.isBambu)
            assertTrue("Dragon Scale should be Bambu in uri path", uriInfo.isBambu)
            assertTrue("Dragon Scale should show plate selector for direct-file path", fileShowPlateSelector)
            assertTrue("Dragon Scale should show plate selector for uri path", uriShowPlateSelector)

            assertEquals("isMultiPlate must match across import paths", fileInfo.isMultiPlate, uriInfo.isMultiPlate)
            assertEquals(
                "detectedExtruderCount must match across import paths",
                fileInfo.detectedExtruderCount,
                uriInfo.detectedExtruderCount
            )
            assertEquals("hasPaintData must match across import paths", fileInfo.hasPaintData, uriInfo.hasPaintData)
            assertEquals(
                "hasLayerToolChanges must match across import paths",
                fileInfo.hasLayerToolChanges,
                uriInfo.hasLayerToolChanges
            )
            assertEquals("detectedColors must match across import paths", fileInfo.detectedColors, uriInfo.detectedColors)
            assertEquals(
                "plate ids must match across import paths",
                fileInfo.plates.map { it.plateId },
                uriInfo.plates.map { it.plateId }
            )
            assertEquals(
                "sourceConfig must match across import paths",
                fileSourceConfig,
                uriSourceConfig
            )
            assertEquals(
                "embedded ZIP entry set must match across import paths",
                fileEntryNames,
                uriEntryNames
            )
            // B93 phase 1 (v1.6.10): for multi-plate 3MFs the initial prepare
            // pipeline intentionally skips the full-file embedProfile to avoid
            // ~20-40s of throwaway work — the user must pick a plate and
            // selectPlate() re-embeds the selected plate. Both URI and direct
            // paths must produce the same artifact shape post-v1.6.10, so
            // compare counts across paths instead of asserting a fixed count.
            // Dragon Scale is multi-plate so both artifacts point at the
            // sanitized file and have 0 project_settings.config entries.
            assertEquals(
                "embed-skip behaviour must match across import paths (B93)",
                fileProjectSettingsCount,
                uriProjectSettingsCount
            )
            assertEquals(
                "B93 phase 1: multi-plate imports skip the full-file " +
                    "embedProfile; the currentModelPath must point at the " +
                    "sanitized (pre-embed) artifact which does not contain " +
                    "Metadata/project_settings.config. If this count is 1, " +
                    "embed ran for a multi-plate file — regression.",
                0,
                fileProjectSettingsCount
            )
        } finally {
            fileViewModel.clearModel()
            uriViewModel.clearModel()
            modelFile.delete()
        }
    }

    private fun copyAssetToCache(assetName: String): File {
        val outFile = File(targetContext.cacheDir, assetName.replace("/", "_"))
        assetContext.assets.open(assetName).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
    }

    private fun zipEntryNames(file: File): Set<String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().map { it.name }.toSet()
    }

    private fun zipEntryCount(file: File, entryName: String): Int = ZipFile(file).use { zip ->
        zip.entries().asSequence().count { it.name == entryName }
    }

    /**
     * B48 regression: H2C benchy (7 model colours → 4 physical extruders) must
     * produce green in both the Prepare preview vertex buffer AND the sliced G-code.
     *
     * This test exercises the full ViewModel flow: load → auto color mapping →
     * preview mesh → recolor using the REAL colorMapping + activeExtruderColors
     * (not hardcoded test values) → verify green RGBA in vertex buffer → slice →
     * verify T1 > 0 in G-code.
     *
     * Red-green TDD: catches regressions in the mapping pipeline that data-layer
     * tests (NativePreparePreviewTest, SemmSlicingTest) would miss.
     */
    @Test
    fun h2cBenchy_fullPipeline_greenVisibleInPreview_andAllToolsInGcode() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("3DBenchy-H2C-Multi-Color.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("H2C benchy loaded with color mapping", timeoutMs = 90_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }

            val info = viewModel.threeMfInfo.value
            val mapping = viewModel.colorMapping.value
            val colors = viewModel.activeExtruderColors.value

            assertNotNull("threeMfInfo should be non-null after H2C benchy load", info)
            assertNotNull("colorMapping should be non-null after H2C benchy load", mapping)
            info!!
            mapping!!

            assertTrue("H2C benchy must have hasPaintData=true", info.hasPaintData)
            assertTrue(
                "H2C benchy must detect 7 model colours, got ${info.detectedColors.size}",
                info.detectedColors.size >= 7
            )
            assertTrue(
                "H2C benchy colorMapping must have 7 entries, got ${mapping.size}",
                mapping.size >= 7
            )
            // Slot 1 (green) must appear in the mapping
            assertTrue(
                "colorMapping must include slot 1 (green), got distinct=${mapping.distinct()}",
                mapping.contains(1)
            )
            // activeExtruderColors must include green at slot 1
            assertTrue(
                "activeExtruderColors[1] must be non-blank (green), got $colors",
                colors.size > 1 && colors[1].isNotBlank()
            )

            // Get native preview mesh and recolor using the REAL mapping + colors
            val preview = NativeLibrary().getPreparePreviewMesh()
            assertNotNull("Native preview mesh should be available for H2C benchy", preview)
            preview!!
            val mesh = preview.toMeshData()
            assertNotNull("MeshData conversion should succeed", mesh)
            mesh!!

            // Build palette using the same logic as MainActivity's InlineModelPreview
            val palette = mapping.map { slot ->
                SlicerViewModel.staticHexColorToFloatArray(colors.getOrElse(slot) { "" })
            }
            assertTrue("Palette must have 7 entries (one per model colour)", palette.size >= 7)

            mesh.recolor(palette)

            // Find green: any triangle with index 5 should have G ≈ 1.0
            val indices = mesh.extruderIndices!!
            var greenFound = false
            for (tri in indices.indices) {
                val idx = indices[tri].toInt() and 0xFF
                if (idx == 5) {
                    val gOffset = tri * 3 * com.u1.slicer.viewer.MeshData.FLOATS_PER_VERTEX + 7
                    val g = mesh.vertices.get(gOffset)
                    if (g > 0.9f) {
                        greenFound = true
                        break
                    }
                }
            }
            assertTrue(
                "H2C benchy Prepare preview must contain green (G>0.9) at index 5 triangles " +
                    "after recolor with real colorMapping=$mapping, colors=$colors",
                greenFound
            )

            // Now slice and verify T1 > 0
            viewModel.startSlicing()

            waitUntil("H2C benchy slice complete", timeoutMs = 180_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.SliceComplete
            }

            val state = viewModel.state.value as SlicerViewModel.SlicerState.SliceComplete
            val gcode = java.io.File(state.result.gcodePath).readText()
            val t1Count = gcode.lines().count { it.trim() == "T1" }
            assertTrue(
                "H2C benchy G-code must have T1 tool changes (green), got $t1Count",
                t1Count > 0
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    /**
     * B83: After selecting plate 4 of a 5-plate file, selecting plate 5 must still produce
     * the correct chip count. Bug: selectPlate() read plateObjectIds from _threeMfInfo.value
     * which is overwritten to the per-plate merged result after each selection, losing the
     * original plates list. Fix: use _fileThreeMfInfo (stable) for plate object IDs.
     *
     * Uses flippy+flappy+mini-with-plate-painted.3mf: plate 5 is SEMM-painted (hasPaintData=true,
     * 2 chips). If plateObjectIds is wrong the extracted plate 5 file omits paint data and
     * mergeThreeMfInfoForPlate collapses to 1 chip.
     */
    @Test
    fun b83_paintedFlippy_selectPlate5AfterPlate4_hasTwoChips() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("flippy+flappy+mini-with-plate-painted.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("plate selector visible") { viewModel.showPlateSelector.value }

            viewModel.selectPlate(4)
            waitUntil("plate 4 loaded", timeoutMs = 90_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }

            // Capture plate 4's info reference so we can detect when plate 5 replaces it.
            val plate4Info = viewModel.threeMfInfo.value
            viewModel.selectPlate(5)
            waitUntil("plate 5 info loaded (threeMfInfo reference changed)", timeoutMs = 90_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null &&
                    viewModel.threeMfInfo.value !== plate4Info
            }

            val info = viewModel.threeMfInfo.value!!
            assertEquals(
                "B83: plate 5 after plate 4 switch must have 2 chips (SEMM paint), got ${info.detectedColors}",
                2, info.detectedColors.size
            )
            assertTrue(
                "B83: plate 5 must have hasPaintData=true",
                info.hasPaintData
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    /**
     * F73: After loading a multi-plate 3MF and selecting an initial plate, tapping "Change plate"
     * and selecting a different plate must invalidate the Prepare preview cache so that
     * InlineModelPreview re-fetches the new plate's native mesh.
     *
     * Bug: loadNativeModel() never called invalidatePrepareMeshCache(), so cachedPrepareMesh
     * held the previous plate's mesh. InlineModelPreview's LaunchedEffect(modelRotation) has
     * an early-return guard "if (mesh != null && cachedMesh != null) return" — with the stale
     * cache, this guard fired and skipped getPreparePreviewMesh(), leaving viewerLoading=true
     * (spinner) indefinitely.
     *
     * Red: cachedPrepareMesh is non-null after plate re-selection (bug present).
     * Green: cachedPrepareMesh is null after plate re-selection (bug fixed).
     */
    @Test
    fun f73_changePlate_multiPlatePlatesAvailableAndCacheInvalidated() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("Dragon Scale infinity.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("plate selector visible") { viewModel.showPlateSelector.value }

            // multiPlatePlates must be populated before initial selection
            assertTrue(
                "F73: multiPlatePlates must be non-empty after multi-plate load",
                viewModel.multiPlatePlates.value.isNotEmpty()
            )

            // Select initial plate
            viewModel.selectPlate(1)
            val plate1Info = viewModel.threeMfInfo.value
            waitUntil("plate 1 loaded", timeoutMs = 90_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
            }

            // multiPlatePlates must survive selectPlate() — this keeps the chip visible
            assertTrue(
                "F73: multiPlatePlates must survive selectPlate() so the Change plate chip stays visible",
                viewModel.multiPlatePlates.value.isNotEmpty()
            )

            // Simulate InlineModelPreview caching the preview mesh after it loads
            val plate1Mesh = NativeLibrary().getPreparePreviewMesh()?.toMeshData()
            assertNotNull("Plate 1 preview mesh should be available for cache simulation", plate1Mesh)
            viewModel.cachedPrepareMesh = plate1Mesh

            // reopenPlateSelector must open the dialog
            viewModel.reopenPlateSelector()
            assertTrue(
                "F73: reopenPlateSelector() must set showPlateSelector=true when plates available",
                viewModel.showPlateSelector.value
            )

            // Select a different plate (simulates user picking from the reopened dialog)
            viewModel.selectPlate(3)
            waitUntil("plate 3 info loaded (threeMfInfo replaced)", timeoutMs = 90_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.threeMfInfo.value !== plate1Info
            }

            // Core assertion: cachedPrepareMesh must be null after plate re-selection.
            // If it is non-null, InlineModelPreview's LaunchedEffect(modelRotation, modelFilePath)
            // hits the B49 early-return guard and never calls getPreparePreviewMesh() for the
            // new plate — the spinner shows indefinitely (F73 regression).
            assertNull(
                "F73: cachedPrepareMesh must be null after plate re-selection so InlineModelPreview " +
                    "re-fetches the new plate's preview mesh (stale cache causes spinner-forever bug)",
                viewModel.cachedPrepareMesh
            )

            // multiPlatePlates must still be non-empty — user can change plate again
            assertTrue(
                "F73: multiPlatePlates must survive second selectPlate() for subsequent changes",
                viewModel.multiPlatePlates.value.isNotEmpty()
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    private fun waitUntil(label: String, timeoutMs: Long = 30_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    /**
     * B94: User drags a single-object model to the right side of the bed;
     * after slicing, the G-code preview shows the model back at its default
     * centre position instead of the drag destination. Reproduces on
     * `spiderman-hanging-pre-cut.3mf` per Discord user DC15.
     *
     * Test flow: load spiderman, wait for ModelLoaded, simulate a drag far
     * to the right via `applyPlacementPositions`, slice, read the G-code
     * and assert the print moves (excluding wipe tower) hit the drag X.
     *
     * Red: pre-fix the G-code shows object X extents centred around the bed
     * default regardless of the drag.
     */
    @Test
    fun spiderman_dragToRight_preservesPositionThroughSlice() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("spiderman-hanging-pre-cut.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("spiderman loaded (or error / plate selector)", timeoutMs = 120_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error ||
                    viewModel.showPlateSelector.value
            }

            // Spiderman file may or may not be multi-plate. If it is, just pick plate 1.
            if (viewModel.showPlateSelector.value) {
                viewModel.selectPlate(1)
                waitUntil("spiderman plate 1 loaded", timeoutMs = 120_000L) {
                    viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
                }
            }

            val loadState = viewModel.state.value
            if (loadState is SlicerViewModel.SlicerState.Error) {
                throw AssertionError("Spiderman load failed: ${loadState.message}")
            }
            Thread.sleep(400) // let mapping settle

            // Drag the object far to the right: put its bounding-box bottom-left
            // corner at X_DRAG. The default CopyArrangeCalculator would place the
            // same object at (bedX - sizeX) / 2, which for a ~130mm-wide object
            // lands around X=70. X_DRAG=200 is unambiguously to the right.
            val modelInfo = viewModel.modelInfo.value
            assertNotNull("lastModelInfo required for B94 test", modelInfo)
            val sizeX = modelInfo!!.sizeX
            val sizeY = modelInfo.sizeY
            val defaultX = maxOf(0f, (270f - sizeX) / 2f)
            val dragX = (270f - sizeX).coerceAtLeast(defaultX + 50f)
            val dragY = maxOf(0f, (270f - sizeY) / 2f)
            assertTrue(
                "B94: dragX=$dragX must sit to the right of the default placement X=$defaultX " +
                    "(otherwise the assertion below is meaningless)",
                dragX > defaultX + 20f
            )
            val dragPositions = floatArrayOf(dragX, dragY)
            val wipeTowerPos = Pair(
                viewModel.config.value.wipeTowerX,
                viewModel.config.value.wipeTowerY
            )
            viewModel.applyPlacementPositions(dragPositions, wipeTowerPos)

            // Slice.
            viewModel.startSlicing()
            waitUntil("spiderman slice complete", timeoutMs = 240_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.SliceComplete ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            val sliceState = viewModel.state.value
            if (sliceState is SlicerViewModel.SlicerState.Error) {
                throw AssertionError("Spiderman slice failed: ${sliceState.message}")
            }
            val result = (sliceState as SlicerViewModel.SlicerState.SliceComplete).result
            val gcode = java.io.File(result.gcodePath).readText()

            // Parse extrusion X extents, excluding the wipe tower area. OrcaSlicer
            // marks wipe-tower G-code with `; FEATURE: Prime tower` between moves.
            val moveRe = Regex("""^G1\s+(?:X([\d.]+))?.*?(?:Y([\d.]+))?.*?E""")
            var maxX = Float.NEGATIVE_INFINITY
            var currentX = 0f
            var inWipeTower = false
            for (raw in gcode.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("; FEATURE:")) {
                    inWipeTower = line.contains("Prime tower", ignoreCase = true) ||
                        line.contains("Wipe", ignoreCase = true)
                    continue
                }
                if (!line.startsWith("G1")) continue
                val m = moveRe.find(line) ?: continue
                val xStr = m.groupValues.getOrNull(1).orEmpty()
                if (xStr.isNotBlank()) {
                    currentX = xStr.toFloatOrNull() ?: currentX
                }
                if (!inWipeTower && currentX > maxX) maxX = currentX
            }

            // If the drag took effect, the object's bottom-left sits at dragX so
            // the right edge is at dragX + sizeX (close to the 270mm bed edge for
            // our chosen dragX). If the drag was ignored, the object stays at the
            // auto-placement centre so the right edge is at defaultX + sizeX.
            // Threshold sits midway between the two: fails clearly when the drag
            // is ignored, passes when it's applied.
            val noDragRightEdge = defaultX + sizeX
            val dragRightEdge = dragX + sizeX
            val midpoint = (noDragRightEdge + dragRightEdge) / 2f
            val diag = "B94 diag: sizeX=$sizeX sizeY=$sizeY defaultX=$defaultX " +
                "dragX=$dragX maxX(model)=$maxX noDragRightEdge=$noDragRightEdge " +
                "dragRightEdge=$dragRightEdge midpoint=$midpoint " +
                "wipeTowerX=${wipeTowerPos.first}"
            assertTrue(
                "B94: the sliced G-code must reflect the user's drag to X=$dragX " +
                    "(right edge ~$dragRightEdge). maxX of print moves should sit " +
                    "well above the no-drag midpoint $midpoint. Got maxX=$maxX. $diag",
                maxX >= midpoint
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    /**
     * B93 phase 1: On multi-plate Bambu 3MFs the initial `prepareImportedModelArtifacts`
     * must skip the full-file `embedProfile` call. For a 73MB / 80-component /
     * 296K paint_color file (Buzz Lightyear) the full-file embed was ~20-40s of
     * throwaway work before the user even picks a plate. After phase 1, the
     * sanitized+embedded artifact pair stored on the ViewModel (`sourceModelFile`
     * and `currentModelPath`) must point at the same underlying file — the
     * sanitized processed file — signalling that `embedProfile` was skipped.
     *
     * The timing budget is intentionally generous (180s on Pixel 8a) to avoid
     * flake; pre-fix measurements were ~100s on the same device and we expect
     * post-fix to sit under ~60s.
     */
    @Test
    fun buzzLightyear_coldLoad_skipsFullFileEmbedOnMultiPlate() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("Buzz_Multipart_3MF_Bambu.3mf")

        try {
            val started = System.currentTimeMillis()
            viewModel.loadModelFromFile(modelFile)

            waitUntil("buzz plate selector visible or error", timeoutMs = 180_000L) {
                viewModel.showPlateSelector.value ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            val elapsedMs = System.currentTimeMillis() - started
            val loadState = viewModel.state.value
            if (loadState is SlicerViewModel.SlicerState.Error) {
                throw AssertionError("Buzz 3MF load failed: ${loadState.message}")
            }

            assertTrue(
                "B93: Buzz cold load to plate selector must complete within 180s, took ${elapsedMs}ms",
                elapsedMs < 180_000L
            )

            // Structural check: after load but before plate selection, the
            // currentModelPath exposed for 3D viewer navigation should point at
            // the sanitized processed file (named `sanitized_*.3mf`), NOT the
            // full-file embedded artifact (named `embedded_sanitized_*.3mf`).
            // This confirms the full-file `embedProfile` call was skipped.
            val currentPath = viewModel.currentModelPath
            assertNotNull("currentModelPath must be set after load", currentPath)
            val name = java.io.File(currentPath!!).name
            assertFalse(
                "B93: multi-plate cold load must NOT produce an embedded full-file " +
                    "artifact. ProfileEmbedder names its output embedded_<input>.3mf; " +
                    "the sanitized pre-embed file is named sanitized_<input>.3mf. " +
                    "Got $name",
                name.startsWith("embedded_", ignoreCase = true)
            )
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    /**
     * B92: On Buzz plate 8 (object default extruder=10, paint state using
     * filament 3), Prepare and the post-slice G-code viewer disagree on which
     * tool maps to which mesh region. Prepare's compact-0 is filament-ascending
     * (painted brown) while the slicer emits T0 for the object default
     * (unpainted white). Without the B92 fix, normalizeGcodePreviewColors
     * renders T0 with Prepare-compact-0's colour (E1 red) even though T0 is the
     * unpainted region, producing Preview = painted-white + unpainted-red
     * (the opposite of Prepare).
     *
     * Assertion strategy: the set of RGBA colours that Prepare renders matches
     * the set of Preview colours returned by normalizeGcodePreviewColors for the
     * T-indices the G-code actually contains, **and** for each triangle count
     * rank the colour at that rank agrees between Prepare and Preview. This
     * catches the swap (both sides have the same colours, but the large/small
     * region assignment is inverted).
     */
    @Test
    fun buzzLightyear_plate8_prepareAndPreviewColoursAgreeByRegionSize() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val modelFile = copyAssetToCache("Buzz_Multipart_3MF_Bambu.3mf")

        try {
            viewModel.loadModelFromFile(modelFile)

            waitUntil("buzz plate selector visible or error", timeoutMs = 240_000L) {
                viewModel.showPlateSelector.value ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            val loadState = viewModel.state.value
            if (loadState is SlicerViewModel.SlicerState.Error) {
                throw AssertionError("Buzz 3MF load failed: ${loadState.message}")
            }

            viewModel.selectPlate(8)
            waitUntil("buzz plate 8 loaded", timeoutMs = 120_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                    viewModel.colorMapping.value != null
            }
            Thread.sleep(600) // let refreshMappedPreviewColors / slicerColorOrder settle

            val colorMapping = viewModel.colorMapping.value!!
            val extruderColors = viewModel.activeExtruderColors.value.toList()
            val slicerColorOrder = viewModel.slicerColorOrder.value
            val semmColorPermutation = viewModel.semmColorPermutationFlow.value

            // ---- Prepare: recolour the mesh and tally per-colour triangle counts ----
            val preview = NativeLibrary().getPreparePreviewMesh()
            assertNotNull("plate 8 preview mesh required", preview)
            val mesh = preview!!.toMeshData()!!
            val palette = colorMapping.map { slot ->
                SlicerViewModel.staticHexColorToFloatArray(extruderColors.getOrElse(slot) { "" })
            }
            mesh.recolor(palette)
            val prepareCounts = mutableMapOf<Int, Int>()
            val buf = mesh.vertices
            val triCount = mesh.vertexCount / 3
            for (tri in 0 until triCount) {
                val base = tri * 3 * com.u1.slicer.viewer.MeshData.FLOATS_PER_VERTEX
                val r = (buf.get(base + 6) * 255).toInt().coerceIn(0, 255)
                val g = (buf.get(base + 7) * 255).toInt().coerceIn(0, 255)
                val b = (buf.get(base + 8) * 255).toInt().coerceIn(0, 255)
                val rgb = (r shl 16) or (g shl 8) or b
                prepareCounts[rgb] = (prepareCounts[rgb] ?: 0) + 1
            }
            assertTrue(
                "Buzz plate 8 Prepare mesh must have at least 2 distinct RGBA colours (painted + unpainted)",
                prepareCounts.size >= 2
            )

            // ---- Slice and read T-counts from the G-code ----
            viewModel.startSlicing()
            waitUntil("buzz plate 8 slice complete", timeoutMs = 300_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.SliceComplete ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            val sliceState = viewModel.state.value
            if (sliceState is SlicerViewModel.SlicerState.Error) {
                throw AssertionError("Buzz plate 8 slice failed: ${sliceState.message}")
            }
            val result = (sliceState as SlicerViewModel.SlicerState.SliceComplete).result
            val gcode = java.io.File(result.gcodePath).readText()
            val lines = gcode.lines()
            val toolCounts = (0..3).associateWith { t -> lines.count { it.trim() == "T$t" } }
                .filter { it.value > 0 }
            assertTrue(
                "Buzz plate 8 must have at least 2 distinct tools in G-code, got $toolCounts",
                toolCounts.size >= 2
            )

            // ---- Preview: colours via the now-wired normalizeGcodePreviewColors ----
            val previewColorsForTools = normalizeGcodePreviewColors(
                extruderColors = extruderColors,
                colorMapping = colorMapping,
                semmColorPermutation = semmColorPermutation,
                slicerColorOrder = slicerColorOrder
            )

            fun hexToRgb(hex: String): Int? {
                if (hex.isBlank()) return null
                val h = if (hex.startsWith("#")) hex.substring(1) else hex
                if (h.length != 6) return null
                return try {
                    h.toInt(16)
                } catch (e: Exception) {
                    null
                }
            }

            val diag = StringBuilder().apply {
                appendLine("B92 diag:")
                appendLine("  colorMapping=$colorMapping")
                appendLine("  extruderColors=$extruderColors")
                appendLine("  slicerColorOrder=$slicerColorOrder")
                appendLine("  semmColorPermutation=$semmColorPermutation")
                appendLine("  prepareCounts=${prepareCounts.mapKeys { (k, _) -> String.format("#%06X", k) }}")
                appendLine("  toolCounts=$toolCounts")
                appendLine("  previewColorsForTools=${previewColorsForTools.mapIndexed { i, c -> "T$i=$c" }}")
            }.toString()

            // Set equality: Prepare's distinct colours must match Preview's distinct
            // colours for the T-indices actually present in the G-code.
            val prepareSet = prepareCounts.keys.toSet()
            val previewSet = toolCounts.keys.mapNotNull { t -> hexToRgb(previewColorsForTools.getOrNull(t).orEmpty()) }.toSet()
            assertEquals(
                "Preview palette (for present tools) must contain the same RGBA set as Prepare\n$diag",
                prepareSet, previewSet
            )

            // B92 core assertion: slicerColorOrder must be non-null for the Buzz
            // plate 8 shape (object default extruder=10 with higher source filament
            // than the paint state 3). Without it, the G-code viewer would render
            // T0 with detectedColors[0]'s assigned colour (E1 red = painted's slot)
            // instead of detectedColors[1]'s assigned colour (E4 white = unpainted's
            // slot), producing the reported Prepare/Preview swap.
            assertNotNull(
                "B92: slicerColorOrder must be populated for plate 8 (object default " +
                    "has higher source filament than the paint state)\n$diag",
                slicerColorOrder
            )
            assertNotNull(
                "B92: semmColorPermutation must be populated when the user's mapping " +
                    "is non-identity\n$diag",
                semmColorPermutation
            )
            val slicerOrder = slicerColorOrder!!
            val semmPerm = semmColorPermutation!!

            // The slicer-order mapping must differ from identity [0,1,…] because
            // detectedColors[1] (unpainted white) is the object default and sits
            // after detectedColors[0] (painted brown) in filament-ascending order,
            // yet leads in OrcaSlicer's tool ordering.
            val identityOrder = (0 until slicerOrder.size).toList()
            assertNotEquals(
                "B92: slicerColorOrder must be non-identity for Buzz plate 8 " +
                    "(object default sits at detectedColors[1])\n$diag",
                identityOrder, slicerOrder
            )

            // For each present physical T-index, the Preview palette must match the
            // user's intent for the detected colour that slicer T<k>=detected[slicerOrder[k]]
            // → physical T<semmPerm[k]>. Any divergence means the viewer renders
            // at least one tool with the wrong colour.
            for (slicerCompactIdx in semmPerm.indices) {
                val physicalSlot = semmPerm[slicerCompactIdx]
                if (physicalSlot !in toolCounts) continue
                val detectedIdx = slicerOrder.getOrElse(slicerCompactIdx) { slicerCompactIdx }
                val userSlot = colorMapping.getOrNull(detectedIdx) ?: continue
                val expected = extruderColors.getOrNull(userSlot).orEmpty()
                if (expected.isBlank()) continue
                val actual = previewColorsForTools.getOrNull(physicalSlot).orEmpty()
                assertEquals(
                    "B92: T$physicalSlot (slicer T$slicerCompactIdx → detectedColors[$detectedIdx] " +
                        "→ user slot $userSlot) must render in $expected\n$diag",
                    expected.uppercase(), actual.uppercase()
                )
            }
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }
}
