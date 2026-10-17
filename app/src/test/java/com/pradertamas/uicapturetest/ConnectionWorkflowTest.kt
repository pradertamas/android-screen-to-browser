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
        assertTrue(ui.indexOf("Saved connections") < ui.indexOf("WorkflowAction(\"Connect to another device"))
        assertTrue(ui.indexOf("AppPairingSection(model, allowed, compact = true)") < ui.indexOf("Saved connections"))
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
            assertTrue(activity.contains("1. Choose role")); assertTrue(activity.contains("WorkflowStart(appPairing"))
            assertTrue(activity.contains("WorkflowPanel(activePath")); assertTrue(activity.contains("selectedPath = null"))
            assertTrue(activity.contains("MediaRoleSelector(mediaMode.role")); assertTrue(activity.contains("Advanced diagnostics"))
        }
    }
    @Test fun onlyValidPublicDeviceIdentifiersCanBeDisplayed() {
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 7 })
        assertEquals(id, PairedDevice.fromPublicId(id)?.publicId)
        assertNull(PairedDevice.fromPublicId("")); assertNull(PairedDevice.fromPublicId("Galaxy S25"))
        val credential = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 9 })
        assertNull(PairedDevice.fromPublicId(credential)); assertNull(PairedDevice.fromPublicId(id + "="))
        assertTrue(emptyList<PairedDevice>().isEmpty())
        val ui = text("ConnectionWorkflow.kt")
        assertTrue(ui.contains("if (devices.isNotEmpty())")); assertTrue(ui.contains("device.publicId.takeLast(8)"))
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
        assertTrue(ui.contains("PairingCode.receiverUrl(code)")); assertTrue(ui.contains("Copy connection link"))
        assertTrue(ui.contains("Share connection link")); assertTrue(ui.contains("ClipDescription.EXTRA_IS_SENSITIVE"))
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
}
