package com.u1.slicer.gcode

import org.json.JSONArray
import java.io.File

fun parseExcludeObjects(gcodePath: String): List<ExcludeObjectInfo> {
    val result = mutableListOf<ExcludeObjectInfo>()
    val nameRegex = Regex("""NAME=(\S+)""")
    val centerRegex = Regex("""CENTER=([\d.]+),([\d.]+)""")

    File(gcodePath).useLines { lines ->
        for (line in lines) {
            if (!line.startsWith("EXCLUDE_OBJECT_DEFINE ")) continue

            val name = nameRegex.find(line)?.groupValues?.get(1) ?: continue
            val centerMatch = centerRegex.find(line) ?: continue
            val cx = centerMatch.groupValues[1].toFloatOrNull() ?: continue
            val cy = centerMatch.groupValues[2].toFloatOrNull() ?: continue

            val polygon = mutableListOf<Pair<Float, Float>>()
            val polyIdx = line.indexOf("POLYGON=")
            if (polyIdx >= 0) {
                try {
                    val arr = JSONArray(line.substring(polyIdx + 8))
                    for (i in 0 until arr.length()) {
                        val pt = arr.getJSONArray(i)
                        polygon.add(pt.getDouble(0).toFloat() to pt.getDouble(1).toFloat())
                    }
                } catch (e: Exception) {
                    // malformed polygon — fall back to center-only
                }
            }

            result.add(ExcludeObjectInfo(name, cx to cy, polygon))
        }
    }
    return result
}
