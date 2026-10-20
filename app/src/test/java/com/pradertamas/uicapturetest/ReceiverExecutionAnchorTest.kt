package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ReceiverExecutionAnchorTest {
    private fun live(id: ReceiverSessionIdentity, visible: Boolean = true) = ReceiverUi(
        ReceiverHome(phase = ReceiverPhase.RECEIVING), presence = ReceiverPresence(id, true, visible))
    private class Fixture {
        var current: String? = null
        val foreground = mutableListOf<String>(); val stops = mutableListOf<Int>(); val mediaStops = mutableListOf<String>()
        val events = mutableListOf<String>()
        var foregroundThrows = false
        val policy = ReceiverAnchorLifecycle({ current == it }, {
            check(!foregroundThrows); foreground.add(it)
        }, { key ->
            if (current != key) false else { mediaStops.add(key); current = null; true }
        }, stops::add, { event, _ -> events.add(event) })
        fun start(key: String, id: Int = 1) { current = key; policy.command("START", key, id) }
    }
    @Test fun admissionRequiresExactCommittedVisibleReceivingAndDispatchesOnce() {
        val starts = mutableListOf<ReceiverSessionIdentity>(); val gate = ReceiverAnchorAdmission(starts::add)
        val id = ReceiverSessionIdentity()
        for (phase in listOf(ReceiverPhase.PAIRING, ReceiverPhase.CONNECTING, ReceiverPhase.ENDED, ReceiverPhase.ERROR))
            gate.changed(live(id).copy(home = ReceiverHome(phase)), MediaRole.RECEIVE, true)
        gate.changed(live(id), MediaRole.SEND, true)
        gate.changed(live(id), MediaRole.RECEIVE, false)
        gate.changed(live(id, false), MediaRole.RECEIVE, true)
        assertTrue(starts.isEmpty())
        repeat(3) { gate.changed(live(id), MediaRole.RECEIVE, true) }
        assertEquals(listOf(id), starts)
    }
    @Test fun visibilitySignalingAndDisconnectChangesDoNotRestartAnAdmittedAnchor() {
        val starts = mutableListOf<ReceiverSessionIdentity>(); val gate = ReceiverAnchorAdmission(starts::add)
        val id = ReceiverSessionIdentity()
        gate.changed(live(id), MediaRole.RECEIVE, true)
        repeat(3) {
            gate.changed(live(id, false), MediaRole.RECEIVE, true)
            gate.changed(live(id).copy(home = ReceiverHome(ReceiverPhase.RECEIVING, signalingAvailable = false)), MediaRole.RECEIVE, true)
        }
        assertEquals(listOf(id), starts)
    }
    @Test fun terminationThenReplacementDispatchesFreshExactIdentity() {
        val starts = mutableListOf<ReceiverSessionIdentity>(); val gate = ReceiverAnchorAdmission(starts::add)
        val a = ReceiverSessionIdentity(); val b = ReceiverSessionIdentity()
        gate.changed(live(a), MediaRole.RECEIVE, true); gate.changed(ReceiverUi(), MediaRole.RECEIVE, false)
        gate.changed(live(b), MediaRole.RECEIVE, true)
        assertEquals(listOf(a, b), starts); assertNotEquals(a.anchorKey, b.anchorKey)
        assertFalse(a.toString().contains(a.anchorKey))
    }
    @Test fun duplicateStartsUseOneForegroundAnchorAndLatestAndroidStartIdForStop() {
        val f = Fixture(); f.start("a"); f.policy.command("START", "a", 2)
        assertEquals(listOf("a"), f.foreground)
        f.current = null; repeat(3) { f.policy.changed() }
        assertEquals(listOf(2), f.stops); assertTrue(f.mediaStops.isEmpty())
    }
    @Test fun exactNotificationStopDelegatesOnceAndThenStopsService() {
        val f = Fixture(); f.start("a"); f.policy.command("STOP", "a", 2)
        f.policy.changed()
        assertEquals(listOf("a"), f.mediaStops); assertEquals(listOf(2), f.stops)
        assertTrue(f.events.contains("anchor-notification-stop-admitted"))
    }
    @Test fun staleStartAndNotificationStopCannotAffectReplacement() {
        val f = Fixture(); f.start("a"); f.current = null; f.policy.changed()
        f.start("b", 2)
        f.policy.command("START", "a", 3); f.policy.command("STOP", "a", 4)
        assertEquals("b", f.policy.key); assertEquals("b", f.current); assertTrue(f.mediaStops.isEmpty())
        assertEquals(listOf(1), f.stops); assertEquals(listOf("a", "b"), f.foreground)
        f.current = null; f.policy.changed(); assertEquals(listOf(1, 4), f.stops)
    }
    @Test fun delayedStartWithoutMatchingProcessLifetimeDoesNotPromoteOrReconstruct() {
        val f = Fixture(); f.current = "new-process-session"
        f.policy.command("START", "old-process-session", 1)
        assertTrue(f.foreground.isEmpty()); assertTrue(f.mediaStops.isEmpty()); assertEquals(listOf(1), f.stops)
    }
    @Test fun nullRestartAndUnknownCommandsStopEmptyServiceWithoutCreatingReceiver() {
        for (command in listOf(null, "unknown")) {
            val f = Fixture(); f.policy.command(command, null, 1)
            assertEquals(listOf(1), f.stops); assertTrue(f.foreground.isEmpty()); assertTrue(f.mediaStops.isEmpty())
        }
    }
    @Test fun staleColdNotificationCannotStopCurrentSession() {
        val f = Fixture(); f.current = "b"; f.policy.command("STOP", "a", 1)
        assertEquals("b", f.current); assertTrue(f.mediaStops.isEmpty()); assertEquals(listOf(1), f.stops)
    }
    @Test fun foregroundFailureDoesNotStopMediaOrStartRetryLoop() {
        val f = Fixture(); f.foregroundThrows = true; f.start("a")
        assertEquals("a", f.current); assertNull(f.policy.key); assertTrue(f.mediaStops.isEmpty())
        assertEquals(listOf(1), f.stops); repeat(3) { f.policy.changed() }
        assertEquals(listOf(1), f.stops)
    }
    @Test fun permissionRefreshOnlyUpdatesExactExistingAnchorAndDoesNotPromoteAgain() {
        var promotions = 0; var refreshes = 0; var stops = 0
        val policy = ReceiverAnchorLifecycle({ it == "b" }, { promotions++ }, { false }, { stops++ },
            refreshNotification = { refreshes++ })
        policy.command("REFRESH", "b", 1); assertEquals(0, promotions); assertEquals(0, refreshes)
        policy.command("START", "b", 2); policy.command("REFRESH", "a", 3); policy.command("REFRESH", "b", 4)
        assertEquals(1, promotions); assertEquals(1, refreshes); assertEquals(1, stops)
    }
    @Test fun terminalRefreshAndSynchronousNotificationStopAreReentrantSafe() {
        var active = true; var stops = 0; var disposed = 0
        lateinit var policy: ReceiverAnchorLifecycle
        policy = ReceiverAnchorLifecycle({ active }, {}, { active = false; disposed++; policy.changed(); true }, { stops++ })
        policy.command("START", "a", 1); policy.command("STOP", "a", 2)
        policy.changed(); assertEquals(1, disposed); assertEquals(1, stops)
    }
    @Test fun serviceManifestAndWiringKeepExecutionSeparateFromMediaOwnership() {
        val main = File("src/main")
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(main.resolve("AndroidManifest.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        val services = manifest.getElementsByTagName("service")
        val service = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(ns, "name") == ".ReceiverExecutionAnchorService" }
        assertEquals("false", service.getAttributeNS(ns, "exported")); assertEquals("specialUse", service.getAttributeNS(ns, "foregroundServiceType"))
        assertEquals(1, service.getElementsByTagName("property").length)
        val permissions = manifest.getElementsByTagName("uses-permission")
        assertTrue((0 until permissions.length).any { (permissions.item(it) as org.w3c.dom.Element)
            .getAttributeNS(ns, "name") == "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" })
        val source = main.resolve("java/com/pradertamas/uicapturetest/ReceiverExecutionAnchorService.kt").readText()
        assertTrue(source.contains("START_NOT_STICKY")); assertTrue(source.contains("stopSelfResult(startId)"))
        assertTrue(source.contains(".setIdentifier(key)")); assertTrue(source.contains("FLAG_IMMUTABLE"))
        assertTrue(source.contains(".setIdentifier(\"receiver-anchor-open\")"))
        assertTrue(source.contains("ReceiverSessions::stopAnchoredSession")); assertTrue(source.contains(".setOngoing(true)"))
        for (forbidden in listOf("ReceiverSessions.get(", "ReceiverEngine(", "WebRtcReceiverSession(", "PeerConnectionFactory", "releaseRequestFirst(", "restartIce("))
            assertFalse(forbidden, source.contains(forbidden))
    }
}
