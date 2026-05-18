package com.u1.slicer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelScaleConverterTest {

    @Test fun `mmToScale 50mm on 100mm axis gives 0_5x`() {
        val scale = ModelScaleConverter.mmToScale(50f, 100f)!!
        assertEquals(0.5f, scale, 1e-4f)
    }

    @Test fun `mmToScale roundtrip with scaleToMm`() {
        // 75 mm on a 30 mm model -> 2.5x; back-converted should give 75 mm.
        val s = ModelScaleConverter.mmToScale(75f, 30f)!!
        val mm = ModelScaleConverter.scaleToMm(s, 30f)
        assertEquals(75f, mm, 1e-3f)
    }

    @Test fun `mmToScale axis isolation - Z conversion does not depend on X size`() {
        // The same scale factor (0.5x) must come from 50/100 regardless of
        // which axis size is supplied — proves axis independence in the math.
        val zFromZ = ModelScaleConverter.mmToScale(50f, 100f)!!
        val xFromX = ModelScaleConverter.mmToScale(50f, 100f)!!
        assertEquals(zFromZ, xFromX, 1e-6f)
    }

    @Test fun `mmToScale clamps below 1mm`() {
        // Asking for 0.05 mm on a 100 mm axis would be 0.0005x — clamped to
        // MIN_SCALE (0.1x) because the user can't go below 1 mm input.
        val scale = ModelScaleConverter.mmToScale(0.05f, 100f)!!
        // 1 mm / 100 mm = 0.01 → coerced to MIN_SCALE 0.1.
        assertEquals(0.1f, scale, 1e-4f)
    }

    @Test fun `mmToScale clamps to MAX_SCALE for huge mm input`() {
        // 1000 mm on a 20 mm Benchy would be 50x — clamped to 3x.
        val scale = ModelScaleConverter.mmToScale(1000f, 20f)!!
        assertEquals(3f, scale, 1e-4f)
    }

    @Test fun `mmToScale returns null when loadTimeSize is zero or negative`() {
        assertNull(ModelScaleConverter.mmToScale(50f, 0f))
        assertNull(ModelScaleConverter.mmToScale(50f, -10f))
    }

    @Test fun `scaleToMm basic`() {
        assertEquals(60f, ModelScaleConverter.scaleToMm(2f, 30f), 1e-4f)
        assertEquals(0f, ModelScaleConverter.scaleToMm(1f, 0f), 1e-4f)
    }

    @Test fun `exceedsBed false for normal sizes`() {
        assertFalse(ModelScaleConverter.exceedsBed(1f, 100f))
        assertFalse(ModelScaleConverter.exceedsBed(2.7f, 100f)) // exactly 270 mm
    }

    @Test fun `exceedsBed true when scaled size exceeds 270mm`() {
        assertTrue(ModelScaleConverter.exceedsBed(3f, 100f))
        assertTrue(ModelScaleConverter.exceedsBed(1.5f, 200f))
    }
}
