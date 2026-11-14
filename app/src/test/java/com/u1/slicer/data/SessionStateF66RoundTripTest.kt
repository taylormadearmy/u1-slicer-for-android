package com.u1.slicer.data

import org.junit.Assert.*
import org.junit.Test

/**
 * F66 v3 schema round-trip coverage. Verifies that every new F66 field
 * (selectedObjectIndex/selectedVolumeIndex/perObjectPoses/perVolumeExtruders/
 * splitObjectOperations/splitVolumeOperations) survives toJson → fromJson with
 * exact equality.
 */
class SessionStateF66RoundTripTest {

    private fun baseSession(
        selectedObjectIndex: Int? = null,
        selectedVolumeIndex: Int? = null,
        perObjectPoses: Map<Int, PerObjectPose> = emptyMap(),
        perVolumeExtruders: Map<String, Int> = emptyMap(),
        splitObjectOperations: List<Int> = emptyList(),
        splitVolumeOperations: List<String> = emptyList(),
    ) = SessionState(
        modelName = "Skadis.3mf",
        rawInputPath = "/sd/Skadis.3mf",
        sourceModelPath = null,
        currentModelPath = null,
        multiPlateSourcePath = null,
        selectedPlateId = null,
        modelScale = Triple(1f, 1f, 1f),
        modelRotation = Triple(0f, 0f, 0f),
        copyCount = 1,
        customObjectPositions = null,
        customWipeTowerPos = null,
        additionalFiles = emptyList(),
        sliceJobId = null,
        wasSliceComplete = false,
        savedAtEpochMs = 1L,
        appVersionCode = 1,
        selectedObjectIndex = selectedObjectIndex,
        selectedVolumeIndex = selectedVolumeIndex,
        perObjectPoses = perObjectPoses,
        perVolumeExtruders = perVolumeExtruders,
        splitObjectOperations = splitObjectOperations,
        splitVolumeOperations = splitVolumeOperations,
    )

    @Test
    fun roundTrip_emptyF66Fields_preservesEverything() {
        val src = baseSession()
        val restored = SessionState.fromJson(SessionState.toJson(src))
        assertEquals(src, restored)
    }

    @Test
    fun roundTrip_selectedObjectIndex_only() {
        val src = baseSession(selectedObjectIndex = 3)
        val restored = SessionState.fromJson(SessionState.toJson(src))!!
        assertEquals(3, restored.selectedObjectIndex)
        assertNull(restored.selectedVolumeIndex)
    }

    @Test
    fun roundTrip_selectedVolumeIndex_setsBoth() {
        val src = baseSession(selectedObjectIndex = 1, selectedVolumeIndex = 0)
        val restored = SessionState.fromJson(SessionState.toJson(src))!!
        assertEquals(1, restored.selectedObjectIndex)
        assertEquals(0, restored.selectedVolumeIndex)
    }

    @Test
    fun roundTrip_perObjectPoses_preservesEveryField() {
        val poses = mapOf(
            0 to PerObjectPose(rotZDeg = 45f),
            3 to PerObjectPose(scaleX = 1.2f, scaleY = 1.2f, scaleZ = 1.2f),
            7 to PerObjectPose(
                rotXDeg = 10f, rotYDeg = 20f, rotZDeg = 30f,
                scaleX = 0.5f, scaleY = 0.5f, scaleZ = 0.5f,
            ),
        )
        val src = baseSession(perObjectPoses = poses)
        val restored = SessionState.fromJson(SessionState.toJson(src))!!
        assertEquals(poses, restored.perObjectPoses)
    }

    @Test
    fun roundTrip_perVolumeExtruders_preservesKeysAndSlots() {
        val ext = mapOf("3:0" to 2, "3:1" to 3, "0:0" to 1)
        val src = baseSession(perVolumeExtruders = ext)
        val restored = SessionState.fromJson(SessionState.toJson(src))!!
        assertEquals(ext, restored.perVolumeExtruders)
    }

    @Test
    fun roundTrip_splitObjectOperations_preservesOrder() {
        val ops = listOf(2, 5, 0, 3)
        val src = baseSession(splitObjectOperations = ops)
        val restored = SessionState.fromJson(SessionState.toJson(src))!!
        assertEquals(ops, restored.splitObjectOperations)
    }

    @Test
    fun roundTrip_splitVolumeOperations_preservesOrderedStrings() {
        val ops = listOf("4:0", "4:1", "2:3")
        val src = baseSession(splitVolumeOperations = ops)
        val restored = SessionState.fromJson(SessionState.toJson(src))!!
        assertEquals(ops, restored.splitVolumeOperations)
    }

    @Test
    fun roundTrip_modelOperations_preservesInterleavedDeleteHistory() {
        val operations = listOf(
            ModelOperation.SplitObject(3),
            ModelOperation.DuplicateObject(3),
            ModelOperation.DeleteObject(1),
            ModelOperation.SplitVolume(2, 0),
        )
        val restored = SessionState.fromJson(SessionState.toJson(
            baseSession().copy(modelOperations = operations)
        ))!!

        assertEquals(operations, restored.modelOperations)
    }

    @Test
    fun roundTrip_f99DestinationsAndF101Recipe_preserved() {
        val src = baseSession().copy(
            adaptiveLayerHeight = AdaptiveLayerHeightState(
                AdaptiveLayerHeightState.Mode.ADAPTIVE,
                AdaptiveLayerHeightState.Preset.DETAIL,
            ),
            canonicalColourRemaps = listOf(
                CanonicalColourRemap(0, CanonicalColourDestination.PhysicalSlot(2)),
                CanonicalColourRemap(3, CanonicalColourDestination.Mix(42L)),
            ),
        )
        assertEquals(src, SessionState.fromJson(SessionState.toJson(src)))
    }

    @Test
    fun roundTrip_fullFatSession_allFieldsPreserved() {
        val src = baseSession(
            selectedObjectIndex = 5,
            selectedVolumeIndex = 2,
            perObjectPoses = mapOf(
                0 to PerObjectPose(rotZDeg = 90f),
                5 to PerObjectPose(scaleX = 0.75f, scaleY = 0.75f, scaleZ = 0.75f),
            ),
            perVolumeExtruders = mapOf("5:0" to 1, "5:1" to 2, "5:2" to 3),
            splitObjectOperations = listOf(0, 5),
            splitVolumeOperations = listOf("5:1"),
        )
        val restored = SessionState.fromJson(SessionState.toJson(src))
        assertEquals(src, restored)
    }

    @Test
    fun fromJson_v2Schema_returnsNull() {
        // v2 sessions (pre-F66) intentionally don't restore — F89 returns null on
        // any schema version that isn't SCHEMA_VERSION. Acceptable because users
        // upgrade forward; a downgrade loses one session, not data.
        val v2Json = """{"version":2,"modelName":"x","rawInputPath":"/a","modelScale":{"x":1,"y":1,"z":1},"modelRotation":{"x":0,"y":0,"z":0},"copyCount":1,"savedAtEpochMs":0,"appVersionCode":0}"""
        assertNull(SessionState.fromJson(v2Json))
    }

    @Test
    fun fromJson_malformedPerObjectPosesKey_returnsNull() {
        // Non-integer key in perObjectPoses → malformed → null.
        val badKey = """{"version":3,"modelName":"x","rawInputPath":"/a","modelScale":{"x":1,"y":1,"z":1},"modelRotation":{"x":0,"y":0,"z":0},"copyCount":1,"savedAtEpochMs":0,"appVersionCode":0,"perObjectPoses":{"abc":{"rx":0,"ry":0,"rz":0,"sx":1,"sy":1,"sz":1}}}"""
        assertNull(SessionState.fromJson(badKey))
    }
}
