package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConnectionWorkflowTest {
    private val source = sequenceOf(File("src/main/java/com/pradertamas/uicapturetest"), File("app/src/main/java/com/pradertamas/uicapturetest")).first { it.isDirectory }
    private fun text(name: String) = File(source, name).readText()
    @Test fun workflowIsPresentationOnlyAndUsesExistingRoleAndConnections() {
        assertEquals("3. Connect to another device", ConnectionWorkflow.title(ConnectionWorkflow.CONNECT))
        assertEquals("3. Let another device connect here", ConnectionWorkflow.title(ConnectionWorkflow.SHOW))
        val ui = text("ConnectionWorkflow.kt")
        assertFalse(ui.contains("Saved connections")); assertFalse(ui.contains("Manage saved connections"))
        assertFalse(ui.contains("AppPairing")); assertFalse(ui.contains("refreshDevices"))
        assertTrue(ui.indexOf("Text(shareLabel)") >= 0)
        assertTrue(ui.indexOf("Text(shareLabel)") < ui.indexOf("WorkflowAction(\"Connect to another device"))
        assertTrue(ui.indexOf("WorkflowAction(\"Connect to another device") < ui.indexOf("WorkflowAction(\"Let another device connect here"))
        assertFalse(ui.contains("4. Status")); assertFalse(ui.contains("switchRole(")); assertFalse(ui.contains("createScreenCaptureIntent("))
        assertFalse(ui.contains("startForegroundService(")); assertFalse(ui.contains("model.join("))
        for (name in listOf("MainActivity.kt", "ReceiverActivity.kt")) {
            val activity = text(name)
            assertTrue(activity.contains("1. Choose role")); assertTrue(activity.contains("WorkflowStart(canChoose"))
            assertTrue(activity.contains("WorkflowPanel(activePath")); assertTrue(activity.contains("selectedPath = null"))
            assertTrue(activity.contains("MediaRoleSelector(mediaMode.role")); assertTrue(activity.contains("Advanced diagnostics"))
        }
    }
    @Test fun mainAndReceiverShareUseRequestFirstWithoutNas() {
        val main = text("MainActivity.kt")
        val receiver = text("ReceiverActivity.kt")
        for (activity in listOf(main, receiver)) {
            assertFalse(activity.contains("AppPairing"))
            assertTrue(activity.contains("rememberRequestShare(mediaMode.role)"))
            assertTrue(activity.contains("share = requestShare.start"))
            assertTrue(activity.contains("overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)"))
            assertTrue(activity.contains("overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)"))
            assertTrue(activity.contains("padding(24.dp)"))
        }
        val shareUi = text("ConnectionRequestShareUi.kt")
        assertTrue(shareUi.contains("Intent.ACTION_SEND")); assertTrue(shareUi.contains("Intent.createChooser"))
        assertFalse(main.contains("PairingHost.remoteShareText()"))
        assertFalse(receiver.contains("PairingCode.receiverUrl(ui.home.code)"))
        val roleSwitch = main.substringAfter("if (requested != mediaMode.role").substringBefore("if (mediaMode.error")
        assertTrue(roleSwitch.contains("AppMediaRoles.switchRole(requested)"))
        assertTrue(roleSwitch.contains("selectedPath = null; joinCode = \"\""))
        assertFalse(roleSwitch.contains("createScreenCaptureIntent"))
        assertTrue(receiver.contains("shareEnabled = canChoose && !requestShare.busy"))
        val selector = text("MediaRoleSelector.kt")
        assertTrue(selector.contains("activeContainerColor = Color(0xFF245C3A)"))
        assertTrue(selector.contains("inactiveContainerColor = MaterialTheme.colorScheme.surface"))
        assertTrue(selector.contains("selected = role == value"))
        assertTrue(selector.contains("heightIn(min = 56.dp)"))
    }
    @Test fun taskViewsHideHomeAndStartAtTopWithoutShareActions() {
        for (name in listOf("MainActivity.kt", "ReceiverActivity.kt")) {
            val activity = text(name)
            val home = activity.substringAfter("if (activePath == null) {").substringBefore("if (activePath != null) WorkflowPanel")
            assertTrue(home.contains("1. Choose role")); assertTrue(home.contains("WorkflowStart("))
            val task = activity.substringAfter("if (activePath != null) WorkflowPanel").substringBefore("if (activePath == null) {")
            assertFalse(task.contains("Share connection link")); assertFalse(task.contains("Copy connection link"))
            assertTrue(task.contains("WorkflowOwnCode(")); assertTrue(task.contains("Scan QR code"))
            assertTrue(task.contains("Connection code")); assertTrue(task.contains("Text(\"Connect\")"))
            assertTrue(task.indexOf("Scan QR code") < task.indexOf("OutlinedTextField"))
            assertTrue(activity.contains("WorkflowPanel(activePath, canChoose, closeTask)"))
            assertTrue(activity.contains("remember(activePath) { androidx.compose.foundation.ScrollState(0) }"))
            assertTrue(activity.contains("verticalScroll(taskScroll)"))
        }
        assertTrue(text("ConnectionWorkflow.kt").contains("Text(\"Back\")"))
        assertTrue(text("MainActivity.kt").contains("scanning = false; externalUi = false"))
        assertTrue(text("ReceiverActivity.kt").contains("addOnCanceledListener { scanning = false }"))
    }
    @Test fun qrCodeAndLinkUseTheExistingSixCharacterProtocol() {
        val url = PairingCode.receiverUrl("7K4M2Q")
        assertEquals("7K4M2Q", PairingCode.parseQr(url))
        val ui = text("ConnectionWorkflow.kt")
        val own = ui.substringAfter("fun WorkflowOwnCode")
        assertTrue(own.contains("pairingQr(code)")); assertTrue(own.contains("Connection code"))
        assertFalse(own.contains("Copy connection link")); assertFalse(own.contains("Share connection link"))
        assertFalse(own.contains("Button(")); assertFalse(own.contains("shareLink"))
        assertEquals("Connection code not found", ConnectionWorkflow.displayStatus("Pairing code not found"))
    }
    @Test fun receiverScannerDoesNotAddCaptureOrMediaPermissionPath() {
        val activity = text("ReceiverActivity.kt")
        assertTrue(activity.contains("GmsBarcodeScanning.getClient")); assertTrue(activity.contains("PairingCode.parseQr"))
        assertTrue(activity.contains("viewerHidden(viewerIdentity, isChangingConfigurations, scanning)"))
        assertTrue(activity.contains("addOnCanceledListener { scanning = false }"))
        assertFalse(activity.contains("!lifecycle.currentState.isAtLeast"))
        val share = activity.indexOf("val requestShare = rememberRequestShare")
        assertTrue(share >= 0 && share < activity.indexOf("WorkflowPanel(activePath"))
        assertFalse(activity.contains("MediaProjection")); assertFalse(activity.contains("createScreenCaptureIntent("))
        val main = text("MainActivity.kt")
        assertTrue(main.contains("claimConsent()")); assertTrue(main.contains("createConfigForUserChoice()"))
    }
    @Test fun systemBackAndVisibleBackShareOnlyTheEnabledTaskCallback() {
        for (name in listOf("MainActivity.kt", "ReceiverActivity.kt")) {
            val activity = text(name)
            assertTrue(activity.contains("import androidx.activity.compose.BackHandler"))
            assertTrue(activity.contains(if (name == "ReceiverActivity.kt")
                "BackHandler(enabled = activePath != null && canChoose && model.requestFlow == null, onBack = closeTask)"
                else "BackHandler(enabled = activePath != null && canChoose, onBack = closeTask)"))
            assertTrue(activity.contains("WorkflowPanel(activePath, canChoose, closeTask)"))
            val callback = activity.substringAfter("val closeTask: () -> Unit = {").substringBefore("}")
            assertTrue(callback.contains("selectedPath = null"))
            for (forbidden in listOf("switchRole", "returnToSend", "finish(", "create(", "join(", "ensureOwnCreate", "regenerate"))
                assertFalse(callback.contains(forbidden))
            assertTrue(activity.substringBefore("val closeTask").contains("&& !scanning"))
            assertTrue(activity.indexOf("val closeTask") < activity.indexOf("BackHandler(enabled"))
        }
        val main = text("MainActivity.kt")
        assertTrue(main.contains("val closeTask: () -> Unit = { PairingHost.controller.typed(\"\"); joinCode = \"\"; selectedPath = null }"))
        val receiver = text("ReceiverActivity.kt")
        assertTrue(receiver.contains("override fun handleOnBackPressed() { viewerBack() }"))
        assertTrue(receiver.contains("if (phase != ReceiverPhase.RECEIVING)"))
        assertTrue(receiver.indexOf("onBackPressedDispatcher.addCallback") < receiver.indexOf("BackHandler(enabled"))
        for (path in listOf(ConnectionWorkflow.CONNECT, ConnectionWorkflow.SHOW)) {
            assertNotNull(ConnectionWorkflow.title(path)) // Both paths use the same activePath gate.
        }
    }
    @Test fun receiverDiagnosticsAreRetainedForDeveloperUiButNotCalledByNormalScreen() {
        val receiver = text("ReceiverActivity.kt")
        assertFalse(receiver.contains("diagnosticsContent()"))
        assertFalse(receiver.substringBefore("internal fun ReceiverDiagnostics").contains("ReceiverDiagnostics("))
        assertFalse(receiver.contains("Text(\"Receiving\""))
        val content = receiver.substringAfter("internal fun ReceiverDiagnostics")
        assertTrue(content.contains("diagnostics = !diagnostics"))
        assertTrue(content.contains("if (diagnostics) Column"))
        assertTrue(content.contains("heightIn(max = 220.dp)"))
        assertTrue(content.contains("Text(ui.metrics)")); assertTrue(content.contains("Current received video:"))
        val main = text("MainActivity.kt")
        assertTrue(main.indexOf("WorkflowStart(") < main.indexOf("TextButton(onClick = { diagnostics"))
        assertTrue(main.contains("if (activePath == null) {\n                        TextButton(onClick = { diagnostics"))
    }
}
