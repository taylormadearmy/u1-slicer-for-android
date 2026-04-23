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
        // Match by list position, not by objectId. Kotlin parses the XML object id
        // (ThreeMfParser.parseObjectId), Native emits Slic3r's runtime ObjectID
        // (reassigned per Model::read_from_file) — the two id spaces never align,
        // and runtime IDs are not stable across loads, so ID-keyed matching produces
        // spurious diffs whose indices drift between runs. The per-object field
        // comparisons (name, extruder) are what's semantically meaningful.
        k.zip(n).forEachIndexed { i, (ko, no) ->
            cmp(out, "objects[$i].extruder", ko.extruder, no.extruder)
            cmp(out, "objects[$i].name", ko.name, no.name)
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
        // Sub-plan #1: both Kotlin and Native now source volumes from g_model and
        // iterate in identical deterministic order (g_model.objects[oi].volumes[vi],
        // nulls skipped). Match by list position rather than (objectId, volumeIndex)
        // because Slic3r's runtime ObjectID is reassigned per Model::read_from_file;
        // the two snapshot paths each trigger their own load, so their ObjectIDs
        // never agree by construction. ObjectID identity across loads is not a
        // meaningful invariant to compare.
        k.zip(n).forEachIndexed { i, (ko, no) ->
            val base = "volumes[$i]"
            cmp(out, "$base.volumeIndex", ko.volumeIndex, no.volumeIndex)
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
