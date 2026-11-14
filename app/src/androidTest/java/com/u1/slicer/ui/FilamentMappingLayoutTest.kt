package com.u1.slicer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.u1.slicer.data.CanonicalFilamentList
import com.u1.slicer.data.FilamentEntry
import com.u1.slicer.data.FilamentSource
import com.u1.slicer.data.defaultExtruderPresets
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilamentMappingLayoutTest {
    @get:Rule val compose = createComposeRule()

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
        compose.setContent {
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
        expected.forEach { text ->
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        }
    }
}
