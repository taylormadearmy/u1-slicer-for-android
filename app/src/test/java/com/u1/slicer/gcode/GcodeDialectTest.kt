package com.u1.slicer.gcode

import com.u1.slicer.machine.MachineFamily
import com.u1.slicer.machine.MachineProfile
import org.junit.Assert.assertSame
import org.junit.Test

class GcodeDialectTest {
    @Test
    fun `selects dialect from machine family and flavor`() {
        assertSame(
            KlipperSnapmakerDialect,
            profile(MachineFamily.SNAPMAKER, "klipper").gcodeDialect(),
        )
        assertSame(
            KlipperGenericDialect,
            profile(MachineFamily.GENERIC, "klipper").gcodeDialect(),
        )
        assertSame(
            MarlinDialect,
            profile(MachineFamily.GENERIC, "marlin").gcodeDialect(),
        )
    }

    private fun profile(family: MachineFamily, flavor: String): MachineProfile =
        requireNotNull(
            MachineProfile.fromResolvedOrcaJson(
                """
                {
                  "type": "machine",
                  "name": "Dialect test",
                  "printable_area": ["0x0", "200x0", "200x200", "0x200"],
                  "printable_height": "200",
                  "nozzle_diameter": ["0.4"],
                  "gcode_flavor": "$flavor"
                }
                """.trimIndent(),
                family,
            ),
        )
}
