package com.pradertamas.uicapturetest

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual Activity/Compose rendering at the formerly empty STOPPED + armed-gate boundary.
 * No real HTTP, socket, camera or projection is needed. */
class ConnectionContentTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<*>? = null
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    @Before fun armedIdleBoundary() = main {
        ConnectionRequestRuntimeGate.armRequestFirst()
        ConnectionRequests.controller.clear()
        AppMediaRoles.switchRole(MediaRole.SEND, stopActive = true)
        PairingHost.controller.hide(); PairingHost.controller.close()
    }
    @After fun dispose() {
        scenario?.close()
        main { ConnectionRequestRuntimeGate.releaseRequestFirst() }
    }
    @Test fun connectSelectionRendersScannerCodeAndConnectEvenWhileGateIsArmed() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Connect to another device").performScrollTo().performClick()
        compose.onNodeWithText("Scan QR code").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("OR").assertIsDisplayed()
        compose.onNodeWithText("Connection code").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }
    @Test fun showSelectionHasCodePreparationContentInsteadOfShell() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Let another device connect here").performScrollTo().performClick()
        compose.onNodeWithText("Connection code").assertIsDisplayed()
        compose.onNodeWithText("Preparing connection code...").assertIsDisplayed()
    }
    @Test fun receiverConnectSelectionAlsoRendersFunctionalControlsWhileBlocked() {
        main { AppMediaRoles.switchRole(MediaRole.RECEIVE) }
        scenario = ActivityScenario.launch(ReceiverActivity::class.java)
        compose.onNodeWithText("Connect to another device").performScrollTo().performClick()
        compose.onNodeWithText("Scan QR code").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("OR").assertIsDisplayed()
        compose.onNodeWithText("Connection code").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }
    @Test fun receiverShowSelectionAlsoHasCodePreparationContent() {
        main { AppMediaRoles.switchRole(MediaRole.RECEIVE) }
        scenario = ActivityScenario.launch(ReceiverActivity::class.java)
        compose.onNodeWithText("Let another device connect here").performScrollTo().performClick()
        compose.onNodeWithText("Connection code").assertIsDisplayed()
        compose.onNodeWithText("Preparing connection code...").assertIsDisplayed()
    }
    @Test fun connectNewIntentRendersFunctionalInvalidLinkContentAndClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Cold bearer scrubbing changes Intent.filterEquals before ActivityScenario can track
        // post-create lifecycle events. Deliver VIEW through the real warm Intent boundary instead.
        val recipient = ActivityScenario.launch<ConnectionRequestActivity>(Intent(context, ConnectionRequestActivity::class.java)
            .setAction(Intent.ACTION_VIEW))
        scenario = recipient
        recipient.onActivity { activity ->
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity,
                Intent(context, ConnectionRequestActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("https://pairing.pradert.synology.me/connect#invalid")))
        }
        compose.onNodeWithText("Connection request").assertIsDisplayed()
        compose.onNodeWithText("Invalid connection link").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed().performClick()
    }
}
