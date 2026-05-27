package com.u1.slicer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-05-26 regression guard against the "0-byte saved G-code" bug.
 *
 * Repro (Pixel 8a, multi-plate H2C file): user slices plate 2 → preview looks
 * fine → taps Save → file picker opens → user accepts → resulting file is 0
 * bytes. Two confirmed real-world instances were found in the device's Google
 * Drive folder (a May-2 file and the May-26 1890038_xav01_H2C_279_104 case
 * that triggered the investigation).
 *
 * The Android `CreateDocument` Activity Result API creates the destination
 * file BEFORE the picker callback fires. If `saveGcodeTo()` then silently
 * early-returns (because `_state` has transitioned away from `SliceComplete`
 * during the picker round-trip — e.g. a re-fired plate selector triggered
 * `selectPlate` in the background), the empty file remains at 0 bytes.
 *
 * Fix (this commit): SlicerViewModel maintains `_lastSliceResult`, kept in
 * sync with the live state via the `state.collect` observer in `init {}`,
 * cleared in `clearModel()`. `saveGcodeTo` / `shareGcode` consult the cache
 * when `_state` is no longer `SliceComplete`, so the save still succeeds.
 *
 * Companion UX fix: when `_state` transitions away from `SliceComplete`,
 * `_parsedGcode` and `_gcodePreview` are cleared so the Preview screen no
 * longer shows a stale preview that suggests a saveable slice exists.
 *
 * This is a source-grep guard because a behavioural test would need an
 * Android context (Application, DataStore, Room) which the JVM test target
 * doesn't have. The fix is purely structural and a regression here would
 * silently re-introduce the 0-byte file class of bugs.
 */
class SaveGcodeStateResilienceTest {

    private val source: String by lazy {
        File("src/main/java/com/u1/slicer/SlicerViewModel.kt").readText()
    }

    @Test
    fun lastSliceResultCacheFieldIsDeclared() {
        assertTrue(
            "SlicerViewModel must declare `_lastSliceResult` so the save / share " +
                "path can fall back to the most recent SliceResult when the live " +
                "state has transitioned away from SliceComplete during the file-picker " +
                "round-trip.",
            Regex("""private\s+var\s+_lastSliceResult\s*:\s*SliceResult\?""").containsMatchIn(source)
        )
    }

    @Test
    fun stateObserverPopulatesAndClearsCache() {
        // The state observer in init {} must do TWO things on every state transition:
        //   1. Mirror SliceComplete results into _lastSliceResult so the cache
        //      always tracks the latest successful slice.
        //   2. Clear _parsedGcode and _gcodePreview when transitioning away from
        //      SliceComplete so the Preview screen doesn't lie about a saveable
        //      slice existing. (B129: the _parsedGcode clear now routes through
        //      setParsedGcodeWithRangeReset(null), which also resets the preview
        //      layer-slider range — guarded in GcodeViewer3DScreenLayerRangeTest.)
        assertTrue(
            "State observer must mirror SliceComplete into _lastSliceResult.",
            Regex(
                """if\s*\(\s*newState\s+is\s+SlicerState\.SliceComplete\s*\)\s*\{[^}]*_lastSliceResult\s*=\s*newState\.result"""
            ).containsMatchIn(source)
        )
        assertTrue(
            "State observer must clear _parsedGcode + _gcodePreview when leaving SliceComplete.",
            Regex(
                """prevState\s+is\s+SlicerState\.SliceComplete\s+&&\s+newState\s+!is\s+SlicerState\.SliceComplete[^}]*setParsedGcodeWithRangeReset\(null\)[^}]*_gcodePreview\.value\s*=\s*\"\""""
            ).containsMatchIn(source)
        )
    }

    @Test
    fun clearModelDropsCache() {
        // `clearModel` is the user-driven full reset. The cache must be cleared
        // here so a stale SliceResult from the previous file doesn't survive
        // into the new session.
        val clearModelStart = source.indexOf("fun clearModel(")
        assertTrue("clearModel function must exist", clearModelStart > 0)
        // Look at the next ~3 KB of source covering the body.
        val window = source.substring(clearModelStart, minOf(clearModelStart + 3000, source.length))
        assertTrue(
            "clearModel() must set _lastSliceResult = null.",
            Regex("""_lastSliceResult\s*=\s*null""").containsMatchIn(window)
        )
    }

    @Test
    fun saveGcodeFallsBackToCacheAndToastsOnFailure() {
        val saveStart = source.indexOf("fun saveGcodeTo(uri: Uri)")
        assertTrue("saveGcodeTo function must exist", saveStart > 0)
        val saveEnd = source.indexOf("fun ", saveStart + 1)
        val saveBody = source.substring(saveStart, if (saveEnd > 0) saveEnd else source.length)
        assertTrue(
            "saveGcodeTo must read SliceResult from the live state OR fall back to _lastSliceResult.",
            saveBody.contains("_state.value as? SlicerState.SliceComplete") &&
                saveBody.contains("_lastSliceResult")
        )
        assertTrue(
            "saveGcodeTo must surface failures via _toastEvents (no more silent swallow).",
            saveBody.contains("_toastEvents.tryEmit(\"Save G-code failed")
        )
        assertTrue(
            "saveGcodeTo must not have an unlogged catch block — every catch surfaces a toast.",
            // The old `catch (_: Throwable) { /* silent */ }` form must not exist
            // anywhere in the function body.
            !Regex("""catch\s*\(\s*_\s*:\s*Throwable\s*\)\s*\{\s*(//[^\n]*\n\s*)?\}""")
                .containsMatchIn(saveBody)
        )
    }

    @Test
    fun shareGcodeFallsBackToCache() {
        val shareStart = source.indexOf("fun shareGcode()")
        assertTrue("shareGcode function must exist", shareStart > 0)
        val shareEnd = source.indexOf("fun ", shareStart + 1)
        val shareBody = source.substring(shareStart, if (shareEnd > 0) shareEnd else source.length)
        assertTrue(
            "shareGcode must use the same cache fallback so it can't silently drop a share when state races.",
            shareBody.contains("_state.value as? SlicerState.SliceComplete") &&
                shareBody.contains("_lastSliceResult")
        )
    }

    // 2026-05-26 follow-up — the ACTUAL cause of the 0-byte save (confirmed by
    // on-device SaveGcode logs): a 272 MB H2C plate takes ~80 s to remap + copy,
    // but the save ran as a plain viewModelScope.launch with no foreground service.
    // When the user backgrounded the app to check the file in Drive, Android's
    // cached-app freezer suspended the process (~70 s later) before the copy
    // finished, leaving the picker's pre-created 0-byte destination empty. The fix
    // wraps the coroutine body in the F90 beginLongOp/endLongOp foreground-service
    // helpers (try/finally) so the freezer can't suspend it mid-operation.

    @Test
    fun saveGcodeHoldsForegroundServiceForDuration() {
        val saveStart = source.indexOf("fun saveGcodeTo(uri: Uri)")
        val saveEnd = source.indexOf("\n    fun ", saveStart + 1)
        val saveBody = source.substring(saveStart, if (saveEnd > 0) saveEnd else source.length)
        assertTrue(
            "saveGcodeTo must hold a LongOpService foreground service (beginLongOp) across the " +
                "remap+copy so the cached-app freezer can't kill it when the user backgrounds the app.",
            Regex("""beginLongOp\("Saving G-code"\)""").containsMatchIn(saveBody)
        )
        assertTrue(
            "saveGcodeTo must release the foreground service in a finally block (endLongOp).",
            Regex("""finally\s*\{\s*endLongOp\(""").containsMatchIn(saveBody)
        )
    }

    @Test
    fun shareGcodeHoldsForegroundServiceForDuration() {
        val shareStart = source.indexOf("fun shareGcode()")
        val shareEnd = source.indexOf("\n    fun ", shareStart + 1)
        val shareBody = source.substring(shareStart, if (shareEnd > 0) shareEnd else source.length)
        assertTrue(
            "shareGcode must hold a LongOpService foreground service across the remap.",
            Regex("""beginLongOp\(""").containsMatchIn(shareBody)
        )
        assertTrue(
            "shareGcode must release the foreground service in a finally block.",
            Regex("""finally\s*\{\s*endLongOp\(""").containsMatchIn(shareBody)
        )
    }
}
