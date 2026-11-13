package com.u1.slicer.printer.elegoo

import com.u1.slicer.network.ExtruderStatus
import com.u1.slicer.network.PrinterStatus
import org.json.JSONObject

/** CC1 SDCP only. CC2 uses a different authenticated protocol. */
internal object ElegooCc1Protocol {
    enum class Command(val code: Int) { STATUS(0), ATTRIBUTES(1), START(128), PAUSE(129), CANCEL(130), RESUME(131) }

    fun parseFrame(text: String): JSONObject? = runCatching {
        val start = text.indexOf('{')
        require(start >= 0 && text.take(start).all { it.isDigit() || it.isWhitespace() })
        JSONObject(text.substring(start))
    }.getOrNull()

    fun discoveryId(frame: JSONObject): String {
        val data = frame.optJSONObject("Data") ?: error("Invalid Elegoo discovery response")
        val model = data.optString("MachineName").lowercase().filter { it.isLetterOrDigit() }
        require(model.endsWith("centauricarbon")) {
            "Only the Elegoo Centauri Carbon CC1 is supported"
        }
        return data.getString("MainboardID").also { require(it.isNotBlank()) }
    }

    fun request(command: Command, id: String, requestId: String, data: JSONObject = JSONObject()): String =
        JSONObject().put("Id", id).put("Topic", "sdcp/request/$id").put("Data", JSONObject()
            .put("Cmd", command.code).put("Data", data).put("RequestID", requestId)
            .put("MainboardID", id).put("TimeStamp", System.currentTimeMillis() / 1000).put("From", 1)).toString()

    fun validateFilename(name: String) {
        require(name.isNotBlank() && name.length <= 255 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() } && name.endsWith(".gcode", ignoreCase = true)) {
            "Use a plain .gcode filename without folders"
        }
    }

    fun startData(name: String): JSONObject {
        validateFilename(name)
        return JSONObject().put("Filename", "/local/$name").put("StartLayer", 0)
            .put("Calibration_switch", 1).put("PrintPlatformType", 0).put("Tlp_Switch", 0)
    }

    fun status(frame: JSONObject): PrinterStatus? {
        val status = frame.optJSONObject("Status") ?: frame.optJSONObject("Data")?.optJSONObject("Status") ?: return null
        val print = status.optJSONObject("PrintInfo") ?: JSONObject()
        val machine = status.optJSONArray("CurrentStatus")?.optInt(0, -1) ?: status.optInt("CurrentStatus", -1)
        val printState = print.optInt("Status", -1)
        val state = when {
            printState == 6 || printState == 26 -> "paused"
            printState == 14 || printState == 25 -> "error"
            printState == 9 -> "complete"
            printState == 8 -> "standby"
            machine == 1 || printState in setOf(1, 5, 7, 10, 11, 12, 13, 15, 16, 17, 18, 19, 20, 21, 22, 23) -> "printing"
            machine == 0 -> "standby"
            else -> "busy"
        }
        val nozzle = status.optDouble("TempOfNozzle", 0.0).toFloat()
        val nozzleTarget = status.optDouble("TempTargetNozzle", 0.0).toFloat()
        return PrinterStatus(state = state, progress = (print.optDouble("Progress", 0.0) / 100).toFloat().coerceIn(0f, 1f),
            filename = print.optString("Filename"), printDuration = print.optDouble("CurrentTicks", 0.0).toFloat(),
            nozzleTemp = nozzle, nozzleTarget = nozzleTarget, bedTemp = status.optDouble("TempOfHotbed", 0.0).toFloat(),
            bedTarget = status.optDouble("TempTargetHotbed", 0.0).toFloat(),
            extruders = listOf(ExtruderStatus(0, nozzle, nozzleTarget, true)))
    }

    fun acknowledgement(frame: JSONObject): Pair<String, Int>? {
        val data = frame.optJSONObject("Data") ?: return null
        val id = data.optString("RequestID")
        val result = data.optJSONObject("Data") ?: return null
        val ack = result.opt("Ack")
        if (id.isBlank() || (ack !is Int && ack !is Long)) return null
        val code = (ack as Number).toLong()
        if (code !in Int.MIN_VALUE..Int.MAX_VALUE) return null
        return id to code.toInt()
    }
}
