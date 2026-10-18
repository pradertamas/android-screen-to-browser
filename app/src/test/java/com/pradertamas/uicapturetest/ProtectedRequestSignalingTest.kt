package com.pradertamas.uicapturetest

import java.io.File
import java.net.URI
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtectedRequestSignalingTest {
    private fun token(seed: Int, bytes: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes) { seed.toByte() })
    private val id = token(1, 16)
    private val secret = token(2, 32)
    private val claim = token(3, 32)
    private fun bootstrap(role: MediaRole = MediaRole.SEND, intent: PairingIntent = PairingIntent.CREATE) =
        ProtectedSignalingBootstrap.create(Any(), "ABC234", role, intent, id, ConnectionCredential(secret))
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private inner class ControllerFixture {
        val queue = Queue(); var state = ConnectionRequestState.WAITING
        var assignment: ConnectionAssignment? = null
        var media = MediaRole.SEND
        val now = Instant.parse("2026-01-01T00:00:00Z")
        fun metadata() = ConnectionRequestMetadata(id, state, now, media, now, now.plusSeconds(300),
            acceptedAt = now, connectBy = now.plusSeconds(120), assignment = assignment)
        val api = object : ConnectionRequestApi {
            override fun create(role: MediaRole): ConnectionRequestResult {
                media = role; state = ConnectionRequestState.WAITING; assignment = null
                val m = metadata()
                return ConnectionRequestResult(m, httpStatus = 201, created = ConnectionRequestCreated(m, ConnectionCredential(secret), ConnectionCredential(token(4, 16))))
            }
            override fun status(id: String, auth: ConnectionCredential, scope: ConnectionRequestAuthScope) = ConnectionRequestResult(metadata())
            override fun accept(id: String, capability: ConnectionCredential, claim: ConnectionCredential): ConnectionRequestResult {
                state = ConnectionRequestState.ACCEPTED; return ConnectionRequestResult(metadata(), httpStatus = 200)
            }
            override fun cancel(id: String, auth: ConnectionCredential) = ConnectionRequestResult()
            override fun withdraw(id: String, auth: ConnectionCredential) = ConnectionRequestResult()
            override fun ready(id: String, auth: ConnectionCredential, ready: Boolean, scope: ConnectionRequestAuthScope) = ConnectionRequestResult()
            override fun session(id: String, auth: ConnectionCredential) = ConnectionRequestResult()
        }
        val controller = ConnectionRequestController(api, queue, { 0L }, { ConnectionCredential(claim) })
        fun initiator(role: MediaRole = MediaRole.SEND): ConnectionRequestCreateAttempt {
            val flow = controller.create(role)!!; queue.run(); state = ConnectionRequestState.CONNECTING
            assignment = ConnectionAssignment("ABC234", role, PairingIntent.CREATE); controller.refreshStatus(); queue.run(); return flow
        }
        fun recipient(winning: Boolean = true, role: MediaRole = MediaRole.SEND): ConnectionRequestRecipientFlow {
            media = role
            val flow = controller.preview(id, token(4, 16), role)!!
            if (winning) { controller.accept(flow); queue.run() }
            state = ConnectionRequestState.CONNECTING
            assignment = if (winning) ConnectionAssignment("ABC234", ConnectionRequestSetupCoordinator.opposite(role), PairingIntent.JOIN) else null
            controller.refreshStatus(flow); queue.run(); return flow
        }
        fun update(next: ConnectionRequestState) { state = next; controller.refreshStatus(); queue.run() }
        // Exercise bootstrap defense independently of the HTTP/parser guards, without weakening production.
        fun corruptAssignment(value: ConnectionAssignment?) {
            val context = controller.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(controller)
            context.javaClass.getDeclaredField("metadata").apply { isAccessible = true }.set(context, metadata().copy(assignment = value))
        }
    }
    private class FakeSocket : ProtectedSocket {
        val frames = mutableListOf<String>(); val closes = mutableListOf<Int>(); var canceled = false; var sendOk = true
        var onSend: ((String) -> Unit)? = null
        var onClose: (() -> Unit)? = null
        var probe: (() -> Unit)? = null
        override fun send(text: String): Boolean { probe?.invoke(); frames.add(text); onSend?.invoke(text); return sendOk }
        override fun close(code: Int) { probe?.invoke(); closes.add(code); onClose?.invoke() }
        override fun cancel() { probe?.invoke(); canceled = true }
    }
    private class FakeFactory : ProtectedSocketFactory {
        val socket = FakeSocket(); lateinit var listener: ProtectedSocketListener; var opens = 0; var url: String? = null
        override fun open(url: String, listener: ProtectedSocketListener): ProtectedSocket {
            opens++; this.url = url; this.listener = listener; return socket
        }
    }
    private class Events : ProtectedSignalingEvents {
        var registered = 0; var present = 0; var failures = 0; var closed = 0
        val payloads = mutableListOf<String>(); val order = mutableListOf<String>()
        var probe: (() -> Unit)? = null
        var onRegistered: (() -> Unit)? = null
        var onPresent: (() -> Unit)? = null
        var onPayload: (() -> Unit)? = null
        override fun registered() { probe?.invoke(); registered++; order.add("registered"); onRegistered?.invoke() }
        override fun peerPresent() { probe?.invoke(); present++; order.add("present"); onPresent?.invoke() }
        override fun payload(text: String) { probe?.invoke(); payloads.add(text); order.add("payload"); onPayload?.invoke() }
        override fun failed() { probe?.invoke(); failures++; order.add("failed") }
        override fun closed() { probe?.invoke(); closed++; order.add("closed") }
    }
    private inner class TransportFixture(dispatch: (() -> Unit) -> Unit = { it() }, b: ProtectedSignalingBootstrap = bootstrap()) {
        val factory = FakeFactory(); val events = Events()
        val transport = ProtectedRequestSignaling.open(b, factory, events, dispatch)
        val socket get() = factory.socket
        fun opened() { factory.listener.opened(socket) }
        fun text(text: String) { factory.listener.text(text) }
        fun register() { opened(); text(registration()) }
    }
    private fun registration() = """{"type":"registration","status":"ok","intent":"create","role":"sender","remotePresence":false}"""
    private val offer = """{"type":"offer","sdp":"synthetic"}"""
    private fun auth(b: ProtectedSignalingBootstrap) = JSONObject(b.consumeAuthFrame()!!)

    @Test fun initiatorConnectingBootstrapUsesInitiatorSecret() { val f = ControllerFixture(); val b = f.controller.protectedSignalingBootstrap(f.initiator())!!; assertEquals(secret, auth(b).getString("secret")) }
    @Test fun initiatorAcceptedCannotBootstrap() { val f = ControllerFixture(); val a = f.initiator(); f.corruptAssignment(null); f.state = ConnectionRequestState.ACCEPTED; f.corruptAssignment(null); assertNull(f.controller.protectedSignalingBootstrap(a)) }
    @Test fun initiatorConnectedCannotBootstrap() { val f = ControllerFixture(); val a = f.initiator(); f.update(ConnectionRequestState.CONNECTED); assertNull(f.controller.protectedSignalingBootstrap(a)) }
    @Test fun staleInitiatorCannotBootstrapReplacement() { val f = ControllerFixture(); val a = f.initiator(); f.initiator(); assertNull(f.controller.protectedSignalingBootstrap(a)) }
    @Test fun winningRecipientBootstrapUsesClaimNotCapability() { val f = ControllerFixture(); val a = f.recipient(); val b = f.controller.protectedSignalingBootstrap(a)!!; assertEquals(claim, auth(b).getString("secret")); assertEquals(PairingIntent.JOIN, b.intent); assertEquals(MediaRole.RECEIVE, b.role) }
    @Test fun nonWinningRecipientCannotBootstrap() { val f = ControllerFixture(); val a = f.recipient(false); assertNull(f.controller.protectedSignalingBootstrap(a)) }
    @Test fun arbitraryRecipientCannotBootstrap() { val f = ControllerFixture(); f.recipient(); assertNull(f.controller.protectedSignalingBootstrap(ConnectionRequestRecipientFlow())) }
    @Test fun staleRecipientCannotBootstrapNewRecipient() { val f = ControllerFixture(); val a = f.recipient(); f.controller.clear(); f.recipient(); assertNull(f.controller.protectedSignalingBootstrap(a)) }
    @Test fun recipientConnectedCannotBootstrap() { val f = ControllerFixture(); val a = f.recipient(); f.update(ConnectionRequestState.CONNECTED); assertNull(f.controller.protectedSignalingBootstrap(a)) }
    @Test fun allTerminalStatesRejectBootstrap() { for (state in listOf(ConnectionRequestState.FAILED, ConnectionRequestState.EXPIRED, ConnectionRequestState.CANCELED)) { val f = ControllerFixture(); val a = f.initiator(); f.update(state); assertNull(f.controller.protectedSignalingBootstrap(a)) } }
    @Test fun missingAssignmentRejected() { val f = ControllerFixture(); val a = f.initiator(); f.corruptAssignment(null); assertNull(f.controller.protectedSignalingBootstrap(a)) }
    @Test fun mismatchingRoleRejected() { val f = ControllerFixture(); val a = f.initiator(); f.corruptAssignment(ConnectionAssignment("ABC234", MediaRole.RECEIVE, PairingIntent.CREATE)); assertNull(f.controller.protectedSignalingBootstrap(a)) }
    @Test fun mismatchingIntentRejected() { val f = ControllerFixture(); val a = f.initiator(); f.corruptAssignment(ConnectionAssignment("ABC234", MediaRole.SEND, PairingIntent.JOIN)); assertNull(f.controller.protectedSignalingBootstrap(a)) }
    @Test fun nonCanonicalCodesRejected() { for (code in listOf("abc234", " ABC234", "ABC23", "ABC230", "ABC23I")) { val f = ControllerFixture(); val a = f.initiator(); f.corruptAssignment(ConnectionAssignment(code, MediaRole.SEND, PairingIntent.CREATE)); assertNull(f.controller.protectedSignalingBootstrap(a)) } }
    @Test fun receiveInitiatorAndSendRecipientMapping() { val f = ControllerFixture(); val b = f.controller.protectedSignalingBootstrap(f.initiator(MediaRole.RECEIVE))!!; assertEquals(MediaRole.RECEIVE, b.role); val r = ControllerFixture(); assertEquals(MediaRole.SEND, r.controller.protectedSignalingBootstrap(r.recipient(role = MediaRole.RECEIVE))!!.role) }
    @Test fun bootstrapAcquisitionNeverMutatesControllerState() { val f = ControllerFixture(); val a = f.initiator(); val before = f.controller.state.value; val rev = f.controller.ownershipChanges.value; f.controller.protectedSignalingBootstrap(a); assertEquals(before, f.controller.state.value); assertEquals(rev, f.controller.ownershipChanges.value); assertTrue(f.queue.tasks.isEmpty()) }
    @Test fun bootstrapToStringRedactsEveryBearer() { val b = bootstrap(); assertEquals("ProtectedSignalingBootstrap[redacted]", b.toString()); assertFalse(b.toString().contains(id)); assertFalse(b.toString().contains("ABC234")) }
    @Test fun consumeAuthExactlyOnce() { val b = bootstrap(); assertNotNull(b.consumeAuthFrame()); assertNull(b.consumeAuthFrame()) }
    @Test fun newBootstrapForValidFlowHasNewConsumeAuthority() { val f = ControllerFixture(); val a = f.initiator(); val first = f.controller.protectedSignalingBootstrap(a)!!; val second = f.controller.protectedSignalingBootstrap(a)!!; assertNotSame(first, second); assertEquals(first.consumeAuthFrame(), second.consumeAuthFrame()); assertNull(first.consumeAuthFrame()) }
    @Test fun authHasExactlyThreeCanonicalFieldsAndUnder512Bytes() { val b = bootstrap(); val frame = b.consumeAuthFrame()!!; val json = JSONObject(frame); assertEquals(setOf("type", "requestId", "secret"), json.keys().asSequence().toSet()); assertEquals("request-auth", json.getString("type")); assertEquals(id, json.getString("requestId")); assertEquals(secret, json.getString("secret")); assertTrue(frame.toByteArray().size < 512) }
    @Test fun invalidBearerCannotConstructBootstrap() { for (bad in listOf("x", secret + "=", "x".repeat(600))) { assertThrows(IllegalArgumentException::class.java) { ProtectedSignalingBootstrap.create(Any(), "ABC234", MediaRole.SEND, PairingIntent.CREATE, id, ConnectionCredential(bad)) } } }
    @Test fun invalidRequestIdCannotConstructBootstrap() { assertThrows(IllegalArgumentException::class.java) { ProtectedSignalingBootstrap.create(Any(), "ABC234", MediaRole.SEND, PairingIntent.CREATE, id + "=", ConnectionCredential(secret)) } }
    @Test fun fourRoleIntentUrlsHaveExactQueryAndPath() {
        for (role in MediaRole.entries) for (intent in PairingIntent.entries) {
            val uri = URI(ProtectedSignalingUrl.build("ABC234", role, intent))
            assertEquals("wss", uri.scheme); assertEquals("realtime-relay-k28l.onrender.com", uri.host); assertEquals("/ws", uri.rawPath)
            assertEquals("session=ABC234&role=${role.signalingRole}&intent=${intent.wire}", uri.rawQuery)
            assertNull(uri.fragment); assertNull(uri.userInfo)
        }
    }
    @Test fun badRelayShapeRejected() { for (base in listOf("https://host/ws", "wss://host/other", "wss://host/ws?q=x", "wss://user@host/ws", "wss://host/ws#x")) assertThrows(IllegalArgumentException::class.java) { ProtectedSignalingUrl.build("ABC234", MediaRole.SEND, PairingIntent.CREATE, base) } }
    @Test fun urlCannotAcceptInjectedCode() { assertThrows(IllegalArgumentException::class.java) { ProtectedSignalingUrl.build("ABC234&secret=x", MediaRole.SEND, PairingIntent.CREATE) } }
    @Test fun openSendsNoMessageUntilSocketOpenThenAuthFirst() { val f = TransportFixture(); assertTrue(f.socket.frames.isEmpty()); f.opened(); assertEquals(1, f.socket.frames.size); assertEquals("request-auth", JSONObject(f.socket.frames.single()).getString("type")); assertEquals(0, f.events.registered) }
    @Test fun authSendFalseFailsClosed() { val f = TransportFixture(); f.socket.sendOk = false; f.opened(); assertEquals(1, f.events.failures); assertTrue(f.socket.canceled); assertFalse(f.transport.send(offer)) }
    @Test fun consumedAuthFailsWithoutSendingAnything() { val b = bootstrap(); b.consumeAuthFrame(); val f = TransportFixture(b = b); f.opened(); assertTrue(f.socket.frames.isEmpty()); assertEquals(1, f.events.failures) }
    @Test fun oversizedCorruptedAuthFailsBeforeSend() { val b = bootstrap(); b.javaClass.getDeclaredField("auth").apply { isAccessible = true }.set(b, "x".repeat(513)); val f = TransportFixture(b = b); f.opened(); assertTrue(f.socket.frames.isEmpty()); assertEquals(1, f.events.failures) }
    @Test fun appCannotSendBeforeRegistrationAndIsNeverQueued() { val f = TransportFixture(); assertFalse(f.transport.send(offer)); f.opened(); assertFalse(f.transport.send(offer)); f.text(registration()); assertEquals(1, f.socket.frames.size); assertTrue(f.transport.send(offer)); assertEquals(2, f.socket.frames.size) }
    @Test fun registrationOkEmitsRegisteredOnce() { val f = TransportFixture(); f.register(); assertEquals(1, f.events.registered); assertEquals(0, f.events.failures) }
    @Test fun failedRegistrationFailsClosed() { val f = TransportFixture(); f.opened(); f.text(registration().replace("\"ok\"", "\"denied\"")); assertEquals(1, f.events.failures) }
    @Test fun duplicateRegistrationFailsClosed() { val f = TransportFixture(); f.register(); f.text(registration()); assertEquals(1, f.events.registered); assertEquals(1, f.events.failures) }
    @Test fun peerBeforeRegistrationFailsClosed() { val f = TransportFixture(); f.opened(); f.text("""{"type":"peer-present"}"""); assertEquals(1, f.events.failures); assertEquals(0, f.events.present) }
    @Test fun offerBeforeRegistrationFailsClosed() { val f = TransportFixture(); f.opened(); f.text(offer); assertEquals(1, f.events.failures); assertTrue(f.events.payloads.isEmpty()) }
    @Test fun peerPresentIsIdempotentAfterRegistration() { val f = TransportFixture(); f.register(); repeat(2) { f.text("""{"type":"peer-present"}""") }; assertEquals(1, f.events.present); assertEquals(0, f.events.failures) }
    @Test fun requestAuthInboundCannotReachPayload() { val f = TransportFixture(); f.register(); f.text("""{"type":"request-auth","secret":"synthetic"}"""); assertEquals(1, f.events.failures); assertTrue(f.events.payloads.isEmpty()) }
    @Test fun requestAuthOutboundCannotReachSocket() { val f = TransportFixture(); f.register(); val count = f.socket.frames.size; assertFalse(f.transport.send("""{"type":"request-auth"}""")); assertEquals(count, f.socket.frames.size); assertEquals(1, f.events.failures) }
    @Test fun malformedAndExtendedJsonFailClosed() { for (text in listOf("not-json", "[]", "{", "{'type':'peer-present'}", "{type:1}", """{"type":"peer-present",}""", """{"type":"peer-present"} true""", """{"type":"offer","sdp":NaN}""", """{"type":"peer-present","type":"offer"}""")) { val f = TransportFixture(); f.register(); f.text(text); assertEquals(text, 1, f.events.failures) } }
    @Test fun oversizedInboundFailsByUtf8Bytes() { val f = TransportFixture(); f.register(); f.text("""{"type":"offer","sdp":"${"é".repeat(33000)}"}"""); assertEquals(1, f.events.failures); assertTrue(f.events.payloads.isEmpty()) }
    @Test fun oversizedOutboundNeverSent() { val f = TransportFixture(); f.register(); assertFalse(f.transport.send("""{"type":"offer","sdp":"${"x".repeat(65536)}"}""")); assertEquals(1, f.socket.frames.size); assertEquals(1, f.events.failures) }
    @Test fun exact64KiBPayloadAllowed() { val f = TransportFixture(); f.register(); val prefix = """{"type":"offer","sdp":""""; val suffix = "\"}"; val text = prefix + "x".repeat(65536 - prefix.length - suffix.length) + suffix; assertEquals(65536, text.toByteArray().size); assertTrue(f.transport.send(text)); f.text(text); assertEquals(text, f.events.payloads.single()) }
    @Test fun binaryFailsWithoutPayload() { val f = TransportFixture(); f.register(); f.factory.listener.binary(); assertEquals(1, f.events.failures); assertTrue(f.events.payloads.isEmpty()) }
    @Test fun server1008IsOneGenericFailure() { val f = TransportFixture(); f.register(); f.factory.listener.closed(1008); f.factory.listener.failed(); assertEquals(listOf("registered", "failed"), f.events.order); assertEquals(1, f.factory.opens) }
    @Test fun localCloseUses1000AndNeverReconnects() { val f = TransportFixture(); f.register(); f.transport.close(); f.transport.close(); f.factory.listener.failed(); assertEquals(listOf(1000), f.socket.closes); assertEquals(1, f.events.closed); assertEquals(0, f.events.failures); assertEquals(1, f.factory.opens) }
    @Test fun remoteNormalCloseEmitsClosed() { val f = TransportFixture(); f.register(); f.factory.listener.closed(1000); assertEquals(1, f.events.closed); assertFalse(f.transport.send(offer)) }
    @Test fun normalCloseBeforeRegistrationIsFailure() { val f = TransportFixture(); f.opened(); f.factory.listener.closed(1000); assertEquals(1, f.events.failures) }
    @Test fun closeBeforeOpenPreventsAuth() { val f = TransportFixture(); f.transport.close(); f.opened(); assertTrue(f.socket.frames.isEmpty()); assertEquals(0, f.events.failures) }
    @Test fun payloadTypesForwardVerbatimAfterRegistration() { val f = TransportFixture(); f.register(); for (type in listOf("offer", "answer", "ice", "peer-left", "bye")) { val text = JSONObject().put("type", type).toString(); f.text(text); assertTrue(f.transport.send(text)); assertEquals(text, f.events.payloads.last()) }; assertEquals(5, f.events.payloads.size) }
    @Test fun remotePresenceRejectedWithoutLegacyFallback() { val f = TransportFixture(); f.register(); assertFalse(f.transport.send("""{"type":"remote-presence-register","token":"synthetic"}""")); assertEquals(1, f.events.failures); assertEquals(1, f.factory.opens) }
    @Test fun legacyRegistrationRemotePresenceTrueRejected() { val f = TransportFixture(); f.opened(); f.text(registration().replace("false", "true")); assertEquals(1, f.events.failures) }
    @Test fun registrationMustMatchRoleAndIntent() { for (bad in listOf(registration().replace("sender", "receiver"), registration().replace("create", "join"), """{"type":"registration","status":"ok"}""")) { val f = TransportFixture(); f.opened(); f.text(bad); assertEquals(1, f.events.failures) } }
    @Test fun malformedPeerLifecycleRejected() { val f = TransportFixture(); f.register(); f.text("""{"type":"peer-present","extra":true}"""); assertEquals(1, f.events.failures) }
    @Test fun eventsRespectDispatchBoundaryAndOrdering() { val queue = Queue(); val f = TransportFixture({ action -> queue.execute { action() } }); f.register(); f.text("""{"type":"peer-present"}"""); f.text(offer); assertTrue(f.events.order.isEmpty()); queue.run(); assertEquals(listOf("registered", "present", "payload"), f.events.order) }
    @Test fun failedDispatchBacklogDoesNotDeliverStaleHandoff() { val queue = Queue(); val f = TransportFixture({ action -> queue.execute { action() } }); f.register(); f.text("""{"type":"peer-present"}"""); f.factory.listener.failed(); queue.run(); assertEquals(listOf("failed"), f.events.order) }
    @Test fun concurrentConsumeOnlyOneAuthFrame() { val b = bootstrap(); val start = java.util.concurrent.CountDownLatch(1); val results = java.util.Collections.synchronizedList(mutableListOf<String?>()); val threads = (1..2).map { Thread { start.await(); results.add(b.consumeAuthFrame()) }.apply { start() } }; start.countDown(); threads.forEach { it.join(2000); assertFalse(it.isAlive) }; assertEquals(1, results.count { it != null }) }
    @Test fun factoryFailureNeverLeaksExceptionOrUrl() { val e = Events(); val t = ProtectedRequestSignaling.open(bootstrap(), ProtectedSocketFactory { _, _ -> error("sensitive synthetic failure") }, e); assertEquals(1, e.failures); assertEquals("ProtectedRequestSignaling[redacted]", t.toString()) }
    @Test fun synchronousFactoryOpenAuthenticatesFirst() { val socket = FakeSocket(); val e = Events(); ProtectedRequestSignaling.open(bootstrap(), ProtectedSocketFactory { _, listener -> listener.opened(socket); socket }, e); assertEquals("request-auth", JSONObject(socket.frames.single()).getString("type")) }
    @Test fun callbacksAfterFailureCannotRegisterOrForward() { val f = TransportFixture(); f.opened(); f.factory.listener.failed(); f.text(registration()); f.text(offer); assertEquals(0, f.events.registered); assertTrue(f.events.payloads.isEmpty()); assertEquals(1, f.events.failures) }
    @Test fun concurrentPeerNotificationsEmitOneHandoff() {
        val f = TransportFixture(); f.register(); val start = java.util.concurrent.CountDownLatch(1)
        val threads = (1..2).map { Thread { start.await(); f.text("""{"type":"peer-present"}""") }.apply { start() } }
        start.countDown(); threads.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertEquals(1, f.events.present); assertEquals(0, f.events.failures)
    }
    @Test fun duplicateSocketOpenCannotResendAuth() { val f = TransportFixture(); f.opened(); f.opened(); assertEquals(1, f.socket.frames.size); assertEquals(1, f.events.failures) }
    @Test fun bootstrapNeverAppearsInPublicUiFieldsOrSecretRendering() {
        val f = ControllerFixture(); val a = f.initiator(); f.controller.protectedSignalingBootstrap(a)
        assertFalse(ConnectionRequestUiState::class.java.declaredFields.any { it.type == ProtectedSignalingBootstrap::class.java || it.type == ConnectionCredential::class.java })
        assertFalse(f.controller.state.value.toString().contains(secret)); assertFalse(f.controller.state.value.toString().contains(claim))
    }
    private fun stateLock(transport: ProtectedRequestSignaling) = transport.javaClass.getDeclaredField("lock")
        .apply { isAccessible = true }.get(transport)
    @Test fun remoteNormalClosePurgesRegistrationPeerAndPayloadBacklog() {
        val queue = Queue(); val f = TransportFixture({ action -> queue.execute { action() } })
        f.register(); f.text("""{"type":"peer-present"}"""); f.text(offer)
        f.factory.listener.closed(1000); f.factory.listener.failed(); queue.run()
        assertEquals(listOf("closed"), f.events.order); assertTrue(f.events.payloads.isEmpty())
        assertEquals(0, f.events.registered); assertEquals(0, f.events.present)
    }
    @Test fun terminalRacesPurgeBacklogAndNotifyOnlyOnce() {
        for (reason in listOf("failure", "protocol", "1008", "binary", "oversize", "local")) {
            val queue = Queue(); val f = TransportFixture({ action -> queue.execute { action() } }); f.register()
            f.text("""{"type":"peer-present"}"""); f.text(offer)
            when (reason) {
                "failure" -> f.factory.listener.failed()
                "protocol" -> f.text("invalid-json")
                "1008" -> f.factory.listener.closed(1008)
                "binary" -> f.factory.listener.binary()
                "oversize" -> f.text("x".repeat(65537))
                else -> f.transport.close()
            }
            f.factory.listener.closed(1000); f.factory.listener.failed(); queue.run()
            assertEquals(reason, listOf(if (reason == "local") "closed" else "failed"), f.events.order)
            assertTrue(f.events.payloads.isEmpty())
        }
    }
    @Test fun synchronousNestedRegistrationAndAllEffectsRunOutsideStateLock() {
        val f = TransportFixture(); val held = mutableListOf<Boolean>(); val lock = stateLock(f.transport)
        f.socket.probe = { held.add(Thread.holdsLock(lock)) }
        f.events.probe = { held.add(Thread.holdsLock(lock)) }
        f.socket.onSend = { if (JSONObject(it).getString("type") == "request-auth") f.text(registration()) }
        f.opened(); assertEquals(1, f.events.registered)
        f.text("""{"type":"peer-present"}"""); f.text(offer); f.factory.listener.failed()
        assertEquals(1, f.events.failures); assertTrue(f.socket.canceled)
        assertTrue(held.size >= 7); assertTrue(held.none { it })
    }
    @Test fun falseAuthSendWinsOverSynchronousSuccessfulRegistration() {
        val f = TransportFixture(); f.socket.sendOk = false
        f.socket.onSend = { f.text(registration()) }
        f.opened(); assertEquals(listOf("failed"), f.events.order)
        assertFalse(f.transport.send(offer)); assertEquals(1, f.socket.frames.size)
        f.factory.listener.closed(1008); assertEquals(1, f.events.failures)
    }
    @Test fun registeredConsumerCanReentrantlySendThenCloseOutsideLock() {
        val f = TransportFixture(); val trace = mutableListOf<String>(); val held = mutableListOf<Boolean>()
        val lock = stateLock(f.transport)
        f.socket.onSend = { text ->
            trace.add(JSONObject(text).getString("type"))
            if (JSONObject(text).getString("type") == "request-auth") f.text(registration())
        }
        var accepted = false
        f.events.onRegistered = {
            held.add(Thread.holdsLock(lock)); trace.add("registered")
            accepted = f.transport.send(offer); f.transport.close()
        }
        f.opened()
        assertTrue(accepted); assertEquals(listOf("request-auth", "registered", "offer"), trace)
        assertEquals(listOf("registered", "closed"), f.events.order); assertEquals(listOf(false), held)
    }
    @Test fun peerConsumerReentrantClosePurgesLaterPayloadOutsideLock() {
        val queue = Queue(); val f = TransportFixture({ action -> queue.execute { action() } }); f.register()
        val lock = stateLock(f.transport); var held = true
        f.events.onPresent = { held = Thread.holdsLock(lock); f.transport.close() }
        f.text("""{"type":"peer-present"}"""); f.text(offer); queue.run()
        assertFalse(held); assertEquals(listOf("registered", "present", "closed"), f.events.order)
        assertTrue(f.events.payloads.isEmpty())
    }
    @Test fun messageConsumerReentrantClosePurgesNextMessage() {
        val queue = Queue(); val f = TransportFixture({ action -> queue.execute { action() } }); f.register()
        var held = true
        f.events.onPayload = { held = Thread.holdsLock(stateLock(f.transport)); f.transport.close() }
        f.text(offer); f.text(offer); queue.run()
        assertFalse(held); assertEquals(1, f.events.payloads.size); assertEquals("closed", f.events.order.last())
    }
    @Test fun synchronousCloseCallbacksCannotDuplicateTerminalOrRunUnderLock() {
        val f = TransportFixture(); f.register(); val held = mutableListOf<Boolean>(); val lock = stateLock(f.transport)
        f.socket.probe = { held.add(Thread.holdsLock(lock)) }; f.events.probe = { held.add(Thread.holdsLock(lock)) }
        f.socket.onClose = { f.factory.listener.closed(1008); f.factory.listener.failed() }
        f.text("invalid-json")
        assertEquals(1, f.events.failures); assertTrue(held.isNotEmpty()); assertTrue(held.none { it })
    }
    @Test fun socketFactoryAndEarlyOpenAreOutsideStateLock() {
        var factoryHeld = true; var socketHeld = true; var transport: ProtectedRequestSignaling? = null
        val socket = FakeSocket(); val events = Events()
        val result = ProtectedRequestSignaling.open(bootstrap(), ProtectedSocketFactory { _, listener ->
            transport = listener as ProtectedRequestSignaling
            val lock = stateLock(transport!!); factoryHeld = Thread.holdsLock(lock)
            socket.probe = { socketHeld = Thread.holdsLock(lock) }
            listener.opened(socket); socket
        }, events)
        assertSame(transport, result); assertFalse(factoryHeld); assertFalse(socketHeld)
    }
    @Test fun sendAdmissionAllowsConcurrentLaterFailureButRejectsSubsequentSend() {
        val f = TransportFixture(); f.register()
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        f.socket.onSend = { entered.countDown(); release.await(2, java.util.concurrent.TimeUnit.SECONDS) }
        var accepted = false
        val thread = Thread { accepted = f.transport.send(offer) }.apply { start() }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        f.factory.listener.failed(); assertFalse(f.transport.send(offer))
        release.countDown(); thread.join(2000); assertFalse(thread.isAlive)
        assertTrue(accepted); assertEquals(1, f.events.failures)
    }
    private fun sizedOffer(bytes: Int): String {
        val prefix = "{\"type\":\"offer\",\"sdp\":\""; val suffix = "\"}"
        return prefix + "x".repeat(bytes - prefix.length - suffix.length) + suffix
    }
    @Test fun exactUtf8BoundaryTripletInboundAndOutbound() {
        for (bytes in listOf(65535, 65536, 65537)) {
            val text = sizedOffer(bytes); assertEquals(bytes, text.toByteArray(Charsets.UTF_8).size)
            val outbound = TransportFixture(); outbound.register()
            assertEquals(bytes <= 65536, outbound.transport.send(text))
            assertEquals(if (bytes <= 65536) 2 else 1, outbound.socket.frames.size)
            val inbound = TransportFixture(); inbound.register(); inbound.text(text)
            assertEquals(if (bytes <= 65536) 1 else 0, inbound.events.payloads.size)
            assertEquals(if (bytes <= 65536) 0 else 1, inbound.events.failures)
        }
    }
    @Test fun multibyteOutboundRejectsByBytesNotCharacters() {
        val text = "{\"type\":\"offer\",\"sdp\":\"${"é".repeat(33000)}\"}"
        assertTrue(text.length < 65536); assertTrue(text.toByteArray(Charsets.UTF_8).size > 65536)
        val f = TransportFixture(); f.register(); assertFalse(f.transport.send(text)); assertEquals(1, f.socket.frames.size)
    }
    @Test fun hardenedFactoryHasNoClientInjectionOrInterceptorsAndDisablesRedirectAndRetry() {
        val factory = OkHttpProtectedSocketFactory(); val policy = factory.policy()
        assertFalse(policy.followRedirects); assertFalse(policy.followSslRedirects); assertFalse(policy.retryOnConnectionFailure)
        assertEquals(0, policy.applicationInterceptors); assertEquals(0, policy.networkInterceptors)
        assertTrue(factory.javaClass.declaredConstructors.none { ctor -> ctor.parameterTypes.any { it == okhttp3.OkHttpClient::class.java } })
        val f = TransportFixture()
        assertThrows(IllegalArgumentException::class.java) { factory.open("ws://invalid/ws", f.factory.listener) }
    }
    @Test fun unknownTypeStillFailsClosed() { val f = TransportFixture(); f.register(); f.text("""{"type":"unknown-control"}"""); assertEquals(1, f.events.failures); assertTrue(f.events.payloads.isEmpty()) }
    private fun source(name: String) = listOf(File("src/main/java/com/pradertamas/uicapturetest/$name.kt"), File("app/src/main/java/com/pradertamas/uicapturetest/$name.kt")).first { it.isFile }.readText()
    @Test fun noActivityOrSetupAutoOpenAndNoBearerPersistence() { for (name in listOf("MainActivity", "ReceiverActivity", "ConnectionRequestActivity", "ConnectionRequestSetupCoordinator", "ConnectionRequestInitiatorMonitor")) { val text = source(name); assertFalse(text.contains("ProtectedRequestSignaling")); assertFalse(text.contains("protectedSignalingBootstrap(")) }; val text = source("ProtectedRequestSignaling"); for (bad in listOf("Log.", "println", "Toast", "SavedState", "SharedPreferences", "createPeerConnection", "MediaProjection", "claimConsent")) assertFalse(bad, text.contains(bad)) }
}
