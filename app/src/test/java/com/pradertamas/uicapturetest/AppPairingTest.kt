package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test

class AppPairingTest {
    companion object {
        val config = PairingBackendConfig("https://pairing.example.test", "pairing.example.test", "/pair")
        fun invite(token: String) = "https://pairing.example.test/pair#invite=$token"
    }
    private val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
    private val id = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
    private class FakeApi(val identity: DeviceIdentity) : AppPairingApi {
        var registrations = 0; var creates = 0; var inspections = 0; var accepts = 0; var fail = false
        override fun register(): DeviceIdentity { registrations++; return identity }
        override fun create(identity: DeviceIdentity): String { creates++; check(!fail); return invite(identity.credential) }
        override fun inspect(identity: DeviceIdentity, token: String): String { inspections++; check(!fail); return "available" }
        override fun accept(identity: DeviceIdentity, token: String): String { accepts++; check(!fail); return "paired" }
    }
    @Test fun identityLazyPersistenceResetAndRedactedModels() {
        var saved: DeviceIdentity? = null; var registered = 0
        val store = DeviceIdentityStore({ saved }, { saved = it; true })
        assertEquals(0, registered)
        val first = store.ensure { registered++; DeviceIdentity(id, token) }
        assertEquals(id, first.deviceId); assertEquals(token, first.credential)
        val reopened = DeviceIdentityStore({ saved }, { saved = it; true })
        assertSame(first, reopened.ensure { error("Must not register again") }); assertEquals(1, registered)
        assertFalse(first.toString().contains(token)); assertFalse(first.toString().contains(id))
        saved = null
        val nextToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
        assertEquals(nextToken, reopened.ensure { DeviceIdentity(id, nextToken) }.credential)
    }
    @Test fun strictFragmentOnlyParserRejectsAmbiguity() {
        val good = invite(token); assertEquals(token, AppPairingLink.parse(good, config))
        for (bad in listOf(good.replace("https:", "http:"), good.replace("pairing.example.test", "example.test"),
            good.replace("/pair#", "/pair/#"), good.replace("#invite=", "?invite="), good + "&invite=$token", good + "&x=1",
            good.replace("#invite=", "#inv%69te="), good.replace("#invite=", "?x=1#invite="), good + "=", good + "\n",
            good.replace("#invite=", "#invite=%41"), good.replace("/pair", "/%70air"), good.replace(".test/pair", ".test:443/pair"),
            "https://pairing.example.test/pair#invite=bad")) assertNull(AppPairingLink.parse(bad, config))
        assertFalse(AppPairingLink.valid(token + "=")); assertFalse(AppPairingLink.valid(token + "A"))
        assertTrue(AppPairingLink.shareText(good, config).contains(good)); assertTrue(AppPairingLink.shareText(good, config).contains("30 minutes"))
    }
    @Test fun createAndInspectInFlightGuardsExplicitPairCancelAndRepeatIntent() {
        val api = FakeApi(DeviceIdentity(id, token)); var saved: DeviceIdentity? = null
        val queue = mutableListOf<() -> Unit>(); val shares = mutableListOf<String>()
        val c = AppPairingController(DeviceIdentityStore({ saved }, { saved = it; true }), api, queue::add, { it() }, {}, shares::add, config)
        assertEquals(0, api.registrations)
        repeat(5) { c.create(true) }; assertEquals(1, queue.size)
        queue.removeAt(0)(); assertEquals(1, api.registrations); assertEquals(1, api.creates); assertEquals(listOf(invite(token)), shares)
        c.incoming(invite(token), true); c.incoming(invite(token), true);assertEquals(1, queue.size)
        queue.removeAt(0)(); assertEquals(AppPairingPhase.AWAITING_CONFIRMATION, c.ui.phase)
        c.cancel(); assertEquals(0, api.accepts); assertEquals(AppPairingPhase.IDLE, c.ui.phase)
        c.incoming(invite(token), true); queue.removeAt(0)()
        repeat(5) { c.accept(true) }; assertEquals(1, queue.size)
        queue.removeAt(0)(); assertEquals(1, api.accepts); assertEquals(AppPairingPhase.PAIRED, c.ui.phase)
        assertEquals("Devices paired.", c.ui.message); assertFalse(c.ui.toString().contains(token))
    }
    @Test fun errorsInvalidLinksActiveMediaAndLateCallbacksLeaveUsableUi() {
        val api = FakeApi(DeviceIdentity(id, token)); val queue = mutableListOf<() -> Unit>()
        val c = AppPairingController(DeviceIdentityStore({ null }, { true }), api, queue::add, { it() }, {}, {}, config)
        c.incoming("https://example.test/", true); assertTrue(queue.isEmpty()); assertEquals("Invalid pairing link.", c.ui.message)
        c.create(false); assertTrue(queue.isEmpty())
        c.incoming(invite(token), false); assertTrue(queue.isEmpty()); assertEquals(0, api.registrations)
        api.fail = true; c.create(true); queue.removeAt(0)(); assertEquals(AppPairingPhase.ERROR, c.ui.phase)
        api.fail = false; c.retry(true); queue.removeAt(0)(); assertEquals(AppPairingPhase.IDLE, c.ui.phase)
        c.incoming(invite(token), true); c.close(); queue.removeAt(0)(); assertNotEquals(AppPairingPhase.AWAITING_CONFIRMATION, c.ui.phase)
    }
    @Test fun backupTransferManifestAndControlPlaneIsolationContracts() {
        val main = sequenceOf(java.io.File("src/main"), java.io.File("app/src/main")).first { it.isDirectory }
        val parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val document = parser.parse(java.io.File(main, "res/xml/$name")); val nodes = document.getElementsByTagName("exclude")
            val expected = if (name == "backup_rules.xml") 1 else 2
            assertEquals(expected, (0 until nodes.length).count { nodes.item(it).attributes.getNamedItem("path").nodeValue == "device_identity.xml" })
            assertEquals(expected, (0 until nodes.length).count { nodes.item(it).attributes.getNamedItem("path").nodeValue == "remote_device.xml" })
        }
        val manifest = java.io.File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:autoVerify=\"true\"")); assertTrue(manifest.contains("android:path=\"\${pairingAppLinkPath}\""))
        val source = java.io.File(main, "java/com/pradertamas/uicapturetest")
        for (name in listOf("AppPairing.kt", "PairingApiClient.kt", "AppPairingSection.kt", "PairingInviteActivity.kt")) {
            val text = java.io.File(source, name).readText()
            assertFalse(text.contains("startForegroundService(")); assertFalse(text.contains("createScreenCaptureIntent("))
            assertFalse(text.contains("switchRole(")); assertFalse(text.contains("newWebSocket(")); assertFalse(text.contains("Log."))
        }
        val activity = java.io.File(source, "PairingInviteActivity.kt").readText()
        assertTrue(activity.contains("savedInstanceState == null")); assertTrue(activity.contains("onNewIntent"))
        val ui = java.io.File(source, "AppPairingSection.kt").readText()
        assertTrue(ui.contains("Intent.ACTION_SEND")); assertTrue(ui.contains("Intent.createChooser"))
        assertTrue(ui.contains("model.cancel()")); assertTrue(ui.contains("model.accept(allowed)"))
    }
}
