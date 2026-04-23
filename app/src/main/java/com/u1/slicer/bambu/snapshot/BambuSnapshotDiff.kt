package com.u1.slicer.bambu.snapshot

data class Disagreement(
    val path: String,
    val kotlinValue: String,
    val nativeValue: String
)

object BambuSnapshotDiff {

    fun diff(kotlin: BambuFileSnapshot, native: BambuFileSnapshot): List<Disagreement> {
        val out = mutableListOf<Disagreement>()
        cmp(out, "isBbl", kotlin.isBbl, native.isBbl)
        cmp(out, "fileVersion", kotlin.fileVersion, native.fileVersion)
        if (kotlin.plates.size != native.plates.size) {
            out += Disagreement("plates.size", "${kotlin.plates.size}", "${native.plates.size}")
        } else {
            kotlin.plates.zip(native.plates).forEachIndexed { i, (k, n) -> diffPlate(out, "plates[$i]", k, n) }
        }
        diffObjects(out, kotlin.objects, native.objects)
        diffVolumes(out, kotlin.volumes, native.volumes)
        return out
    }

    private fun diffPlate(out: MutableList<Disagreement>, base: String, k: PlateSnapshot, n: PlateSnapshot) {
        cmp(out, "$base.plateIndex", k.plateIndex, n.plateIndex)
        diffStringList(out, "$base.filamentColours", k.filamentColours, n.filamentColours)
        diffStringList(out, "$base.filamentSettingsIds", k.filamentSettingsIds, n.filamentSettingsIds)
        if (k.objectInstanceMap.toSet() != n.objectInstanceMap.toSet()) {
            out += Disagreement("$base.objectInstanceMap", "${k.objectInstanceMap}", "${n.objectInstanceMap}")
        }
        if (k.customGcode.size != n.customGcode.size) {
            out += Disagreement("$base.customGcode.size", "${k.customGcode.size}", "${n.customGcode.size}")
        } else {
            k.customGcode.zip(n.customGcode).forEachIndexed { i, (a, b) ->
                if (a != b) out += Disagreement("$base.customGcode[$i]", "$a", "$b")
            }
        }
        diffStringMap(out, "$base.plateConfig", k.plateConfig, n.plateConfig)
    }

    private fun diffObjects(out: MutableList<Disagreement>, k: List<ObjectSnapshot>, n: List<ObjectSnapshot>) {
        if (k.size != n.size) {
            out += Disagreement("objects.size", "${k.size}", "${n.size}")
            return
        }
        // Compare by objectId to be order-independent.
        val kMap = k.associateBy { it.objectId }
        val nMap = n.associateBy { it.objectId }
        (kMap.keys + nMap.keys).sorted().forEach { id ->
            val ko = kMap[id]; val no = nMap[id]
            if (ko == null || no == null) {
                out += Disagreement("objects[$id]", "$ko", "$no")
            } else {
                cmp(out, "objects[$id].extruder", ko.extruder, no.extruder)
                cmp(out, "objects[$id].name", ko.name, no.name)
            }
        }
    }

    private fun diffVolumes(out: MutableList<Disagreement>, k: List<VolumeSnapshot>, n: List<VolumeSnapshot>) {
        // Size mismatch is a single top-level disagreement — emit once and stop.
        // Without this, the prior code emitted volumes[0]..volumes[N-1] every time
        // one side returned empty (common case: KotlinBambuSnapshot.volumes = emptyList()
        // while native populates from g_model), creating ~420 brittle per-index baseline
        // entries that would re-index if upstream Orca reordered ModelObject::volumes.
        if (k.size != n.size) {
            out += Disagreement("volumes.size", "${k.size}", "${n.size}")
            return
        }
        // Sizes match: index by (objectId, volumeIndex) for order-independent match.
        val key: (VolumeSnapshot) -> Pair<Int, Int> = { it.objectId to it.volumeIndex }
        val kMap = k.associateBy(key); val nMap = n.associateBy(key)
        (kMap.keys + nMap.keys).sortedWith(compareBy({ it.first }, { it.second })).forEachIndexed { i, vk ->
            val ko = kMap[vk]; val no = nMap[vk]
            val base = "volumes[$i]"
            if (ko == null || no == null) {
                out += Disagreement(base, "$ko", "$no"); return@forEachIndexed
            }
            cmp(out, "$base.extruder", ko.extruder, no.extruder)
            cmp(out, "$base.isMmPainted", ko.isMmPainted, no.isMmPainted)
            cmp(out, "$base.isSeamPainted", ko.isSeamPainted, no.isSeamPainted)
            (ko.paintStateSet.keys + no.paintStateSet.keys).sorted().forEach { st ->
                val a = ko.paintStateSet[st]; val b = no.paintStateSet[st]
                if (a != b) out += Disagreement("$base.paintStateSet[$st]", "$a", "$b")
            }
            (ko.paintSupportsStateSet.keys + no.paintSupportsStateSet.keys).sorted().forEach { st ->
                val a = ko.paintSupportsStateSet[st]; val b = no.paintSupportsStateSet[st]
                if (a != b) out += Disagreement("$base.paintSupportsStateSet[$st]", "$a", "$b")
            }
        }
    }

    private fun diffStringList(out: MutableList<Disagreement>, base: String, k: List<String>, n: List<String>) {
        if (k.size != n.size) {
            out += Disagreement("$base.size", "${k.size}", "${n.size}"); return
        }
        k.zip(n).forEachIndexed { i, (a, b) ->
            if (a != b) out += Disagreement("$base[$i]", a, b)
        }
    }

    private fun diffStringMap(out: MutableList<Disagreement>, base: String, k: Map<String, String>, n: Map<String, String>) {
        (k.keys + n.keys).sorted().forEach { key ->
            val a = k[key]; val b = n[key]
            if (a != b) out += Disagreement("$base[$key]", a ?: "<absent>", b ?: "<absent>")
        }
    }

    private fun cmp(out: MutableList<Disagreement>, path: String, k: Any?, n: Any?) {
        if (k != n) out += Disagreement(path, "$k", "$n")
    }
}
