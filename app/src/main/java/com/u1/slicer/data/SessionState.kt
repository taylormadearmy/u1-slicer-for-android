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
    val sliceJobId: Long?,
    val wasSliceComplete: Boolean,
    val savedAtEpochMs: Long,
    val appVersionCode: Int,
    // ---- F66 (schema v3) ----
    val selectedObjectIndex: Int? = null,
    val selectedVolumeIndex: Int? = null,
    val perObjectPoses: Map<Int, PerObjectPose> = emptyMap(),
    val perVolumeExtruders: Map<String, Int> = emptyMap(), // key = "objIdx:volIdx", value = 1-indexed slot
    val splitObjectOperations: List<Int> = emptyList(),    // replay order: load-time-indexed obj indices that were split
    val splitVolumeOperations: List<String> = emptyList(), // replay order: "objIdx:volIdx" entries
    // ---- M3-A (schema v3) ----
    val projectMixes: List<MixedFilamentRow> = emptyList(),
    // ---- v3 optional extension: tool space of the active/restored G-code ----
    val gcodeToolSpace: String? = null,
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
            sliceJobId == other.sliceJobId &&
            wasSliceComplete == other.wasSliceComplete &&
            savedAtEpochMs == other.savedAtEpochMs &&
            appVersionCode == other.appVersionCode &&
            selectedObjectIndex == other.selectedObjectIndex &&
            selectedVolumeIndex == other.selectedVolumeIndex &&
            perObjectPoses == other.perObjectPoses &&
            perVolumeExtruders == other.perVolumeExtruders &&
            splitObjectOperations == other.splitObjectOperations &&
            splitVolumeOperations == other.splitVolumeOperations &&
            projectMixes == other.projectMixes &&
            gcodeToolSpace == other.gcodeToolSpace
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
        result = 31 * result + (sliceJobId?.hashCode() ?: 0)
        result = 31 * result + wasSliceComplete.hashCode()
        result = 31 * result + savedAtEpochMs.hashCode()
        result = 31 * result + appVersionCode
        result = 31 * result + (selectedObjectIndex ?: 0)
        result = 31 * result + (selectedVolumeIndex ?: 0)
        result = 31 * result + perObjectPoses.hashCode()
        result = 31 * result + perVolumeExtruders.hashCode()
        result = 31 * result + splitObjectOperations.hashCode()
        result = 31 * result + splitVolumeOperations.hashCode()
        result = 31 * result + projectMixes.hashCode()
        result = 31 * result + (gcodeToolSpace?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val SCHEMA_VERSION = 3

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
            state.sliceJobId?.let { obj.put("sliceJobId", it) }
            state.gcodeToolSpace?.let { obj.put("gcodeToolSpace", it) }
            obj.put("wasSliceComplete", state.wasSliceComplete)
            obj.put("savedAtEpochMs", state.savedAtEpochMs)
            obj.put("appVersionCode", state.appVersionCode)

            // ---- F66 (v3) fields ----
            state.selectedObjectIndex?.let { obj.put("selectedObjectIndex", it) }
            state.selectedVolumeIndex?.let { obj.put("selectedVolumeIndex", it) }
            if (state.perObjectPoses.isNotEmpty()) {
                val posesObj = JSONObject()
                state.perObjectPoses.forEach { (idx, pose) ->
                    posesObj.put(idx.toString(), JSONObject().apply {
                        put("rx", pose.rotXDeg.toDouble())
                        put("ry", pose.rotYDeg.toDouble())
                        put("rz", pose.rotZDeg.toDouble())
                        put("sx", pose.scaleX.toDouble())
                        put("sy", pose.scaleY.toDouble())
                        put("sz", pose.scaleZ.toDouble())
                    })
                }
                obj.put("perObjectPoses", posesObj)
            }
            if (state.perVolumeExtruders.isNotEmpty()) {
                val extObj = JSONObject()
                state.perVolumeExtruders.forEach { (k, v) -> extObj.put(k, v) }
                obj.put("perVolumeExtruders", extObj)
            }
            if (state.splitObjectOperations.isNotEmpty()) {
                val arr = JSONArray()
                state.splitObjectOperations.forEach { arr.put(it) }
                obj.put("splitObjectOperations", arr)
            }
            if (state.splitVolumeOperations.isNotEmpty()) {
                val arr = JSONArray()
                state.splitVolumeOperations.forEach { arr.put(it) }
                obj.put("splitVolumeOperations", arr)
            }
            // ---- M3-A: project mixes ----
            val mixesArray = JSONArray()
            for (m in state.projectMixes) {
                mixesArray.put(JSONObject().apply {
                    put("id", m.id)
                    put("components", JSONArray(m.components))
                    put("weights", JSONArray(m.weights))
                    // legacy 2-way mirror so older builds still read these rows
                    put("componentA", m.componentA)
                    put("componentB", m.componentB)
                    put("mixBPercent", m.mixBPercent)
                    put("distributionMode", m.distributionMode.name)
                    put("label", m.label)
                    put("inLibrary", m.inLibrary)
                    // BETA top-surface settings (absent in old saves -> defaults on read)
                    put("topMixMode", m.topMixMode.name)
                    put("fineTopLines", m.fineTopLines)
                    put("ironingGlaze", m.ironingGlaze)
                })
            }
            obj.put("projectMixes", mixesArray)
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
                // ---- F66 (v3) fields ----
                val selectedObjectIndex =
                    if (obj.has("selectedObjectIndex")) obj.getInt("selectedObjectIndex") else null
                val selectedVolumeIndex =
                    if (obj.has("selectedVolumeIndex")) obj.getInt("selectedVolumeIndex") else null
                val perObjectPoses: Map<Int, PerObjectPose> = if (obj.has("perObjectPoses")) {
                    val posesObj = obj.getJSONObject("perObjectPoses")
                    val out = HashMap<Int, PerObjectPose>(posesObj.length())
                    val keys = posesObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val idx = k.toIntOrNull() ?: return null
                        val po = posesObj.getJSONObject(k)
                        out[idx] = PerObjectPose(
                            rotXDeg = po.getDouble("rx").toFloat(),
                            rotYDeg = po.getDouble("ry").toFloat(),
                            rotZDeg = po.getDouble("rz").toFloat(),
                            scaleX = po.getDouble("sx").toFloat(),
                            scaleY = po.getDouble("sy").toFloat(),
                            scaleZ = po.getDouble("sz").toFloat(),
                        )
                    }
                    out
                } else emptyMap()
                val perVolumeExtruders: Map<String, Int> = if (obj.has("perVolumeExtruders")) {
                    val extObj = obj.getJSONObject("perVolumeExtruders")
                    val out = HashMap<String, Int>(extObj.length())
                    val keys = extObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        out[k] = extObj.getInt(k)
                    }
                    out
                } else emptyMap()
                val splitObjectOperations: List<Int> = if (obj.has("splitObjectOperations")) {
                    val arr = obj.getJSONArray("splitObjectOperations")
                    (0 until arr.length()).map { arr.getInt(it) }
                } else emptyList()
                val splitVolumeOperations: List<String> = if (obj.has("splitVolumeOperations")) {
                    val arr = obj.getJSONArray("splitVolumeOperations")
                    (0 until arr.length()).map { arr.getString(it) }
                } else emptyList()
                // ---- M3-A: project mixes ----
                val mixesArray = obj.optJSONArray("projectMixes")
                val projectMixes: List<MixedFilamentRow> = if (mixesArray == null) emptyList() else
                    (0 until mixesArray.length()).map { i ->
                        val o = mixesArray.getJSONObject(i)
                        val mode = MixedFilamentRow.MixDistributionMode.valueOf(o.getString("distributionMode"))
                        // BETA top-surface settings — absent keys (old saves) fall back to defaults.
                        val topMixMode = try {
                            MixedFilamentRow.TopMixMode.valueOf(o.optString("topMixMode", MixedFilamentRow.TopMixMode.STRIPES.name))
                        } catch (e: IllegalArgumentException) {
                            MixedFilamentRow.TopMixMode.STRIPES
                        }
                        val fineTopLines = o.optBoolean("fineTopLines", false)
                        val ironingGlaze = o.optBoolean("ironingGlaze", false)
                        val comps = o.optJSONArray("components")
                        val row = if (comps != null) {
                            val weightsArr = o.getJSONArray("weights")
                            MixedFilamentRow(
                                id = o.getLong("id"),
                                components = (0 until comps.length()).map { comps.getInt(it) },
                                weights = (0 until weightsArr.length()).map { weightsArr.getInt(it) },
                                distributionMode = mode,
                                label = o.getString("label"),
                                inLibrary = o.getBoolean("inLibrary"),
                            )
                        } else {
                            MixedFilamentRow.fromLegacy(
                                id = o.getLong("id"),
                                componentA = o.getInt("componentA"),
                                componentB = o.getInt("componentB"),
                                mixBPercent = o.getInt("mixBPercent"),
                                distributionMode = mode,
                                label = o.getString("label"),
                                inLibrary = o.getBoolean("inLibrary"),
                            )
                        }
                        row.copy(topMixMode = topMixMode, fineTopLines = fineTopLines, ironingGlaze = ironingGlaze)
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
                    sliceJobId = if (obj.has("sliceJobId")) obj.getLong("sliceJobId") else null,
                    gcodeToolSpace = if (obj.has("gcodeToolSpace")) obj.getString("gcodeToolSpace") else null,
                    wasSliceComplete = obj.optBoolean("wasSliceComplete", false),
                    savedAtEpochMs = obj.getLong("savedAtEpochMs"),
                    appVersionCode = obj.getInt("appVersionCode"),
                    selectedObjectIndex = selectedObjectIndex,
                    selectedVolumeIndex = selectedVolumeIndex,
                    perObjectPoses = perObjectPoses,
                    perVolumeExtruders = perVolumeExtruders,
                    splitObjectOperations = splitObjectOperations,
                    splitVolumeOperations = splitVolumeOperations,
                    projectMixes = projectMixes,
                )
            } catch (e: JSONException) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }
}
