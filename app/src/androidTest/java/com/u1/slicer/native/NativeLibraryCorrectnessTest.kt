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
        } finally {
            primary.delete()
            multiPlate.delete()
        }
    }
}
