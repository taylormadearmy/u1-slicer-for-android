package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelOperationTest {
    @Test
    fun codec_roundTripsStructuralOperationsInTheirExactOrder() {
        val operations = listOf(
            ModelOperation.SplitObject(4),
            ModelOperation.DuplicateObject(4),
            ModelOperation.DeleteObject(1),
            ModelOperation.SplitVolume(3, 2),
        )

        val restored = ModelOperation.fromJsonArray(ModelOperation.toJsonArray(operations))

        assertEquals(operations, restored)
    }

    @Test
    fun codec_rejectsUnknownOrMalformedOperations() {
        assertEquals(null, ModelOperation.fromJsonArray("[{\"type\":\"delete\"}]"))
        assertEquals(null, ModelOperation.fromJsonArray("[{\"type\":\"unknown\",\"objectIndex\":0}]"))
    }
}
