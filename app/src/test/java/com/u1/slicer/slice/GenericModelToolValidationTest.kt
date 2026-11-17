package com.u1.slicer.slice

import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.ThreeMfPlate
import org.junit.Assert.*
import org.junit.Test

class GenericModelToolValidationTest {
    @Test fun `drawer plate five uses only tool one on a single-nozzle machine`() {
        // Metadata from the supplied file; the actual Android parser is exercised by the device regression.
        val info = twoPlates().copy(plates = listOf(ThreeMfPlate(5, "", listOf("6"))),
            objectExtruderMap = mapOf("6" to 1, "8" to 2))
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

    @Test fun `implicit tool one does not inherit another plates tool two`() {
        validateGenericModelTools(twoPlates().copy(objectExtruderMap = mapOf("b" to 2)), 1, 1)
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
