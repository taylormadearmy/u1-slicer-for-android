package com.u1.slicer.bambu.snapshot

import org.junit.Test
import org.junit.Assert.*

class NativeBambuSnapshotTest {
    @Test
    fun `parses native JSON dump into BambuFileSnapshot`() {
        val nativeJson = """
            {
              "source": "fixture.3mf",
              "isBbl": true,
              "fileVersion": "1.9.0",
              "plates": [
                {
                  "plateIndex": 1,
                  "filamentColours": ["#FF0000", "#00FF00"],
                  "filamentSettingsIds": ["Bambu PLA Basic", "Bambu PLA Basic"],
                  "objectInstanceMap": [{"objectId": 5, "instanceId": 0}],
                  "customGcode": [{"printZ": 1.2, "type": "ToolChange", "extruder": 2, "color": "#00FF00"}],
                  "plateConfig": {"bed_type": "Cool Plate"}
                }
              ],
              "objects": [{"objectId": 5, "name": "body", "extruder": 1, "sourcePath": ""}],
              "volumes": [{
                "objectId": 5, "volumeIndex": 0, "extruder": null,
                "paintStateSet": {"1": 240, "2": 96},
                "paintSupportsStateSet": {},
                "isMmPainted": true, "isSeamPainted": false
              }]
            }
        """.trimIndent()

        val snapshot = NativeBambuSnapshot.parse(nativeJson)

        assertEquals("fixture.3mf", snapshot.source)
        assertTrue(snapshot.isBbl)
        assertEquals(1, snapshot.plates.size)
        assertEquals(listOf("#FF0000", "#00FF00"), snapshot.plates[0].filamentColours)
        assertEquals(1, snapshot.objects.size)
        assertEquals(1, snapshot.volumes.size)
        assertEquals(mapOf(1 to 240, 2 to 96), snapshot.volumes[0].paintStateSet)
        assertNull(snapshot.volumes[0].extruder)
    }

    @Test
    fun `returns empty snapshot when native call returns null`() {
        val snapshot = NativeBambuSnapshot.parseOrEmpty(null, fallbackSource = "x.3mf")
        assertEquals("x.3mf", snapshot.source)
        assertTrue(snapshot.plates.isEmpty())
    }
}
