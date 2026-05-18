package com.u1.slicer.model

/**
 * F83 (GitHub #136): convert between absolute-mm input and the percent-based
 * scale factor the slicer pipeline uses. The Prepare-screen scale field accepts
 * either a percent (200 → 2.0×) or an absolute mm value (50 → whatever scale
 * makes the load-time axis 50 mm).
 *
 * The math is a thin division — `mm / loadTimeSize` — but lives here so it can
 * be unit-tested independently of the Compose UI and because callers need
 * shared clamp / warn rules.
 *
 * The percent slider already coerces into 10–300 %; the mm path applies the
 * same range plus a 1 mm minimum so a user typing "0.05" doesn't produce a
 * sub-millimetre print, and a 270 mm bed-bound warning so they know when an
 * axis runs off the bed.
 */
object ModelScaleConverter {

    /** Minimum permitted mm value for any axis (clamps user input below 1 mm). */
    const val MIN_MM: Float = 1f

    /** Snapmaker U1 build volume (square, X = Y = Z = 270 mm). */
    const val BED_MM: Float = 270f

    /** Existing percent-scale clamp range, kept in sync with the percent slider. */
    const val MIN_SCALE: Float = 0.1f
    const val MAX_SCALE: Float = 3f

    /**
     * Convert an mm value on a single axis to a scale factor. Returns null when
     * [loadTimeSize] is zero / negative (no model loaded yet) so callers can
     * fall back to the unchanged scale.
     *
     * The returned scale is clamped to [MIN_SCALE]..[MAX_SCALE] to match the
     * percent slider's range — typing 1000 mm on a 20 mm Benchy doesn't blow
     * the slicer out, it caps at 3×.
     */
    fun mmToScale(mm: Float, loadTimeSize: Float): Float? {
        if (loadTimeSize <= 0f) return null
        val clampedMm = mm.coerceAtLeast(MIN_MM)
        return (clampedMm / loadTimeSize).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** Convert a scale factor to its mm size on a given axis. */
    fun scaleToMm(scale: Float, loadTimeSize: Float): Float = scale * loadTimeSize

    /**
     * Returns true when applying [scale] to [loadTimeSize] yields a footprint
     * that exceeds the 270 mm bed dimension. UI uses this to surface a soft
     * warning — the slice path still runs.
     */
    fun exceedsBed(scale: Float, loadTimeSize: Float): Boolean =
        scaleToMm(scale, loadTimeSize) > BED_MM
}
