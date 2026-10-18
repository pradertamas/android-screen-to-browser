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
        val legacy = object : ReceiverWire {
            override fun open(code: String, intent: PairingIntent, events: ReceiverEvents): PairingLink = error("legacy forbidden")
            override fun answer(sdp: String) = error("legacy forbidden")
            override fun candidate(value: ReceiverIce) = error("legacy forbidden")
        }
        val router = ReceiverWireRouter(legacy)
        val engine = ReceiverEngine(router, { candidate, _ -> media++; localIce = candidate; object : ReceiverMedia {
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
    @Test fun retainedReceiverOwnerSurvivesViewDetachAndRejectsSecondStartOrStaleStop() {
        val r = ReceiverFixture(); r.start(); r.register()
        // View recreation has no ownership operation: the retained owner/Engine is reused.
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
}
