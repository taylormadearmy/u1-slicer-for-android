package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.U1SlicerApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reusable G-code differential harness — slices a fixed set of fixtures
 * end-to-end through the SlicerViewModel pipeline and dumps a structured
 * "G-code config snapshot" per fixture so two builds (e.g. v1.6.13 vs
 * post-Phase 2) can be diffed for unintentional changes to print-affecting
 * parameters.
 *
 * The snapshot for each fixture captures:
 *   - All known per-extruder config keys' values from the G-code header
 *     (retraction, temperatures, fan speeds, ooze prevention, idle temps,
 *     ramming, loading/unloading speeds, machine max speeds, etc.)
 *   - The PRINT_START / PRINT_END / change_filament_gcode templates
 *     (verbatim to compare line-by-line)
 *   - First 100 lines of the body G-code (entrance sequence)
 *   - Last 100 lines of the body G-code (end-of-print sequence)
 *   - First 50 lines after the first tool change (toolchange sequence
 *     sample) for fixtures that have multi-colour output
 *
 * Snapshots are written to
 * `/sdcard/Android/media/com.u1.slicer.orca/baselines/<fixture>.snapshot.txt`
 * for `adb pull` to host.
 *
 * To use:
 *   1. Run on the BASELINE build (e.g. tag v1.6.13). `adb pull` the
 *      baselines dir to `c:/tmp/gcode-baselines/baseline/`.
 *   2. Run on the CANDIDATE build (e.g. current HEAD). `adb pull` to
 *      `c:/tmp/gcode-baselines/candidate/`.
 *   3. `diff -ru c:/tmp/gcode-baselines/baseline c:/tmp/gcode-baselines/candidate`
 *      Inspect each diff: any change in retraction*, nozzle_temperature*,
 *      hot_plate_temp*, fan_*, ooze_*, idle_*, standby_*, ramming, loading,
 *      machine_max_*, or PRINT_START/END/change_filament_gcode templates is
 *      a print-impact change that needs explicit justification.
 *
 * To pin baselines for CI: commit the baseline snapshot files to
 * `app/src/androidTest/assets/gcode-baselines/<fixture>.snapshot.txt` and
 * extend this test to read the pinned baseline + assert the candidate
 * matches. (Out of scope for the initial harness; future work.)
 */
@RunWith(AndroidJUnit4::class)
class GcodeBaselineDiffTest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    private lateinit var baselineDir: File

    @Before
    fun setup() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        // Use the package's own external files dir AND a parallel public
        // Download mirror so the snapshots survive both:
        //   (a) Android 14 scoped storage hiding `/sdcard/Android/data/`
        //       from `adb pull` and `adb shell ls` outside the app process.
        //   (b) The uid mismatch when reinstalling the app (the next
        //       install gets a different uid that can't write into a dir
        //       owned by the previous install's uid).
        //
        // The fix for (b): use a uid-stamped subdirectory. Each install
        // gets a distinct dir owned by its own uid; old dirs persist on
        // disk but don't block new writes. Host runner picks the most
        // recent dir to pull from.
        //
        // We deliberately DO NOT wipe the directory here — Orchestrator
        // gives each test its own process, so a `listFiles().delete()` in
        // @Before would erase prior tests' snapshots in the same run.
        val uidStamp = android.os.Process.myUid()
        baselineDir = File("/sdcard/Download/u1-slicer-baselines-uid$uidStamp")
            .also { it.mkdirs() }
    }

    @After
    fun teardown() {
        // Leave baseline files for adb pull. They're gitignored / transient.
    }

    private fun copyAssetToCache(assetName: String): File {
        val outFile = File(targetContext.cacheDir, assetName.replace("/", "_"))
        assetContext.assets.open(assetName).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
    }

    private fun waitUntil(label: String, timeoutMs: Long = 60_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    /**
     * Keys whose value drift between builds is a print-impact concern.
     * The snapshot captures each in stable alphabetical order so a diff
     * lines up cleanly even when OrcaSlicer reorders its header section.
     */
    private val watchedKeys = listOf(
        // Per-extruder retraction (hardware-tuned for U1's direct drive)
        "deretraction_speed",
        "retract_length_toolchange",
        "retraction_length",
        "retraction_minimum_travel",
        "retraction_speed",
        // Per-filament temperature (material-tuned, Phase 2 cascade)
        "hot_plate_temp",
        "hot_plate_temp_initial_layer",
        "nozzle_temperature",
        "nozzle_temperature_initial_layer",
        // Standby/idle temperature (material × hardware)
        "idle_temperature",
        "ooze_prevention",
        "standby_temperature_delta",
        // Filament loading / toolchange / ramming (hardware-tuned)
        "cooling_tube_length",
        "cooling_tube_retraction",
        "enable_filament_ramming",
        "extra_loading_move",
        "filament_cooling_moves",
        "filament_loading_speed",
        "filament_loading_speed_start",
        "filament_toolchange_delay",
        "filament_unloading_speed",
        "filament_unloading_speed_start",
        "parking_pos_retraction",
        "single_extruder_multi_material",
        "tool_change_temprature_wait",
        // Cooling (material-tuned)
        "fan_max_speed",
        "fan_min_speed",
        "overhang_fan_speed",
        "slow_down_layer_time",
        "slow_down_min_speed",
        // Filament physical (material-tuned)
        "filament_density",
        "filament_diameter",
        "filament_max_volumetric_speed",
        "filament_type",
        // Wipe tower (slot-aware)
        "flush_volumes_matrix",
        "flush_multiplier",
        "filament_minimal_purge_on_wipe_tower",
        "purge_in_prime_tower",
        // Machine limits (hardware)
        "machine_max_acceleration_extruding",
        "machine_max_acceleration_retracting",
        "machine_max_acceleration_travel",
        "machine_max_acceleration_x",
        "machine_max_acceleration_y",
        "machine_max_acceleration_z",
        "machine_max_acceleration_e",
        "machine_max_jerk_x",
        "machine_max_jerk_y",
        "machine_max_jerk_z",
        "machine_max_jerk_e",
        "machine_max_speed_x",
        "machine_max_speed_y",
        "machine_max_speed_z",
        "machine_max_speed_e",
        // Pressure advance (Klipper)
        "enable_pressure_advance",
        "pressure_advance",
        // Per-feature jerk
        "default_jerk",
        "outer_wall_jerk",
        "inner_wall_jerk",
        "infill_jerk",
        "initial_layer_jerk",
        "top_surface_jerk",
        "travel_jerk",
        // Bed type (affects bed temp resolution)
        "curr_bed_type",
        // G-code dialect
        "gcode_flavor",
        // Filament flow calibration
        "filament_flow_ratio",
    ).sorted()

    /** Read a single `; key = value` header line from G-code. */
    private fun readHeaderValue(gcode: String, key: String): String? =
        gcode.lineSequence()
            .firstOrNull { it.trimStart().startsWith("; $key = ") }
            ?.substringAfter("= ")

    /** Read multi-line G-code template values like `machine_start_gcode`. */
    private fun readMultilineTemplate(gcode: String, key: String): String? {
        // OrcaSlicer emits these on a single line with `\n` escaped to `\n`
        // literal — find the line and unescape for readability.
        val raw = readHeaderValue(gcode, key) ?: return null
        return raw.replace("\\n", "\n")
    }

    private fun extractToolchangeSequence(gcode: String, sampleLines: Int = 50): String {
        val lines = gcode.lines()
        val firstTcIdx = lines.indexOfFirst { it.trim().matches(Regex("""T\d+""")) }
        if (firstTcIdx < 0) return "<no tool changes>"
        val start = firstTcIdx
        val end = (start + sampleLines).coerceAtMost(lines.size)
        return lines.subList(start, end).joinToString("\n")
    }

    private fun bodyHeadTail(gcode: String, headLines: Int = 100, tailLines: Int = 100): Pair<String, String> {
        val lines = gcode.lines()
        // Skip the comment-header section (consecutive `;` lines at top) so
        // the body sample starts at the actual first command.
        val bodyStart = lines.indexOfFirst { line ->
            val t = line.trimStart()
            t.isNotEmpty() && !t.startsWith(";")
        }.coerceAtLeast(0)
        val head = lines.subList(bodyStart, (bodyStart + headLines).coerceAtMost(lines.size))
            .joinToString("\n")
        val tailFrom = (lines.size - tailLines).coerceAtLeast(bodyStart)
        val tail = lines.subList(tailFrom, lines.size).joinToString("\n")
        return head to tail
    }

    /** Build the structured snapshot text for one slice's G-code. */
    private fun buildSnapshot(label: String, gcodePath: String): String {
        val gcode = File(gcodePath).readText()
        val sb = StringBuilder()
        sb.appendLine("# Snapshot for: $label")
        sb.appendLine("# Source path:   $gcodePath")
        sb.appendLine()
        sb.appendLine("## Header values (alphabetical)")
        for (key in watchedKeys) {
            val v = readHeaderValue(gcode, key) ?: "<absent>"
            sb.appendLine("$key = $v")
        }
        sb.appendLine()
        sb.appendLine("## machine_start_gcode")
        sb.appendLine(readMultilineTemplate(gcode, "machine_start_gcode") ?: "<absent>")
        sb.appendLine()
        sb.appendLine("## machine_end_gcode")
        sb.appendLine(readMultilineTemplate(gcode, "machine_end_gcode") ?: "<absent>")
        sb.appendLine()
        sb.appendLine("## change_filament_gcode")
        sb.appendLine(readMultilineTemplate(gcode, "change_filament_gcode") ?: "<absent>")
        sb.appendLine()
        sb.appendLine("## machine_pause_gcode")
        sb.appendLine(readMultilineTemplate(gcode, "machine_pause_gcode") ?: "<absent>")
        sb.appendLine()
        val (head, tail) = bodyHeadTail(gcode)
        sb.appendLine("## Body head (100 lines after first command)")
        sb.appendLine(head)
        sb.appendLine()
        sb.appendLine("## Body tail (last 100 lines)")
        sb.appendLine(tail)
        sb.appendLine()
        sb.appendLine("## First tool-change sequence sample (50 lines from first T<n>)")
        sb.appendLine(extractToolchangeSequence(gcode))
        sb.appendLine()
        return sb.toString()
    }

    /**
     * Slice via the production SlicerViewModel pipeline and write a
     * snapshot for the resulting G-code.
     */
    private fun snapshotFixture(
        snapshotName: String,
        assetName: String,
        plateId: Int? = null,
    ) = runBlocking {
        val app = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(app)
        val modelFile = copyAssetToCache(assetName)
        Log.i("GcodeBaselineDiffTest", "Snapshotting $snapshotName from $assetName plate=$plateId")
        try {
            viewModel.loadModelFromFile(modelFile)
            // Wait for either ModelLoaded (single-plate path) or
            // showPlateSelector (multi-plate path).
            waitUntil("$snapshotName loaded or plate selector visible", timeoutMs = 240_000L) {
                viewModel.showPlateSelector.value ||
                    viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            val loadState = viewModel.state.value
            if (loadState is SlicerViewModel.SlicerState.Error) {
                throw AssertionError("$snapshotName load failed: ${loadState.message}")
            }
            if (viewModel.showPlateSelector.value) {
                val plate = plateId ?: 1
                viewModel.selectPlate(plate)
                waitUntil("$snapshotName plate $plate loaded", timeoutMs = 120_000L) {
                    viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded &&
                        viewModel.colorMapping.value != null
                }
            }
            // Slice with current presets / config.
            viewModel.startSlicing()
            waitUntil("$snapshotName slice complete", timeoutMs = 600_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.SliceComplete ||
                    viewModel.state.value is SlicerViewModel.SlicerState.Error
            }
            val sliceState = viewModel.state.value
            if (sliceState is SlicerViewModel.SlicerState.Error) {
                throw AssertionError("$snapshotName slice failed: ${sliceState.message}")
            }
            sliceState as SlicerViewModel.SlicerState.SliceComplete
            val snapshot = buildSnapshot(snapshotName, sliceState.result.gcodePath)
            val outFile = File(baselineDir, "$snapshotName.snapshot.txt")
            // Delete-before-write: across reinstalls the test app's UID
            // changes, so files left from prior runs are owned by a
            // previous UID and trigger EACCES on overwrite even though
            // the directory is shared via media_rw group. Deleting the
            // stale file first lets the fresh-UID write succeed.
            // (Discovered 2026-04-28 sweep run b37v82bdb after the
            // post-Buzz-fix sweep retained snapshots from an earlier
            // sweep with a prior UID.)
            if (outFile.exists()) outFile.delete()
            outFile.writeText(snapshot)
            assertNotNull(
                "Wrote snapshot to ${outFile.absolutePath} (size=${outFile.length()})",
                outFile,
            )
            Log.i("GcodeBaselineDiffTest", "Snapshot written: ${outFile.absolutePath} (${outFile.length()} bytes)")
        } finally {
            viewModel.clearModel()
            modelFile.delete()
        }
    }

    /** STL: single-extruder, no embed → exercises the !has_embedded_profile path. */
    @Test
    fun snapshot_3DBenchy_stl() {
        snapshotFixture("01-3DBenchy-stl", "3DBenchy.stl")
    }

    /** Bambu dual-colour with embed → retraction-vs-embed gate, wipe tower, T-changes. */
    @Test
    fun snapshot_calibCube_dualColour() {
        snapshotFixture("02-calibCube-dualColour", "calib-cube-10-dual-colour-merged.3mf")
    }

    /** SEMM paint → canonical-aware embed, paint segmentation, multi-colour T-changes. */
    @Test
    fun snapshot_coloredBenchy_semm() {
        snapshotFixture("03-coloredBenchy-SEMM", "colored_3DBenchy (1).3mf")
    }

    /** Layer-tool / Hueforge plate 4 → pause injection + retraction-vs-bowden. */
    @Test
    fun snapshot_flippy_layerTool_plate4() {
        snapshotFixture("04-flippy-layerTool-plate4", "flippy+flappy+mini.3mf", plateId = 4)
    }

    /** PrusaSlicer 3MF → non-Snapmaker embed path (different start gcode). */
    @Test
    fun snapshot_prusa_korokMask() {
        snapshotFixture("05-prusa-korokMask", "PrusaSlicer-printables-Korok_mask_4colour.3mf")
    }
}
