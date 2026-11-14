package com.u1.slicer.machine

import com.u1.slicer.data.U1NozzleConfiguration
import com.u1.slicer.gcode.GcodeToolRemapper
import com.u1.slicer.gcode.GcodeToolSpace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class U1SliceNozzlesTest {
    @Test fun `mismatch identifies the head and its installed diameter`() {
        val error = runCatching {
            U1SliceNozzles(0.2f, listOf(0), GcodeToolSpace.PHYSICAL).validate(U1NozzleConfiguration(), null)
        }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("E1 is configured as 0.4 mm"))
    }
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `every supported diameter resolves for uniform active heads`() {
        for (diameter in listOf(0.2f, 0.4f, 0.6f, 0.8f)) {
            val hardware = U1NozzleConfiguration(List(4) { diameter })
            assertEquals(diameter, resolveU1SliceDiameter(hardware, listOf(0, 1, 2, 3)), 0f)
            U1SliceNozzles(diameter, listOf(0, 3), GcodeToolSpace.PHYSICAL)
                .validate(hardware, null)
        }
    }

    @Test fun `different unused nozzles do not prevent a single head slice`() {
        val hardware = U1NozzleConfiguration(listOf(0.4f, 0.6f, 0.2f, 0.8f))
        assertEquals(0.2f, resolveU1SliceDiameter(hardware, listOf(2)), 0f)
        assertEquals(0.8f, resolveU1SliceDiameter(hardware, listOf(3)), 0f)
    }

    @Test fun `mixed diameters among active heads require matching heads`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            resolveU1SliceDiameter(U1NozzleConfiguration(listOf(0.2f, 0.4f, 0.2f, 0.8f)), listOf(0, 1))
        }
        assertTrue(error.message!!.contains("same nozzle size"))
    }

    @Test fun `missing active head fails before slicing`() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveU1SliceDiameter(U1NozzleConfiguration(), emptyList())
        }
    }

    @Test fun `sparse canonical tool maps to installed E3 nozzle`() {
        val requirement = U1SliceNozzles(0.2f, listOf(3), GcodeToolSpace.CANONICAL)
        val hardware = U1NozzleConfiguration(listOf(0.4f, 0.6f, 0.2f, 0.8f))
        requirement.validate(hardware, listOf(0, 1, 3, 2))
        assertThrows(IllegalArgumentException::class.java) {
            requirement.validate(hardware, listOf(0, 1, 2, 3))
        }
    }

    @Test fun `remapping to equal nozzle size permits upload`() {
        val hardware = U1NozzleConfiguration(listOf(0.2f, 0.4f, 0.2f, 0.8f))
        val requirement = U1SliceNozzles(0.2f, listOf(0), GcodeToolSpace.CANONICAL)
        val remapped = requirement.remap(mapOf(0 to 2))
        assertEquals(listOf(2), remapped.tools)
        assertEquals(GcodeToolSpace.PHYSICAL, remapped.toolSpace)
        remapped.validate(hardware, listOf(0))
    }

    @Test fun `remapping to different nozzle size requires reslicing`() {
        val hardware = U1NozzleConfiguration(listOf(0.2f, 0.4f, 0.6f, 0.8f))
        val requirement = U1SliceNozzles(0.2f, listOf(0), GcodeToolSpace.CANONICAL)
        for (destination in 1..3) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                requirement.remap(mapOf(0 to destination)).validate(hardware, null)
            }
            assertTrue(error.message!!.contains("slice again"))
        }
    }

    @Test fun `physical requirement checks destination when physical heads are remapped`() {
        val requirement = U1SliceNozzles(0.2f, listOf(2), GcodeToolSpace.PHYSICAL)
        val hardware = U1NozzleConfiguration(listOf(0.4f, 0.4f, 0.2f, 0.4f))
        requirement.validate(hardware, null)
        assertThrows(IllegalArgumentException::class.java) { requirement.validate(hardware, listOf(0, 1, 0)) }
    }

    @Test fun `appended metadata survives history and durable file copies`() {
        val requirement = U1SliceNozzles(0.8f, listOf(0, 2), GcodeToolSpace.PHYSICAL)
        val cache = temporary.newFile("cache.gcode")
        cache.writeText("; generated gcode\n" + "G1 X10 Y10 E1\n".repeat(1000))
        assertNull(U1SliceNozzles.read(cache))
        cache.appendText("\n${requirement.toGcodeLine()}\n")
        assertEquals(requirement, U1SliceNozzles.read(cache))
        val history = cache.copyTo(temporary.root.resolve("history.gcode"))
        val durable = history.copyTo(temporary.root.resolve("saved.gcode"))
        cache.delete()
        history.delete()
        assertEquals(requirement, U1SliceNozzles.read(durable))
    }

    @Test fun `remapper updates custom footer metadata along with tool commands`() {
        val original = U1SliceNozzles(0.6f, listOf(0, 3), GcodeToolSpace.CANONICAL)
        val line = GcodeToolRemapper.remapLine(original.toGcodeLine(), mapOf(0 to 2, 3 to 1))
        assertEquals(U1SliceNozzles(0.6f, listOf(1, 2), GcodeToolSpace.PHYSICAL), U1SliceNozzles.parse(line))
        assertEquals("T2", GcodeToolRemapper.remapLine("T0", mapOf(0 to 2)))
    }

    @Test fun `malformed present marker fails instead of becoming legacy unbound gcode`() {
        for (payload in listOf("", "0.2|PHYSICAL", "0.3|PHYSICAL|0", "0.2|UNKNOWN|0", "0.2|PHYSICAL|", "0.2|PHYSICAL|-1")) {
            val file = temporary.newFile()
            val line = U1SliceNozzles.PREFIX + payload
            file.writeText("G1 X1 Y1 E1\n$line\n")
            assertThrows(IllegalArgumentException::class.java) { U1SliceNozzles.read(file) }
            assertThrows(IllegalArgumentException::class.java) {
                GcodeToolRemapper.remapLine(line, mapOf(0 to 2))
            }
        }
    }

    @Test fun `legacy gcode without marker remains readable`() {
        val file = temporary.newFile()
        file.writeText("; nozzle_diameter = 0.4\nG1 X1 Y1 E1\n")
        assertNull(U1SliceNozzles.read(file))
        assertNull(U1SliceNozzles.parse("; unrelated comment"))
    }

    @Test fun `old U1 jobs retain conservative nozzle requirements through export`() {
        val file = temporary.newFile()
        file.writeText("T2\nG1 X1 E1\n; printer_settings_id = Snapmaker U1 (0.4 nozzle) - multiplate\n; nozzle_diameter = 0.4,0.4,0.4,0.4\n")
        val requirement = requireNotNull(U1SliceNozzles.read(file))
        requirement.validate(U1NozzleConfiguration(), null)
        val changed = U1NozzleConfiguration().withDiameter(2, 0.2f)
        assertThrows(IllegalArgumentException::class.java) { requirement.validate(changed, listOf(0)) }
        val exported = requirement.remap(mapOf(0 to 1))
        assertEquals(listOf(0, 1, 2, 3), exported.tools)
        assertThrows(IllegalArgumentException::class.java) { exported.validate(changed, null) }
    }
}
