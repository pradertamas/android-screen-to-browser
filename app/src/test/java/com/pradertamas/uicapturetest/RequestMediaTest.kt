package com.pradertamas.uicapturetest

import java.time.Instant
import java.util.Base64
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RequestMediaTest {
    private fun token(n: Int, bytes: Int = 16) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes) { n.toByte() })
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { var n = 0; while (tasks.isNotEmpty()) { check(++n < 100); tasks.removeFirst().run() } }
        fun dispatch(action: () -> Unit) = execute(Runnable(action))
    }
    private inner class Fixture {
        val worker = Queue()
        var server = ConnectionRequestState.WAITING
        var role = MediaRole.SEND
        var assignment: ConnectionAssignment? = null
        var cancels = 0; var withdraws = 0; var creates = 0
        var exitError: ConnectionRequestError? = null
        var onCancel: (() -> Unit)? = null
        val now = Instant.parse("2026-01-01T00:00:00Z")
        fun metadata(id: String) = ConnectionRequestMetadata(id, server, now, role, now, now.plusSeconds(300),
            acceptedAt = now, connectBy = now.plusSeconds(120), assignment = assignment)
        val api = object : ConnectionRequestApi {
            override fun create(role: MediaRole): ConnectionRequestResult {
                creates++; this@Fixture.role = role; server = ConnectionRequestState.WAITING; assignment = null
                val m = metadata(token(creates))
                return ConnectionRequestResult(m, httpStatus = 201, created = ConnectionRequestCreated(m,
                    ConnectionCredential(token(30, 32)), ConnectionCredential(token(31))))
            }
            override fun status(id: String, auth: ConnectionCredential, scope: ConnectionRequestAuthScope) = ConnectionRequestResult(metadata(id))
            override fun accept(id: String, capability: ConnectionCredential, claim: ConnectionCredential): ConnectionRequestResult {
                server = ConnectionRequestState.ACCEPTED; return ConnectionRequestResult(metadata(id), httpStatus = 200)
            }
            override fun cancel(id: String, auth: ConnectionCredential): ConnectionRequestResult {
                cancels++; onCancel?.invoke()
                if (exitError != null) return ConnectionRequestResult(error = exitError, httpStatus = 409)
                server = ConnectionRequestState.CANCELED; assignment = null
                return ConnectionRequestResult(metadata(id))
            }
            override fun withdraw(id: String, auth: ConnectionCredential): ConnectionRequestResult {
                withdraws++
                if (exitError != null) return ConnectionRequestResult(error = exitError)
                server = ConnectionRequestState.FAILED; assignment = null
                return ConnectionRequestResult(metadata(id).copy(failureReason = "recipient_withdrew"))
            }
            override fun ready(id: String, auth: ConnectionCredential, ready: Boolean, scope: ConnectionRequestAuthScope) = ConnectionRequestResult()
            override fun session(id: String, auth: ConnectionCredential) = ConnectionRequestResult()
        }
        val controller = ConnectionRequestController(api, worker, { 0 }, { ConnectionCredential(token(40, 32)) })
        val handoffs = ConnectionRequestMediaHandoff(controller)
        fun initiator(local: MediaRole = MediaRole.SEND): ConnectionRequestCreateAttempt {
            val flow = controller.create(local)!!; worker.run()
            server = ConnectionRequestState.CONNECTING; assignment = ConnectionAssignment("ABC234", local, PairingIntent.CREATE)
            controller.refreshStatus(); worker.run(); return flow
        }
        fun recipient(local: MediaRole = MediaRole.RECEIVE): ConnectionRequestRecipientFlow {
            role = ConnectionRequestSetupCoordinator.opposite(local)
            val flow = controller.preview(token(1), token(31), role)!!
            controller.accept(flow); worker.run()
            server = ConnectionRequestState.CONNECTING; assignment = ConnectionAssignment("ABC234", local, PairingIntent.JOIN)
            controller.refreshStatus(flow); worker.run(); return flow
        }
    }
    private class Socket : ProtectedSocket {
        val sent = mutableListOf<String>(); var closes = 0
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun close(code: Int) { closes++ }
        override fun cancel() {}
    }
    private class Factory : ProtectedSocketFactory {
        val socket = Socket(); lateinit var listener: ProtectedSocketListener; var opens = 0
        override fun open(url: String, listener: ProtectedSocketListener): ProtectedSocket { opens++; this.listener = listener; return socket }
        fun register(role: MediaRole, intent: PairingIntent) {
            listener.opened(socket)
            listener.text(JSONObject().put("type", "registration").put("status", "ok").put("role", role.signalingRole)
                .put("intent", intent.wire).put("remotePresence", false).toString())
        }
        fun text(type: String) = listener.text(JSONObject().put("type", type).toString())
    }
    private class SenderEvents : SenderSignalingEvents {
        val order = mutableListOf<String>()
        override fun registered() { order.add("ready") }
        override fun peerPresent() { order.add("peer") }
        override fun payload(text: String) { order.add(JSONObject(text).getString("type")) }
        override fun ended() { order.add("ended") }
    }
    @Test fun handoffIsOneShotAndFresh() {
        val f = Fixture(); val a = f.initiator(); val id = f.handoffs.reserve(a, MediaRole.SEND)!!
        assertNull(f.handoffs.reserve(a, MediaRole.SEND))
        val redeemed = f.handoffs.consume(id, MediaRole.SEND)!!
        assertSame(a, redeemed.bootstrap.flow); assertEquals(RequestMediaPhase.STARTED, f.controller.mediaPhase(redeemed.owner))
        assertNull(f.handoffs.consume(id, MediaRole.SEND))
        f.handoffs.disposed(redeemed.owner)
        assertEquals(RequestMediaPhase.DISPOSED, f.controller.mediaPhase(redeemed.owner))
        assertNull(f.controller.protectedSignalingBootstrap(a))
    }
    @Test fun concurrentHandoffConsumptionHasExactlyOneWinner() {
        val f = Fixture(); val id = f.handoffs.reserve(f.initiator(), MediaRole.SEND)!!
        val start = CountDownLatch(1); val wins = AtomicInteger()
        val threads = List(2) { Thread { start.await(); if (f.handoffs.consume(id, MediaRole.SEND) != null) wins.incrementAndGet() } }
        threads.forEach(Thread::start); start.countDown(); threads.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertEquals(1, wins.get())
    }
    @Test fun sameRoleReplacementRejectsOldHandoffAndOldDisposedAck() {
        val f = Fixture(); val a = f.initiator(); val old = f.handoffs.reserve(a, MediaRole.SEND)!!
        val b = f.initiator(); assertNull(f.handoffs.consume(old, MediaRole.SEND))
        val fresh = f.handoffs.reserve(b, MediaRole.SEND)!!
        assertSame(b, f.handoffs.consume(fresh, MediaRole.SEND)!!.bootstrap.flow)
    }
    @Test fun wrongRoleFailsClosed() {
        val f = Fixture(); val a = f.initiator(); assertNull(f.handoffs.reserve(a, MediaRole.RECEIVE))
        val id = f.handoffs.reserve(a, MediaRole.SEND)!!
        assertNull(f.handoffs.consume(id, MediaRole.RECEIVE)); assertNull(f.handoffs.consume(id, MediaRole.SEND))
    }
    @Test fun captureFailureNeverRedeemsOrAuthenticates() {
        val order = mutableListOf<String>()
        try { captureBeforeRequestSignaling({ order.add("capture"); error("failure") }, { order.add("redeem"); Any() }, { order.add("open") }) }
        catch (_: IllegalStateException) {}
        assertEquals(listOf("capture"), order)
    }
    @Test fun captureSuccessPrecedesFreshBootstrapAndSocketOpen() {
        val f = Fixture(); val id = f.handoffs.reserve(f.initiator(), MediaRole.SEND)!!
        val order = mutableListOf<String>(); val factory = Factory(); val queue = Queue()
        captureBeforeRequestSignaling({ order.add("capture") }, { order.add("redeem"); f.handoffs.consume(id, MediaRole.SEND) }) {
            order.add("open"); ProtectedSenderSignalingChannel(it.bootstrap, factory, queue::dispatch).open(SenderEvents())
        }
        assertEquals(listOf("capture", "redeem", "open"), order); assertEquals(1, factory.opens)
        assertTrue(factory.socket.sent.isEmpty()) // Auth only on socket open.
    }
    @Test fun protectedSenderRequiresRealPeerAndForwardsAnswerIceAndEndsOnce() {
        val f = Fixture(); val flow = f.initiator(); val b = f.controller.protectedSignalingBootstrap(flow)!!
        val factory = Factory(); val q = Queue(); val events = SenderEvents()
        val channel = ProtectedSenderSignalingChannel(b, factory, q::dispatch); channel.open(events)
        factory.register(MediaRole.SEND, PairingIntent.CREATE); q.run()
        assertEquals(listOf("ready"), events.order)
        factory.text("peer-present"); factory.text("peer-present"); q.run()
        factory.listener.text("""{"type":"answer","sdp":"answer"}""")
        factory.listener.text("""{"type":"ice","candidate":{"candidate":"ice","sdpMid":"0","sdpMLineIndex":0}}"""); q.run()
        assertTrue(channel.send("""{"type":"offer","sdp":"offer"}"""))
        factory.text("peer-left"); q.run(); factory.listener.failed(); q.run()
        assertEquals(listOf("ready", "peer", "answer", "ice", "ended"), events.order)
    }
    @Test fun protectedSenderRejectsInboundOffer() {
        val f = Fixture(); val b = f.controller.protectedSignalingBootstrap(f.initiator())!!
        val factory = Factory(); val q = Queue(); val events = SenderEvents()
        ProtectedSenderSignalingChannel(b, factory, q::dispatch).open(events)
        factory.register(MediaRole.SEND, PairingIntent.CREATE); q.run()
        factory.listener.text("""{"type":"offer","sdp":"bad"}"""); q.run()
        assertEquals(listOf("ready", "ended"), events.order)
    }
    @Test fun legacyAdapterKeepsEpochSyntheticPresenceAndLifecycle() {
        val q = Queue(); val events = SenderEvents(); val calls = mutableListOf<String>()
        val host = object : LegacySenderHost {
            override fun attach(epoch: Long, payload: (String) -> Unit, ended: () -> Unit, result: (Boolean) -> Unit) { calls.add("attach:$epoch"); result(true) }
            override fun send(epoch: Long, text: String, result: (Boolean) -> Unit) { calls.add("send:$epoch"); result(true) }
            override fun streaming(epoch: Long) { calls.add("stream:$epoch") }
            override fun disposed(epoch: Long) { calls.add("dispose:$epoch") }
        }
        val channel = LegacySenderSignalingChannel(7, q::dispatch, host)
        channel.open(events); q.run(); channel.send("offer"); channel.streaming(); channel.close(); channel.disposed()
        assertEquals(listOf("ready", "peer"), events.order)
        assertEquals(listOf("attach:7", "send:7", "stream:7", "dispose:7"), calls)
    }
    private inner class ReceiverFixture(recipient: Boolean = false) {
        val f = Fixture(); val flow: Any = if (recipient) f.recipient() else f.initiator(MediaRole.RECEIVE)
        val factory = Factory(); val q = Queue(); val timers = mutableListOf<Long>(); var generated = 0; var media = 0; var disposed = 0
        val receivedIce = mutableListOf<ReceiverIce>()
        var localIce: ((ReceiverIce) -> Unit)? = null
        var mediaFailure: (() -> Unit)? = null
        val legacy = object : ReceiverWire {
            override fun open(code: String, intent: PairingIntent, events: ReceiverEvents): PairingLink = error("legacy forbidden")
            override fun answer(sdp: String) = error("legacy forbidden")
            override fun candidate(value: ReceiverIce) = error("legacy forbidden")
        }
        val router = ReceiverWireRouter(legacy)
        val engine = ReceiverEngine(router, { candidate, failure -> media++; localIce = candidate; mediaFailure = failure; object : ReceiverMedia {
            override fun offer(sdp: String, remoteReady: () -> Unit, answer: (String) -> Unit, failure: () -> Unit) { remoteReady(); answer("answer") }
            override fun ice(candidate: ReceiverIce) { receivedIce.add(candidate) }
            override fun close() { disposed++ }
        } }, { ms, _ -> timers.add(ms); {} }, {}, { generated++; "ABC234" })
        val owner = RequestReceiverOwner(engine, router, f.handoffs, q::dispatch, factory)
        fun start() { assertTrue(owner.start(f.handoffs.reserve(flow, MediaRole.RECEIVE)!!)) }
        fun register() { factory.register(MediaRole.RECEIVE, if (flow is ConnectionRequestRecipientFlow) PairingIntent.JOIN else PairingIntent.CREATE); q.run() }
    }
    @Test fun requestCreateReceiverDoesNotGenerateRotateOrOffer() {
        val r = ReceiverFixture(); r.start(); r.register()
        assertEquals("", r.engine.home.code); assertEquals(0, r.generated); assertFalse(r.timers.contains(ReceiverEngine.ROTATE_MS))
        r.factory.text("peer-present"); r.q.run()
        assertEquals(ReceiverPhase.CONNECTING, r.engine.home.phase); assertEquals(0, r.media)
        r.engine.create(); assertFalse(r.engine.join("ABC234")); assertEquals(1, r.factory.opens)
    }
    @Test fun requestJoinReceiverUsesAssignmentAndExistingAnswerMedia() {
        val r = ReceiverFixture(true); r.start(); r.register(); r.factory.text("peer-present"); r.q.run()
        r.factory.listener.text("""{"type":"offer","sdp":"offer"}"""); r.q.run()
        assertEquals(1, r.media); assertEquals("answer", JSONObject(r.factory.socket.sent.last()).getString("type"))
        r.factory.text("bye"); r.q.run(); assertFalse(r.owner.active); assertEquals(1, r.disposed)
    }
    @Test fun receiverAnswerInboundFailsClosed() {
        val r = ReceiverFixture(); r.start(); r.register()
        r.factory.listener.text("""{"type":"answer","sdp":"wrong"}"""); r.q.run()
        assertFalse(r.owner.active); assertEquals(0, r.media)
    }
    @Test fun requestRegistrationConflictNeverRetriesOrGenerates() {
        val r = ReceiverFixture(); r.start(); r.factory.listener.opened(r.factory.socket)
        r.factory.listener.text("""{"type":"registration","status":"conflict","intent":"create","role":"receiver","remotePresence":false}"""); r.q.run()
        assertFalse(r.owner.active); assertEquals(1, r.factory.opens); assertEquals(0, r.generated)
    }
    @Test fun retainedReceiverOwnerRejectsSecondStartOrStaleStop() {
        val r = ReceiverFixture(); r.start(); r.register()
        // Retained-owner behavior only; actual renderer/Activity recreation is pending B4E/device testing.
        val retained = r.owner
        assertSame(retained, r.owner); assertTrue(retained.active)
        assertFalse(retained.start("another")); assertFalse(retained.stop(Any())); assertEquals(1, r.factory.opens)
        assertTrue(retained.stop(r.flow)); assertFalse(retained.active)
        assertFalse(r.owner.start("already-consumed"))
    }
    @Test fun scopedInitiatorCancelRejectsStaleSameRoleFlow() {
        val f = Fixture(); val a = f.initiator(); val b = f.initiator(); val before = f.cancels
        assertFalse(f.controller.cancelInitiator(a)); f.worker.run(); assertEquals(before, f.cancels)
        assertTrue(f.controller.cancelInitiator(b)); f.worker.run(); assertEquals(before + 1, f.cancels)
    }
    @Test fun exitNetworkFailureKeepsContextAndExplicitRetryButPreventsRestart() {
        val f = Fixture(); val a = f.initiator(); val id = f.handoffs.reserve(a, MediaRole.SEND)!!
        val owner = f.handoffs.consume(id, MediaRole.SEND)!!.owner
        assertFalse(f.controller.requestMediaExit(a)) // Must acknowledge local teardown first.
        f.handoffs.disposed(owner); f.exitError = ConnectionRequestError.NETWORK
        assertTrue(f.controller.requestMediaExit(a)); f.worker.run()
        assertSame(a, f.controller.setupSnapshot()!!.flow); assertEquals(ConnectionRequestError.NETWORK, f.controller.state.value.error)
        assertNull(f.handoffs.reserve(a, MediaRole.SEND)); assertNull(f.controller.protectedSignalingBootstrap(a))
        assertFalse(f.controller.clearDisposedMedia(a))
        f.exitError = null; assertTrue(f.controller.requestMediaExit(a)); f.worker.run()
        assertTrue(f.controller.clearDisposedMedia(a)); assertNull(f.controller.setupSnapshot())
    }
    @Test fun recipientWithdrawFailureRetainsClaimAndAllowsExactRetry() {
        val f = Fixture(); val a = f.recipient(MediaRole.SEND)
        f.exitError = ConnectionRequestError.NETWORK
        assertTrue(f.controller.requestMediaExit(a)); f.worker.run()
        assertTrue(f.controller.recipientAccess(a)!!.won); assertNull(f.controller.protectedSignalingBootstrap(a))
        f.exitError = null; assertTrue(f.controller.requestMediaExit(a)); f.worker.run()
        assertEquals(ConnectionRequestState.FAILED, f.controller.state.value.request!!.state)
    }
    @Test fun connectedConflictReconcilesWithoutInventingLocalTerminal() {
        val f = Fixture(); val a = f.initiator(); f.exitError = ConnectionRequestError.INVALID_OPERATION
        f.onCancel = { f.server = ConnectionRequestState.CONNECTED }
        assertTrue(f.controller.requestMediaExit(a)); f.worker.run()
        assertEquals(ConnectionRequestState.CONNECTING, f.controller.state.value.request!!.state)
        assertTrue(f.controller.reconcileMediaExit(a)); f.worker.run()
        assertEquals(ConnectionRequestState.CONNECTED, f.controller.state.value.request!!.state)
        assertFalse(f.controller.requestMediaExit(a)); assertNull(f.controller.protectedSignalingBootstrap(a))
    }
    @Test fun freshRedemptionRejectsConnectedAndDoesNotCacheEarlierAuthority() {
        val f = Fixture(); val a = f.initiator(); val id = f.handoffs.reserve(a, MediaRole.SEND)!!
        assertEquals(RequestMediaPhase.RESERVED, f.controller.mediaPhaseForFlow(a))
        f.server = ConnectionRequestState.CONNECTED; f.controller.refreshStatus(); f.worker.run()
        assertNull(f.handoffs.consume(id, MediaRole.SEND))
        assertEquals(RequestMediaPhase.DISPOSED, f.controller.mediaPhaseForFlow(a))
        assertTrue(f.controller.clearDisposedMedia(a))
    }
    @Test fun liveMediaOwnerCannotBeReplacedBeforeDisposal() {
        val f = Fixture(); val a = f.initiator(); val id = f.handoffs.reserve(a, MediaRole.SEND)!!
        val owner = f.handoffs.consume(id, MediaRole.SEND)!!.owner
        assertNull(f.controller.create(MediaRole.SEND)); assertFalse(f.controller.cancelInitiator(a))
        f.handoffs.disposed(owner)
        assertNotNull(f.controller.create(MediaRole.SEND))
    }
    @Test fun staleExitCompletionCannotOverwriteReplacementOrReleaseGate() {
        val f = Fixture(); val a = f.initiator(); var b: ConnectionRequestCreateAttempt? = null
        ConnectionRequestRuntimeGate.armRequestFirst()
        try {
            f.onCancel = { f.onCancel = null; b = f.controller.create(MediaRole.SEND) }
            assertTrue(f.controller.requestMediaExit(a)); f.worker.run()
            assertSame(b, f.controller.setupSnapshot()!!.flow)
            assertEquals(ConnectionRequestState.WAITING, f.controller.state.value.request!!.state)
            assertFalse(f.controller.clearDisposedMedia(a)); assertFalse(f.controller.reconcileMediaExit(a))
            assertFalse(ConnectionRequestRuntimeGate.legacyStartAllowed())
        } finally { ConnectionRequestRuntimeGate.releaseRequestFirst() }
    }
    @Test fun receiverIceUsesExistingBufferAndDisposedAcknowledgement() {
        val r = ReceiverFixture(); r.start(); r.register(); r.factory.text("peer-present"); r.q.run()
        r.factory.listener.text("""{"type":"ice","candidate":{"candidate":"ice","sdpMid":"0","sdpMLineIndex":0}}"""); r.q.run()
        r.factory.listener.text("""{"type":"offer","sdp":"offer"}"""); r.q.run()
        assertEquals(listOf(ReceiverIce("0", 0, "ice")), r.receivedIce)
        r.localIce!!(ReceiverIce(null, 1, "local"))
        assertEquals("local", JSONObject(r.factory.socket.sent.last()).getJSONObject("candidate").getString("candidate"))
        r.engine.firstFrame(); assertEquals(ReceiverPhase.RECEIVING, r.engine.home.phase)
        r.factory.text("peer-left"); r.q.run()
        assertEquals(1, r.disposed); assertEquals(RequestMediaPhase.DISPOSED, r.f.controller.mediaPhaseForFlow(r.flow))
        assertNull(r.f.handoffs.reserve(r.flow, MediaRole.RECEIVE))
    }
    @Test fun lateReceiverEventsAfterStopNeverRecreateMedia() {
        val r = ReceiverFixture(true); r.start(); r.register(); assertTrue(r.owner.stop(r.flow))
        r.factory.text("peer-present"); r.factory.listener.text("""{"type":"offer","sdp":"late"}"""); r.q.run()
        assertEquals(0, r.media); assertFalse(r.owner.active); assertEquals(ReceiverPhase.ENDED, r.engine.home.phase)
    }
    @Test fun localIdentifiersHaveRedactedDiagnostics() {
        val f = Fixture(); val id = f.handoffs.reserve(f.initiator(), MediaRole.SEND)!!
        assertFalse(id.toString().contains(id.value)); assertFalse(id.value.contains("ABC234"))
        val redemption = f.handoffs.consume(id, MediaRole.SEND)!!
        assertEquals("RequestMediaOwner[redacted]", redemption.owner.toString())
        assertEquals("RequestMediaRedemption[redacted]", redemption.toString())
    }
    private fun cleanup(f: Fixture, id: RequestMediaHandoffId?, legacy: (Long) -> Unit = { error("legacy forbidden") }) =
        SenderStartCleanup(SenderStartMode.REQUEST, id?.value, 7, f.handoffs::abandon, f.handoffs::disposed, legacy)

    @Test fun requestPreValidationFailuresAbandonAndAllowSameFlowRetryWithoutHttpExit() {
        for (failure in listOf("missing consent", "invalid consent object", "wrong role", "forbidden legacy extras")) {
            val f = Fixture(); val flow = f.initiator(); val id = f.handoffs.reserve(flow, MediaRole.SEND)!!
            var abandons = 0
            val cleanup = SenderStartCleanup(SenderStartMode.REQUEST, id.value, 7,
                { abandons++; f.handoffs.abandon(it) }, f.handoffs::disposed, { error("legacy forbidden") })
            // Same boundary as service: cleanup authority is captured before fallible reads/validation.
            try { error(failure) } catch (_: Exception) { cleanup.disposeRequest(); cleanup.retireLegacy() }
            cleanup.disposeRequest(); cleanup.retireLegacy()
            assertEquals(1, abandons)
            assertNull(f.handoffs.consume(id, MediaRole.SEND))
            assertNull(f.controller.mediaPhaseForFlow(flow))
            assertNotNull(f.handoffs.reserve(flow, MediaRole.SEND))
            assertEquals(0, f.cancels); assertEquals(0, f.withdraws)
            assertSame(flow, f.controller.setupSnapshot()!!.flow)
        }
    }
    @Test fun unknownMalformedConsumedAndMissingIdsAreHarmless() {
        val f = Fixture(); val flow = f.initiator(); val id = f.handoffs.reserve(flow, MediaRole.SEND)!!
        for (bad in listOf(null, "", "not-a-token", "unknown")) {
            SenderStartCleanup(SenderStartMode.REQUEST, bad, 9, f.handoffs::abandon, f.handoffs::disposed,
                { error("legacy forbidden") }).disposeRequest()
        }
        val redemption = f.handoffs.consume(id, MediaRole.SEND)!!
        f.handoffs.abandon(id.value) // Consumed ID must not dispose the active owner.
        assertEquals(RequestMediaPhase.STARTED, f.controller.mediaPhase(redemption.owner))
    }
    @Test fun requestRedeemedCleanupAcknowledgesOnceInsteadOfAbandoning() {
        val f = Fixture(); val flow = f.initiator(); val id = f.handoffs.reserve(flow, MediaRole.SEND)!!
        var abandons = 0; var acknowledgements = 0
        val cleanup = SenderStartCleanup(SenderStartMode.REQUEST, id.value, 9,
            { abandons++; f.handoffs.abandon(it) }, { acknowledgements++; f.handoffs.disposed(it) }, { error("legacy forbidden") })
        cleanup.redeemed(f.handoffs.consume(id, MediaRole.SEND)!!.owner)
        repeat(5) { cleanup.disposeRequest(); cleanup.retireLegacy() }
        assertEquals(0, abandons); assertEquals(1, acknowledgements)
        assertEquals(RequestMediaPhase.DISPOSED, f.controller.mediaPhaseForFlow(flow))
    }
    @Test fun staleEarlyAbandonCannotTouchNewReservation() {
        val f = Fixture(); val a = f.initiator(); val old = f.handoffs.reserve(a, MediaRole.SEND)!!
        val cleanup = cleanup(f, old); val b = f.initiator(); val fresh = f.handoffs.reserve(b, MediaRole.SEND)!!
        cleanup.disposeRequest(); cleanup.disposeRequest()
        assertEquals(RequestMediaPhase.RESERVED, f.controller.mediaPhaseForFlow(b))
        assertSame(b, f.handoffs.consume(fresh, MediaRole.SEND)!!.owner.flow)
    }
    @Test fun legacyMissingConsentAndInvalidSessionRetireCapturedEpochOnce() {
        for (failure in listOf("missing consent", "invalid session", "wrong role")) {
            val retired = mutableListOf<Long>()
            val cleanup = SenderStartCleanup(SenderStartMode.LEGACY, "ignored-request-id", 17,
                { error("request forbidden") }, { error("request forbidden") }, retired::add)
            try { error(failure) } catch (_: Exception) { cleanup.disposeRequest(); cleanup.retireLegacy() }
            repeat(3) { cleanup.disposeRequest(); cleanup.retireLegacy() }
            assertEquals(listOf(17L), retired)
        }
    }
    @Test fun legacyChannelTakesExactlyOnceRetirementOwnership() {
        var fallback = 0; var channelDisposals = 0
        val cleanup = SenderStartCleanup(SenderStartMode.LEGACY, null, 17,
            { error("request forbidden") }, { error("request forbidden") }, { fallback++ })
        cleanup.legacyChannel(object : SenderSignalingChannel {
            override fun open(events: SenderSignalingEvents) {}
            override fun send(text: String) = true
            override fun close() {}
            override fun disposed() { channelDisposals++ }
        })
        repeat(4) { cleanup.disposeRequest(); cleanup.retireLegacy() }
        assertEquals(1, channelDisposals); assertEquals(0, fallback)
    }
    @Test fun staleLegacyRetirementUsesAutoPairingEpochGuard() {
        val timers = mutableListOf<() -> Unit>()
        val host = AutoPairing(object : PairingTransport {
            override fun open(code: String, intent: PairingIntent, message: (String, String, String) -> Unit, ended: () -> Unit) =
                object : PairingLink { override fun send(text: String) = true; override fun close() {} }
        }, { _, action -> timers.add(action); {} }, {}, { "ABC234" })
        host.show(); val a = host.home.epoch
        val cleanup = SenderStartCleanup(SenderStartMode.LEGACY, null, a,
            { error("request forbidden") }, { error("request forbidden") }, host::mediaStopped)
        host.close(); host.show(); val b = host.home
        cleanup.retireLegacy(); assertEquals(b, host.home)
    }
    @Test fun reservedThenExitBeforeRedeemNeverOpensSocket() {
        val f = Fixture(); val flow = f.initiator(); val id = f.handoffs.reserve(flow, MediaRole.SEND)!!
        assertTrue(f.controller.requestMediaExit(flow)) // HTTP deliberately still queued.
        assertNull(f.handoffs.consume(id, MediaRole.SEND))
        assertNull(f.controller.protectedSignalingBootstrap(flow))
        assertEquals(RequestMediaPhase.DISPOSED, f.controller.mediaPhaseForFlow(flow))
    }
    @Test fun runningSenderLateDisposalCannotAckNewStartedOwner() {
        val f = Fixture(); val a = f.initiator(); val id = f.handoffs.reserve(a, MediaRole.SEND)!!
        val cleanup = cleanup(f, id); val redemption = f.handoffs.consume(id, MediaRole.SEND)!!; cleanup.redeemed(redemption.owner)
        val factory = Factory(); val q = Queue()
        ProtectedSenderSignalingChannel(redemption.bootstrap, factory, q::dispatch).open(object : SenderSignalingEvents {
            override fun registered() {}; override fun peerPresent() {}; override fun payload(text: String) {}
            override fun ended() { cleanup.disposeRequest(); cleanup.retireLegacy() }
        })
        factory.register(MediaRole.SEND, PairingIntent.CREATE); q.run()
        // Explicit local context replacement, not create() bypass of the live-owner guard.
        f.controller.clear(); val b = f.initiator(); val bid = f.handoffs.reserve(b, MediaRole.SEND)!!
        val bOwner = f.handoffs.consume(bid, MediaRole.SEND)!!.owner; val state = f.controller.state.value
        factory.listener.failed(); q.run(); cleanup.disposeRequest()
        assertEquals(RequestMediaPhase.DISPOSED, f.controller.mediaPhase(redemption.owner))
        assertEquals(RequestMediaPhase.STARTED, f.controller.mediaPhase(bOwner)); assertEquals(state, f.controller.state.value)
    }
    @Test fun concurrentScopedCancelsAdmitOnlyOneHttpOperation() {
        val f = Fixture(); val flow = f.initiator(); val start = CountDownLatch(1); val admitted = AtomicInteger()
        val threads = List(2) { Thread { start.await(); if (f.controller.cancelInitiator(flow)) admitted.incrementAndGet() } }
        threads.forEach(Thread::start); start.countDown(); threads.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertEquals(1, admitted.get()); f.worker.run(); assertEquals(1, f.cancels)
    }
    @Test fun receiverExitWinsBeforeStartForInitiatorAndRecipient() {
        for (recipient in listOf(false, true)) {
            val r = ReceiverFixture(recipient); val id = r.f.handoffs.reserve(r.flow, MediaRole.RECEIVE)!!
            assertTrue(r.f.controller.requestMediaExit(r.flow))
            assertFalse(r.owner.start(id)); assertEquals(0, r.factory.opens); assertEquals(0, r.media)
        }
    }
    @Test fun receiverStartWinsThenScopedStopAndFailedExitNeverRestart() {
        for (recipient in listOf(false, true)) {
            val r = ReceiverFixture(recipient); r.start(); r.register()
            assertFalse(r.f.controller.requestMediaExit(r.flow)) // Live owner must be stopped first.
            assertTrue(r.owner.stop(r.flow)); r.f.exitError = ConnectionRequestError.NETWORK
            assertTrue(r.f.controller.requestMediaExit(r.flow)); r.f.worker.run()
            assertNull(r.f.handoffs.reserve(r.flow, MediaRole.RECEIVE)); assertFalse(r.owner.active)
            assertEquals(1, r.factory.opens); assertEquals(0, r.generated)
        }
    }
    @Test fun connectedReceiverSocketFailureBeforeMediaNeverReconnectsOrExitsHttp() {
        val r = ReceiverFixture(); r.start(); r.register()
        r.f.server = ConnectionRequestState.CONNECTED; r.f.controller.refreshStatus(); r.f.worker.run()
        r.factory.listener.failed(); r.q.run()
        assertFalse(r.owner.active); assertEquals(0, r.media); assertEquals(0, r.generated)
        assertEquals(1, r.factory.opens); assertEquals(0, r.f.cancels); assertEquals(0, r.f.withdraws)
        assertNull(r.f.controller.protectedSignalingBootstrap(r.flow as ConnectionRequestCreateAttempt))
    }
    @Test fun connectedReceiverMediaFailureOnlyDisposesMedia() {
        val r = ReceiverFixture(true); r.start(); r.register(); r.factory.text("peer-present"); r.q.run()
        r.factory.listener.text("""{"type":"offer","sdp":"offer"}"""); r.q.run()
        r.f.server = ConnectionRequestState.CONNECTED; r.f.controller.refreshStatus(); r.f.worker.run()
        r.mediaFailure!!(); r.q.run()
        assertEquals(1, r.disposed); assertFalse(r.owner.active); assertEquals(0, r.generated)
        assertEquals(0, r.f.withdraws); assertEquals(0, r.f.cancels); assertEquals(1, r.factory.opens)
        assertNull(r.f.handoffs.reserve(r.flow, MediaRole.RECEIVE))
    }
    @Test fun connectedSenderFailureNeverRetiresLegacyOrSendsHttpExit() {
        val f = Fixture(); val a = f.initiator(); val id = f.handoffs.reserve(a, MediaRole.SEND)!!
        var legacy = 0; val cleanup = cleanup(f, id) { legacy++ }
        val redemption = f.handoffs.consume(id, MediaRole.SEND)!!; cleanup.redeemed(redemption.owner)
        val q = Queue(); val factory = Factory()
        ProtectedSenderSignalingChannel(redemption.bootstrap, factory, q::dispatch).open(object : SenderSignalingEvents {
            override fun registered() {}; override fun peerPresent() {}; override fun payload(text: String) {}
            override fun ended() { cleanup.disposeRequest(); cleanup.retireLegacy() }
        })
        factory.register(MediaRole.SEND, PairingIntent.CREATE); q.run()
        f.server = ConnectionRequestState.CONNECTED; f.controller.refreshStatus(); f.worker.run()
        factory.listener.failed(); q.run()
        assertEquals(0, legacy); assertEquals(0, f.cancels); assertEquals(0, f.withdraws)
        assertEquals(1, factory.opens); assertNull(f.controller.protectedSignalingBootstrap(a))
        assertEquals(RequestMediaPhase.DISPOSED, f.controller.mediaPhase(redemption.owner))
    }
    @Test fun captureFailureBeforeRedemptionReleasesReservationForRetry() {
        val f = Fixture(); val a = f.initiator(); val id = f.handoffs.reserve(a, MediaRole.SEND)!!
        val cleanup = cleanup(f, id); var redeem = 0; var opens = 0
        try {
            captureBeforeRequestSignaling({ error("capture failed") }, {
                redeem++; f.handoffs.consume(checkNotNull(cleanup.handoffId), MediaRole.SEND)
            }) { opens++ }
        } catch (_: Exception) { cleanup.disposeRequest() }
        assertEquals(0, redeem); assertEquals(0, opens)
        assertNotNull(f.handoffs.reserve(a, MediaRole.SEND)); assertEquals(0, f.cancels)
    }
    @Test fun serviceCapturesCleanupBeforeAnyFallibleValidation() {
        // Wiring audit supplements the behavior tests above; no Android test framework is introduced.
        val source = java.io.File("src/main/java/com/pradertamas/uicapturetest/WebRtcService.kt").readText()
        val captured = source.indexOf("startCleanup = SenderStartCleanup(")
        assertTrue(captured >= 0)
        assertTrue(captured < source.indexOf("val consent = intent.getParcelableExtra"))
        assertTrue(captured < source.indexOf("check(AppMediaRoles.role == MediaRole.SEND"))
        assertTrue(captured < source.indexOf("check(PairingCode.normalize(session) == session)"))
        assertTrue(source.contains("startCleanup?.disposeRequest()"))
        assertTrue(source.contains("startCleanup?.retireLegacy()"))
    }
    @Test fun senderConsentRetainedResumeAndResultAreOneShotForBothSides() {
        for (recipient in listOf(false, true)) {
            val f = Fixture(); val flow: Any = if (recipient) f.recipient(MediaRole.SEND) else f.initiator()
            val lifecycle = ConnectionRequestMediaLifecycle(f.controller) { error("live media cannot release") }
            val retained = RequestSenderLaunch(f.controller, f.handoffs, lifecycle::requestExit)
            var prompts = 0; var services = 0; var sent: RequestMediaHandoffId? = null
            assertTrue(retained.begin(flow, true, MediaRole.SEND, true) { prompts++ })
            repeat(5) { assertFalse(retained.begin(flow, true, MediaRole.SEND, true) { prompts++ }) }
            assertTrue(retained.consentPromptInFlight)
            assertEquals(RequestLaunchResult.TRANSFERRED, retained.result(true, MediaRole.SEND, true) { services++; sent = it })
            assertEquals(RequestLaunchResult.IGNORED, retained.result(true, MediaRole.SEND, true) { services++ })
            retained.cancel() // Source Activity/ViewModel removal after transfer cannot abandon/exit.
            assertEquals(1, prompts); assertEquals(1, services)
            assertNotNull(f.handoffs.consume(sent!!, MediaRole.SEND)); assertEquals(0, f.cancels); assertEquals(0, f.withdraws)
        }
    }
    @Test fun senderConsentRequiresResumedRoleGateAndConnecting() {
        val f = Fixture(); val flow = f.initiator(); val launch = RequestSenderLaunch(f.controller, f.handoffs) {}
        assertFalse(launch.begin(flow, false, MediaRole.SEND, true) { error("prompt") })
        assertFalse(launch.begin(flow, true, MediaRole.RECEIVE, true) { error("prompt") })
        assertFalse(launch.begin(flow, true, MediaRole.SEND, false) { error("prompt") })
        f.server = ConnectionRequestState.CONNECTED; f.controller.refreshStatus(); f.worker.run()
        assertFalse(launch.begin(flow, true, MediaRole.SEND, true) { error("prompt") })
    }
    @Test fun consentCancelUsesExactSideExitAndFinalizesWithoutMediaOwner() {
        for (recipient in listOf(false, true)) {
            val f = Fixture(); val flow: Any = if (recipient) f.recipient(MediaRole.SEND) else f.initiator()
            var releases = 0; val life = ConnectionRequestMediaLifecycle(f.controller) { releases++ }
            val launch = RequestSenderLaunch(f.controller, f.handoffs, life::requestExit)
            launch.begin(flow, true, MediaRole.SEND, true) {}
            assertEquals(RequestLaunchResult.CLOSED, launch.result(false, MediaRole.SEND, true) { error("service") })
            f.worker.run(); life.changed()
            assertEquals(if (recipient) 0 else 1, f.cancels); assertEquals(if (recipient) 1 else 0, f.withdraws)
            assertEquals(1, releases); assertNull(f.controller.mediaSnapshot())
        }
    }
    @Test fun consentLaunchAndServiceExceptionsHaveCleanupAndExplicitNetworkRetry() {
        for (promptThrows in listOf(true, false)) {
            val f = Fixture(); val flow = f.initiator(); f.exitError = ConnectionRequestError.NETWORK
            var releases = 0; val life = ConnectionRequestMediaLifecycle(f.controller) { releases++ }
            val launch = RequestSenderLaunch(f.controller, f.handoffs, life::requestExit)
            launch.begin(flow, true, MediaRole.SEND, true) { if (promptThrows) error("platform") }
            if (!promptThrows) assertEquals(RequestLaunchResult.CLOSED, launch.result(true, MediaRole.SEND, true) { error("service") })
            f.worker.run(); repeat(4) { life.changed() }
            assertEquals(1, f.cancels); assertTrue(life.state.value.retry); assertEquals(0, releases)
            assertSame(flow, f.controller.mediaSnapshot()!!.flow); assertTrue(f.controller.mediaSnapshot()!!.exitRequested)
            assertNull(f.handoffs.reserve(flow, MediaRole.SEND))
            f.exitError = null; life.retry(checkNotNull(life.state.value.retryTarget)); f.worker.run(); life.changed()
            assertEquals(2, f.cancels); assertEquals(1, releases)
        }
    }
    @Test fun staleConsentResultCannotStartServiceOrExitReplacement() {
        val f = Fixture(); val a = f.initiator(); var exits = 0
        val launch = RequestSenderLaunch(f.controller, f.handoffs) { exits++ }
        launch.begin(a, true, MediaRole.SEND, true) {}
        val b = f.initiator(); val before = f.controller.state.value
        assertEquals(RequestLaunchResult.IGNORED, launch.result(true, MediaRole.SEND, true) { error("service") })
        assertEquals(before, f.controller.state.value); assertEquals(0, exits)
        assertNotNull(f.handoffs.reserve(b, MediaRole.SEND))
    }
    @Test fun receiverEntryNavigationUsesExactOneShotTokenForRecipient() {
        val r = ReceiverFixture(true); var navigations = 0; var delivered: RequestMediaHandoffId? = null
        val life = ConnectionRequestMediaLifecycle(r.f.controller) {}
        assertTrue(startRequestReceiver(r.f.controller, r.f.handoffs, r.flow, true, MediaRole.RECEIVE, true, life::requestExit) {
            navigations++; delivered = it; true
        })
        assertFalse(startRequestReceiver(r.f.controller, r.f.handoffs, r.flow, true, MediaRole.RECEIVE, true, life::requestExit) { error("duplicate") })
        assertTrue(r.owner.start(delivered!!)); assertFalse(r.owner.start(delivered!!))
        assertEquals(1, navigations); assertEquals(1, r.factory.opens); assertEquals(0, r.f.withdraws)
    }
    @Test fun initiatorReceiverStartsWithoutNavigationAndRepeatedEventsAreInert() {
        val r = ReceiverFixture(); val life = ConnectionRequestMediaLifecycle(r.f.controller) {}
        repeat(4) { startRequestReceiver(r.f.controller, r.f.handoffs, r.flow, true, MediaRole.RECEIVE, true, life::requestExit) { r.owner.start(it) } }
        assertEquals(1, r.factory.opens); assertEquals(0, r.generated)
    }
    @Test fun receiverNavigationFailureAbandonsAndWithdrawsExactRecipient() {
        val f = Fixture(); val flow = f.recipient(); var release = 0
        val life = ConnectionRequestMediaLifecycle(f.controller) { release++ }
        assertFalse(startRequestReceiver(f.controller, f.handoffs, flow, true, MediaRole.RECEIVE, true, life::requestExit) { error("navigation") })
        f.worker.run(); life.changed()
        assertEquals(1, f.withdraws); assertEquals(1, release)
    }
    @Test fun disposedConnectingMediaExitsEachSideThenReleases() {
        for (recipient in listOf(false, true)) {
            val r = ReceiverFixture(recipient); var releases = 0
            val life = ConnectionRequestMediaLifecycle(r.f.controller) { releases++ }
            r.start(); life.changed(); assertEquals(0, releases)
            assertTrue(r.owner.stop(r.flow)); life.changed(); r.f.worker.run(); life.changed()
            assertEquals(if (recipient) 0 else 1, r.f.cancels); assertEquals(if (recipient) 1 else 0, r.f.withdraws)
            assertEquals(1, releases); assertNull(r.f.controller.mediaSnapshot())
        }
    }
    @Test fun disposedConnectedMediaFinalizesWithoutHttpAndActiveMediaCannotFinalize() {
        val r = ReceiverFixture(); var releases = 0
        val life = ConnectionRequestMediaLifecycle(r.f.controller) { releases++ }
        r.start(); r.f.server = ConnectionRequestState.CONNECTED; r.f.controller.refreshStatus(); r.f.worker.run()
        life.changed(); assertFalse(r.f.controller.finishMediaFlow(r.flow) { releases++ })
        assertEquals(0, releases); assertTrue(r.owner.stop(r.flow)); life.changed()
        assertEquals(1, releases); assertEquals(0, r.f.cancels); assertEquals(0, r.f.withdraws)
    }
    @Test fun disposedTerminalMediaFinalizesAndStaleFinalizationCannotReleaseB() {
        for (terminal in listOf(ConnectionRequestState.CANCELED, ConnectionRequestState.EXPIRED, ConnectionRequestState.FAILED)) {
            val r = ReceiverFixture(); var releases = 0; val life = ConnectionRequestMediaLifecycle(r.f.controller) { releases++ }
            r.start(); r.f.server = terminal; r.f.assignment = null; r.f.controller.refreshStatus(); r.f.worker.run()
            assertFalse(r.f.controller.finishMediaFlow(r.flow) { releases++ })
            r.owner.stop(r.flow); life.changed(); assertEquals(1, releases)
            val b = r.f.initiator(); val state = r.f.controller.state.value
            assertFalse(r.f.controller.finishMediaFlow(r.flow) { releases++ })
            assertEquals(1, releases); assertEquals(state, r.f.controller.state.value); assertSame(b, r.f.controller.mediaSnapshot()!!.flow)
        }
    }
    @Test fun cleanupConflictReconcilesExactlyOnceToConnectedAndReleases() {
        val f = Fixture(); val flow = f.initiator(); var releases = 0
        val life = ConnectionRequestMediaLifecycle(f.controller) { releases++ }
        f.exitError = ConnectionRequestError.CONFLICT; f.onCancel = { f.server = ConnectionRequestState.CONNECTED }
        life.requestExit(flow); f.worker.run(); life.changed()
        assertEquals(ConnectionRequestOperation.STATUS, f.controller.state.value.operation)
        f.worker.run(); life.changed(); repeat(5) { life.changed() }
        assertEquals(1, f.cancels); assertEquals(1, releases); assertNull(f.controller.mediaSnapshot())
    }
    @Test fun staleLifecycleRetryAndExitCannotTouchCurrentFlow() {
        val f = Fixture(); val a = f.initiator(); f.exitError = ConnectionRequestError.NETWORK
        val life = ConnectionRequestMediaLifecycle(f.controller) { error("stale release") }
        life.requestExit(a); f.worker.run(); life.changed(); assertTrue(life.state.value.retry)
        val rendered = checkNotNull(life.state.value.retryTarget)
        val b = f.initiator(); val state = f.controller.state.value
        life.changed(); life.retry(rendered); life.requestExit(a)
        assertEquals(state, f.controller.state.value); assertSame(b, f.controller.mediaSnapshot()!!.flow)
    }
    @Test fun processAbandonNotificationClosesEarlyServiceFailureWithoutChangingCoreAbandonPolicy() {
        val f = Fixture(); val flow = f.initiator(); var releases = 0
        val life = ConnectionRequestMediaLifecycle(f.controller) { releases++ }
        val processLike = ConnectionRequestMediaHandoff(f.controller, life::requestExit)
        val id = processLike.reserve(flow, MediaRole.SEND)!!
        processLike.abandon(id.value); f.worker.run(); life.changed()
        assertEquals(1, f.cancels); assertEquals(1, releases)
    }
    @Test fun neverLaunchedSenderViewModelCannotExitReceiverNavigation() {
        val f = Fixture(); val flow = f.recipient(); var exits = 0
        val sender = RequestSenderLaunch(f.controller, f.handoffs) { exits++ }
        assertFalse(sender.begin(flow, true, MediaRole.RECEIVE, true) { error("prompt") })
        sender.cancel(); assertEquals(0, exits); assertFalse(f.controller.mediaSnapshot()!!.exitRequested)
    }
    @Test fun gateCannotFinalizeWaitingAcceptedOrConnectingAndActiveOperationIsGuarded() {
        val f = Fixture(); val flow = f.controller.create(MediaRole.SEND)!!; f.worker.run(); var releases = 0
        assertFalse(f.controller.finishMediaFlow(flow) { releases++ }) // WAITING
        f.server = ConnectionRequestState.ACCEPTED; f.controller.refreshStatus(); f.worker.run()
        assertFalse(f.controller.finishMediaFlow(flow) { releases++ })
        f.server = ConnectionRequestState.CONNECTING; f.assignment = ConnectionAssignment("ABC234", MediaRole.SEND, PairingIntent.CREATE)
        f.controller.refreshStatus(); f.worker.run()
        assertFalse(f.controller.finishMediaFlow(flow) { releases++ })
        f.controller.requestMediaExit(flow)
        assertFalse(f.controller.finishMediaFlow(flow) { releases++ }); assertEquals(0, releases)
    }
    @Test fun nonterminalReconciliationRequiresExplicitRetryWithoutLoop() {
        val f = Fixture(); val flow = f.initiator(); f.exitError = ConnectionRequestError.CONFLICT
        val life = ConnectionRequestMediaLifecycle(f.controller) { error("cannot release CONNECTING") }
        life.requestExit(flow); f.worker.run(); life.changed(); f.worker.run(); life.changed()
        assertTrue(life.state.value.retry)
        repeat(8) { life.changed() }; assertTrue(f.worker.tasks.isEmpty()); assertEquals(1, f.cancels)
        life.retry(checkNotNull(life.state.value.retryTarget)); assertEquals(1, f.worker.tasks.size)
        f.worker.run(); life.changed(); assertTrue(life.state.value.retry)
    }
    @Test fun receiverNeverStartsInitialMediaFromConnected() {
        val r = ReceiverFixture(); r.f.server = ConnectionRequestState.CONNECTED
        r.f.controller.refreshStatus(); r.f.worker.run()
        assertFalse(startRequestReceiver(r.f.controller, r.f.handoffs, r.flow, true, MediaRole.RECEIVE, true, {}) { r.owner.start(it) })
        assertEquals(0, r.factory.opens); assertEquals(0, r.media)
    }
    @Test fun mediaSnapshotHasNoBearerFieldsAndCanceledPromptStopsAutomaticInitiatorMonitor() {
        val f = Fixture(); val flow = f.initiator(); val life = ConnectionRequestMediaLifecycle(f.controller) {}
        val fields = ConnectionRequestMediaSnapshot::class.java.declaredFields.map { it.name }
        for (bad in listOf("requestId", "secret", "claim", "code", "url", "bootstrap")) assertFalse(fields.contains(bad))
        life.requestExit(flow)
        assertFalse(f.controller.initiatorMonitorSnapshot()!!.active)
        assertFalse(f.controller.refreshInitiatorStatus(MediaRole.SEND, flow))
    }
    @Test fun renderedOldRetryCallbackCannotRetryReplacementCleanupError() {
        for (recipient in listOf(false, true)) {
            val f = Fixture(); val a: Any = if (recipient) f.recipient() else f.initiator()
            var releases = 0
            val life = ConnectionRequestMediaLifecycle(f.controller) { releases++ }
            f.exitError = ConnectionRequestError.NETWORK
            life.requestExit(a); f.worker.run(); life.changed()
            // Same capture as the composable's onClick, before B exists.
            val renderedA = checkNotNull(life.state.value.retryTarget)
            val oldOnClick = { life.retry(renderedA) }
            f.controller.clear()
            val b: Any = if (recipient) f.recipient() else f.initiator()
            life.requestExit(b); f.worker.run(); life.changed()
            val bUi = life.state.value; val bState = f.controller.state.value
            val calls = f.cancels + f.withdraws
            oldOnClick(); f.worker.run()
            assertEquals(calls, f.cancels + f.withdraws); assertEquals(0, releases)
            assertSame(b, f.controller.mediaSnapshot()!!.flow)
            assertEquals(bState, f.controller.state.value); assertSame(bUi, life.state.value)
        }
    }
    @Test fun renderedCurrentRetryIsSingleFlightAndInvalidatedOnSuccess() {
        for (recipient in listOf(false, true)) {
            val f = Fixture(); val flow: Any = if (recipient) f.recipient() else f.initiator()
            var releases = 0; val life = ConnectionRequestMediaLifecycle(f.controller) { releases++ }
            f.exitError = ConnectionRequestError.NETWORK
            life.requestExit(flow); f.worker.run(); life.changed()
            val rendered = checkNotNull(life.state.value.retryTarget)
            val onClick = { life.retry(rendered) }
            f.exitError = null; onClick(); onClick()
            assertEquals(1, f.worker.tasks.size)
            f.worker.run(); life.changed(); onClick()
            assertEquals(2, f.cancels + f.withdraws); assertEquals(1, releases)
            assertNull(life.state.value.retryTarget); assertNull(f.controller.mediaSnapshot())
        }
    }
    @Test fun retryErrorEpisodeInvalidatesPreviousRenderedTargetEvenForSameFlow() {
        val f = Fixture(); val flow = f.initiator(); f.exitError = ConnectionRequestError.NETWORK
        val life = ConnectionRequestMediaLifecycle(f.controller) { error("release") }
        life.requestExit(flow); f.worker.run(); life.changed()
        val old = checkNotNull(life.state.value.retryTarget)
        life.retry(old); f.worker.run(); life.changed()
        val current = checkNotNull(life.state.value.retryTarget)
        assertNotSame(old, current)
        life.retry(old); assertTrue(f.worker.tasks.isEmpty())
        life.retry(current); assertEquals(1, f.worker.tasks.size)
        assertEquals("RequestCleanupRetryTarget[redacted]", current.toString())
    }
    @Test fun receiverNavigationWarmRouteUsesExactBoundTaskAndReuseFlags() {
        // Same result whether caller is in task 12 (same-task) or 99 (cross-task).
        val warm = requestReceiverRoute(RequestReceiverTask(true, 12))!!
        assertEquals(12, warm.taskId)
        assertEquals(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP, warm.flags)
        assertEquals(0, warm.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(0, warm.flags and android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        assertNull(requestReceiverRoute(RequestReceiverTask(true, null)))
    }
    @Test fun receiverNavigationColdRouteHasSeparateExplicitTaskPolicy() {
        val cold = requestReceiverRoute(RequestReceiverTask(false, null))!!
        assertNull(cold.taskId)
        assertEquals(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP, cold.flags)
        // Stale routing metadata cannot turn an unbound owner into a warm owner.
        assertNull(requestReceiverRoute(RequestReceiverTask(false, 12))!!.taskId)
    }
    @Test fun backWinsBeforeReceiverTransferAbandonsAndWithdraws() {
        val f = Fixture(); val flow = f.recipient(); var releases = 0
        val life = ConnectionRequestMediaLifecycle(f.controller) { releases++ }
        val transfer = RequestReceiverTransfer(f.handoffs::abandon)
        val id = f.handoffs.reserve(flow, MediaRole.RECEIVE)!!
        transfer.reserved(id)
        if (transfer.cancel()) life.requestExit(flow)
        assertFalse(transfer.start { error("navigation after Back") })
        assertNull(f.handoffs.consume(id, MediaRole.RECEIVE))
        f.worker.run(); life.changed()
        assertEquals(1, f.withdraws); assertEquals(1, releases)
    }
    @Test fun transferWinsBeforeBackSourceCannotWithdrawAndDuplicateHandoffIsInert() {
        val f = Fixture(); val flow = f.recipient()
        val transfer = RequestReceiverTransfer(f.handoffs::abandon)
        var delivered: RequestMediaHandoffId? = null
        assertTrue(startRequestReceiver(f.controller, f.handoffs, flow, true, MediaRole.RECEIVE, true,
            { error("exit") }, transfer) { delivered = it; true })
        assertTrue(transfer.transferred); assertFalse(transfer.cancel())
        assertNotNull(f.handoffs.consume(checkNotNull(delivered), MediaRole.RECEIVE))
        assertNull(f.handoffs.consume(checkNotNull(delivered), MediaRole.RECEIVE))
        assertEquals(0, f.withdraws); assertFalse(f.controller.mediaSnapshot()!!.exitRequested)
    }
    @Test fun terminalWhileConsentPromptIsOpenRejectsLateAllowWithoutServiceOrReservation() {
        for (terminal in listOf(ConnectionRequestState.CANCELED, ConnectionRequestState.EXPIRED, ConnectionRequestState.FAILED)) {
            val f = Fixture(); val flow = f.initiator()
            val launch = RequestSenderLaunch(f.controller, f.handoffs) { f.controller.markMediaExitRequested(it) }
            assertTrue(launch.begin(flow, true, MediaRole.SEND, true) {})
            f.server = terminal; f.assignment = null; f.controller.refreshStatus(); f.worker.run()
            assertEquals(RequestLaunchResult.CLOSED, launch.result(true, MediaRole.SEND, true) { error("service") })
            assertNull(f.controller.mediaSnapshot()!!.phase)
            assertNull(f.handoffs.reserve(flow, MediaRole.SEND))
        }
    }
    @Test fun terminalDuringNavigationRejectsFreshRedemptionAndCannotTouchReplacement() {
        val r = ReceiverFixture(true); val id = r.f.handoffs.reserve(r.flow, MediaRole.RECEIVE)!!
        r.f.server = ConnectionRequestState.EXPIRED; r.f.assignment = null
        r.f.controller.refreshStatus(); r.f.worker.run()
        assertFalse(r.owner.start(id)); assertEquals(0, r.factory.opens); assertEquals(0, r.media)
        r.f.controller.clear(); val b = r.f.initiator(MediaRole.RECEIVE)
        val state = r.f.controller.state.value
        assertFalse(r.owner.start(id)); assertEquals(state, r.f.controller.state.value)
        assertSame(b, r.f.controller.mediaSnapshot()!!.flow)
    }
    @Test fun retainedOwnerFailedRebindHandoffKeepsTaskAndNextValidRequestUsesIt() {
        val r = ReceiverFixture(true)
        val registry = ReceiverOwnerRegistry(); val retained = Any(); var bookkeepingEffects = 0
        registry.bind(MediaRole.RECEIVE, retained, { bookkeepingEffects++ }, { true }, 21)
        registry.unbind(retained) // RECEIVE -> SEND departure, same retained VM.
        assertFalse(registry.task().bound)
        repeat(3) {
            // Production bindReceiverToTask uses exactly this atomic registry admission.
            registry.bind(MediaRole.RECEIVE, retained, { bookkeepingEffects++ }, { true }, 21)
            if (it == 0) {
                val id = r.f.handoffs.reserve(r.flow, MediaRole.RECEIVE)!!
                r.f.server = ConnectionRequestState.EXPIRED; r.f.assignment = null
                r.f.controller.refreshStatus(); r.f.worker.run()
                assertFalse(r.owner.start(id))
            } else assertFalse(r.owner.start("missing-handoff"))
            assertTrue(registry.attached(retained)); assertEquals(21, registry.task().taskId)
            assertEquals(0, r.factory.opens)
        }
        r.f.controller.clear()
        val valid = r.f.recipient(); val id = r.f.handoffs.reserve(valid, MediaRole.RECEIVE)!!
        var starts = 0
        assertTrue(dispatchRequestReceiver(requestReceiverRoute(registry.task())!!, { listOf(11, 21, 31) }, { it },
            { task, _ -> assertEquals(21, task); starts++; assertTrue(r.owner.start(id)) },
            { error("cold fallback") }))
        assertEquals(1, starts); assertEquals(1, r.factory.opens); assertEquals(0, bookkeepingEffects)
    }
    @Test fun receiverTaskAttachmentIsIdempotentAndRejectsStaleIdentity() {
        val registry = ReceiverOwnerRegistry(); val a = Any(); val b = Any()
        registry.bind(MediaRole.RECEIVE, a, { error("stop") }, { true }, 10)
        repeat(3) { registry.bind(MediaRole.RECEIVE, a, { error("stop") }, { true }, 10) }
        assertSame(a, registry.current!!.identity); assertEquals(10, registry.task().taskId)
        registry.clear() // role departure
        registry.bind(MediaRole.RECEIVE, b, {}, { true }, 20)
        assertTrue(runCatching { registry.bind(MediaRole.RECEIVE, a, {}, { true }, 30) }.isFailure)
        registry.unbind(a)
        assertSame(b, registry.current!!.identity); assertEquals(20, registry.task().taskId)
        assertTrue(runCatching { registry.bind(MediaRole.SEND, b, {}, { true }, 40) }.isFailure)
        assertTrue(runCatching { registry.bind(MediaRole.RECEIVE, b, {}, { true }, -1) }.isFailure)
        assertEquals(20, registry.task().taskId)
        registry.unbind(b); assertFalse(registry.task().bound); assertNull(registry.task().taskId)
    }
    @Test fun receiverTaskEnumerationSelectsExactMiddleTask() {
        var selected = 0; var flags = 0
        val route = requestReceiverRoute(RequestReceiverTask(true, 2))!!
        assertTrue(dispatchRequestReceiver(route, { listOf(1, 2, 3) }, { it },
            { task, receivedFlags -> selected = task; flags = receivedFlags }, { error("cold") }))
        assertEquals(2, selected); assertEquals(route.flags, flags)
    }
    @Test fun missingTaskRejectsReservationAndExitsWithoutColdFallback() {
        val f = Fixture(); val flow = f.recipient()
        val life = ConnectionRequestMediaLifecycle(f.controller) {}
        assertFalse(startRequestReceiver(f.controller, f.handoffs, flow, true, MediaRole.RECEIVE, true, life::requestExit) { id ->
            assertSame(flow, f.handoffs.flowFor(id.value))
            dispatchRequestReceiver(requestReceiverRoute(RequestReceiverTask(true, 4))!!, { listOf(1, 2) }, { it },
                { _, _ -> error("wrong task") }, { error("cold fallback") })
        })
        f.worker.run(); life.changed()
        assertEquals(1, f.withdraws); assertNull(f.controller.mediaSnapshot())
    }
    @Test fun taskStartAndEnumerationExceptionsRejectExactReservationWithoutColdFallback() {
        for (enumerationFails in listOf(false, true)) {
            val f = Fixture(); val flow = f.recipient(); var selected = 0
            val life = ConnectionRequestMediaLifecycle(f.controller) {}
            var token: RequestMediaHandoffId? = null
            assertFalse(startRequestReceiver(f.controller, f.handoffs, flow, true, MediaRole.RECEIVE, true, life::requestExit) { id ->
                token = id
                dispatchRequestReceiver(requestReceiverRoute(RequestReceiverTask(true, 2))!!,
                    { if (enumerationFails) throw SecurityException() else listOf(1, 2, 3) }, { it },
                    { task, _ -> selected = task; throw IllegalArgumentException() }, { error("cold fallback") })
            })
            assertNull(f.handoffs.flowFor(checkNotNull(token).value))
            assertEquals(if (enumerationFails) 0 else 2, selected)
            f.worker.run(); life.changed(); assertEquals(1, f.withdraws)
        }
    }
    @Test fun coldNavigationDoesNotEnumerateWarmTasks() {
        var starts = 0
        val route = requestReceiverRoute(RequestReceiverTask(false, null))!!
        assertTrue(dispatchRequestReceiver<Int>(route, { error("enumeration") }, { it },
            { _, _ -> error("warm") }, { flags -> starts++; assertEquals(route.flags, flags) }))
        assertEquals(1, starts)
    }
    @Test fun receiverLifecycleAssociatesBeforeAnyHandoffAndResumeHasNoMediaEffects() {
        val base = java.io.File("src/main/java/com/pradertamas/uicapturetest")
        val activity = java.io.File(base, "ReceiverActivity.kt").readText()
        val delivery = activity.substringAfter("private fun deliverRequestHandoff").substringBefore("override fun onNewIntent")
        assertTrue(delivery.indexOf("ensureBoundToTask(taskId)") < delivery.indexOf("model.startRequest(id)"))
        val create = activity.substringAfter("override fun onCreate").substringBefore("setContent")
        assertTrue(create.indexOf("ensureBoundToTask(taskId)") < create.indexOf("deliverRequestHandoff(requestHandoff)"))
        assertTrue(activity.substringAfter("override fun onResume").substringBefore("override fun onPause").contains("ensureBoundToTask(taskId)"))
        val vm = java.io.File(base, "ReceiverViewModel.kt").readText()
        val attach = vm.substringAfter("internal fun ensureBoundToTask").substringBefore("private val main")
        assertTrue(attach.contains("bindReceiverToTask(this, taskId"))
        for (forbidden in listOf("engine.", "request.start", "reserve(", "consume(", "startActivity", "api."))
            assertFalse(attach.contains(forbidden))
    }
    @Test fun retryAndExactTaskNavigationProductionWiringAudit() {
        val base = java.io.File("src/main/java/com/pradertamas/uicapturetest")
        val sender = java.io.File(base, "RequestSenderLaunchViewModel.kt").readText().substringAfter("internal fun RequestCleanupRetry")
        assertTrue(sender.contains("val retryTarget = ui.retryTarget"))
        assertTrue(sender.contains("lifecycle.retry(retryTarget)")); assertFalse(sender.contains("mediaSnapshot()"))
        for (name in listOf("MainActivity.kt", "ReceiverActivity.kt"))
            assertTrue(java.io.File(base, name).readText().contains("RequestCleanupRetry()"))
        val navigation = java.io.File(base, "RequestReceiverNavigation.kt").readText()
        assertTrue(navigation.contains("taskId(it) == route.taskId"))
        assertTrue(navigation.contains("task.startActivity(activity, intent, null)"))
        assertFalse(navigation.contains("REORDER_TO_FRONT"))
        val receiver = java.io.File(base, "ReceiverActivity.kt").readText()
        assertTrue(receiver.indexOf("model.ensureBoundToTask(taskId)") < receiver.indexOf("model.startRequest(id)"))
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<activity android:name=\".ReceiverActivity\" android:exported=\"false\" />"))
    }
    @Test fun androidWiringUsesSeparateConsentAndScrubbedReceiverHandoffs() {
        val base = java.io.File("src/main/java/com/pradertamas/uicapturetest")
        val sender = java.io.File(base, "RequestSenderLaunchViewModel.kt").readText()
        val intentBuilder = sender.substringAfter("internal fun requestSenderIntent").substringBefore("@Composable")
        assertTrue(intentBuilder.contains("WebRtcService.MODE")); assertTrue(intentBuilder.contains("WebRtcService.HANDOFF"))
        assertTrue(intentBuilder.contains("WebRtcService.CONSENT"))
        for (bad in listOf("WebRtcService.SESSION", "WebRtcService.INTENT", "WebRtcService.EPOCH")) assertFalse(intentBuilder.contains(bad))
        val main = java.io.File(base, "MainActivity.kt").readText()
        assertTrue(main.contains("RequestSenderConsent(this, requestSender, null, initiator = true)"))
        assertTrue(main.contains("val rtcConsent = rememberLauncherForActivityResult"))
        val receiver = java.io.File(base, "ReceiverActivity.kt").readText()
        assertTrue(receiver.contains("override fun onNewIntent")); assertTrue(receiver.contains("removeExtra(REQUEST_MEDIA_HANDOFF)"))
        assertTrue(receiver.contains("renderer?.let(model::detach)")); assertTrue(receiver.contains("model.attach(it)"))
        val recipient = java.io.File(base, "ConnectionRequestActivity.kt").readText()
        assertTrue(recipient.contains("model.mediaTransferred = true; finish()"))
    }
}
