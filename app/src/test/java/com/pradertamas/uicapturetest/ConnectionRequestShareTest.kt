package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Executor
import java.util.ArrayDeque

class ConnectionRequestShareTest {
    @Test fun foregroundStatusBetweenReadyAndChooserDoesNotLoseFirstShare() {
        for (role in listOf(MediaRole.SEND, MediaRole.RECEIVE)) {
            val f = Fixture(); f.start(role); f.finish()
            val monitor = ConnectionRequestInitiatorMonitor(f.controller,
                ConnectionRequestPollScheduler { _, _ -> {} }, { f.elapsed })
            monitor.foreground(ConnectionRequestForegroundOwner(), role)
            assertEquals(ConnectionRequestOperation.STATUS, f.controller.state.value.operation)
            assertNotNull("Read-only foreground status must not invalidate a ready link", f.link(role))
            assertEquals(1, f.creates)
        }
    }
    @Test fun monitorStatusBeforeShareObserverStillOffersExactlyOneCompleteLink() {
        val f = Fixture(); f.start(); f.queue.run() // CREATE complete, share observer has not run.
        val monitor = ConnectionRequestInitiatorMonitor(f.controller,
            ConnectionRequestPollScheduler { _, _ -> {} }, { f.elapsed })
        monitor.foreground(ConnectionRequestForegroundOwner(), MediaRole.SEND)
        f.coordinator.onRequestState(f.controller.state.value)
        assertNull(f.ui.delivery)
        f.finish() // Existing pre-delivery policy waits for the active GET's completion.
        val delivery = f.ui.delivery!!
        assertEquals("https://pairing.pradert.synology.me/connect#v=1&r=${token(16, 1)}&c=${token(16, 18)}&i=send", f.link())
        assertNull(f.coordinator.takeLink(f.owner, delivery, MediaRole.SEND))
        f.finish(); assertNull(f.ui.delivery); assertNull(f.ui.error); assertEquals(1, f.creates)
    }
    @Test fun explicitReshareDuringReadOnlyStatusUsesExistingLinkWithoutNewCreate() {
        val f = Fixture(); f.start(); f.finish(); val original = f.link()
        f.coordinator.chooserReturned(f.owner)
        f.controller.refreshStatus()
        f.start(); assertEquals(original, f.link()); assertNull(f.ui.error)
        f.finish(); assertEquals(1, f.creates); assertEquals(0, f.cancels)
    }
    @Test fun pendingLabelSingleFlightAndChooserCancelReset() {
        val f = Fixture()
        assertEquals("Share connection link", f.ui.buttonLabel)
        assertFalse(f.ui.busy); assertEquals(0, f.creates); assertTrue(f.queue.tasks.isEmpty())
        f.start()
        assertTrue(f.ui.preparing); assertTrue(f.ui.busy); assertEquals("Preparing link...", f.ui.buttonLabel)
        repeat(3) { f.start() }; assertEquals(1, f.queue.tasks.size)
        f.finish(); assertFalse(f.ui.preparing)
        assertNotNull(f.link()); assertFalse(f.ui.preparing) // chooser opens; no preparing state.
        f.coordinator.chooserReturned(f.owner) // Android chooser result includes cancel/back.
        assertFalse(f.ui.busy); assertEquals("Share connection link", f.ui.buttonLabel)
        assertEquals(1, f.creates)
    }
    @Test fun failedPreparationResetsButtonAndExplicitRetrySucceedsOnce() {
        val f = Fixture(); f.createError = ConnectionRequestError.NETWORK
        f.start(); f.finish()
        assertFalse(f.ui.preparing); assertFalse(f.ui.busy)
        assertEquals("Share connection link", f.ui.buttonLabel)
        assertEquals(RequestShareError.CREATE_FAILED, f.ui.error); assertNull(f.link())
        f.createError = null; f.start(); f.start(); f.finish()
        assertNull(f.ui.error); assertNotNull(f.link()); assertNull(f.link()); assertEquals(2, f.creates)
    }
    @Test fun mutatingOperationStillBlocksShareExportAndStart() {
        val f = Fixture(); f.start(); f.finish(); f.link(); f.coordinator.chooserReturned(f.owner)
        f.controller.cancel()
        assertNull(f.controller.shareableInitiatorData(MediaRole.SEND))
        f.start(); assertEquals(RequestShareError.UNAVAILABLE, f.ui.error); assertNull(f.ui.delivery)
        assertEquals(1, f.creates)
    }
    private fun token(bytes: Int, seed: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes) { seed.toByte() })
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private inner class Fixture {
        val events = mutableListOf<String>()
        val queue = Queue()
        val gate = LegacyConnectionStartGate()
        var elapsed = 0L
        var ttlMs = 300_000L
        var creates = 0; var cancels = 0
        var createError: ConnectionRequestError? = null
        var cancelError: ConnectionRequestError? = null
        var serverState = ConnectionRequestState.WAITING
        var createdRole: MediaRole? = null
        var onCreate: (() -> Unit)? = null
        val api = object : ConnectionRequestApi {
            override fun create(role: MediaRole): ConnectionRequestResult {
                events.add("create"); creates++; createdRole = role
                createError?.let { return ConnectionRequestResult(error = it) }
                val m = metadata(role)
                onCreate?.also { onCreate = null }?.invoke()
                return ConnectionRequestResult(m, httpStatus = 201, created = ConnectionRequestCreated(m,
                    ConnectionCredential(token(32, 17)), ConnectionCredential(token(16, 18))))
            }
            override fun cancel(id: String, auth: ConnectionCredential): ConnectionRequestResult {
                events.add("cancel"); cancels++
                return if (cancelError != null) ConnectionRequestResult(error = cancelError)
                    else ConnectionRequestResult(metadata(createdRole!!).copy(state = ConnectionRequestState.CANCELED), httpStatus = 200)
            }
            override fun status(id: String, auth: ConnectionCredential, scope: ConnectionRequestAuthScope) =
                ConnectionRequestResult(metadata(createdRole!!).copy(state = serverState), httpStatus = if (serverState.terminal) 410 else 200)
            override fun accept(id: String, capability: ConnectionCredential, claim: ConnectionCredential): ConnectionRequestResult = error("No accept in Share")
            override fun withdraw(id: String, auth: ConnectionCredential): ConnectionRequestResult = error("No withdraw in Share")
            override fun ready(id: String, auth: ConnectionCredential, ready: Boolean, scope: ConnectionRequestAuthScope): ConnectionRequestResult = error("No readiness in Share")
            override fun session(id: String, auth: ConnectionCredential): ConnectionRequestResult = error("No session in Share")
        }
        fun metadata(role: MediaRole) = ConnectionRequestMetadata(token(16, creates), ConnectionRequestState.WAITING,
            Instant.parse("2026-01-01T00:00:00Z"), role, expiresAt = Instant.parse("2026-01-01T00:00:00Z").plusMillis(ttlMs))
        val controller = ConnectionRequestController(api, queue, elapsedMs = { elapsed })
        val coordinator = ConnectionRequestShareCoordinator(controller,
            { events.add("arm"); gate.armRequestFirst() }, { events.add("release"); gate.releaseRequestFirst() }, gate::legacyStartAllowed)
        val owner = ConnectionRequestShareOwner()
        fun start(role: MediaRole = MediaRole.SEND, o: ConnectionRequestShareOwner = owner) {
            coordinator.start(o, role) { assertFalse(gate.legacyStartAllowed()); events.add("stop") }
        }
        fun finish() { queue.run(); coordinator.onRequestState(controller.state.value) }
        fun link(role: MediaRole = MediaRole.SEND, o: ConnectionRequestShareOwner = owner): String? =
            coordinator.state.value.delivery?.let { coordinator.takeLink(o, it, role) }
        val ui get() = coordinator.state.value
    }
    @Test fun sendArmStopCreateOrderIsSynchronous() {
        val f = Fixture(); f.start(); assertEquals(listOf("arm", "stop"), f.events)
        assertEquals(0, f.creates); f.finish(); assertEquals(listOf("arm", "stop", "create"), f.events)
        assertEquals(MediaRole.SEND, f.createdRole)
    }
    @Test fun receiveArmStopCreateOrderIsSynchronous() {
        val f = Fixture(); f.start(MediaRole.RECEIVE); assertEquals(listOf("arm", "stop"), f.events)
        f.finish(); assertEquals(MediaRole.RECEIVE, f.createdRole); assertTrue(f.link(MediaRole.RECEIVE)!!.endsWith("i=receive"))
    }
    @Test fun sendUrlAndCanonicalFragment() {
        val f = Fixture(); f.start(); f.finish(); val link = f.link()!!
        assertEquals("https://pairing.pradert.synology.me/connect#v=1&r=${token(16, 1)}&c=${token(16, 18)}&i=send", link)
        val uri = URI(link); assertNull(uri.rawQuery); assertEquals("/connect", uri.rawPath)
        assertFalse(link.contains(token(32, 17))); assertFalse(link.contains("%26")); assertFalse(link.contains("%3D"))
    }
    @Test fun builderRejectsMalformedOrNonCanonicalData() {
        for (bad in listOf("bad", "B".repeat(22), token(16, 1) + "=", "A&x=1")) {
            assertNull(ConnectionRequestLink.build(ConnectionRequestShareData(bad, token(16, 2), MediaRole.SEND)))
            assertNull(ConnectionRequestLink.build(ConnectionRequestShareData(token(16, 1), bad, MediaRole.RECEIVE)))
        }
    }
    @Test fun targetedDataAndWrapperDoNotExposeInitiatorAuthority() {
        val f = Fixture(); f.start(); f.finish(); val data = f.controller.shareableInitiatorData(MediaRole.SEND)!!
        assertEquals(token(16, 18), data.recipientCapability)
        for (secret in listOf(token(16, 1), token(16, 18), token(32, 17))) assertFalse(data.toString().contains(secret))
        assertNull(f.controller.shareableInitiatorData(MediaRole.RECEIVE))
    }
    private fun reshare(role: MediaRole) {
        val f = Fixture(); f.start(role); f.finish(); val first = f.link(role)!!
        f.coordinator.chooserReturned(f.owner); f.start(role); f.finish()
        assertEquals(first, f.link(role)); assertEquals(1, f.creates); assertEquals(0, f.cancels)
        assertFalse(f.gate.legacyStartAllowed())
    }
    @Test fun sendWaitingReshareNoCreate() = reshare(MediaRole.SEND)
    @Test fun receiveWaitingReshareNoCreate() = reshare(MediaRole.RECEIVE)
    @Test fun differentRoleReplacesAndNeverSharesOldRole() {
        val f = Fixture(); f.start(); f.finish(); val send = f.link()!!; f.coordinator.chooserReturned(f.owner)
        f.start(MediaRole.RECEIVE); f.finish(); val receive = f.link(MediaRole.RECEIVE)!!
        assertNotEquals(send, receive); assertTrue(receive.endsWith("i=receive")); assertEquals(1, f.cancels); assertEquals(2, f.creates)
        assertFalse(f.gate.legacyStartAllowed())
    }
    @Test fun doubleTapBeforeCreateDoesNotArmStopOrCreateTwice() {
        val f = Fixture(); f.start(); f.start(); assertEquals(listOf("arm", "stop"), f.events)
        f.finish(); assertEquals(1, f.creates)
    }
    @Test fun doubleTapBeforeAndDuringChooserDoesNotReplay() {
        val f = Fixture(); f.start(); f.finish(); val delivery = f.ui.delivery!!
        f.start(); assertNotNull(f.link()); assertNull(f.coordinator.takeLink(f.owner, delivery, MediaRole.SEND))
        f.start(); assertEquals(1, f.creates); assertEquals(1, f.events.count { it == "stop" })
    }
    @Test fun successDeliveryIsExactlyOnceEvenWithRepeatedObservations() {
        val f = Fixture(); f.start(); f.finish(); val id = f.ui.delivery
        repeat(3) { f.coordinator.onRequestState(f.controller.state.value) }
        assertEquals(id, f.ui.delivery); assertNotNull(f.link()); assertNull(f.link())
    }
    @Test fun allCreateFailuresReleaseWithoutRestartOrChooser() {
        for (error in listOf(ConnectionRequestError.NETWORK, ConnectionRequestError.UNAVAILABLE, ConnectionRequestError.CONFLICT,
            ConnectionRequestError.RATE_LIMITED, ConnectionRequestError.SERVER, ConnectionRequestError.INVALID_RESPONSE, ConnectionRequestError.CONFIGURATION)) {
            val f = Fixture(); f.createError = error; f.start(); f.finish()
            assertNull(f.ui.delivery); assertEquals(RequestShareError.CREATE_FAILED, f.ui.error)
            assertTrue(f.gate.legacyStartAllowed()); assertEquals(listOf("arm", "stop", "create", "release"), f.events)
        }
    }
    @Test fun malformedHttpCreateDoesNotOfferChooser() {
        val queue = Queue(); val gate = LegacyConnectionStartGate()
        val controller = ConnectionRequestController(ConnectionRequestApiClient(ConnectionRequestTransport { _, _, _, _ -> ConnectionRequestWireResponse(201, "bad-json") }), queue, elapsedMs = { 0 })
        val coordinator = ConnectionRequestShareCoordinator(controller, gate::armRequestFirst, gate::releaseRequestFirst)
        coordinator.start(ConnectionRequestShareOwner(), MediaRole.SEND) {}
        queue.run(); coordinator.onRequestState(controller.state.value)
        assertNull(coordinator.state.value.delivery); assertTrue(gate.legacyStartAllowed())
    }
    @Test fun chooserFailureKeepsWaitingAndRetryUsesSameLink() {
        val f = Fixture(); f.start(); f.finish(); val first = f.link()
        f.coordinator.chooserFailed(f.owner)
        assertEquals(ConnectionRequestState.WAITING, f.controller.state.value.request?.state)
        assertFalse(f.gate.legacyStartAllowed()); assertEquals(RequestShareError.CHOOSER_FAILED, f.ui.error)
        f.start(); f.finish(); assertEquals(first, f.link()); assertEquals(1, f.creates)
    }
    @Test fun externalUiReturnDoesNotCreateOrRelease() {
        val f = Fixture(); f.start(); f.finish(); f.link(); f.coordinator.chooserReturned(f.owner)
        repeat(3) { f.coordinator.onRequestState(f.controller.state.value) }
        assertEquals(1, f.creates); assertEquals(ConnectionRequestState.WAITING, f.controller.state.value.request?.state)
        assertFalse(f.gate.legacyStartAllowed()); assertNull(f.ui.delivery)
    }
    @Test fun recreationDuringCreateContinuesWithoutAutomaticChooserOrDuplicate() {
        val f = Fixture(); f.start(); f.coordinator.detach(f.owner)
        val newOwner = ConnectionRequestShareOwner(); f.start(o = newOwner); assertEquals(1, f.queue.tasks.size)
        f.finish(); assertNull(f.ui.delivery); assertFalse(f.ui.busy); assertEquals(1, f.creates)
        f.start(o = newOwner); f.finish(); assertNotNull(f.link(o = newOwner)); assertEquals(1, f.creates)
    }
    @Test fun recreationWithReadyOrOpenChooserDoesNotReplay() {
        for (taken in listOf(false, true)) {
            val f = Fixture(); f.start(); f.finish(); if (taken) f.link()
            f.coordinator.detach(f.owner); val fresh = ConnectionRequestShareOwner()
            assertNull(f.ui.delivery); f.coordinator.chooserReturned(f.owner)
            f.start(o = fresh); f.finish(); assertNotNull(f.link(o = fresh)); assertEquals(1, f.creates)
        }
    }
    @Test fun wrongOwnerCannotConsumeOrCancelNewDelivery() {
        val f = Fixture(); f.start(); f.finish(); val outsider = ConnectionRequestShareOwner()
        assertNull(f.link(o = outsider)); val before = f.ui
        f.coordinator.chooserFailed(outsider); f.coordinator.chooserReturned(outsider); f.coordinator.detach(outsider)
        assertEquals(before, f.ui); assertNotNull(f.link())
    }
    @Test fun roleChangeCannotLaunchOldRoleLink() {
        val f = Fixture(); f.start(); f.finish(); assertNull(f.link(MediaRole.RECEIVE)); assertNull(f.ui.delivery)
        assertEquals(RequestShareError.UNAVAILABLE, f.ui.error)
    }
    @Test fun nonWaitingRequestRefusesShareWithoutSilentReplacement() {
        for (state in listOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING, ConnectionRequestState.CONNECTED)) {
            val f = Fixture(); f.start(); f.finish(); f.link(); f.coordinator.chooserReturned(f.owner)
            f.serverState = state; f.controller.refreshStatus(); f.queue.run()
            val count = f.events.size; f.start()
            assertEquals(count, f.events.size); assertEquals(1, f.creates); assertNull(f.ui.delivery)
            assertEquals(RequestShareError.UNAVAILABLE, f.ui.error)
        }
    }
    @Test fun terminalRequestCanCreateNew() {
        val f = Fixture(); f.start(); f.finish(); f.link(); f.coordinator.chooserReturned(f.owner)
        f.serverState = ConnectionRequestState.CANCELED; f.controller.refreshStatus(); f.queue.run()
        f.start(); f.finish(); assertEquals(2, f.creates); assertNotNull(f.link())
    }
    @Test fun elapsedExpiryDoesNotReshareEvenWithWrongPhoneWallClock() {
        val f = Fixture(); f.start(); f.finish(); val old = f.link(); f.coordinator.chooserReturned(f.owner)
        f.elapsed = 300_000L; f.start(); f.finish()
        assertEquals(2, f.creates); assertNotEquals(old, f.link())
    }
    @Test fun replacementCancelFailureAndCreateFailureReleaseOnlyEmptyLocalOwner() {
        val f = Fixture(); f.start(); f.finish(); f.link(); f.coordinator.chooserReturned(f.owner)
        f.cancelError = ConnectionRequestError.NETWORK; f.createError = ConnectionRequestError.NETWORK
        f.start(MediaRole.RECEIVE); f.finish()
        assertNull(f.controller.state.value.request); assertTrue(f.gate.legacyStartAllowed()); assertNull(f.ui.delivery)
        assertEquals(1, f.cancels)
    }
    @Test fun failedOldCancelWithSuccessfulReplacementKeepsGateAndUsesNewRequest() {
        val f = Fixture(); f.start(); f.finish(); f.link(); f.coordinator.chooserReturned(f.owner)
        f.cancelError = ConnectionRequestError.NETWORK; f.start(MediaRole.RECEIVE); f.finish()
        assertFalse(f.gate.legacyStartAllowed()); assertTrue(f.link(MediaRole.RECEIVE)!!.endsWith("i=receive"))
    }
    @Test fun stopFailureNeverCreatesOrOffersChooserAndKeepsGate() {
        val f = Fixture(); f.coordinator.start(f.owner, MediaRole.SEND) { throw IllegalStateException("synthetic") }
        f.finish(); assertEquals(0, f.creates); assertFalse(f.gate.legacyStartAllowed())
        assertEquals(RequestShareError.LEGACY_STOP_FAILED, f.ui.error)
    }
    @Test fun publicShareStateDoesNotContainIdentifiersOrCredentials() {
        val f = Fixture(); f.start(); f.finish()
        val text = f.ui.toString()
        for (value in listOf(token(16, 1), token(16, 18), token(32, 17))) assertFalse(text.contains(value))
    }
    @Test fun senderStopInvalidatesEvenAlreadyQueuedTimersAndSocketCallbacks() {
        val timers = mutableListOf<() -> Unit>()
        var opens = 0; var closes = 0
        lateinit var capturedMessage: (String, String, String) -> Unit
        lateinit var capturedEnded: () -> Unit
        val engine = AutoPairing(object : PairingTransport {
            override fun open(code: String, intent: PairingIntent, message: (String, String, String) -> Unit, ended: () -> Unit): PairingLink {
                opens++; capturedMessage = message; capturedEnded = ended
                return object : PairingLink { override fun send(text: String) = true; override fun close() { closes++ } }
            }
        }, { _, task -> timers.add(task); {} }, {})
        engine.show(); capturedMessage("registration", "ok", "")
        val f = Fixture()
        f.coordinator.start(f.owner, MediaRole.SEND) {
            assertFalse(f.gate.legacyStartAllowed())
            engine.hide(); engine.close()
            assertEquals(PairingPhase.STOPPED, engine.home.phase)
            assertEquals(1, closes)
        }
        timers.toList().forEach { it() }; capturedMessage("registration", "conflict", ""); capturedEnded()
        assertNull(f.gate.runLegacyStart { engine.show() })
        f.finish()
        assertEquals(1, opens); assertEquals(1, f.creates)
        assertEquals(PairingPhase.STOPPED, engine.home.phase)
    }
    @Test fun receiverStopInvalidatesEvenAlreadyQueuedTimersAndSocketCallbacks() {
        val timers = mutableListOf<() -> Unit>()
        var opens = 0; var closes = 0
        lateinit var capturedEvents: ReceiverEvents
        val engine = ReceiverEngine(object : ReceiverWire {
            override fun open(code: String, intent: PairingIntent, events: ReceiverEvents): PairingLink {
                opens++; capturedEvents = events
                return object : PairingLink { override fun send(text: String) = true; override fun close() { closes++ } }
            }
            override fun answer(sdp: String) = sdp
            override fun candidate(value: ReceiverIce) = "synthetic"
        }, { _, _ -> error("No media during waiting") }, { _, task -> timers.add(task); {} }, {})
        engine.create(); capturedEvents.registration("ok")
        val f = Fixture()
        f.coordinator.start(f.owner, MediaRole.RECEIVE) {
            assertFalse(f.gate.legacyStartAllowed()); engine.stop()
            assertEquals(1, closes)
        }
        timers.toList().forEach { it() }; capturedEvents.registration("conflict"); capturedEvents.present(); capturedEvents.ended()
        assertNull(f.gate.runLegacyStart { engine.create() })
        f.finish()
        assertEquals(1, opens); assertEquals(1, f.creates)
        assertEquals(ReceiverPhase.ENDED, engine.home.phase)
    }
    @Test fun customApiCannotBypassInvalidTtlContextValidation() {
        for (ttl in listOf(0L, -1L)) {
            val f = Fixture(); f.ttlMs = ttl
            val attempt = f.controller.create(MediaRole.SEND)!!; f.queue.run()
            assertEquals(ConnectionRequestError.INVALID_RESPONSE, f.controller.state.value.error)
            assertNull(f.controller.state.value.request); assertNull(f.controller.state.value.operation)
            assertNull(f.controller.shareableInitiatorData(MediaRole.SEND, attempt))
            f.start(); f.finish()
            assertNull(f.ui.delivery); assertFalse(f.ui.busy); assertTrue(f.gate.legacyStartAllowed())
        }
    }
    @Test fun customApiDeadlineOverflowFailsBeforePublishingContext() {
        val f = Fixture(); f.elapsed = Long.MAX_VALUE - 1
        f.start(); f.finish()
        assertEquals(ConnectionRequestError.INVALID_RESPONSE, f.controller.state.value.error)
        assertNull(f.controller.state.value.request); assertNull(f.ui.delivery)
        assertFalse(f.ui.busy); assertTrue(f.gate.legacyStartAllowed())
    }
    @Test fun productionClockIncludesDeepSleepAndDoesNotUseUptime() {
        val text = source("ConnectionRequestController")
        assertTrue(text.contains("android.os.SystemClock::elapsedRealtime"))
        assertFalse(text.contains("System::nanoTime")); assertFalse(text.contains("uptimeMillis"))
    }
    @Test fun fourMinutesReusableThenSixMinutesExpired() {
        val f = Fixture(); f.start(); f.finish(); f.link(); f.coordinator.chooserReturned(f.owner)
        f.elapsed = 240_000; assertNotNull(f.controller.shareableInitiatorData(MediaRole.SEND))
        f.elapsed += 120_000; assertNull(f.controller.shareableInitiatorData(MediaRole.SEND))
        f.start(); f.finish(); assertEquals(2, f.creates); assertNotNull(f.link())
    }
    @Test fun tenMinuteSleepJumpInvalidatesWaitingLink() {
        val f = Fixture(); f.start(); f.finish()
        f.elapsed = 600_000
        assertNull(f.controller.shareableInitiatorData(MediaRole.SEND)); assertNull(f.link())
    }
    @Test fun networkLatencyDoesNotExtendDeadline() {
        val f = Fixture(); f.onCreate = { f.elapsed = 299_999 }; f.start(); f.finish()
        assertNotNull(f.controller.shareableInitiatorData(MediaRole.SEND))
        f.elapsed = 300_000; assertNull(f.controller.shareableInitiatorData(MediaRole.SEND))
    }
    @Test fun deadlineUsesServerTtlRatherThanClientFiveMinuteConstant() {
        val f = Fixture(); f.ttlMs = 120_000; f.start(); f.finish()
        f.elapsed = 119_999; assertNotNull(f.controller.shareableInitiatorData(MediaRole.SEND))
        f.elapsed = 120_000; assertNull(f.controller.shareableInitiatorData(MediaRole.SEND))
    }
    @Test fun statusRefreshCannotExtendOriginalDeadline() {
        val f = Fixture(); f.start(); f.finish()
        f.elapsed = 299_000; f.controller.refreshStatus(); f.queue.run()
        f.elapsed = 300_000; assertNull(f.controller.shareableInitiatorData(MediaRole.SEND))
    }
    @Test fun replacementGetsIndependentDeadline() {
        val f = Fixture(); f.start(); f.finish(); f.link(); f.coordinator.chooserReturned(f.owner)
        f.elapsed = 200_000; f.start(MediaRole.RECEIVE); f.finish()
        f.elapsed = 300_001; assertNotNull(f.controller.shareableInitiatorData(MediaRole.RECEIVE))
        f.elapsed = 500_000; assertNull(f.controller.shareableInitiatorData(MediaRole.RECEIVE))
    }
    @Test fun createReturnsOpaqueAttemptAndTagsOnlyItsOwnContext() {
        val f = Fixture(); val a = f.controller.create(MediaRole.SEND)!!
        assertEquals("ConnectionRequestCreateAttempt[redacted]", a.toString())
        assertNull(f.controller.shareableInitiatorData(MediaRole.SEND, a))
        f.queue.run(); assertNotNull(f.controller.shareableInitiatorData(MediaRole.SEND, a))
        assertNull(f.controller.shareableInitiatorData(MediaRole.SEND, ConnectionRequestCreateAttempt()))
    }
    @Test fun locallyRejectedCreateReturnsNoAttempt() {
        val f = Fixture(); f.controller.preview(token(16, 1), token(16, 2), MediaRole.SEND)
        assertNull(f.controller.create(MediaRole.SEND)); assertTrue(f.queue.tasks.isEmpty())
    }
    private fun superseded(role: MediaRole) {
        val f = Fixture(); f.start()
        val b = f.controller.create(role)!!
        f.finish()
        assertNull(f.ui.delivery); assertFalse(f.ui.busy); assertFalse(f.gate.legacyStartAllowed())
        assertNotNull(f.controller.shareableInitiatorData(role, b))
        f.start(role); f.finish(); assertNotNull(f.link(role)); assertEquals(1, f.creates)
    }
    @Test fun sameRoleExternalAttemptCannotSatisfyShare() = superseded(MediaRole.SEND)
    @Test fun differentRoleExternalAttemptCannotSatisfyShare() = superseded(MediaRole.RECEIVE)
    @Test fun lateAResponseAfterBSuccessDoesNotDeliverAOrBForOldShare() {
        val f = Fixture()
        f.onCreate = { f.controller.create(MediaRole.SEND); f.queue.run() }
        f.start(); f.finish()
        assertEquals(2, f.creates); assertNull(f.ui.delivery); assertFalse(f.ui.busy)
        assertEquals(1, f.cancels) // Stale A successful response is retired best-effort.
        f.start(); f.finish(); assertNotNull(f.link()); assertEquals(2, f.creates)
    }
    @Test fun replacingCreateAfterDeliveryCannotDeliverForeignLink() {
        val f = Fixture(); f.start(); f.finish()
        f.controller.create(MediaRole.SEND); f.queue.run()
        assertNull(f.link()); assertFalse(f.ui.busy)
    }
    @Test fun supersededFailureCannotReleaseAnotherAttemptsGate() {
        val f = Fixture(); f.start(); f.controller.create(MediaRole.SEND)
        f.createError = ConnectionRequestError.NETWORK; f.finish()
        assertFalse(f.ui.busy); assertNull(f.ui.delivery); assertFalse(f.gate.legacyStartAllowed())
    }
    @Test fun emptyAttemptCompletionPolicyRejectsStaleAttemptAndActiveContext() {
        val f = Fixture(); val a = f.controller.create(MediaRole.SEND)!!
        val b = f.controller.create(MediaRole.SEND)!!; var releases = 0
        f.controller.ifEmptyCurrentCreateAttempt(a) { releases++ }
        f.controller.ifEmptyCurrentCreateAttempt(b) { releases++ }
        assertEquals(0, releases)
        f.queue.run(); f.controller.ifEmptyCurrentCreateAttempt(b) { releases++ }
        assertEquals(0, releases)
        f.controller.clear(); f.controller.ifEmptyCurrentCreateAttempt(b) { releases++ }
        assertEquals(0, releases)
    }
    @Test fun validArmedReshareDoesNotArmOrStopAgain() {
        val f = Fixture(); f.start(); f.finish(); val first = f.link(); f.coordinator.chooserReturned(f.owner)
        f.events.clear(); f.start(); assertTrue(f.events.isEmpty()); assertEquals(first, f.link())
    }
    @Test fun validUnarmedReshareArmsThenStopsWithoutCreate() {
        val f = Fixture(); f.start(); f.finish(); val first = f.link(); f.coordinator.chooserReturned(f.owner)
        f.gate.releaseRequestFirst(); f.events.clear(); f.start()
        assertEquals(listOf("arm", "stop"), f.events); assertEquals(first, f.link()); assertEquals(1, f.creates)
    }
    private fun source(name: String): String {
        val path = "src/main/java/com/pradertamas/uicapturetest/$name.kt"
        return listOf(File(path), File("app/$path")).first { it.isFile }.readText()
    }
    @Test fun activitiesWireSynchronousStopsAndSharedIntentBoundary() {
        val main = source("MainActivity"); val receiver = source("ReceiverActivity")
        val stop = main.substringAfter("rememberRequestShare(mediaMode.role) {").substringBefore('}')
        assertTrue(stop.indexOf("controller.hide()") < stop.indexOf("controller.close()"))
        assertTrue(receiver.contains("rememberRequestShare(mediaMode.role) { model.stop() }"))
        assertFalse(stop.contains("post")); assertFalse(stop.contains("launch"))
        val ui = source("ConnectionRequestShareUi")
        assertTrue(ui.contains("Looper.myLooper() == Looper.getMainLooper()"))
        assertTrue(ui.contains("lifecycle == Lifecycle.State.RESUMED"))
        assertTrue(ui.contains("Intent.ACTION_SEND")); assertTrue(ui.contains("Intent.EXTRA_TEXT, link"))
        assertTrue(ui.contains("DisposableEffect(owner, role)"))
    }
    @Test fun sharingHasNoLegacyProtectionPollingSignalingOrProjection() {
        val text = source("ConnectionRequestShareCoordinator") + source("ConnectionRequestShareUi")
        for (forbidden in listOf("protectSharedWaiting", "sharedWaitingProtected", "remoteSharing", "participant-state", "createSession(",
            "setReady(", "newWebSocket", "MediaProjection", "createScreenCaptureIntent", "controller.show", "controller.join", "Timer(", "while (", "delay(", "Handler(")) assertFalse(text.contains(forbidden))
        assertFalse(source("MainActivity").contains("protectSharedWaiting()"))
        assertFalse(source("ReceiverActivity").contains("receiverUrl(ui.home.code)"))
    }
}
