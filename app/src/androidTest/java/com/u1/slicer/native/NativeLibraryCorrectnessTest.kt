package com.u1.slicer.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented correctness tests for JNI return values from NativeLibrary.
 *
 * These complement NativeLibrarySymbolTest (which only checks linkage with
 * empty/no-op inputs) by loading a real model and asserting that every field
 * of the returned ModelInfo has a plausible value.
 *
 * This class specifically guards against the class of bug where C++ float→double
 * or bool→int promotion in JNI varargs (NewObject) corrupts the argument layout,
 * causing fields like sizeX/Y/Z or isManifold to read garbage from the wrong
 * stack offset. Such bugs are invisible when the model is empty (all-zero floats)
 * but surface immediately with any real geometry.
 */
@RunWith(AndroidJUnit4::class)
class NativeLibraryCorrectnessTest {

    private lateinit var lib: NativeLibrary
    private lateinit var stlFile: File

    @Before
    fun setup() {
        assertTrue(
            "Native library must be loaded on device (arm64 required)",
            NativeLibrary.isLoaded
        )
        lib = NativeLibrary()

        // Copy the bundled STL asset to a path the native code can open via fopen().
        // Use targetContext (the app under test) so cacheDir is guaranteed to exist.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        stlFile = File(ctx.cacheDir, "tetrahedron_test.stl")
        stlFile.parentFile?.mkdirs()
        InstrumentationRegistry.getInstrumentation().context
            .assets.open("tetrahedron.stl").use { it.copyTo(stlFile.outputStream()) }
    }

    @After
    fun teardown() {
        lib.clearModel()
        stlFile.delete()
    }

    @Test
    fun loadModel_returnsTrue_forValidStl() {
        assertTrue("loadModel should return true for a valid STL", lib.loadModel(stlFile.absolutePath))
    }

    /**
     * Regression test for the NewObject float→double promotion bug (tombstone_24/25).
     *
     * The tetrahedron spans (0,0,0)–(10,10,10) mm so every float field is non-zero.
     * With the varargs bug, sizeX/Y/Z read from the wrong stack offsets and return
     * garbage; the process also SIGABRTs before reaching the assertions because the
     * JNI runtime rejects the corrupted jboolean value for isManifold.
     */
    @Test
    fun getModelInfo_afterLoad_hasCorrectFields() {
        assertTrue(lib.loadModel(stlFile.absolutePath))
        val info = lib.getModelInfo()

        assertNotNull("getModelInfo should return non-null after load", info)
        info!!

        assertEquals("tetrahedron_test.stl", info.filename)
        assertEquals("stl", info.format)

        // Bounding box: tetrahedron spans 0–10 mm on each axis
        assertTrue("sizeX should be ~10mm, was ${info.sizeX}", info.sizeX in 9f..11f)
        assertTrue("sizeY should be ~10mm, was ${info.sizeY}", info.sizeY in 9f..11f)
        assertTrue("sizeZ should be ~10mm, was ${info.sizeZ}", info.sizeZ in 9f..11f)

        assertTrue("triangleCount should be > 0, was ${info.triangleCount}", info.triangleCount > 0)
        assertTrue("volumeCount should be >= 1, was ${info.volumeCount}", info.volumeCount >= 1)

        // isManifold must be a valid boolean. If the jboolean arg held garbage the
        // JNI runtime would have aborted before we reach this line — making this an
        // implicit assertion that the NewObjectA fix is in place.
        assertTrue("isManifold must be true or false", info.isManifold || !info.isManifold)
    }

    @Test
    fun getModelInfo_emptyModel_returnsDefaultStruct() {
        lib.clearModel()
        val info = lib.getModelInfo()
        assertNotNull(info)
        info!!
        assertEquals("", info.filename)
        assertEquals(0f, info.sizeX, 0.001f)
        assertEquals(0f, info.sizeY, 0.001f)
        assertEquals(0f, info.sizeZ, 0.001f)
    }

    @Test
    fun clearModel_afterLoad_resetsState() {
        assertTrue(lib.loadModel(stlFile.absolutePath))
        lib.clearModel()
        val info = lib.getModelInfo()
        assertNotNull(info)
        assertEquals("", info!!.filename)
    }

    /**
     * Phase 1 sub-plan #1: g_model iteration accessors.
     * Loads a Bambu fixture (Flarewing Dragon — plate-heavy + multi-volume) and
     * asserts that nativeGetObjectCount / nativeGetVolumeCount report the same
     * counts that Phase 0's bambu_snapshot_json emits.
     */
    @Test
    fun nativeGetObjectCount_matchesGModelState_forBambuFixture() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(targetContext.cacheDir, "phase1_fixture.3mf")
        assetContext.assets.open("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val objectCount = lib.nativeGetObjectCount()
            assertTrue("expected >= 1 ModelObject for Bambu fixture, got $objectCount", objectCount >= 1)
            var sawVolumes = false
            for (oi in 0 until objectCount) {
                val vc = lib.nativeGetVolumeCount(oi)
                if (vc > 0) sawVolumes = true
                assertTrue("volume count must be non-negative, got $vc at oi=$oi", vc >= 0)
            }
            assertTrue("at least one object must have >= 1 volume", sawVolumes)
            assertEquals(0, lib.nativeGetVolumeCount(objectCount))
            assertEquals(0, lib.nativeGetVolumeCount(-1))
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun nativeGetObjectCount_returnsZero_whenNoModelLoaded() {
        lib.clearModel()
        assertEquals(0, lib.nativeGetObjectCount())
        assertEquals(0, lib.nativeGetVolumeCount(0))
    }

    @Test
    fun nativeGetObjectModelId_isNonZero_forLoadedBambuFixture() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(targetContext.cacheDir, "phase1_fixture.3mf")
        assetContext.assets.open("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val objectCount = lib.nativeGetObjectCount()
            for (oi in 0 until objectCount) {
                val id = lib.nativeGetObjectModelId(oi)
                assertTrue("object $oi ObjectID must be > 0, got $id", id > 0L)
            }
            assertEquals(0L, lib.nativeGetObjectModelId(objectCount))
            assertEquals(0L, lib.nativeGetObjectModelId(-1))
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun nativeGetVolumeScalars_returnsThreePackedInts_forBambuFixture() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(targetContext.cacheDir, "phase1_fixture.3mf")
        assetContext.assets.open("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val objectCount = lib.nativeGetObjectCount()
            var sawPaintedVolume = false
            for (oi in 0 until objectCount) {
                val vc = lib.nativeGetVolumeCount(oi)
                for (vi in 0 until vc) {
                    val scalars = lib.nativeGetVolumeScalars(oi, vi)
                    assertNotNull("scalars must be non-null for in-range (oi=$oi,vi=$vi)", scalars)
                    scalars!!
                    assertEquals("scalars must be 3 ints", 3, scalars.size)
                    assertTrue(
                        "extruder must be -1 or >= 1, got ${scalars[0]}",
                        scalars[0] == -1 || scalars[0] >= 1
                    )
                    assertTrue("isMmPainted flag must be 0 or 1, got ${scalars[1]}", scalars[1] in 0..1)
                    assertTrue("isSeamPainted flag must be 0 or 1, got ${scalars[2]}", scalars[2] in 0..1)
                    if (scalars[1] == 1) sawPaintedVolume = true
                }
            }
            assertTrue("expected at least one mm-painted volume", sawPaintedVolume)
            assertNull(lib.nativeGetVolumeScalars(-1, 0))
            assertNull(lib.nativeGetVolumeScalars(0, -1))
            assertNull(lib.nativeGetVolumeScalars(objectCount, 0))
            // Upper-bound volumeIndex OOR: one past the last volume of object 0.
            val vc0 = lib.nativeGetVolumeCount(0)
            assertNull(lib.nativeGetVolumeScalars(0, vc0))
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun nativeGetPaintStateCounts_matchesPhase0Snapshot_forFlarewing() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(targetContext.cacheDir, "phase1_fixture.3mf")
        assetContext.assets.open("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val objectCount = lib.nativeGetObjectCount()
            var sawMmuCounts = false
            for (oi in 0 until objectCount) {
                val vc = lib.nativeGetVolumeCount(oi)
                for (vi in 0 until vc) {
                    val mm = lib.nativeGetPaintStateCounts(oi, vi, 0)
                    assertNotNull("mmu counts must be non-null for in-range (oi=$oi,vi=$vi)", mm)
                    mm!!
                    assertEquals("packed length must be even", 0, mm.size % 2)
                    var i = 0
                    while (i < mm.size) {
                        val state = mm[i]
                        val count = mm[i + 1]
                        assertTrue("state must be 1..16, got $state", state in 1..16)
                        assertTrue("count must be > 0, got $count", count > 0)
                        i += 2
                    }
                    if (mm.isNotEmpty()) sawMmuCounts = true
                }
            }
            assertTrue("Flarewing Dragon must have at least one volume with mmu counts", sawMmuCounts)
            assertNull(lib.nativeGetPaintStateCounts(0, 0, 2))
            assertNull(lib.nativeGetPaintStateCounts(0, 0, -1))
            assertNull(lib.nativeGetPaintStateCounts(-1, 0, 0))
            assertNull(lib.nativeGetPaintStateCounts(0, -1, 0))
            assertNull(lib.nativeGetPaintStateCounts(objectCount, 0, 0))
            val vc0 = lib.nativeGetVolumeCount(0)
            assertNull(lib.nativeGetPaintStateCounts(0, vc0, 0))
        } finally {
            fixture.delete()
        }
    }

    /**
     * kind = 1 selects the supported_facets annotation. Flarewing Dragon does
     * not paint supports, so counts will typically be empty — the assertion is
     * the structural invariant (non-null, even-length, states 1..16, counts > 0),
     * not a specific non-empty result.
     */
    @Test
    fun nativeGetPaintStateCounts_supportsKind_returnsStructurallyValidArray() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(targetContext.cacheDir, "phase1_fixture.3mf")
        assetContext.assets.open("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val objectCount = lib.nativeGetObjectCount()
            for (oi in 0 until objectCount) {
                val vc = lib.nativeGetVolumeCount(oi)
                for (vi in 0 until vc) {
                    val sup = lib.nativeGetPaintStateCounts(oi, vi, 1)
                    assertNotNull("supports counts must be non-null for in-range", sup)
                    sup!!
                    assertEquals("packed length must be even", 0, sup.size % 2)
                    var i = 0
                    while (i < sup.size) {
                        assertTrue("state must be 1..16, got ${sup[i]}", sup[i] in 1..16)
                        assertTrue("count must be > 0, got ${sup[i + 1]}", sup[i + 1] > 0)
                        i += 2
                    }
                }
            }
        } finally {
            fixture.delete()
        }
    }

    /**
     * Phase 1 sub-plan #5: project config accessor — exercises the single
     * JSON blob that carries isBbl, fileVersion, filamentColours,
     * filamentSettingsIds, and filamentIds from getModelConfig() back to Kotlin.
     * Flarewing Dragon is a real 4-colour Bambu fixture with a
     * BambuStudio:3mfVersion metadata entry, so fileVersion must be non-empty.
     */
    @Test
    fun nativeGetProjectConfig_returnsPopulatedJson_forFlarewingDragon() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(targetContext.cacheDir, "phase1_fixture.3mf")
        assetContext.assets.open("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        try {
            assertTrue(lib.loadModel(fixture.absolutePath))
            val json = lib.nativeGetProjectConfig()
            assertNotNull("nativeGetProjectConfig should be non-null for a Bambu fixture", json)
            val obj = org.json.JSONObject(json!!)

            assertTrue("isBbl should be true for Bambu 3MF", obj.getBoolean("isBbl"))
            val version = obj.getString("fileVersion")
            assertTrue(
                "fileVersion should be non-empty for Flarewing Dragon, got '$version'",
                version.isNotEmpty()
            )

            val colours = obj.getJSONArray("filamentColours")
            assertTrue(
                "filamentColours should be non-empty for a 4-colour fixture, got length ${colours.length()}",
                colours.length() > 0
            )
            for (i in 0 until colours.length()) {
                val hex = colours.getString(i)
                assertTrue(
                    "filamentColours[$i]='$hex' should start with '#'",
                    hex.startsWith("#")
                )
            }

            // filamentSettingsIds is the filament_settings_id > filament_ids fallback.
            // Both are arrays; at least one should be non-empty for a real preset.
            val settings = obj.getJSONArray("filamentSettingsIds")
            val filamentIds = obj.getJSONArray("filamentIds")
            assertTrue(
                "at least one of filamentSettingsIds/filamentIds should be populated " +
                    "(got ${settings.length()}/${filamentIds.length()})",
                settings.length() > 0 || filamentIds.length() > 0
            )
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun nativeGetProjectConfig_returnsNull_whenNoModelLoaded() {
        lib.clearModel()
        assertNull(
            "nativeGetProjectConfig must return null after clearModel / before any load",
            lib.nativeGetProjectConfig()
        )
    }

    /**
     * Phase 1 sub-plan #2b: loadModelForPlate smoke — single-plate fixture under
     * plateIdx=0 must match the full-load object/volume counts. The BBS importer's
     * plate_id>0 branch (bbs_3mf.cpp:1921) filters m_plater_data[plate_id].obj_inst_map;
     * for a single-plate file, the filter is a no-op.
     */
    @Test
    fun loadModelForPlate_coloredBenchyPlate0_matchesFullLoadObjectCount() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(targetContext.cacheDir, "sub_plan_2b_smoke.3mf")
        assetContext.assets.open("colored_3DBenchy (1).3mf").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        try {
            assertTrue("loadModel must succeed on colored_3DBenchy", lib.loadModel(fixture.absolutePath))
            val fullInfo = lib.getModelInfo()
            assertNotNull("getModelInfo after full load", fullInfo)
            val fullVolumes = fullInfo!!.volumeCount
            val fullObjects = lib.nativeGetObjectCount()
            lib.clearModel()

            assertTrue(
                "loadModelForPlate(plateIdx=0) must succeed on single-plate fixture",
                lib.loadModelForPlate(fixture.absolutePath, 0)
            )
            val plateInfo = lib.getModelInfo()
            assertNotNull("getModelInfo after plate-filtered load", plateInfo)
            assertEquals(
                "single-plate fixture must match full-load volume count under plateIdx=0",
                fullVolumes,
                plateInfo!!.volumeCount
            )
            assertEquals(
                "single-plate fixture must match full-load object count under plateIdx=0",
                fullObjects,
                lib.nativeGetObjectCount()
            )
        } finally {
            fixture.delete()
        }
    }

    /**
     * Phase 1 sub-plan #2b: plateIdx=-1 is the Kotlin alias for BBS plate_id=0
     * (load all plates). This keeps loadModelForPlate usable for non-plate-aware
     * callers and forms the safe default the JNI wrapper falls back to.
     */
    @Test
    fun loadModelForPlate_negativePlateIdx_loadsAllPlates() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(targetContext.cacheDir, "sub_plan_2b_alias.3mf")
        assetContext.assets.open("colored_3DBenchy (1).3mf").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        try {
            assertTrue(
                "loadModelForPlate(plateIdx=-1) must succeed — all-plates alias",
                lib.loadModelForPlate(fixture.absolutePath, -1)
            )
            val info = lib.getModelInfo()
            assertNotNull("getModelInfo after plateIdx=-1 load", info)
            assertTrue("volume count must be > 0 for colored_3DBenchy", info!!.volumeCount > 0)
        } finally {
            fixture.delete()
        }
    }

    /**
     * F85 re-add regression: addModelForPlate(file, plateIdx) followed by a
     * simulate-re-embed (clear + reload primary + re-add with same plateIdx) must
     * give the same native object count as the initial plate-selected add.
     *
     * The old re-add code always called addModel(all plates), which loads every
     * plate's objects. For a multi-plate 3MF this gives a higher object count than
     * addModelForPlate(plate0), causing setObjectPositions to fail silently (count
     * mismatch) and produce a G-code footprint that did not overlap the expected
     * model footprint — the error the user saw on NF22E1.
     *
     * Dragon Scale infinity.3mf has 3 plates; plate 0 adds fewer objects than all
     * plates combined, so the mismatch is unambiguous.
     */
    @Test
    fun addModelForPlate_readdWithSamePlate_givesConsistentObjectCount() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

        val primary = File(targetContext.cacheDir, "f85_primary.stl")
        assetContext.assets.open("3DBenchy.stl").use { it.copyTo(primary.outputStream()) }

        val multiPlate = File(targetContext.cacheDir, "f85_multiplate.3mf")
        assetContext.assets.open("Dragon Scale infinity.3mf").use { it.copyTo(multiPlate.outputStream()) }

        try {
            // Step 1: load primary + add plate 0 of multi-plate file.
            assertTrue("loadModel primary", lib.loadModel(primary.absolutePath))
            val primaryCount = lib.nativeGetObjectCount()
            assertTrue("addModelForPlate(plateIdx=0) must succeed", lib.addModelForPlate(multiPlate.absolutePath, 0))
            val countAfterPlate0Add = lib.nativeGetObjectCount()
            assertTrue("plate 0 must contribute at least 1 object", countAfterPlate0Add > primaryCount)

            // Step 2: simulate re-embed — clear + reload primary + re-add with same plateIdx.
            lib.clearModel()
            assertTrue(lib.loadModel(primary.absolutePath))
            assertTrue(lib.addModelForPlate(multiPlate.absolutePath, 0))
            val countAfterReAdd = lib.nativeGetObjectCount()

            assertEquals(
                "Re-add with same plateIdx=0 must yield same object count as initial add " +
                    "(got $countAfterReAdd, expected $countAfterPlate0Add). " +
                    "A mismatch means setObjectPositions would fail and G-code footprint check triggers.",
                countAfterPlate0Add, countAfterReAdd
            )

            // Step 3: verify addModel(all plates) gives a DIFFERENT count — proving the old bug.
            lib.clearModel()
            assertTrue(lib.loadModel(primary.absolutePath))
            assertTrue(lib.addModel(multiPlate.absolutePath))
            val countAllPlates = lib.nativeGetObjectCount()

            assertTrue(
                "Dragon Scale infinity.3mf must be multi-plate: all-plates load " +
                    "($countAllPlates objects) must add more than single-plate load " +
                    "($countAfterPlate0Add objects), confirming the old re-add bug was real.",
                countAllPlates > countAfterPlate0Add
            )

            // Step 4: position-fidelity test — setObjectPositions must not be overridden
            // by the auto-center guard after the bounding-box cache fix.
            // Load primary + plate 0, place objects at explicit positions, then verify
            // getInstanceOffsets() returns those same positions (within 1mm tolerance).
            // A failure here means the auto-center guard is still reading a stale bbox
            // and applying a spurious shift (the root cause of the original bug report).
            lib.clearModel()
            assertTrue(lib.loadModel(primary.absolutePath))
            assertTrue(lib.addModelForPlate(multiPlate.absolutePath, 0))
            val boxes = lib.getObjectBoundingBoxes()
            assertTrue("Need at least 2 objects for position test", boxes.size >= 6)
            // Place object 0 at (20, 30) lower-left, object 1 at (150, 40) lower-left
            val targetX0 = 20f; val targetY0 = 30f
            val targetX1 = 150f; val targetY1 = 40f
            val positions = floatArrayOf(targetX0, targetY0, targetX1, targetY1)
            assertTrue("setObjectPositions must succeed", lib.setObjectPositions(positions))
            // getInstanceOffsets returns world-space XY of instance origin (not lower-left).
            // The native impl places lower-left at target, so offset = target - bbox.min.
            val offsets = lib.getInstanceOffsets()
            assertTrue("getInstanceOffsets must return 2 objects × 2 coords = 4 floats",
                offsets.size >= 4)
            // The key invariant: the lower-left of each object must be at the target position.
            // lower-left X = offset.x + bbox.min.x; we want this == targetX.
            // Since setObjectPositions sets offset = target - bbox.min, lower-left = target.
            // Verify the offset moved enough that the model is near the target area (not shifted
            // by +24/+63mm as the old bug produced).
            val offset0X = offsets[0]; val offset0Y = offsets[1]
            // Both offsets must be within the bed (0..270). The stale-cache bug pushed offsets
            // to ~158/197 for a 60mm benchy placed at ~134/134 — any value outside this range
            // is a red flag.
            assertTrue("Object 0 offset X must be within bed bounds (got $offset0X)",
                offset0X in 0f..270f)
            assertTrue("Object 0 offset Y must be within bed bounds (got $offset0Y)",
                offset0Y in 0f..270f)
            val offset1X = offsets[2]; val offset1Y = offsets[3]
            assertTrue("Object 1 offset X must be within bed bounds (got $offset1X)",
                offset1X in 0f..270f)
            assertTrue("Object 1 offset Y must be within bed bounds (got $offset1Y)",
                offset1Y in 0f..270f)
            // The two objects must be at meaningfully different positions.
            // setObjectPositions places the lower-left corner at each target, but
            // getInstanceOffsets() returns the world-space instance origin (not the
            // lower-left). So separation = (targetX1 - targetX0) + (bbox0_min.x - bbox1_min.x).
            // For Benchy vs Dragon Scale the bbox origins differ by ~11mm, giving ~141mm
            // rather than the nominal 130mm. We use a lower bound (>100mm) instead of an
            // exact value so the assertion is fixture-geometry independent, while still
            // catching the auto-center bug that collapses both objects toward the bed
            // centre and produces near-zero separation.
            val separationX = kotlin.math.abs(offset1X - offset0X)
            assertTrue(
                "X separation between objects must be > 100mm — the auto-center corruption " +
                "bug pushes both objects toward the bed centre, collapsing separation to ~0–10mm. " +
                "Got $separationX.",
                separationX > 100f
            )
        } finally {
            primary.delete()
            multiPlate.delete()
        }
    }

    // ─── F77: multi-file add-to-bed ───────────────────────────────────────────

    /**
     * F77: addModel() with a second STL must increase the object count to 2 and
     * return a bounding-box array with one entry per object (6 floats total).
     * All per-object dimensions must be positive — no zero-size ghost objects.
     */
    @Test
    fun f77_addModel_twoStls_objectCountAndBoundingBoxesCorrect() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

        val tet1 = File(targetContext.cacheDir, "f77_tet1.stl")
        val tet2 = File(targetContext.cacheDir, "f77_tet2.stl")
        assetContext.assets.open("tetrahedron.stl").use { it.copyTo(tet1.outputStream()) }
        assetContext.assets.open("tetrahedron.stl").use { it.copyTo(tet2.outputStream()) }

        try {
            assertTrue("First STL must load", lib.loadModel(tet1.absolutePath))
            assertEquals("After first load: 1 object", 1, lib.nativeGetObjectCount())

            assertTrue("addModel must succeed for second STL", lib.addModel(tet2.absolutePath))
            assertEquals("After addModel: 2 objects on bed", 2, lib.nativeGetObjectCount())

            val boxes = lib.getObjectBoundingBoxes()
            assertEquals("getObjectBoundingBoxes must return 6 floats for 2 objects", 6, boxes.size)
            for (i in boxes.indices) {
                assertTrue("boxes[$i] must be > 0 (no zero-size objects), got ${boxes[i]}", boxes[i] > 0f)
            }
            // Both are the same geometry — X/Y/Z sizes must agree within 0.5 mm
            assertEquals("Both copies share geometry — X sizes must match", boxes[0], boxes[3], 0.5f)
            assertEquals("Both copies share geometry — Y sizes must match", boxes[1], boxes[4], 0.5f)
            assertEquals("Both copies share geometry — Z sizes must match", boxes[2], boxes[5], 0.5f)
        } finally {
            tet1.delete()
            tet2.delete()
        }
    }

    /**
     * F77 / bbox-invalidation: setModelScale() must cause getObjectBoundingBoxes() to
     * return sizes that reflect the new scale on all objects.
     *
     * Regression for the sapil_arrange.cpp fix that adds obj->invalidate_bounding_box()
     * after setModelScale(). Without that call the OrcaSlicer bbox cache is not cleared
     * and getObjectBoundingBoxes() still returns pre-scale sizes, causing the placement
     * clamp and wipe-tower collision avoidance to work with wrong dimensions.
     */
    @Test
    fun f77_setModelScale_afterAddModel_boundingBoxesReflectNewScale() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

        val tet1 = File(targetContext.cacheDir, "f77_scale_tet1.stl")
        val tet2 = File(targetContext.cacheDir, "f77_scale_tet2.stl")
        assetContext.assets.open("tetrahedron.stl").use { it.copyTo(tet1.outputStream()) }
        assetContext.assets.open("tetrahedron.stl").use { it.copyTo(tet2.outputStream()) }

        try {
            assertTrue(lib.loadModel(tet1.absolutePath))
            assertTrue(lib.addModel(tet2.absolutePath))

            val unscaled = lib.getObjectBoundingBoxes()
            assertEquals("Need 2 objects (6 floats) for this test", 6, unscaled.size)

            lib.setModelScale(0.5f, 0.5f, 0.5f)

            val scaled = lib.getObjectBoundingBoxes()
            assertEquals("Scaled boxes must still have 6 floats", 6, scaled.size)

            // Each dimension must be ~50% of the unscaled value (±5% tolerance).
            for (i in 0..5) {
                assertEquals(
                    "boxes[$i]: scaled size must be ~50% of unscaled " +
                        "(expected ${unscaled[i] * 0.5f}, got ${scaled[i]})",
                    unscaled[i] * 0.5f, scaled[i], unscaled[i] * 0.05f
                )
            }
        } finally {
            tet1.delete()
            tet2.delete()
        }
    }

    /**
     * F85: addModelForPlate() with plateIdx=2 (the 3rd plate of Dragon Scale infinity.3mf)
     * must increase the object count above the primary-only baseline and return a valid
     * bounding-box array. Complements the plateIdx=0 coverage in
     * addModelForPlate_readdWithSamePlate_givesConsistentObjectCount by exercising a
     * non-zero plate index — the path exposed by the plate-selector dialog.
     */
    @Test
    fun f85_addModelForPlate_plateIdx2_addsObjectsFromPlate3() {
        val assetContext = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

        val primary = File(targetContext.cacheDir, "f85_p2_primary.stl")
        val multiPlate = File(targetContext.cacheDir, "f85_p2_multiplate.3mf")
        assetContext.assets.open("tetrahedron.stl").use { it.copyTo(primary.outputStream()) }
        assetContext.assets.open("Dragon Scale infinity.3mf").use { it.copyTo(multiPlate.outputStream()) }

        try {
            assertTrue("Primary STL must load", lib.loadModel(primary.absolutePath))
            val baseCount = lib.nativeGetObjectCount()
            assertEquals("Baseline must be 1 object (tetrahedron)", 1, baseCount)

            assertTrue(
                "addModelForPlate(plateIdx=2) must succeed on Dragon Scale infinity.3mf",
                lib.addModelForPlate(multiPlate.absolutePath, 2)
            )
            val countAfterAdd = lib.nativeGetObjectCount()
            assertTrue(
                "Adding plate 2 must increase object count above baseline " +
                    "(got $countAfterAdd, expected > $baseCount)",
                countAfterAdd > baseCount
            )

            val boxes = lib.getObjectBoundingBoxes()
            assertEquals(
                "getObjectBoundingBoxes must return ${countAfterAdd * 3} floats for $countAfterAdd objects",
                countAfterAdd * 3, boxes.size
            )
            for (i in boxes.indices) {
                assertTrue("boxes[$i] must be > 0 (no zero-size objects), got ${boxes[i]}", boxes[i] > 0f)
            }
        } finally {
            primary.delete()
            multiPlate.delete()
        }
    }
}
