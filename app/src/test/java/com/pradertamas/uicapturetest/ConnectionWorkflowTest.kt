package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class ConnectionWorkflowTest {
    private val source = sequenceOf(File("src/main/java/com/pradertamas/uicapturetest"), File("app/src/main/java/com/pradertamas/uicapturetest")).first { it.isDirectory }
    private fun text(name: String) = File(source, name).readText()
    @Test fun workflowIsPresentationOnlyAndUsesExistingRoleAndConnections() {
        assertEquals("3. Connect to another device", ConnectionWorkflow.title(ConnectionWorkflow.CONNECT))
        assertEquals("3. Let another device connect here", ConnectionWorkflow.title(ConnectionWorkflow.SHOW))
        val ui = text("ConnectionWorkflow.kt")
        assertFalse(ui.contains("Saved connections")); assertFalse(ui.contains("Manage saved connections"))
        assertFalse(ui.contains("AppPairing")); assertFalse(ui.contains("refreshDevices"))
        assertTrue(ui.indexOf("Text(\"Share connection link\")") < ui.indexOf("WorkflowAction(\"Connect to another device"))
        assertTrue(ui.indexOf("WorkflowAction(\"Connect to another device") < ui.indexOf("WorkflowAction(\"Let another device connect here"))
        val inviteUi = text("AppPairingSection.kt")
        assertFalse(inviteUi.contains("Manage saved connections")); assertFalse(inviteUi.contains("expanded"))
        assertTrue(inviteUi.contains("onClick = { model.create(allowed) }"))
        assertTrue(inviteUi.contains("AppPairingLink.shareText(delivery, model.config)"))
        assertTrue(inviteUi.contains("model.shared(value)")); assertTrue(inviteUi.contains("Intent.createChooser"))
        assertTrue(inviteUi.contains("30 minutes and can be used once"))
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
        assertTrue(text("PairingInviteActivity.kt").contains("AppPairingSection"))
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
    @Test fun onlyValidPublicDeviceIdentifiersCanBeDisplayed() {
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 7 })
        assertEquals(id, PairedDevice.fromPublicId(id)?.publicId)
        assertNull(PairedDevice.fromPublicId("")); assertNull(PairedDevice.fromPublicId("Galaxy S25"))
        val credential = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 9 })
        assertNull(PairedDevice.fromPublicId(credential)); assertNull(PairedDevice.fromPublicId(id + "="))
        assertTrue(emptyList<PairedDevice>().isEmpty())
        val ui = text("ConnectionWorkflow.kt")
        assertFalse(ui.contains("device.publicId")); assertFalse(ui.contains("devices.forEach"))
        assertFalse(ui.contains("device.credential")); assertFalse(ui.contains("Galaxy S25"))
    }
    @Test fun listRefreshDoesNotCreateOrReplaceDeviceIdentity() {
        var stored: DeviceIdentity? = null
        var writes = 0
        val store = DeviceIdentityStore({ stored }, { writes++; stored = it; true })
        assertNull(store.existing()); assertEquals(0, writes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val credential = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        stored = DeviceIdentity(id, credential)
        assertSame(stored, store.existing()); assertEquals(0, writes)
        stored = DeviceIdentity("invalid", credential); assertNull(store.existing())
        val vm = text("AppPairingViewModel.kt")
        val refresh = vm.substringAfter("fun refreshDevices()").substringBefore("private var incoming")
        assertTrue(refresh.contains(".existing() ?: return")); assertFalse(refresh.contains("ensure(")); assertFalse(refresh.contains("register("))
        assertTrue(refresh.contains("api.listPairings(identity)")); assertTrue(refresh.contains("otherDeviceId"))
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
        assertTrue(activity.contains("!isChangingConfigurations && !scanning"))
        assertTrue(activity.contains("addOnCanceledListener { scanning = false }"))
        assertTrue(activity.contains("Lifecycle.State.STARTED"))
        assertTrue(activity.indexOf("val connectionShare = rememberLauncherForActivityResult") < activity.indexOf("if (activePath != null)"))
        assertFalse(activity.contains("MediaProjection")); assertFalse(activity.contains("createScreenCaptureIntent("))
        val main = text("MainActivity.kt")
        assertTrue(main.contains("claimConsent()")); assertTrue(main.contains("createConfigForUserChoice()"))
        val section = text("AppPairingSection.kt")
        assertTrue(section.contains("model.accept(allowed)")); assertFalse(section.contains("startForegroundService("))
        assertFalse(section.contains("switchRole(")); assertFalse(section.contains("model.join("))
    }
    @Test fun systemBackAndVisibleBackShareOnlyTheEnabledTaskCallback() {
        for (name in listOf("MainActivity.kt", "ReceiverActivity.kt")) {
            val activity = text(name)
            assertTrue(activity.contains("import androidx.activity.compose.BackHandler"))
            assertTrue(activity.contains("BackHandler(enabled = activePath != null && canChoose, onBack = closeTask)"))
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
        assertTrue(receiver.contains("override fun handleOnBackPressed() { returnToSend(stopActive = true) }"))
        assertTrue(receiver.contains("if (phase != ReceiverPhase.RECEIVING)"))
        assertTrue(receiver.indexOf("onBackPressedDispatcher.addCallback") < receiver.indexOf("BackHandler(enabled"))
        for (path in listOf(ConnectionWorkflow.CONNECT, ConnectionWorkflow.SHOW)) {
            assertNotNull(ConnectionWorkflow.title(path)) // Both paths use the same activePath gate.
        }
    }
    @Test fun rootDiagnosticsFollowWorkflowInsideThePaddedScrollableColumn() {
        val receiver = text("ReceiverActivity.kt")
        val rootColumn = receiver.substringAfter(".padding(24.dp).verticalScroll(taskScroll)")
            .substringBefore("if (phase == ReceiverPhase.RECEIVING && activePath == null) diagnosticsContent()")
        assertTrue(rootColumn.contains("Arrangement.spacedBy(12.dp)"))
        assertTrue(rootColumn.indexOf("WorkflowStart(") < rootColumn.indexOf("if (activePath == null) diagnosticsContent()"))
        assertTrue(rootColumn.contains("if (activePath == null) diagnosticsContent()"))
        assertTrue(receiver.contains("if (phase == ReceiverPhase.RECEIVING && activePath == null) diagnosticsContent()"))
        val content = receiver.substringAfter("val diagnosticsContent: @Composable () -> Unit = {").substringBefore("LaunchedEffect(phase)")
        assertTrue(content.contains("diagnostics = !diagnostics"))
        assertTrue(content.contains("if (diagnostics) Column"))
        assertTrue(content.contains("heightIn(max = 220.dp)"))
        assertTrue(content.contains("Text(ui.metrics)")); assertTrue(content.contains("Current received video:"))
        val main = text("MainActivity.kt")
        assertTrue(main.indexOf("WorkflowStart(") < main.indexOf("TextButton(onClick = { diagnostics"))
        assertTrue(main.contains("if (activePath == null) {\n                        TextButton(onClick = { diagnostics"))
    }
}
