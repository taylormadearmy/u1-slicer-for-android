package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.bambu.bambuCanonicalList
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.applyOverridesToCanonical
import com.u1.slicer.data.resolvePerFilamentTypeAndTemp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 2 end-to-end alignment tests — the cascade detector at the
 * G-code header level.
 *
 * The unit tests in `PerFilamentResolverTest` cover the resolution logic
 * in isolation. These tests prove that the resolved per-filament arrays
 * survive the embed → slice → G-code header round-trip without truncation
 * or cascade.
 *
 * The contract being defended:
 *
 *   For a multi-filament file (H2C benchy: 7 file filaments) with a user
 *   override at fileIndex N, the sliced G-code's `; filament_type = ...`
 *   header line must contain the override material at position N AND ONLY
 *   at position N.
 *
 * See `docs/superpowers/reviews/2026-04-26-phase2-architecture-review.md`
 * §4 Step 1 for the test plan.
 */
@RunWith(AndroidJUnit4::class)
class Phase2AlignmentTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File
    private lateinit var outDir: File
    private lateinit var embedder: ProfileEmbedder

    @Before
    fun setup() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = ctx.cacheDir
        outDir = File(cacheDir, "phase2_alignment_out").also { it.mkdirs() }
        embedder = ProfileEmbedder(ctx)
    }

    @After
    fun teardown() {
        lib.clearModel()
        outDir.deleteRecursively()
    }

    private fun asset(name: String): File {
        val file = File(cacheDir, name.replace("/", "_"))
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { it.copyTo(file.outputStream()) }
        return file
    }

    private fun makeConfig(extruderCount: Int) = SliceConfig(
        layerHeight = 0.2f,
        firstLayerHeight = 0.2f,
        perimeters = 2,
        topSolidLayers = 5,
        bottomSolidLayers = 4,
        fillDensity = 0.15f,
        fillPattern = "gyroid",
        printSpeed = 150f,
        travelSpeed = 200f,
        firstLayerSpeed = 50f,
        nozzleTemp = 220,
        bedTemp = 65,
        nozzleDiameter = 0.4f,
        filamentDiameter = 1.75f,
        retractLength = 0.8f,
        retractSpeed = 45f,
        extruderCount = extruderCount,
        extruderTemps = IntArray(extruderCount) { 220 },
        wipeTowerEnabled = true,
        wipeTowerX = 170f,
        wipeTowerY = 140f,
        wipeTowerWidth = 60f,
    )

    private fun fourPLAPresets(): List<ExtruderPreset> = (0..3).map { i ->
        ExtruderPreset(index = i, color = "#FF0000", materialType = "PLA")
    }

    /**
     * Reads the `; filament_type = ...` header line from the G-code and
     * splits it into a list of materials. The header is written by the
     * slicer once, near the top of the file. Returns `null` if absent.
     */
    private fun readFilamentTypeHeader(gcode: String): List<String>? {
        val line = gcode.lines().firstOrNull { it.trimStart().startsWith("; filament_type = ") }
            ?: return null
        return line.substringAfter("= ").split(";").map { it.trim() }
    }

    /**
     * Reads the `; nozzle_temperature = ...` header line from the G-code.
     * OrcaSlicer emits this as a comma-separated list of integers — one per
     * extruder. Returns `null` if absent.
     */
    private fun readNozzleTemperatureHeader(gcode: String): List<Int>? {
        val line = gcode.lines().firstOrNull {
            it.trimStart().startsWith("; nozzle_temperature = ")
        } ?: return null
        return line.substringAfter("= ").split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    /**
     * Generic per-extruder header reader. OrcaSlicer emits per-extruder
     * config keys as `; <key> = a,b,c,...` (comma-separated) for numeric
     * arrays and `; <key> = a;b;c;...` (semicolon-separated) for string
     * arrays. Returns the raw token list or null if the header is absent.
     */
    private fun readArrayHeader(gcode: String, key: String, separator: Char): List<String>? {
        val line = gcode.lines().firstOrNull {
            it.trimStart().startsWith("; $key = ")
        } ?: return null
        return line.substringAfter("= ").split(separator).map { it.trim() }
    }

    /**
     * Cascade detector: H2C benchy (7 file filaments) with PETG override at
     * fileIndex 0 must produce a `filament_type` header where index 0 is
     * PETG and ALL other indices are PLA. A failure on indices 4 (which
     * shares slot 0 with index 0 in the auto-suggested mapping) indicates
     * the slot-preset round-trip cascade is back.
     *
     * Red on `28137c5` if the cascade returns; green when the per-filament
     * resolver is wired correctly through embed → slice.
     */
    @Test
    fun h2cBenchy_overrideFileIndexZeroToPETG_appearsOnlyAtIndexZero() {
        val input = asset("3DBenchy-H2C-Multi-Color.3mf")
        val origInfo = ThreeMfParser.parse(input)
        assertTrue("H2C benchy must have hasPaintData=true", origInfo.hasPaintData)

        val canonical = bambuCanonicalList(input)
            ?: error("bambuCanonicalList must produce a canonical list for H2C benchy")
        assertEquals("H2C benchy must have 7 file filaments", 7, canonical.size)

        // Apply user override: fileIndex 0 → PETG. Mirrors the Prepare
        // screen tap ("Filament 1 → PETG") from the smoke-test scenario.
        val overrides = mapOf(0 to (null to "PETG"))
        val overridden = applyOverridesToCanonical(
            canonical = canonical,
            overrides = overrides,
        )
        // H2C auto-suggested 7→4 mapping (deterministic stand-in for the
        // closest-extruder heuristic): collisions at slots 0/1/2.
        val colorMapping = listOf(0, 1, 2, 3, 0, 1, 2)
        val (filamentTypes, nozzleTemps) = resolvePerFilamentTypeAndTemp(
            canonical = overridden,
            overrides = overrides,
            colorMapping = colorMapping,
            presets = fourPLAPresets(),
            filamentLibrary = emptyList(),
        )

        // Sanity-check the resolver's output before we even slice — these
        // are the same assertions PerFilamentResolverTest makes. If they
        // fail here, the resolver has regressed.
        assertEquals(
            "Resolver must produce PETG only at index 0 — cascade detector.",
            listOf("PETG", "PLA", "PLA", "PLA", "PLA", "PLA", "PLA"),
            filamentTypes,
        )
        assertEquals(
            listOf(235, 220, 220, 220, 220, 220, 220),
            nozzleTemps,
        )

        // Build embed config with the overrides flowing in via the
        // `overrides` parameter. targetExtruderCount = canonical.size so
        // normalizePerFilamentArrays does NOT truncate the 7-entry array.
        val processed = BambuSanitizer.process(input, outDir)
        val sourceConfig = java.util.zip.ZipFile(input).use { embedder.parseSourceConfig(it) }
        val embedOverrides: Map<String, Any> = mapOf(
            "filament_type" to filamentTypes.toMutableList(),
            "nozzle_temperature" to nozzleTemps.map { it.toString() }.toMutableList(),
            "nozzle_temperature_initial_layer" to nozzleTemps.map { it.toString() }.toMutableList(),
        )
        val config = embedder.buildConfig(
            info = origInfo,
            sourceConfig = sourceConfig,
            overrides = embedOverrides,
            targetExtruderCount = canonical.size,
        )

        // The embedded config must carry the full 7-entry override — if
        // `normalizePerFilamentArrays` truncates here, the override is lost
        // before the slicer even runs.
        val embeddedTypes = config["filament_type"] as? List<*>
            ?: error("filament_type must be a list in embedded config")
        assertEquals(
            "Embedded filament_type must have 7 entries (no truncation).",
            7, embeddedTypes.size,
        )
        assertEquals(
            "Embedded filament_type[0] must be PETG.",
            "PETG", embeddedTypes[0].toString(),
        )

        val embedded = embedder.embed(processed, config, outDir, origInfo)
        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))

        // Slice with 4 physical extruders. Phase 2 (2026-04-28) structural
        // fix: applyConfigToPrusa is now gated on !has_embedded_profile for
        // nozzle_temperature et al., AND profile_keys[] now whitelists those
        // keys. The embed's canonical-sized nozzle_temperature array
        // (=[235,220,220,220,220,220,220]) reaches the slicer via
        // profile_keys[] and is no longer overwritten — cfg.extruderTemps
        // (the slot-space JNI default of [220,220,220,220]) is consulted
        // only for STL / non-Snapmaker fallbacks.
        val result = lib.slice(makeConfig(4))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("Slice must succeed: ${result.errorMessage}", result.success)

        val gcode = File(result.gcodePath).readText()
        val header = readFilamentTypeHeader(gcode)
            ?: error("G-code must contain a `; filament_type = ...` header line")

        Log.i("Phase2AlignmentTest", "H2C benchy filament_type header: $header")

        assertEquals(
            "G-code header must have 7 filament_type entries (one per file " +
                "filament). Got ${header.size}: $header. If <7, " +
                "normalizePerFilamentArrays truncated the override array " +
                "(handoff §1 bug 13).",
            7, header.size,
        )
        assertEquals(
            "G-code header filament_type[0] must be PETG (the user's " +
                "override). Got '${header[0]}'.",
            "PETG", header[0],
        )
        for (i in 1 until 7) {
            assertEquals(
                "G-code header filament_type[$i] must be PLA (no cascade). " +
                    "Got '${header[i]}'. If PETG appears at index 4 (shares " +
                    "slot 0 with index 0), the cascade bug is back. Full " +
                    "header: $header.",
                "PLA", header[i],
            )
        }

        // Phase 2 §4 — nozzle_temperature cascade detector. The PETG override
        // at fileIndex 0 must reach the slicer's nozzle_temperature. Phase 2
        // (2026-04-28) structural fix: nozzle_temperature is now in
        // profile_keys[] AND applyConfigToPrusa is gated on
        // !has_embedded_profile for it. Pre-fix the C++ side at
        // sapil_print.cpp:285 unconditionally overwrote the embedded
        // canonical array with cfg.extruder_temps (slot-space, 4 entries),
        // then B48 padding at line 858 grew the array using the LAST value
        // → PETG (235°C) clobbered to 220°C at every position. The E2E
        // result on baf136e captured the legacy behaviour
        // ("nozzle_temperature = 220,220,...,220").
        val tempHeader = readNozzleTemperatureHeader(gcode)
            ?: error("G-code must contain a `; nozzle_temperature = ...` header line")
        Log.i("Phase2AlignmentTest", "H2C benchy nozzle_temperature header: $tempHeader")
        assertTrue(
            "nozzle_temperature must have at least 7 entries (one per file " +
                "filament). Got ${tempHeader.size}: $tempHeader.",
            tempHeader.size >= 7
        )
        assertEquals(
            "nozzle_temperature[0] must be 235°C (PETG default for the " +
                "user's override on fileIndex 0). Got ${tempHeader[0]}. If 220, " +
                "the slot-space cfg.extruder_temps clobbered the embed's " +
                "canonical array at sapil_print.cpp:285.",
            235, tempHeader[0]
        )

        // Phase 2 (2026-04-28) — broadened cascade detector. The
        // applyConfigToPrusa parallel-write pattern was retired in 1e95c7d
        // for ALL per-filament tuning keys (not just nozzle_temperature).
        // Verify each whitelisted key reaches the G-code header at the
        // canonical extent. Without this, a future regression on, say,
        // hot_plate_temp would slip past the test that only checks
        // nozzle_temperature.
        //
        // The keys are everything Phase 2 added to profile_keys[] +
        // gated in applyConfigToPrusa. For string arrays the separator
        // is ';' (filament_type, filament_settings_id); numeric arrays
        // use ','.
        // Phase 2 (2026-04-28) — only `nozzle_temperature_initial_layer`
        // remains in the canonical embed flow because the gcode
        // differential vs v1.6.13 showed that respecting embed values
        // for other per-filament tuning keys (bed temp, fan, volumetric
        // speed, slow_down, flush) caused U1 print regressions on Bambu/
        // Prusa-prepared files. The user's only override surface today
        // is material type, which only drives nozzle temp. Other
        // material-tuned keys stay clamped to U1 hardware defaults via
        // applyConfigToPrusa until their corresponding override UI ships.
        // See docs/superpowers/exploration/2026-04-28-gcode-baseline-diff.md
        val canonicalKeys = listOf(
            "nozzle_temperature_initial_layer",
        )
        for (key in canonicalKeys) {
            val arr = readArrayHeader(gcode, key, ',') ?: continue
            assertTrue(
                "Cascade detector: `$key` must have ≥7 entries (canonical extent " +
                    "for H2C benchy with 7 file filaments). Got ${arr.size}: $arr. " +
                    "Fewer entries → applyConfigToPrusa overwrote the embed value " +
                    "with a slot-space (4-entry) default and B48 padding stretched " +
                    "the wrong value. See " +
                    "docs/superpowers/exploration/2026-04-27-applyConfigToPrusa-cascade-audit.md",
                arr.size >= 7
            )
        }
    }
}
