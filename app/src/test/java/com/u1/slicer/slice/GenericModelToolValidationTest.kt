package com.u1.slicer.slice

import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.bambu.ThreeMfPlate
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GenericModelToolValidationTest {
    @Test fun `drawer plate five uses only tool one on a single-nozzle machine`() {
        val file = File(requireNotNull(javaClass.classLoader?.getResource("regression/4x4_Drawer_ALL.3mf")).toURI())
        val info = ThreeMfParser.parse(file)
        assertEquals(setOf(1, 2), info.usedExtruderIndices)
        assertEquals(listOf("6"), info.plates.single { it.plateId == 5 }.objectIds)
        validateGenericModelTools(info, extruderCount = 1, plateId = 5)
    }

    private fun twoPlates() = ThreeMfInfo(
        objects = emptyList(), plates = listOf(
            ThreeMfPlate(plateId = 1, name = "One", objectIds = listOf("a")),
            ThreeMfPlate(plateId = 2, name = "Two", objectIds = listOf("b")),
        ), isBambu = true, isMultiPlate = true,
        usedExtruderIndices = setOf(1, 2), volumeExtruders = setOf(1, 2),
        objectExtruderMap = mapOf("a" to 1, "b" to 2),
    )

    @Test fun `tool two remains unavailable on a one-tool machine`() {
        val error = runCatching { validateGenericModelTools(twoPlates(), 1, 2) }.exceptionOrNull()
        assertEquals("The model references tools not available on the selected machine.", error?.message)
    }

    @Test fun `compound parts cannot hide their second tool`() {
        val info = twoPlates().copy(objectPartExtruders = mapOf("a" to setOf(1, 2)))
        assertThrows(IllegalArgumentException::class.java) { validateGenericModelTools(info, 1, 1) }
    }

    @Test fun `other plates paint and layer changes do not invalidate a plain selected plate`() {
        val info = twoPlates().copy(hasPaintData = true, hasLayerToolChanges = true)
        validateGenericModelTools(info, 1, 1)
    }

    @Test fun `selected plate painting and layer changes remain unsupported on one tool`() {
        for (plate in listOf(
            twoPlates().plates.first().copy(hasPaintData = true, paintExtruderStates = setOf(1, 2)),
            twoPlates().plates.first().copy(hasLayerToolChanges = true, layerToolExtruders = setOf(2)),
        )) {
            val info = twoPlates().copy(plates = listOf(plate))
            assertThrows(IllegalArgumentException::class.java) { validateGenericModelTools(info, 1, 1) }
        }
    }
}
