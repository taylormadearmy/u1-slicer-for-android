package com.u1.slicer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeSoakScriptTest {
    private fun script(): String {
        val file = listOf(
            File("../scripts/run-v4-upgrade-soak.ps1"),
            File("scripts/run-v4-upgrade-soak.ps1"),
        ).firstOrNull(File::isFile)
        return requireNotNull(file) { "run-v4-upgrade-soak.ps1 not found" }.readText()
    }

    @Test
    fun `launch uses application id plus namespaced activity class`() {
        val source = script()

        assertTrue(source.contains("\$AppId/com.u1.slicer.MainActivity"))
        assertFalse(source.contains("\$AppId/.MainActivity"))
    }

    @Test
    fun `launch fails when adb reports an invalid component`() {
        val source = script()

        assertTrue(source.contains("shell am start -W -n \$MainActivity"))
        assertTrue(source.contains("Error type|does not exist"))
        assertTrue(source.contains("throw \"Could not launch"))
    }
}
