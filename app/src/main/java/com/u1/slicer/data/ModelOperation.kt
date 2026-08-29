package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Structural mutations made to the native model after it was loaded.
 *
 * Indices deliberately refer to the model as it exists immediately before the
 * operation. Keeping one ordered log is essential: a delete changes every
 * subsequent object index, so independently replaying splits, copies, and
 * deletes cannot faithfully reconstruct the model.
 */
sealed interface ModelOperation {
    val objectIndex: Int

    data class SplitObject(override val objectIndex: Int) : ModelOperation
    data class SplitVolume(override val objectIndex: Int, val volumeIndex: Int) : ModelOperation
    data class DuplicateObject(override val objectIndex: Int) : ModelOperation
    data class DeleteObject(override val objectIndex: Int) : ModelOperation

    companion object {
        fun toJsonArray(operations: List<ModelOperation>): String = JSONArray().apply {
            operations.forEach { operation ->
                put(JSONObject().apply {
                    put("type", when (operation) {
                        is SplitObject -> "splitObject"
                        is SplitVolume -> "splitVolume"
                        is DuplicateObject -> "duplicateObject"
                        is DeleteObject -> "deleteObject"
                    })
                    put("objectIndex", operation.objectIndex)
                    if (operation is SplitVolume) put("volumeIndex", operation.volumeIndex)
                })
            }
        }.toString()

        /** Returns null rather than partially replaying a corrupt structural history. */
        fun fromJsonArray(json: String): List<ModelOperation>? {
            return try {
                val array = JSONArray(json)
                val operations = ArrayList<ModelOperation>(array.length())
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val objectIndex = item.optInt("objectIndex", -1)
                    if (objectIndex < 0) return null
                    when (item.getString("type")) {
                        "splitObject" -> operations += SplitObject(objectIndex)
                        "splitVolume" -> {
                            val volumeIndex = item.optInt("volumeIndex", -1)
                            if (volumeIndex < 0) return null
                            operations += SplitVolume(objectIndex, volumeIndex)
                        }
                        "duplicateObject" -> operations += DuplicateObject(objectIndex)
                        "deleteObject" -> operations += DeleteObject(objectIndex)
                        else -> return null
                    }
                }
                operations
            } catch (_: Exception) {
                null
            }
        }
    }
}
