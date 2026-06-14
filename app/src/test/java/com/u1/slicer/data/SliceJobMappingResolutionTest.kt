package com.u1.slicer.data

import com.u1.slicer.gcode.GcodeToolSpace
import com.u1.slicer.gcode.gcodeToolSpaceToDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 2 (post-v2.0.0-validation, bug-class fix) — guards the
 * `shareJobGcode` mapping resolution.
 *
 * The bug class: at slice time the live `_colorMapping` is
 * plate-narrowed for multi-plate Bambu files (size = plate filament
 * count, not file filament count). The pre-fix `colorMappingCsv` stored
 * that plate-narrowed list verbatim, then `shareJobGcode` decoded and
 * fed it positionally to `applyPrintTimeRemap`. For Dragon plate 1
 * (canonical 13, plate uses fileIndices 4 + 7) the stored CSV was
 * "0,1" and `applyPrintTimeRemap` built `{0→0, 1→1}` — leaving T4 / T7
 * un-rewritten, shipping canonical-space G-code to the printer.
 *
 * Post-fix the slice-time persist site routes through
 * `resolveExportMapping()` so the stored CSV is canonical-positional,
 * and `shareJobGcode` routes through `resolveCanonicalExportMapping`
 * for parity with Save / Share / Send.
 *
 * These tests document the post-fix contract from the resolution side:
 * given the four shapes a `SliceJob` row can take, the resolver
 * produces the right canonical-wide mapping.
 */
class SliceJobMappingResolutionTest {

    private fun resolveForJob(job: SliceJob): List<Int>? {
        return resolveExportMapping(job)
    }

    private fun jobOf(
        canonicalListSize: Int? = null,
        colorMappingCsv: String? = null,
        selectedExtruderAtSlice: Int? = null,
        gcodeToolSpace: String? = null,
    ): SliceJob = SliceJob(
        modelName = "fixture",
        gcodePath = "/tmp/x.gcode",
        totalLayers = 1,
        estimatedTimeSeconds = 1f,
        estimatedFilamentMm = 1f,
        layerHeight = 0.2f,
        fillDensity = 20f,
        nozzleTemp = 220,
        bedTemp = 60,
        supportEnabled = false,
        filamentType = "PLA",
        canonicalListSize = canonicalListSize,
        colorMappingCsv = colorMappingCsv,
        selectedExtruderAtSlice = selectedExtruderAtSlice,
        gcodeToolSpace = gcodeToolSpace,
    )

    @Test
    fun preePhase2Job_noCanonicalSize_resolvesToNullForIdentityCopy() {
        // Pre-schema-v5 job. Stored G-code is already physical-slot.
        val job = jobOf(canonicalListSize = null)
        assertNull(resolveForJob(job))
    }

    @Test
    fun physicalToolSpaceJob_resolvesToNullForIdentityCopyEvenWithCanonicalMetadata() {
        val job = jobOf(
            canonicalListSize = 4,
            colorMappingCsv = "3,2,1,0",
            selectedExtruderAtSlice = 0,
            gcodeToolSpace = gcodeToolSpaceToDb(GcodeToolSpace.PHYSICAL),
        )
        assertNull(resolveForJob(job))
    }

    @Test
    fun canonicalToolSpaceJob_resolvesStoredCanonicalMapping() {
        val job = jobOf(
            canonicalListSize = 4,
            colorMappingCsv = "3,2,1,0",
            selectedExtruderAtSlice = 0,
            gcodeToolSpace = gcodeToolSpaceToDb(GcodeToolSpace.CANONICAL),
        )
        assertEquals(listOf(3, 2, 1, 0), resolveForJob(job))
    }

    @Test
    fun singleColourJob_e3SelectedAtSlice_remapsT0ToSlot2() {
        val job = jobOf(
            canonicalListSize = 1,
            colorMappingCsv = null,
            selectedExtruderAtSlice = 2,
        )
        assertEquals(listOf(2), resolveForJob(job))
    }

    @Test
    fun singleColourJob_nullSelectedAtSlice_falsBackToSlot0() {
        // Defensive: pre-v6 single-colour job that never wrote
        // selectedExtruderAtSlice. T0 maps to E1.
        val job = jobOf(
            canonicalListSize = 1,
            colorMappingCsv = null,
            selectedExtruderAtSlice = null,
        )
        assertEquals(listOf(0), resolveForJob(job))
    }

    @Test
    fun multiColourJob_canonicalWideCsv_returnedAsIs() {
        // Post-fix: the slice-time persist site stores canonical-wide
        // mapping. The resolver's case-1 path returns it verbatim.
        // Dragon plate 1 (canonical 13) with user picks for plate
        // fileIndices 4 + 7 routed through resolveExportMapping at
        // slice time stores "0,1,2,3,1,1,2,0,3,1,2,3,0" (mod-4 fallback
        // for unmapped indices, user's slot at 4 = 1 and at 7 = 0).
        val job = jobOf(
            canonicalListSize = 13,
            colorMappingCsv = "0,1,2,3,1,1,2,0,3,1,2,3,0",
            selectedExtruderAtSlice = 0,
        )
        val mapping = resolveForJob(job)
        assertEquals(13, mapping?.size)
        assertEquals(1, mapping?.get(4))  // user's pick for canonical fileIdx 4
        assertEquals(0, mapping?.get(7))  // user's pick for canonical fileIdx 7
        assertEquals(0, mapping?.get(0))  // mod-4 fallback at unmapped indices
        assertEquals(1, mapping?.get(1))
        assertEquals(2, mapping?.get(2))
        assertEquals(3, mapping?.get(3))
    }

    @Test
    fun multiColourJob_nullCsv_fallsBackToIdentityModFour() {
        // Pre-Filament-Mapping-dialog multi-colour job, OR a
        // pre-schema-v5 multi-colour job. Identity-mod-4 produces a
        // printable file even though the user never confirmed.
        val job = jobOf(
            canonicalListSize = 5,
            colorMappingCsv = null,
            selectedExtruderAtSlice = null,
        )
        assertEquals(listOf(0, 1, 2, 3, 0), resolveForJob(job))
    }

    @Test
    fun legacyPlateNarrowedCsv_smallerThanCanonical_documentsKnownLimitation() {
        // Documents the "transitional" behavior for jobs sliced under
        // the v2.0.0 PRE-fix code: the stored CSV is plate-narrowed.
        // Without `confirmedMappingFileIndices` (which the SliceJob
        // schema doesn't carry, intentionally — the slice-time persist
        // site now stores canonical-wide), the resolver expands
        // positionally with mod-4 fallback. This is best-effort and
        // imperfect for plates whose filaments don't start at canonical
        // fileIdx 0 — but those jobs were already broken pre-fix, so
        // the post-fix behavior is no worse.
        //
        // New jobs sliced under the post-fix code never hit this
        // codepath because their stored CSV is canonical-wide
        // (length == canonicalListSize).
        val job = jobOf(
            canonicalListSize = 13,
            colorMappingCsv = "0,1",  // legacy bad CSV from pre-fix v2.0.0 slice
            selectedExtruderAtSlice = 0,
        )
        val mapping = resolveForJob(job)
        assertEquals(13, mapping?.size)
        // Positional expansion with mod-4 fallback for unmapped indices.
        assertEquals(0, mapping?.get(0))
        assertEquals(1, mapping?.get(1))
        assertEquals(2, mapping?.get(2))  // 2 % 4
        assertEquals(3, mapping?.get(3))  // 3 % 4
    }

    @Test
    fun multiColourJob_canonicalWideCsv_clampsOutOfRangeSlots() {
        // Defensive: malformed CSV with out-of-range slot values gets
        // clamped to U1's physical 0..3 range.
        val job = jobOf(
            canonicalListSize = 4,
            colorMappingCsv = "99,-5,1,2",
            selectedExtruderAtSlice = 0,
        )
        assertEquals(listOf(3, 0, 1, 2), resolveForJob(job))
    }

    @Test
    fun multiColourJob_blankCsv_treatedAsAbsent() {
        // Defensive: explicit blank CSV behaves like null.
        val job = jobOf(
            canonicalListSize = 3,
            colorMappingCsv = "",
            selectedExtruderAtSlice = 0,
        )
        assertEquals(listOf(0, 1, 2), resolveForJob(job))
    }
}
