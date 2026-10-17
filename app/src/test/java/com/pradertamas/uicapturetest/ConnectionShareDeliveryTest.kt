package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ConnectionShareDeliveryTest {
    private val config = PairingBackendConfig("https://pairing.example.test", "pairing.example.test", "/pair")
    private val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 1 })
    private val credential = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 2 })
    private val inviteToken = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
    private val link = "https://pairing.example.test/pair#invite=$inviteToken"
    @Test fun clickCreatesInviteThenDeliversWholeBackendLinkExactlyOnce() {
        val queue = mutableListOf<() -> Unit>()
        var event: String? = null
        var creates = 0
        val delivery = ConnectionShareDelivery(config, { event = it }, {})
        val api = object : AppPairingApi {
            override fun register() = DeviceIdentity(id, credential)
            override fun create(identity: DeviceIdentity): String { creates++; return link }
            override fun inspect(identity: DeviceIdentity, token: String) = "available"
            override fun accept(identity: DeviceIdentity, token: String) = "paired"
        }
        val controller = AppPairingController(DeviceIdentityStore({ null }, { true }), api,
            queue::add, { it() }, {}, delivery::offer, config)
        controller.create(true)
        assertNull(event); assertEquals(AppPairingPhase.CREATING_INVITE, controller.ui.phase)
        queue.removeAt(0)(); assertEquals(1, creates); assertEquals(link, event)
        val value = delivery.take(link)
        assertEquals(link, value); assertNull(event)
        val payload = AppPairingLink.shareText(value!!, config)
        assertTrue(payload.contains(link)); assertFalse(payload.contains(credential)); assertFalse(payload.contains(id))
        assertNotEquals("7K4M2Q", value)
        // Recomposition, RESUMED notification and retained owner after rotation cannot redeliver.
        repeat(4) { assertNull(delivery.take(link)); assertNull(event) }
    }
    @Test fun apiFailureAndInvalidBackendLinkProduceNoShareEvent() {
        for (invalid in listOf(false, true)) {
            var event: String? = null
            val delivery = ConnectionShareDelivery(config, { event = it }, {})
            val api = object : AppPairingApi {
                override fun register() = DeviceIdentity(id, credential)
                override fun create(identity: DeviceIdentity): String { if (!invalid) error("fixture"); return "7K4M2Q" }
                override fun inspect(identity: DeviceIdentity, token: String) = "available"
                override fun accept(identity: DeviceIdentity, token: String) = "paired"
            }
            val controller = AppPairingController(DeviceIdentityStore({ null }, { true }), api, { it() }, { it() }, {}, delivery::offer, config)
            controller.create(true)
            assertEquals(AppPairingPhase.ERROR, controller.ui.phase); assertNull(event)
        }
    }
    @Test fun failedNativeLaunchRetainsInviteForExplicitShareRetryOnly() {
        var event: String? = null; var error = ""
        val delivery = ConnectionShareDelivery(config, { event = it }, { error = it })
        delivery.offer(link); assertEquals(link, delivery.take(link))
        delivery.launchFailed(link)
        assertNull(event); assertTrue(error.contains("could not be shared"))
        assertNull(delivery.take(link)) // No automatic replay on resume/recomposition.
        assertTrue(delivery.retry()); assertEquals(link, event); assertEquals("", error)
        assertEquals(link, delivery.take(link)); assertFalse(delivery.retry()); assertNull(event)
    }
    @Test fun wrongConsumerCancellationAndNewOwnerCannotResurrectAnEvent() {
        var event: String? = null
        val delivery = ConnectionShareDelivery(config, { event = it }, {})
        delivery.offer(link); assertNull(delivery.take("wrong")); assertEquals(link, event)
        delivery.clear(); assertNull(event); assertNull(delivery.take(link)); assertFalse(delivery.retry())
        val fresh = ConnectionShareDelivery(config, { event = it }, {})
        assertNull(fresh.take(link)); assertFalse(fresh.retry()) // No saved-state bearer link.
    }
    @Test fun actualUiUsesLifecycleOwnerAndLauncherWithoutSilentFailure() {
        val source = sequenceOf(java.io.File("src/main/java/com/pradertamas/uicapturetest"), java.io.File("app/src/main/java/com/pradertamas/uicapturetest")).first { it.isDirectory }
        val ui = java.io.File(source, "AppPairingSection.kt").readText()
        assertTrue(ui.contains("LocalLifecycleOwner.current.lifecycle")); assertFalse(ui.contains("as? ComponentActivity"))
        assertTrue(ui.contains("rememberLauncherForActivityResult")); assertTrue(ui.contains("shareLauncher.launch(Intent.createChooser"))
        assertTrue(ui.contains("Intent.ACTION_SEND")); assertTrue(ui.contains("type = \"text/plain\""))
        assertTrue(ui.indexOf("model.shared(value)") < ui.indexOf("shareLauncher.launch"))
        assertTrue(ui.contains("model.shareFailed(delivery)")); assertTrue(ui.contains("shareError.isNotEmpty()"))
        assertFalse(ui.contains("createScreenCaptureIntent(")); assertFalse(ui.contains("startForegroundService("))
        assertFalse(ui.contains("switchRole(")); assertFalse(ui.contains("newWebSocket("))
        val controller = java.io.File(source, "AppPairing.kt").readText()
        assertFalse(controller.contains("createScreenCaptureIntent(")); assertFalse(controller.contains("newWebSocket("))
        assertFalse(controller.contains("switchRole("))
    }
}
