package com.u1.slicer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * nozzleTempDefaultForMaterial: returns a sensible nozzle temp for a slot
 * that has no linked FilamentProfile (e.g. after a Sync-from-Printer clears
 * the profile link).
 *
 * Rules:
 *  - Each named material maps to its standard temp
 *  - Lookup is case-insensitive
 *  - Unknown / unrecognised material falls back to 210 (PLA default)
 */
class NozzleTempDefaultTest {

    @Test fun `PLA returns 220`()  { assertEquals(220, nozzleTempDefaultForMaterial("PLA")) }
    @Test fun `PETG returns 235`() { assertEquals(235, nozzleTempDefaultForMaterial("PETG")) }
    @Test fun `ABS returns 270`()  { assertEquals(270, nozzleTempDefaultForMaterial("ABS")) }
    @Test fun `ASA returns 260`()  { assertEquals(260, nozzleTempDefaultForMaterial("ASA")) }
    @Test fun `PA returns 260`()   { assertEquals(260, nozzleTempDefaultForMaterial("PA")) }
    @Test fun `TPU returns 225`()  { assertEquals(225, nozzleTempDefaultForMaterial("TPU")) }
    @Test fun `PVA returns 210`()  { assertEquals(210, nozzleTempDefaultForMaterial("PVA")) }

    @Test fun `lowercase petg returns 235`() { assertEquals(235, nozzleTempDefaultForMaterial("petg")) }
    @Test fun `mixed-case Abs returns 270`() { assertEquals(270, nozzleTempDefaultForMaterial("Abs")) }

    @Test fun `unknown material falls back to 220`() { assertEquals(220, nozzleTempDefaultForMaterial("PC")) }
    @Test fun `empty string falls back to 220`()     { assertEquals(220, nozzleTempDefaultForMaterial("")) }
}
