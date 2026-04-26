package com.u1.slicer.bambu.snapshot

import org.junit.Test
import org.junit.Assert.assertEquals

class BambuFileSnapshotTest {
    @Test
    fun `round-trips through JSON`() {
        val snapshot = BambuFileSnapshot(
            source = "fixture.3mf",
            isBbl = true,
            fileVersion = "1.9.0",
            plates = listOf(
                PlateSnapshot(
                    plateIndex = 1,
                    filamentColours = listOf("#FF0000", "#00FF00"),
                    filamentSettingsIds = listOf("Bambu PLA Basic", "Bambu PLA Basic"),
                    objectInstanceMap = listOf(ObjectInstance(objectId = 5, instanceId = 0)),
                    customGcode = listOf(
                        CustomGcodeEntry(printZ = 1.2, type = "ToolChange", extruder = 2, color = "#00FF00")
                    ),
                    plateConfig = mapOf("bed_type" to "Cool Plate")
                )
            ),
            objects = listOf(
                ObjectSnapshot(objectId = 5, name = "body", extruder = 1, sourcePath = "")
            ),
            volumes = listOf(
                VolumeSnapshot(
                    objectId = 5,
                    volumeIndex = 0,
                    extruder = null,
                    paintStateSet = mapOf(1 to 240, 2 to 96),
                    paintSupportsStateSet = emptyMap(),
                    isMmPainted = true,
                    isSeamPainted = false
                )
            )
        )
        val json = BambuFileSnapshotJson.encode(snapshot)
        val decoded = BambuFileSnapshotJson.decode(json)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `decodes empty arrays as empty not null`() {
        val json = """{"source":"x","isBbl":false,"fileVersion":"","plates":[],"objects":[],"volumes":[]}"""
        val decoded = BambuFileSnapshotJson.decode(json)
        assertEquals(emptyList<PlateSnapshot>(), decoded.plates)
        assertEquals(emptyList<ObjectSnapshot>(), decoded.objects)
        assertEquals(emptyList<VolumeSnapshot>(), decoded.volumes)
    }
}
