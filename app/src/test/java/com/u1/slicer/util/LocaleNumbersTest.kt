package com.u1.slicer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B139: user-typed decimal fields must accept a comma decimal separator (European locales),
 * because Android numeric keyboards emit the locale separator while Kotlin's toFloatOrNull()
 * only accepts '.'. [toFloatLenient] normalizes both. Machine-format parsing is unaffected.
 */
class LocaleNumbersTest {

    @Test
    fun `comma decimal parses as period`() {
        assertEquals(0.16f, "0,16".toFloatLenient()!!, 0.0001f)
    }

    @Test
    fun `period decimal parses unchanged`() {
        assertEquals(0.16f, "0.16".toFloatLenient()!!, 0.0001f)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(0.5f, " 0,5 ".toFloatLenient()!!, 0.0001f)
        assertEquals(0.5f, " 0.5 ".toFloatLenient()!!, 0.0001f)
    }

    @Test
    fun `whole number string parses`() {
        assertEquals(2f, "2".toFloatLenient()!!, 0.0001f)
    }

    @Test
    fun `negative comma decimal parses`() {
        assertEquals(-0.2f, "-0,2".toFloatLenient()!!, 0.0001f)
    }

    @Test
    fun `blank and whitespace-only return null`() {
        assertNull("".toFloatLenient())
        assertNull("   ".toFloatLenient())
    }

    @Test
    fun `non-numeric returns null`() {
        assertNull("abc".toFloatLenient())
        assertNull("1.2.3".toFloatLenient())
    }
}
