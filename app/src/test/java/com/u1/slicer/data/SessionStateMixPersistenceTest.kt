package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateMixPersistenceTest {

    /** Minimal valid SessionState — only the two truly required string fields are meaningful; rest are zeroed/null/empty. */
    private fun minimalSession(
        projectMixes: List<MixedFilamentRow> = emptyList(),
    ) = SessionState(
        modelName = "x",
        rawInputPath = "/x",
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
        savedAtEpochMs = 0L,
        appVersionCode = 0,
        projectMixes = projectMixes,
    )

    private fun sampleRow(
        id: Long = 1L,
        a: Int = 1,
        b: Int = 2,
        pct: Int = 50,
        dist: MixedFilamentRow.MixDistributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
        inLib: Boolean = false,
    ) = MixedFilamentRow.fromLegacy(
        id = id, componentA = a, componentB = b, mixBPercent = pct,
        distributionMode = dist,
        label = MixedFilamentRow.autoLabel(listOf(a, b)),
        inLibrary = inLib,
    )

    @Test
    fun `roundtrip preserves empty projectMixes`() {
        val s = minimalSession()
        val json = SessionState.toJson(s)
        val back = SessionState.fromJson(json)!!
        assertEquals(emptyList<MixedFilamentRow>(), back.projectMixes)
    }

    @Test
    fun `roundtrip preserves multiple projectMixes with both distribution modes`() {
        val s = minimalSession(
            projectMixes = listOf(
                sampleRow(1L, 1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE),
                sampleRow(2L, 1, 3, 33, MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS, inLib = true),
            ),
        )
        val json = SessionState.toJson(s)
        val back = SessionState.fromJson(json)!!
        assertEquals(2, back.projectMixes.size)
        assertEquals(1L, back.projectMixes[0].id)
        assertEquals(MixedFilamentRow.MixDistributionMode.LAYER_CYCLE, back.projectMixes[0].distributionMode)
        assertEquals(2L, back.projectMixes[1].id)
        assertEquals(MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS, back.projectMixes[1].distributionMode)
        assertEquals(true, back.projectMixes[1].inLibrary)
    }

    @Test
    fun `fromJson tolerates missing projectMixes field (older state)`() {
        // An older app version's SessionState JSON won't have the field.
        // Reading it back must default to an empty list.
        val s = minimalSession()
        val json = SessionState.toJson(s)
        // Strip the field if present (simulate older format).
        val stripped = org.json.JSONObject(json).apply { remove("projectMixes") }.toString()
        val back = SessionState.fromJson(stripped)!!
        assertEquals(emptyList<MixedFilamentRow>(), back.projectMixes)
    }
}
