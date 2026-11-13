package com.u1.slicer.ui.printer

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.u1.slicer.ConfigCard
import com.u1.slicer.data.Printer
import com.u1.slicer.data.SliceConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class U1PrepareSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editNozzlesFromPrepareSavesE4WithoutNavigatingAway() {
        val printer = Printer("prepare-u1", "Workshop U1")
        var saved: Printer? = null
        compose.setContent {
            MaterialTheme {
                Column {
                    U1PrepareNozzleSettings(printer, onSave = { saved = it },
                        onTest = { error("No network access expected") },
                        onNavigateSettings = { error("Existing printer should edit in Prepare") })
                }
            }
        }
        compose.onNodeWithText("Edit nozzles").performClick()
        compose.onNodeWithText("E4 nozzle").performScrollTo().performClick()
        compose.onNode(hasText("0.2 mm") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(listOf(0.4f, 0.4f, 0.4f, 0.2f), saved?.u1Nozzles?.diametersMm)
            assertEquals(printer.id, saved?.id)
        }
    }

    @Test fun layerSummaryAndExpandedSettingsShowEffectiveNozzleLayerHeight() {
        compose.setContent {
            MaterialTheme {
                ConfigCard(SliceConfig(nozzleDiameter = 0.2f, layerHeight = 0.1f), {},
                    bedSizeX = 270f, bedSizeY = 270f, maxPrintHeight = 270f, onOverridesChange = {})
            }
        }
        compose.onNodeWithText("0.10 mm").assertExists()
        compose.onNodeWithText("Layer & Infill").performClick()
        compose.onNodeWithText("For this job: 0.10 mm").assertExists()
    }
}
