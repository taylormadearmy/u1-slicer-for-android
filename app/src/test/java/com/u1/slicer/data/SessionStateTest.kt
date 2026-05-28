package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {

    private fun sampleSession(
        customObjectPositions: FloatArray? = floatArrayOf(135f, 135f, 70f, 70f),
        customWipeTowerPos: Pair<Float, Float>? = 170f to 140f,
        additionalFiles: List<SessionState.AdditionalFile> = listOf(
            SessionState.AdditionalFile(path = "/cache/extra.stl", plateIdx = -1),
            SessionState.AdditionalFile(path = "/cache/multi.3mf", plateIdx = 2),
        ),
        selectedPlateId: Int? = 8,
        sliceJobId: Long? = 42L,
        wasSliceComplete: Boolean = false,
    ) = SessionState(
        modelName = "Buzz Lightyear.3mf",
        rawInputPath = "/cache/buzz.3mf",
        sourceModelPath = "/cache/buzz.sanitized.3mf",
        currentModelPath = "/cache/buzz.embedded.plate8.3mf",
        multiPlateSourcePath = "/cache/buzz.sanitized.3mf",
        selectedPlateId = selectedPlateId,
        modelScale = Triple(0.95f, 0.95f, 0.95f),
        modelRotation = Triple(0f, 0f, 1.5707964f),
        copyCount = 2,
        customObjectPositions = customObjectPositions,
        customWipeTowerPos = customWipeTowerPos,
        additionalFiles = additionalFiles,
        sliceJobId = sliceJobId,
        wasSliceComplete = wasSliceComplete,
        savedAtEpochMs = 1716480000000L,
        appVersionCode = 295,
    )

    @Test
    fun toJson_fromJson_roundTrip_basicFields() {
        val src = sampleSession()
        val json = SessionState.toJson(src)
        val parsed = SessionState.fromJson(json)
        assertNotNull(parsed)
        assertEquals(src.modelName, parsed!!.modelName)
        assertEquals(src.rawInputPath, parsed.rawInputPath)
        assertEquals(src.sourceModelPath, parsed.sourceModelPath)
        assertEquals(src.currentModelPath, parsed.currentModelPath)
        assertEquals(src.multiPlateSourcePath, parsed.multiPlateSourcePath)
        assertEquals(src.selectedPlateId, parsed.selectedPlateId)
        assertEquals(src.modelScale, parsed.modelScale)
        assertEquals(src.modelRotation, parsed.modelRotation)
        assertEquals(src.copyCount, parsed.copyCount)
        assertEquals(src.savedAtEpochMs, parsed.savedAtEpochMs)
        assertEquals(src.appVersionCode, parsed.appVersionCode)
    }

    @Test
    fun toJson_fromJson_roundTrip_customObjectPositions() {
        val src = sampleSession(customObjectPositions = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        val parsed = SessionState.fromJson(SessionState.toJson(src))!!
        assertNotNull(parsed.customObjectPositions)
        assertTrue(src.customObjectPositions!!.contentEquals(parsed.customObjectPositions!!))
        assertEquals(170f to 140f, parsed.customWipeTowerPos)
    }

    @Test
    fun toJson_fromJson_roundTrip_emptyAdditionalFiles() {
        val src = sampleSession(additionalFiles = emptyList())
        val parsed = SessionState.fromJson(SessionState.toJson(src))!!
        assertEquals(0, parsed.additionalFiles.size)
    }

    @Test
    fun toJson_fromJson_roundTrip_multipleAdditionalFiles() {
        val files = listOf(
            SessionState.AdditionalFile("/a.stl", -1),
            SessionState.AdditionalFile("/b.3mf", 3),
            SessionState.AdditionalFile("/c.obj", -1),
        )
        val src = sampleSession(additionalFiles = files)
        val parsed = SessionState.fromJson(SessionState.toJson(src))!!
        assertEquals(3, parsed.additionalFiles.size)
        assertEquals("/a.stl", parsed.additionalFiles[0].path)
        assertEquals(-1, parsed.additionalFiles[0].plateIdx)
        assertEquals("/b.3mf", parsed.additionalFiles[1].path)
        assertEquals(3, parsed.additionalFiles[1].plateIdx)
        assertEquals("/c.obj", parsed.additionalFiles[2].path)
    }

    @Test
    fun toJson_fromJson_roundTrip_nullablesAllNull() {
        val src = sampleSession(
            customObjectPositions = null,
            customWipeTowerPos = null,
            additionalFiles = emptyList(),
            selectedPlateId = null,
        )
        val parsed = SessionState.fromJson(SessionState.toJson(src))!!
        assertNull(parsed.customObjectPositions)
        assertNull(parsed.customWipeTowerPos)
        assertNull(parsed.selectedPlateId)
        assertEquals(0, parsed.additionalFiles.size)
    }

    @Test
    fun fromJson_malformedJson_returnsNull() {
        assertNull(SessionState.fromJson("this is not json"))
        assertNull(SessionState.fromJson(""))
        assertNull(SessionState.fromJson("{"))
    }

    @Test
    fun fromJson_missingVersionField_returnsNull() {
        val noVersion = """{"modelName":"x","rawInputPath":"/a","modelScale":{"x":1,"y":1,"z":1},"modelRotation":{"x":0,"y":0,"z":0},"copyCount":1,"savedAtEpochMs":0,"appVersionCode":0}"""
        assertNull(SessionState.fromJson(noVersion))
    }

    @Test
    fun fromJson_unknownSchemaVersion_returnsNull() {
        val futureVersion = """{"version":99,"modelName":"x","rawInputPath":"/a","modelScale":{"x":1,"y":1,"z":1},"modelRotation":{"x":0,"y":0,"z":0},"copyCount":1,"savedAtEpochMs":0,"appVersionCode":0}"""
        assertNull(SessionState.fromJson(futureVersion))
    }

    @Test
    fun fromJson_missingRequiredModelName_returnsNull() {
        val noName = """{"version":3,"rawInputPath":"/a","modelScale":{"x":1,"y":1,"z":1},"modelRotation":{"x":0,"y":0,"z":0},"copyCount":1,"savedAtEpochMs":0,"appVersionCode":0}"""
        assertNull(SessionState.fromJson(noName))
    }

    @Test
    fun fromJson_missingRequiredRawInputPath_returnsNull() {
        val noPath = """{"version":3,"modelName":"x","modelScale":{"x":1,"y":1,"z":1},"modelRotation":{"x":0,"y":0,"z":0},"copyCount":1,"savedAtEpochMs":0,"appVersionCode":0}"""
        assertNull(SessionState.fromJson(noPath))
    }

    @Test
    fun fromJson_oddLengthCustomObjectPositions_returnsNull() {
        val odd = """{"version":3,"modelName":"x","rawInputPath":"/a","modelScale":{"x":1,"y":1,"z":1},"modelRotation":{"x":0,"y":0,"z":0},"copyCount":1,"customObjectPositions":[1,2,3],"additionalFiles":[],"savedAtEpochMs":0,"appVersionCode":0}"""
        assertNull(SessionState.fromJson(odd))
    }

    @Test
    fun fromJson_pastSchemaVersion_returnsNull() {
        val past = """{"version":1,"modelName":"x","rawInputPath":"/a","modelScale":{"x":1,"y":1,"z":1},"modelRotation":{"x":0,"y":0,"z":0},"copyCount":1,"savedAtEpochMs":0,"appVersionCode":0}"""
        assertNull(SessionState.fromJson(past))
    }

    @Test
    fun toJson_fromJson_roundTrip_sliceJobIdAndWasSliceComplete() {
        val src = sampleSession(sliceJobId = 12345L)
            .copy(wasSliceComplete = true)
        val parsed = SessionState.fromJson(SessionState.toJson(src))!!
        assertEquals(12345L, parsed.sliceJobId)
        assertEquals(true, parsed.wasSliceComplete)
    }

    @Test
    fun toJson_fromJson_roundTrip_nullSliceJobIdAndFalseWasSliceComplete() {
        val src = sampleSession(sliceJobId = null).copy(wasSliceComplete = false)
        val parsed = SessionState.fromJson(SessionState.toJson(src))!!
        assertNull(parsed.sliceJobId)
        assertEquals(false, parsed.wasSliceComplete)
    }
}
