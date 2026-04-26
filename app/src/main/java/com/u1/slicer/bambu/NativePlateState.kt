package com.u1.slicer.bambu

import org.json.JSONArray

/**
 * Plate state read FROM native after [NativeLibrary.loadModel] / [NativeLibrary.loadModelForPlate].
 * Single source of truth for per-plate extruder assignments and paint data.
 * Replaces the Kotlin-side [SlicerViewModel.buildSelectedPlateInfo] synthesis.
 */
data class NativePlateState(
    /** All extruders used by volumes on this plate (1-based, sorted). */
    val usedExtruders: Set<Int>,
    /** True if any volume has MMU paint segmentation data. */
    val hasPaintData: Boolean,
    /** Per-object volume breakdown. */
    val objects: List<ObjectEntry>
) {
    data class VolumeEntry(
        val volumeIndex: Int,
        val extruder: Int,
        val isMmPainted: Boolean,
        val isSeamPainted: Boolean
    )

    data class ObjectEntry(
        val objectIndex: Int,
        val objectExtruder: Int,
        val volumes: List<VolumeEntry>
    )

    /**
     * Build an object-extruder map compatible with [ThreeMfInfo.objectExtruderMap].
     * Key: objectIndex as string. Value: max extruder across volumes (or object default).
     */
    fun buildObjectExtruderMap(): Map<String, Int> = objects.associate { obj ->
        val maxVolumeExt = obj.volumes
            .map { if (it.extruder > 0) it.extruder else obj.objectExtruder }
            .filter { it > 0 }
            .maxOrNull() ?: obj.objectExtruder
        obj.objectIndex.toString() to maxVolumeExt
    }

    companion object {
        /**
         * Parse the JSON returned by [NativeLibrary.nativeGetAllVolumeExtruders].
         * Returns an empty state for null or empty input.
         */
        fun parseVolumeMapJson(json: String?): NativePlateState {
            if (json.isNullOrBlank()) return NativePlateState(
                usedExtruders = emptySet(), hasPaintData = false, objects = emptyList()
            )
            val arr = JSONArray(json)
            val objects = mutableListOf<ObjectEntry>()
            val allExtruders = mutableSetOf<Int>()
            var hasPaint = false

            for (i in 0 until arr.length()) {
                val objJson = arr.optJSONObject(i) ?: continue
                val objExt = objJson.optInt("objectExtruder", 0)
                val volsJson = objJson.optJSONArray("volumes") ?: continue
                val volumes = mutableListOf<VolumeEntry>()

                for (j in 0 until volsJson.length()) {
                    val vj = volsJson.optJSONObject(j) ?: continue
                    val volExt = vj.optInt("extruder", -1)
                    val mmPainted = vj.optBoolean("isMmPainted", false)
                    val seamPainted = vj.optBoolean("isSeamPainted", false)
                    volumes.add(VolumeEntry(vj.optInt("volumeIndex", j), volExt, mmPainted, seamPainted))

                    val effective = if (volExt > 0) volExt else objExt
                    if (effective > 0) allExtruders.add(effective)
                    if (mmPainted) hasPaint = true
                }
                objects.add(ObjectEntry(objJson.optInt("objectIndex", i), objExt, volumes))
            }

            return NativePlateState(
                usedExtruders = allExtruders.toSortedSet(),
                hasPaintData = hasPaint,
                objects = objects
            )
        }
    }
}
