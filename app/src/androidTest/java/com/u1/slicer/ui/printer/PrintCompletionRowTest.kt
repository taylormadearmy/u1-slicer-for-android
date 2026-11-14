package com.u1.slicer.ui.printer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.u1.slicer.network.PrinterStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrintCompletionRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun estimateDisappearsWhenPausedAndReturnsOnResume() {
        val status = mutableStateOf(PrinterStatus("printing", 0.5f, printDuration = 3600f))
        compose.setContent { MaterialTheme { PrintCompletionRow(status.value) } }
        compose.onNodeWithText("Estimated finish").assertIsDisplayed()

        compose.runOnIdle { status.value = status.value.copy(state = "paused") }
        compose.onNodeWithText("Estimated finish").assertDoesNotExist()

        compose.runOnIdle { status.value = status.value.copy(state = "printing") }
        compose.onNodeWithText("Estimated finish").assertIsDisplayed()

        compose.runOnIdle { status.value = status.value.copy(state = "complete", progress = 1f) }
        compose.onNodeWithText("Estimated finish").assertDoesNotExist()
    }

    @Test fun estimateAppearsOnlyAfterUsefulProgressArrives() {
        val status = mutableStateOf(PrinterStatus("printing", 0f))
        compose.setContent { MaterialTheme { PrintCompletionRow(status.value) } }
        compose.onNodeWithText("Estimated finish").assertDoesNotExist()

        compose.runOnIdle { status.value = status.value.copy(progress = 0.1f, printDuration = 600f) }
        compose.onNodeWithText("Estimated finish").assertIsDisplayed()
    }
}
