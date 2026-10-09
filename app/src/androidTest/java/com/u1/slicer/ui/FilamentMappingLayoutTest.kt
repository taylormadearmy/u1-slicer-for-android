package com.u1.slicer.ui

import androidx.compose.material3.MaterialTheme
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentSource
import com.u1.slicer.data.defaultExtruderPresets
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilamentMappingLayoutTest {
    @Test
    fun fourFilamentLabelsAndPickersAreVisibleWithoutScrolling() {
        assertMappingVisible(4, (1..4).flatMap { filament ->
            listOf("Filament $filament", "E$filament · PLA")
        } + listOf("Auto-suggest", "Send to Printer"))
    }

    @Test
    fun singleFilamentLabelAndPickerAreVisible() {
        assertMappingVisible(1, listOf("Filament 1", "#FF0000", "E1 · PLA", "Send to Printer"))
    }

    private fun assertMappingVisible(count: Int, expected: List<String>) {
        val presets = defaultExtruderPresets()
        val filaments = presets.take(count).map { preset ->
            FilamentEntry(preset.index, preset.color, "PLA", FilamentSource.FILE_COLOUR)
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        FilamentMappingDialog(
                            canonicalList = CanonicalFilamentList(filaments),
                            extruderPresets = presets,
                            initialMapping = List(count) { it },
                            onConfirm = {},
                            onDismiss = {},
                        )
                    }
                }
            }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val deadline = SystemClock.uptimeMillis() + 10_000
            var missing = expected
            while (missing.isNotEmpty() && SystemClock.uptimeMillis() < deadline) {
                val root = automation.rootInActiveWindow
                if (root?.packageName?.toString() == "android" &&
                    root.findAccessibilityNodeInfosByText("Android app compatibility").isNotEmpty()) {
                    root.findAccessibilityNodeInfosByText("Don't show again").firstOrNull()
                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                val visible = mutableSetOf<String>()
                collectVisibleText(automation.rootInActiveWindow, visible)
                missing = expected.filterNot { it in visible }
                if (missing.isNotEmpty()) SystemClock.sleep(100)
            }
            assertTrue("Mapping controls are hidden or clipped: $missing", missing.isEmpty())
        }
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, visible: MutableSet<String>) {
        if (node == null) return
        if (node.isVisibleToUser) node.text?.toString()?.let(visible::add)
        (0 until node.childCount).forEach { collectVisibleText(node.getChild(it), visible) }
    }
}
