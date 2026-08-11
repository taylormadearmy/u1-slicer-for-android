package com.u1.slicer.ui.printer

import com.u1.slicer.ui.shouldShowFilamentMappingNickname
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class F78ConditionalRenderingTest {

    @Test
    fun activePrinterSelector_isOnlyVisible_whenMoreThanOnePrinterIsConfigured() {
        assertFalse(shouldShowActivePrinterSelector(0))
        assertFalse(shouldShowActivePrinterSelector(1))
        assertTrue(shouldShowActivePrinterSelector(2))
    }

    @Test
    fun filamentMappingNickname_requiresExplicitOptInAndANickname() {
        assertFalse(shouldShowFilamentMappingNickname(false, "U1"))
        assertFalse(shouldShowFilamentMappingNickname(true, ""))
        assertTrue(shouldShowFilamentMappingNickname(true, "A1 Mini"))
    }
}
