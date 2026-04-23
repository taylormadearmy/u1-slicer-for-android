package com.u1.slicer.bambu.snapshot

import com.u1.slicer.NativeLibrary
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Parses the JSON dump produced by NativeLibrary.nativeDumpBambuModel.
 * The native side walks g_model after Model::read_from_file and emits
 * the same BambuFileSnapshot shape the Kotlin path produces, so the
 * differential harness can compare them apples-to-apples.
 */
object NativeBambuSnapshot {

    /**
     * Loads `file` via NativeLibrary.loadModel and returns a snapshot of g_model.
     * Holds NativeLibrary.previewMutex for the entire load+dump to prevent races
     * against concurrent setModelRotation / getPreparePreviewMesh callers.
     *
     * Suspend because previewMutex is a coroutine Mutex; callers in instrumented
     * tests should wrap with runBlocking { }.
     */
    suspend fun snapshot(file: File, native: NativeLibrary): BambuFileSnapshot =
        NativeLibrary.previewMutex.withLock {
            if (!native.loadModel(file.absolutePath)) {
                return@withLock parseOrEmpty(null, fallbackSource = file.name)
            }
            val json = native.nativeDumpBambuModel(file.absolutePath)
            parseOrEmpty(json, fallbackSource = file.name)
        }

    fun parseOrEmpty(json: String?, fallbackSource: String): BambuFileSnapshot =
        if (json.isNullOrBlank()) {
            BambuFileSnapshot(fallbackSource, false, "", emptyList(), emptyList(), emptyList())
        } else {
            parse(json)
        }

    fun parse(json: String): BambuFileSnapshot = BambuFileSnapshotJson.decode(json)
}
