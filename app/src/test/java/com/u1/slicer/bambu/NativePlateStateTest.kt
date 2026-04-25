package com.u1.slicer.bambu

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativePlateStateTest {

    @Test
    fun `parseVolumeMapJson extracts extruders from single object`() {
        val json = """[{
            "objectIndex": 0, "objectExtruder": 1,
            "volumes": [
                {"volumeIndex": 0, "extruder": 1, "isMmPainted": false, "isSeamPainted": false},
                {"volumeIndex": 1, "extruder": 2, "isMmPainted": false, "isSeamPainted": false}
            ]
        }]"""
        val state = NativePlateState.parseVolumeMapJson(json)
        assertEquals(setOf(1, 2), state.usedExtruders)
        assertFalse(state.hasPaintData)
        assertEquals(1, state.objects.size)
        assertEquals(2, state.objects[0].volumes.size)
    }

    @Test
    fun `parseVolumeMapJson detects paint data`() {
        val json = """[{
            "objectIndex": 0, "objectExtruder": 1,
            "volumes": [
                {"volumeIndex": 0, "extruder": 1, "isMmPainted": true, "isSeamPainted": false}
            ]
        }]"""
        val state = NativePlateState.parseVolumeMapJson(json)
        assertTrue(state.hasPaintData)
    }

    @Test
    fun `parseVolumeMapJson handles compound object with three extruders`() {
        val json = """[{
            "objectIndex": 0, "objectExtruder": 1,
            "volumes": [
                {"volumeIndex": 0, "extruder": 1, "isMmPainted": false, "isSeamPainted": false},
                {"volumeIndex": 1, "extruder": 2, "isMmPainted": false, "isSeamPainted": false},
                {"volumeIndex": 2, "extruder": 3, "isMmPainted": false, "isSeamPainted": false}
            ]
        }]"""
        val state = NativePlateState.parseVolumeMapJson(json)
        assertEquals(setOf(1, 2, 3), state.usedExtruders)
        assertEquals(3, state.objects[0].volumes.size)
    }

    @Test
    fun `parseVolumeMapJson inherits object extruder when volume is -1`() {
        val json = """[{
            "objectIndex": 0, "objectExtruder": 2,
            "volumes": [
                {"volumeIndex": 0, "extruder": -1, "isMmPainted": false, "isSeamPainted": false}
            ]
        }]"""
        val state = NativePlateState.parseVolumeMapJson(json)
        assertEquals(setOf(2), state.usedExtruders)
    }

    @Test
    fun `parseVolumeMapJson returns empty state for null input`() {
        val state = NativePlateState.parseVolumeMapJson(null)
        assertTrue(state.usedExtruders.isEmpty())
        assertFalse(state.hasPaintData)
        assertTrue(state.objects.isEmpty())
    }

    @Test
    fun `parseVolumeMapJson handles multiple objects`() {
        val json = """[
            {"objectIndex": 0, "objectExtruder": 1, "volumes": [
                {"volumeIndex": 0, "extruder": 1, "isMmPainted": false, "isSeamPainted": false}
            ]},
            {"objectIndex": 1, "objectExtruder": 3, "volumes": [
                {"volumeIndex": 0, "extruder": 3, "isMmPainted": false, "isSeamPainted": false}
            ]}
        ]"""
        val state = NativePlateState.parseVolumeMapJson(json)
        assertEquals(setOf(1, 3), state.usedExtruders)
        assertEquals(2, state.objects.size)
    }

    @Test
    fun `buildObjectExtruderMap produces per-object max extruder`() {
        val json = """[{
            "objectIndex": 0, "objectExtruder": 1,
            "volumes": [
                {"volumeIndex": 0, "extruder": 1, "isMmPainted": false, "isSeamPainted": false},
                {"volumeIndex": 1, "extruder": 3, "isMmPainted": false, "isSeamPainted": false}
            ]
        }]"""
        val state = NativePlateState.parseVolumeMapJson(json)
        val map = state.buildObjectExtruderMap()
        assertEquals(mapOf("0" to 3), map)
    }
}
