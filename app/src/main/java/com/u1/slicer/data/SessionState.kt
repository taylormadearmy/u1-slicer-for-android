package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Ephemeral Prepare-screen state persisted across process death so F89 can
 * offer a Resume banner on next launch. JSON-serialized into a single
 * `session_state_json` key in the shared `u1_slicer_settings` DataStore.
 *
 * `fromJson` returns null on any parse failure (malformed JSON, missing
 * required fields, unknown schema version) — callers treat null identically
 * to "no session". The bad blob stays in DataStore until the next mutation
 * overwrites it. We'd rather lose a session than crash on a malformed read.
 */
data class SessionState(
    val modelName: String,
    val rawInputPath: String,
    val sourceModelPath: String?,
    val currentModelPath: String?,
    val multiPlateSourcePath: String?,
    val selectedPlateId: Int?,
    val modelScale: Triple<Float, Float, Float>,
    val modelRotation: Triple<Float, Float, Float>,
    val copyCount: Int,
    val customObjectPositions: FloatArray?,
    val customWipeTowerPos: Pair<Float, Float>?,
    val additionalFiles: List<AdditionalFile>,
    val savedAtEpochMs: Long,
    val appVersionCode: Int,
) {
    data class AdditionalFile(val path: String, val plateIdx: Int)

    // FloatArray needs content-based equality for the data class contract.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionState) return false
        return modelName == other.modelName &&
            rawInputPath == other.rawInputPath &&
            sourceModelPath == other.sourceModelPath &&
            currentModelPath == other.currentModelPath &&
            multiPlateSourcePath == other.multiPlateSourcePath &&
            selectedPlateId == other.selectedPlateId &&
            modelScale == other.modelScale &&
            modelRotation == other.modelRotation &&
            copyCount == other.copyCount &&
            ((customObjectPositions == null && other.customObjectPositions == null) ||
                (customObjectPositions != null && other.customObjectPositions != null &&
                    customObjectPositions.contentEquals(other.customObjectPositions))) &&
            customWipeTowerPos == other.customWipeTowerPos &&
            additionalFiles == other.additionalFiles &&
            savedAtEpochMs == other.savedAtEpochMs &&
            appVersionCode == other.appVersionCode
    }

    override fun hashCode(): Int {
        var result = modelName.hashCode()
        result = 31 * result + rawInputPath.hashCode()
        result = 31 * result + (sourceModelPath?.hashCode() ?: 0)
        result = 31 * result + (currentModelPath?.hashCode() ?: 0)
        result = 31 * result + (multiPlateSourcePath?.hashCode() ?: 0)
        result = 31 * result + (selectedPlateId ?: 0)
        result = 31 * result + modelScale.hashCode()
        result = 31 * result + modelRotation.hashCode()
        result = 31 * result + copyCount
        result = 31 * result + (customObjectPositions?.contentHashCode() ?: 0)
        result = 31 * result + (customWipeTowerPos?.hashCode() ?: 0)
        result = 31 * result + additionalFiles.hashCode()
        result = 31 * result + savedAtEpochMs.hashCode()
        result = 31 * result + appVersionCode
        return result
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun toJson(state: SessionState): String {
            val obj = JSONObject()
            obj.put("version", SCHEMA_VERSION)
            obj.put("modelName", state.modelName)
            obj.put("rawInputPath", state.rawInputPath)
            state.sourceModelPath?.let { obj.put("sourceModelPath", it) }
            state.currentModelPath?.let { obj.put("currentModelPath", it) }
            state.multiPlateSourcePath?.let { obj.put("multiPlateSourcePath", it) }
            state.selectedPlateId?.let { obj.put("selectedPlateId", it) }
            obj.put("modelScale", JSONObject().apply {
                put("x", state.modelScale.first.toDouble())
                put("y", state.modelScale.second.toDouble())
                put("z", state.modelScale.third.toDouble())
            })
            obj.put("modelRotation", JSONObject().apply {
                put("x", state.modelRotation.first.toDouble())
                put("y", state.modelRotation.second.toDouble())
                put("z", state.modelRotation.third.toDouble())
            })
            obj.put("copyCount", state.copyCount)
            state.customObjectPositions?.let { arr ->
                val ja = JSONArray()
                arr.forEach { ja.put(it.toDouble()) }
                obj.put("customObjectPositions", ja)
            }
            state.customWipeTowerPos?.let { (x, y) ->
                obj.put("customWipeTowerPos", JSONObject().apply {
                    put("x", x.toDouble())
                    put("y", y.toDouble())
                })
            }
            val filesArr = JSONArray()
            state.additionalFiles.forEach { f ->
                filesArr.put(JSONObject().apply {
                    put("path", f.path)
                    put("plateIdx", f.plateIdx)
                })
            }
            obj.put("additionalFiles", filesArr)
            obj.put("savedAtEpochMs", state.savedAtEpochMs)
            obj.put("appVersionCode", state.appVersionCode)
            return obj.toString()
        }

        fun fromJson(json: String): SessionState? {
            return try {
                val obj = JSONObject(json)
                val version = if (obj.has("version")) obj.getInt("version") else return null
                if (version != SCHEMA_VERSION) return null
                val modelName = if (obj.has("modelName")) obj.getString("modelName") else return null
                val rawInputPath = if (obj.has("rawInputPath")) obj.getString("rawInputPath") else return null
                val scaleObj = obj.getJSONObject("modelScale")
                val rotObj = obj.getJSONObject("modelRotation")
                val customPositions = if (obj.has("customObjectPositions")) {
                    val ja = obj.getJSONArray("customObjectPositions")
                    // Downstream restore reads (x, y) PAIRS — odd-length is malformed.
                    if (ja.length() % 2 != 0) return null
                    FloatArray(ja.length()) { i -> ja.getDouble(i).toFloat() }
                } else null
                val customTower = if (obj.has("customWipeTowerPos")) {
                    val o = obj.getJSONObject("customWipeTowerPos")
                    o.getDouble("x").toFloat() to o.getDouble("y").toFloat()
                } else null
                val filesArr = obj.optJSONArray("additionalFiles")
                val files = if (filesArr == null) emptyList() else (0 until filesArr.length()).map { i ->
                    val f = filesArr.getJSONObject(i)
                    AdditionalFile(path = f.getString("path"), plateIdx = f.getInt("plateIdx"))
                }
                SessionState(
                    modelName = modelName,
                    rawInputPath = rawInputPath,
                    sourceModelPath = if (obj.has("sourceModelPath")) obj.getString("sourceModelPath") else null,
                    currentModelPath = if (obj.has("currentModelPath")) obj.getString("currentModelPath") else null,
                    multiPlateSourcePath = if (obj.has("multiPlateSourcePath")) obj.getString("multiPlateSourcePath") else null,
                    selectedPlateId = if (obj.has("selectedPlateId")) obj.getInt("selectedPlateId") else null,
                    modelScale = Triple(scaleObj.getDouble("x").toFloat(), scaleObj.getDouble("y").toFloat(), scaleObj.getDouble("z").toFloat()),
                    modelRotation = Triple(rotObj.getDouble("x").toFloat(), rotObj.getDouble("y").toFloat(), rotObj.getDouble("z").toFloat()),
                    copyCount = obj.getInt("copyCount"),
                    customObjectPositions = customPositions,
                    customWipeTowerPos = customTower,
                    additionalFiles = files,
                    savedAtEpochMs = obj.getLong("savedAtEpochMs"),
                    appVersionCode = obj.getInt("appVersionCode"),
                )
            } catch (e: JSONException) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }
}
