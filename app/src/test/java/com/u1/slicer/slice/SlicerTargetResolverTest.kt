package com.u1.slicer.slice

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlicerTargetResolverTest {

    @Test
    fun `moonraker printer defaults to snapmaker target`() {
        val printer = Printer(
            id = "p1",
            nickname = "U1",
            kind = PrinterKind.MOONRAKER,
            moonrakerUrl = "http://printer",
        )

        assertEquals(
            SlicerTarget.SnapmakerU1,
            resolveDefaultSliceTarget(activePrinter = printer),
        )
    }

    @Test
    fun `bambu printer defaults to matching bambu model target`() {
        val printer = Printer(
            id = "p2",
            nickname = "A1 Mini",
            kind = PrinterKind.BAMBU_LAN,
            bambu = BambuConfig(
                ip = "192.168.1.8",
                accessCode = "12345678",
                serial = "03W09C123400001",
                model = BambuModel.A1_MINI,
            ),
        )

        assertEquals(
            SlicerTarget.BambuA1Mini,
            resolveDefaultSliceTarget(activePrinter = printer),
        )
    }

    @Test
    fun `every bambu model defaults to its matching local target`() {
        BambuModel.entries.forEach { model ->
            val printer = Printer(
                id = model.name,
                nickname = model.name,
                kind = PrinterKind.BAMBU_LAN,
                bambu = BambuConfig(
                    ip = "192.168.1.9",
                    accessCode = "12345678",
                    serial = "SERIAL${model.name}",
                    model = model,
                ),
            )

            assertEquals(SlicerTarget.forBambuModel(model), resolveDefaultSliceTarget(printer))
            assertTrue(isLocalSliceAvailable(printer))
        }
    }

    @Test
    fun `a1 mini and moonraker remain locally sliceable by default`() {
        val a1Mini = Printer(
            id = "a1",
            nickname = "A1 Mini",
            kind = PrinterKind.BAMBU_LAN,
            bambu = BambuConfig("192.168.1.8", "12345678", "A1", BambuModel.A1_MINI),
        )
        val u1 = Printer(
            id = "u1",
            nickname = "U1",
            kind = PrinterKind.MOONRAKER,
            moonrakerUrl = "http://printer",
        )

        assertTrue(isLocalSliceAvailable(a1Mini))
        assertTrue(isLocalSliceAvailable(u1))
    }

    @Test
    fun `compatibility is strict across printer families`() {
        assertTrue(
            SlicerTarget.BambuA1Mini.isCompatibleWith(
                kind = PrinterKind.BAMBU_LAN,
                bambuModel = BambuModel.A1_MINI,
            ),
        )
        assertFalse(
            SlicerTarget.BambuA1Mini.isCompatibleWith(
                kind = PrinterKind.MOONRAKER,
                bambuModel = null,
            ),
        )
        assertFalse(
            SlicerTarget.SnapmakerU1.isCompatibleWith(
                kind = PrinterKind.BAMBU_LAN,
                bambuModel = BambuModel.A1_MINI,
            ),
        )
    }
}
