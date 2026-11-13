package com.u1.slicer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeExtruderProfileSourceTest {
    private fun source(path: String): String = listOf(
        path,
        "../$path",
        path.removePrefix("app/"),
    ).map(::File).firstOrNull(File::exists)?.readText() ?: error("$path not found")

    @Test
    fun genericAuthoredProfilesKeepSemmAndExtruderOffsets() {
        val source = source("app/src/main/cpp/src/sapil_print.cpp")

        assertTrue(source.contains("const bool is_snapmaker_target = config.machine_target == \"SNAPMAKER_U1\""))
        assertTrue(source.contains("if (is_snapmaker_target || !has_embedded_profile)"))
        assertTrue(source.contains("profile_offsets->values.resize(n_ext"))
        assertFalse(
            "extruder offsets must not be overwritten unconditionally",
            source.contains(
                "dpc.set_key_value(\"extruder_offset\", new Slic3r::ConfigOptionPoints(" +
                    "std::vector<Slic3r::Vec2d>(n_ext, Slic3r::Vec2d(0, 0))));\n\n    // F91"
            ),
        )
    }

    @Test
    fun arrangeSplitUsesConfiguredExtruderCeiling() {
        val arrange = source("app/src/main/cpp/src/sapil_arrange.cpp")
        assertTrue(arrange.contains("int configured_extruders"))
        assertTrue(arrange.contains("std::min(configured_extruders, 4)"))
        assertFalse(arrange.contains("constexpr unsigned MAX_EXTRUDERS = 4"))

        val nativeLibrary = source("app/src/main/java/com/u1/slicer/NativeLibrary.kt")
        assertTrue(nativeLibrary.contains("nativeSplitVolume(objIdx: Int, volIdx: Int, configuredExtruderCount: Int)"))

        val viewModel = source("app/src/main/java/com/u1/slicer/SlicerViewModel.kt")
        assertTrue(viewModel.contains("private fun configuredArrangeExtruderCount(): Int"))
        assertTrue(viewModel.contains("_config.value.extruderCount.coerceIn(1, activePhysicalSlotCount())"))
        assertTrue(viewModel.contains("native.nativeSplitVolume(objIdx, volIdx, configuredArrangeExtruderCount())"))
    }
}
