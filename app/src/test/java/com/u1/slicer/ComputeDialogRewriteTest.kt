package com.u1.slicer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2.5 — JVM unit tests for [computeDialogRewrite].
 *
 * The dialog's `userMapping` is relative to the file's filaments
 * (mapping[i] = the slot the user wants for file filament i). The
 * G-code on disk has T-indices that already reflect the slice-time
 * `sliceMapping`. The rewrite list maps source T-indices in the G-code
 * to destination T-indices for upload.
 */
class ComputeDialogRewriteTest {

    @Test
    fun `null sliceMapping passes user mapping through unchanged`() {
        // Single-colour or pre-Phase-2.5 path: no slice-time mapping.
        // The user's mapping is interpreted as file-indexed.
        val out = computeDialogRewrite(null, listOf(0, 3))
        assertEquals(listOf(0, 3), out)
    }

    @Test
    fun `mismatched sizes pass user mapping through`() {
        // Defensive: if sliceMapping size != userMapping size, fall back
        // to legacy behaviour rather than producing nonsense.
        val out = computeDialogRewrite(listOf(0, 3), listOf(2))
        assertEquals(listOf(2), out)
    }

    @Test
    fun `identity slice with identity user is identity`() {
        val out = computeDialogRewrite(listOf(0, 1), listOf(0, 1))
        // Result spans 0..1, all identity
        assertEquals(2, out.size)
        assertEquals(0, out[0])
        assertEquals(1, out[1])
    }

    @Test
    fun `slice 0,3 — user keeps 0,3 — rewrite is identity at those indices`() {
        // Common path: user accepts auto-suggest unchanged. The G-code
        // has T0/T3; the rewrite should leave T0→T0, T3→T3.
        val out = computeDialogRewrite(listOf(0, 3), listOf(0, 3))
        assertEquals(4, out.size)  // covers indices 0..3
        assertEquals(0, out[0])
        assertEquals(1, out[1])    // identity default for unmapped
        assertEquals(2, out[2])    // identity default
        assertEquals(3, out[3])
    }

    @Test
    fun `slice 0,3 — user changes to 2,1 — rewrites T0 to T2 and T3 to T1`() {
        // The case Phase 2.5 fixes. G-code has T0 (filament 0 in slot 0)
        // and T3 (filament 1 in slot 3). User changes to filament 0→slot 2,
        // filament 1→slot 1. Final G-code should have T2 and T1.
        val out = computeDialogRewrite(listOf(0, 3), listOf(2, 1))
        assertEquals(4, out.size)
        assertEquals(2, out[0])    // T0 (filament 0) → T2 (slot 2)
        assertEquals(1, out[1])    // T1 — identity default (no source filament uses T1)
        assertEquals(2, out[2])    // T2 — identity default
        assertEquals(1, out[3])    // T3 (filament 1) → T1 (slot 1)
    }

    @Test
    fun `slice 0,1,2,3 — user fully reorders to 3,2,1,0`() {
        val out = computeDialogRewrite(listOf(0, 1, 2, 3), listOf(3, 2, 1, 0))
        assertEquals(4, out.size)
        assertEquals(3, out[0])
        assertEquals(2, out[1])
        assertEquals(1, out[2])
        assertEquals(0, out[3])
    }

    @Test
    fun `slice with duplicate slots (overlap) — last filament wins`() {
        // sliceMapping has duplicates: the slicer collapsed two filaments
        // onto one slot. Re-mapping post-slice can't un-collapse them,
        // so we accept that the duplicate source T-index will follow the
        // LAST file filament's user choice. Documented behaviour.
        val out = computeDialogRewrite(
            sliceMapping = listOf(0, 0, 1),
            userMapping = listOf(2, 3, 1),
        )
        // T0 came from both filament 0 and filament 1 in the slice;
        // last-write-wins → T0 → T3 (filament 1's user choice)
        assertEquals(3, out[0])
        assertEquals(1, out[1])
    }

    @Test
    fun `user mapping has higher index than slice mapping — list grows`() {
        // sliceMapping = [0, 1] but user picks slot 5 — output list must
        // cover index 5 so applyPrintTimeRemap doesn't drop it.
        val out = computeDialogRewrite(listOf(0, 1), listOf(0, 5))
        assertEquals(6, out.size)
        assertEquals(0, out[0])
        assertEquals(5, out[1])
        assertEquals(2, out[2])
        assertEquals(3, out[3])
        assertEquals(4, out[4])
        assertEquals(5, out[5])  // identity default
    }

    @Test
    fun `slice mapping has higher index than user mapping — list still spans both`() {
        val out = computeDialogRewrite(listOf(0, 5), listOf(2, 1))
        assertEquals(6, out.size)
        assertEquals(2, out[0])
        assertEquals(1, out[1])
        assertEquals(2, out[2])
        assertEquals(3, out[3])
        assertEquals(4, out[4])
        assertEquals(1, out[5])
    }
}
