package com.u1.slicer.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.pm.ApplicationInfo
import com.u1.slicer.SlicerViewModel
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.printer.PrinterViewModel
import java.io.File

/**
 * Debug-only BroadcastReceiver for reliable ADB-driven E2E testing.
 *
 * Registered dynamically from MainActivity.onCreate() in debug builds only.
 * Accepts commands via `adb shell am broadcast` to drive the app without
 * fragile pixel-coordinate tapping.
 *
 * ## Usage examples:
 *
 * ```bash
 * # Load a file from /sdcard/Download/
 * adb -s SERIAL shell am broadcast -a com.u1.slicer.orca.LOAD_FILE \
 *     --es path "/sdcard/Download/Dragon Scale infinity-1-plate-2-colours.3mf"
 *
 * # Set printer URL
 * adb -s SERIAL shell am broadcast -a com.u1.slicer.orca.SET_PRINTER \
 *     --es ip "192.168.0.151"
 *
 * # Sync filaments from printer
 * adb -s SERIAL shell am broadcast -a com.u1.slicer.orca.SYNC_FILAMENTS
 *
 * # Set extruder colours (R/G/B/W)
 * adb -s SERIAL shell am broadcast -a com.u1.slicer.orca.SET_COLORS \
 *     --es colors "#FF0000,#00FF00,#0000FF,#FFFFFF"
 *
 * # Start slicing
 * adb -s SERIAL shell am broadcast -a com.u1.slicer.orca.SLICE
 *
 * # Select plate (for multi-plate files)
 * adb -s SERIAL shell am broadcast -a com.u1.slicer.orca.SELECT_PLATE \
 *     --ei plate 1
 *
 * # Navigate to a screen
 * adb -s SERIAL shell am broadcast -a com.u1.slicer.orca.NAVIGATE \
 *     --es screen "printer"
 *
 * # Dump current state and pipeline diagnostics to logcat
 * adb -s SERIAL shell am broadcast -a com.u1.slicer.orca.DUMP_STATE
 *
 * # Check G-code for tool changes
 * adb -s SERIAL shell am broadcast -a com.u1.slicer.orca.CHECK_GCODE
 *
 * # Dump the embedded model_settings.config from the last embedded 3MF
 * adb -s SERIAL shell am broadcast -a com.u1.slicer.orca.DUMP_EMBEDDED_CONFIG
 * ```
 *
 * All output goes to logcat with tag "TestCmd".
 * Filter with: `adb logcat -s TestCmd`
 *
 * ## TODOs for future improvement:
 * - Add WAIT_FOR_STATE command that polls until a target state is reached
 * - Add SCREENSHOT command that triggers a Compose screenshot to a file
 * - Add SET_OVERRIDES command to set SlicingOverrides per-key
 * - Add EXPORT_GCODE command to copy output.gcode to /sdcard/Download/
 * - Move to a ContentProvider for richer query/response patterns
 * - Add Compose UI test IDs (testTag) for Espresso/ComposeTestRule migration
 */
class TestCommandReceiver(
    private val slicerViewModel: SlicerViewModel,
    private val printerViewModel: PrinterViewModel,
    private val navigateTo: (String) -> Unit
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "TestCmd"
        private const val PREFIX = "com.u1.slicer.orca."

        const val ACTION_LOAD_FILE = "${PREFIX}LOAD_FILE"
        const val ACTION_SET_PRINTER = "${PREFIX}SET_PRINTER"
        const val ACTION_SYNC_FILAMENTS = "${PREFIX}SYNC_FILAMENTS"
        const val ACTION_SET_COLORS = "${PREFIX}SET_COLORS"
        const val ACTION_SLICE = "${PREFIX}SLICE"
        const val ACTION_SELECT_PLATE = "${PREFIX}SELECT_PLATE"
        const val ACTION_NAVIGATE = "${PREFIX}NAVIGATE"
        const val ACTION_DUMP_STATE = "${PREFIX}DUMP_STATE"
        const val ACTION_CHECK_GCODE = "${PREFIX}CHECK_GCODE"
        const val ACTION_DUMP_EMBEDDED_CONFIG = "${PREFIX}DUMP_EMBEDDED_CONFIG"
        const val ACTION_IMPORT_BACKUP = "${PREFIX}IMPORT_BACKUP"

        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(ACTION_LOAD_FILE)
            addAction(ACTION_SET_PRINTER)
            addAction(ACTION_SYNC_FILAMENTS)
            addAction(ACTION_SET_COLORS)
            addAction(ACTION_SLICE)
            addAction(ACTION_SELECT_PLATE)
            addAction(ACTION_NAVIGATE)
            addAction(ACTION_DUMP_STATE)
            addAction(ACTION_CHECK_GCODE)
            addAction(ACTION_DUMP_EMBEDDED_CONFIG)
            addAction(ACTION_IMPORT_BACKUP)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        val isDebug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!isDebug) {
            Log.w(TAG, "TestCommandReceiver disabled in release builds")
            return
        }

        val action = intent.action ?: return
        Log.i(TAG, "Received: $action")

        when (action) {
            ACTION_LOAD_FILE -> handleLoadFile(context, intent)
            ACTION_SET_PRINTER -> handleSetPrinter(intent)
            ACTION_SYNC_FILAMENTS -> handleSyncFilaments()
            ACTION_SET_COLORS -> handleSetColors(intent)
            ACTION_SLICE -> handleSlice()
            ACTION_SELECT_PLATE -> handleSelectPlate(intent)
            ACTION_NAVIGATE -> handleNavigate(intent)
            ACTION_DUMP_STATE -> handleDumpState(context)
            ACTION_CHECK_GCODE -> handleCheckGcode(context)
            ACTION_DUMP_EMBEDDED_CONFIG -> handleDumpEmbeddedConfig(context)
            ACTION_IMPORT_BACKUP -> handleImportBackup(context, intent)
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    private fun handleLoadFile(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path")
        if (path.isNullOrBlank()) {
            Log.e(TAG, "LOAD_FILE: missing --es path")
            return
        }

        // The path may be in /sdcard/Download/ which is inaccessible on Android 14+.
        // Two approaches:
        // 1. If file is already in app's filesDir or cacheDir, use it directly
        // 2. Otherwise, caller must first copy it:
        //    adb shell "run-as com.u1.slicer.orca cp /sdcard/Download/file.3mf files/test_load.3mf"
        //    Then use: --es path "files/test_load.3mf" (relative to filesDir)

        val file = if (File(path).isAbsolute) {
            val absFile = File(path)
            if (absFile.canRead()) absFile
            else {
                // Try as relative to filesDir
                val relFile = File(context.filesDir, path)
                if (relFile.canRead()) relFile else null
            }
        } else {
            File(context.filesDir, path)
        }

        if (file == null || !file.exists()) {
            Log.e(TAG, "LOAD_FILE: cannot read file at '$path'")
            Log.e(TAG, "LOAD_FILE: For /sdcard/ files, first copy with:")
            Log.e(TAG, "  adb shell \"run-as com.u1.slicer.orca cp /sdcard/Download/FILE files/test_load.3mf\"")
            Log.e(TAG, "  Then use: --es path \"test_load.3mf\"")
            return
        }

        Log.i(TAG, "LOAD_FILE: loading ${file.absolutePath} (${file.length() / 1024}KB)")
        mainHandler.post { slicerViewModel.loadModelFromFile(file) }
    }

    private fun handleSetPrinter(intent: Intent) {
        val ip = intent.getStringExtra("ip")
        if (ip.isNullOrBlank()) {
            Log.e(TAG, "SET_PRINTER: missing --es ip")
            return
        }
        val url = if (ip.startsWith("http")) ip else "http://$ip"
        Log.i(TAG, "SET_PRINTER: setting URL to $url")
        printerViewModel.updateUrl(url)
    }

    private fun handleSyncFilaments() {
        Log.i(TAG, "SYNC_FILAMENTS: starting sync")
        printerViewModel.syncFilaments()
    }

    private fun handleSetColors(intent: Intent) {
        val colorsStr = intent.getStringExtra("colors")
        if (colorsStr.isNullOrBlank()) {
            Log.e(TAG, "SET_COLORS: missing --es colors (comma-separated hex)")
            return
        }
        val colors = colorsStr.split(",").map { it.trim() }
        Log.i(TAG, "SET_COLORS: setting ${colors.size} colors: $colors")
        colors.forEachIndexed { i, color ->
            if (i < 4) {
                val preset = ExtruderPreset(index = i, color = color)
                printerViewModel.updateExtruderPreset(preset)
            }
        }
    }

    private fun handleSlice() {
        Log.i(TAG, "SLICE: starting slice")
        mainHandler.post { slicerViewModel.startSlicing() }
    }

    private fun handleSelectPlate(intent: Intent) {
        val plateId = intent.getIntExtra("plate", -1)
        if (plateId < 0) {
            Log.e(TAG, "SELECT_PLATE: missing --ei plate")
            return
        }
        Log.i(TAG, "SELECT_PLATE: selecting plate $plateId")
        mainHandler.post { slicerViewModel.selectPlate(plateId) }
    }

    private fun handleNavigate(intent: Intent) {
        val screen = intent.getStringExtra("screen")
        if (screen.isNullOrBlank()) {
            Log.e(TAG, "NAVIGATE: missing --es screen")
            return
        }
        Log.i(TAG, "NAVIGATE: navigating to $screen")
        mainHandler.post { navigateTo(screen) }
    }

    private fun handleDumpState(context: Context) {
        val state = slicerViewModel.state.value
        val info = slicerViewModel.threeMfInfo.value
        val presets = slicerViewModel.extruderPresets.value
        Log.i(TAG, "=== DUMP_STATE ===")
        Log.i(TAG, "State: $state")
        Log.i(TAG, "ThreeMfInfo: $info")
        Log.i(TAG, "  detectedColors: ${info?.detectedColors}")
        Log.i(TAG, "  detectedExtruderCount: ${info?.detectedExtruderCount}")
        Log.i(TAG, "  hasPaintData: ${info?.hasPaintData}")
        Log.i(TAG, "  hasPlateJsons: ${info?.hasPlateJsons}")
        Log.i(TAG, "  isMultiPlate: ${info?.isMultiPlate}")
        Log.i(TAG, "ExtruderPresets: ${presets.map { "${it.label}=${it.color} profileId=${it.filamentProfileId}" }}")
        Log.i(TAG, "ActiveExtruderColors: ${slicerViewModel.activeExtruderColors.value}")

        // List files in app dir
        val filesDir = context.filesDir
        filesDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            Log.i(TAG, "  file: ${f.name} (${f.length() / 1024}KB)")
        }
        Log.i(TAG, "=== END DUMP_STATE ===")
    }

    private fun handleCheckGcode(context: Context) {
        val gcodeFile = File(context.filesDir, "output.gcode")
        if (!gcodeFile.exists()) {
            Log.e(TAG, "CHECK_GCODE: output.gcode not found")
            return
        }
        val text = gcodeFile.readText()
        val lines = text.lines()
        val t0Count = lines.count { it.trimStart().startsWith("T0") }
        val t1Count = lines.count { it.trimStart().startsWith("T1") }
        val t2Count = lines.count { it.trimStart().startsWith("T2") }
        val t3Count = lines.count { it.trimStart().startsWith("T3") }
        val layerCount = lines.count { it.contains(";LAYER_CHANGE") }
        val hasWipeTower = text.contains(";TYPE:Wipe tower")

        Log.i(TAG, "=== CHECK_GCODE ===")
        Log.i(TAG, "File size: ${gcodeFile.length() / 1024}KB")
        Log.i(TAG, "Lines: ${lines.size}")
        Log.i(TAG, "Layers: $layerCount")
        Log.i(TAG, "T0 commands: $t0Count")
        Log.i(TAG, "T1 commands: $t1Count")
        Log.i(TAG, "T2 commands: $t2Count")
        Log.i(TAG, "T3 commands: $t3Count")
        Log.i(TAG, "Wipe tower: $hasWipeTower")
        Log.i(TAG, "Multi-colour: ${t1Count > 3}")
        Log.i(TAG, "=== END CHECK_GCODE ===")
    }

    private fun handleDumpEmbeddedConfig(context: Context) {
        // Find the most recent embedded 3MF
        val filesDir = context.filesDir
        val embedded = filesDir.listFiles()
            ?.filter { it.name.startsWith("embedded_") && it.name.endsWith(".3mf") }
            ?.maxByOrNull { it.lastModified() }

        if (embedded == null) {
            Log.e(TAG, "DUMP_EMBEDDED_CONFIG: no embedded_*.3mf found")
            return
        }

        Log.i(TAG, "=== DUMP_EMBEDDED_CONFIG: ${embedded.name} ===")
        try {
            val zip = java.util.zip.ZipFile(embedded)
            for (entry in zip.entries()) {
                if (entry.name.contains("model_settings") || entry.name.contains("Slic3r_PE_model")) {
                    val content = zip.getInputStream(entry).bufferedReader().readText()
                    Log.i(TAG, "--- ${entry.name} ---")
                    content.lines().forEach { line -> Log.i(TAG, line) }
                }
                if (entry.name.contains("project_settings")) {
                    val content = zip.getInputStream(entry).bufferedReader().readText()
                    // Just dump key multi-colour keys
                    val lines = content.lines()
                    val relevantKeys = listOf(
                        "single_extruder_multi_material", "enable_prime_tower",
                        "extruder_count", "is_extruder_used"
                    )
                    for (key in relevantKeys) {
                        val line = lines.firstOrNull { it.startsWith("$key ") || it.startsWith("$key=") }
                        Log.i(TAG, "project_settings: ${line ?: "$key = <not found>"}")
                    }
                }
            }
            zip.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading embedded 3MF: ${e.message}")
        }
        Log.i(TAG, "=== END DUMP_EMBEDDED_CONFIG ===")
    }

    private fun handleImportBackup(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path")
        if (path.isNullOrBlank()) {
            Log.e(TAG, "IMPORT_BACKUP: missing --es path")
            return
        }
        val file = if (path.startsWith("/")) File(path) else File(context.filesDir, path)
        if (!file.exists()) {
            Log.e(TAG, "IMPORT_BACKUP: file not found: ${file.absolutePath}")
            return
        }
        val json = file.readText()
        Log.i(TAG, "IMPORT_BACKUP: importing ${json.length} chars from ${file.name}")
        slicerViewModel.importBackup(json)
    }
}
