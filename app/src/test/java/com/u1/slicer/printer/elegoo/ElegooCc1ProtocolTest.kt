package com.u1.slicer.printer.elegoo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ElegooCc1ProtocolTest {
    @Test fun parsesLengthPrefixedFramesButRejectsOtherPrefixes() {
        assertNotNull(ElegooCc1Protocol.parseFrame("123\n{\"Status\":{}}"))
        assertNull(ElegooCc1Protocol.parseFrame("junk{\"Status\":{}}"))
        assertNull(ElegooCc1Protocol.parseFrame("42"))
    }

    @Test fun statusMapsPausedProgressAndSingleExtruder() {
        val status = ElegooCc1Protocol.status(JSONObject("""{"Status":{"CurrentStatus":[1],"PrintInfo":{"Status":6,"Progress":52,"Filename":"cube.gcode","CurrentTicks":120},"TempOfNozzle":210,"TempTargetNozzle":215,"TempOfHotbed":60,"TempTargetHotbed":60}}"""))!!
        assertEquals("paused", status.state)
        assertEquals(0.52f, status.progress, 0.001f)
        assertEquals(120f, status.printDuration, 0f)
        assertEquals(210f, status.extruders.single().temp, 0f)
        assertEquals("cube.gcode", status.filename)
    }

    @Test fun mapsCompleteErrorAndBusyWithoutTreatingBusyAsIdle() {
        fun state(machine: Int, print: Int) = ElegooCc1Protocol.status(JSONObject("""{"Status":{"CurrentStatus":[$machine],"PrintInfo":{"Status":$print}}}"""))!!.state
        assertEquals("complete", state(0, 9))
        assertEquals("error", state(1, 14))
        assertEquals("busy", state(2, 0))
        assertEquals("standby", state(0, 0))
        assertNull(ElegooCc1Protocol.status(JSONObject("{}")))
    }

    @Test fun requestsHaveExplicitAllowlistedCommandAndCorrelationId() {
        val frame = JSONObject(ElegooCc1Protocol.request(ElegooCc1Protocol.Command.PAUSE, "board", "request"))
        assertEquals("sdcp/request/board", frame.getString("Topic"))
        assertEquals(129, frame.getJSONObject("Data").getInt("Cmd"))
        assertEquals("request", frame.getJSONObject("Data").getString("RequestID"))
        assertEquals("board", frame.getJSONObject("Data").getString("MainboardID"))
    }

    @Test fun acknowledgementsNeedRequestIdAndExplicitAck() {
        assertEquals("abc" to 2, ElegooCc1Protocol.acknowledgement(JSONObject("""{"Data":{"RequestID":"abc","Data":{"Ack":2}}}""")))
        assertNull(ElegooCc1Protocol.acknowledgement(JSONObject("""{"Data":{"RequestID":"abc","Data":{}}}""")))
        assertNull(ElegooCc1Protocol.acknowledgement(JSONObject("""{"Data":{"Data":{"Ack":0}}}""")))
        assertNull(ElegooCc1Protocol.acknowledgement(JSONObject("""{"Data":{"RequestID":"abc","Data":{"Ack":0.5}}}""")))
    }

    @Test fun startUsesLocalFileAndRejectsTraversal() {
        assertEquals("/local/test.gcode", ElegooCc1Protocol.startData("test.gcode").getString("Filename"))
        listOf("../test.gcode", "a/b.gcode", "a\\b.gcode", "a\ngcode", "a.3mf", "").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { ElegooCc1Protocol.startData(name) }
        }
    }

    @Test fun discoveryRejectsCarbon2AndOtherModels() {
        assertEquals("board", ElegooCc1Protocol.discoveryId(JSONObject("""{"Data":{"MachineName":"Centauri Carbon","MainboardID":"board"}}""")))
        listOf("Centauri Carbon 2", "Centauri Carbon2", "Centauri Other", "Neptune 4", "").forEach { model ->
            assertThrows(IllegalArgumentException::class.java) {
                ElegooCc1Protocol.discoveryId(JSONObject().put("Data", JSONObject().put("MachineName", model).put("MainboardID", "board")))
            }
        }
    }

    @Test fun hostAcceptsAddressButRejectsPathsAndCredentials() {
        assertEquals("192.168.1.5", normalizeCc1Host(" http://192.168.1.5/ "))
        listOf("", "host:3030", "user@host", "host/websocket", "host?x").forEach {
            assertThrows(IllegalArgumentException::class.java) { normalizeCc1Host(it) }
        }
    }
}
