package com.u1.slicer.bambu.snapshot

import com.u1.slicer.NativeLibrary
import java.io.File

/**
 * Parses the JSON dump produced by NativeLibrary.nativeDumpBambuModel.
 * The native side walks g_model after Model::read_from_file and emits
 * the same BambuFileSnapshot shape the Kotlin path produces, so the
 * differential harness can compare them apples-to-apples.
 */
object NativeBambuSnapshot {

    fun snapshot(file: File, native: NativeLibrary): BambuFileSnapshot {
        if (!native.loadModel(file.absolutePath)) {
            return parseOrEmpty(null, fallbackSource = file.name)
        }
        val json = native.nativeDumpBambuModel(file.absolutePath)
        return parseOrEmpty(json, fallbackSource = file.name)
    }

    fun parseOrEmpty(json: String?, fallbackSource: String): BambuFileSnapshot =
        if (json.isNullOrBlank()) {
            BambuFileSnapshot(fallbackSource, false, "", emptyList(), emptyList(), emptyList())
        } else {
            parse(json)
        }

    fun parse(json: String): BambuFileSnapshot = BambuFileSnapshotJson.decode(json)
}
