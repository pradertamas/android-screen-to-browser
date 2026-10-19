package com.pradertamas.uicapturetest

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real Compose control behavior; does not claim a real WebRTC peer or physical lifecycle. */
class ActiveShareControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun activeActionsReplaceShareAndKeepConflictingCardsDisabled() {
        var shown = 0; var stopped = 0; var shared = 0; var connected = 0
        compose.setContent {
            MaterialTheme { WorkflowStart(false, null, { shared++ }, activeShare = { shown++ },
                stopActiveShare = { stopped++ }) { connected++ } }
        }
        compose.onNodeWithText("Share connection link").assertDoesNotExist()
        compose.onNodeWithText("Connect to another device").assertIsNotEnabled()
        compose.onNodeWithText("Let another device connect here").assertIsNotEnabled()
        compose.onNodeWithText("Show active share").performClick()
        compose.onNodeWithText("Stop active share").performClick()
        compose.runOnIdle { assertEquals(1, shown); assertEquals(1, stopped); assertEquals(0, shared); assertEquals(0, connected) }
    }
    @Test fun observableExpiryRestoresNormalPrimaryAction() {
        val live = MutableStateFlow(true)
        compose.setContent {
            val active by live.collectAsState()
            MaterialTheme { WorkflowStart(!active, null, {}, activeShare = if (active) ({}) else null) {} }
        }
        compose.onNodeWithText("Show active share").assertIsDisplayed()
        compose.runOnIdle { live.value = false }
        compose.onNodeWithText("Show active share").assertDoesNotExist()
        compose.onNodeWithText("Stop active share").assertDoesNotExist()
        compose.onNodeWithText("Share connection link").assertIsEnabled()
        compose.onNodeWithText("Connect to another device").assertIsEnabled()
    }
}
