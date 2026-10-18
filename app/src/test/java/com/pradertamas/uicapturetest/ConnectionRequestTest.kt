package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executor
import java.io.IOException

class ConnectionRequestTest {
    private val id = token(16, 1)
    private val initiator = token(32, 2)
    private val capability = token(16, 3)
    private fun token(bytes: Int, seed: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes) { seed.toByte() })
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun step() { tasks.removeFirst().run() }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private fun body(state: String = "WAITING", role: String = "send", requestId: String = id): JSONObject = JSONObject()
        .put("requestId", requestId).put("state", state).put("initiatorRole", role)
        .put("serverNow", "2026-01-01T00:00:00Z").put("createdAt", "2026-01-01T00:00:00Z")
        .put("expiresAt", "2026-01-01T00:05:00Z")
        .also {
            if (state in listOf("ACCEPTED", "CONNECTING", "CONNECTED", "FAILED")) it
                .put("acceptedAt", "2026-01-01T00:00:00Z").put("connectBy", "2026-01-01T00:02:00Z")
                .put("initiatorReady", false).put("recipientReady", false)
            if (state in listOf("CONNECTING", "CONNECTED")) it.put("session", JSONObject().put("code", "ABC234")
                .put("role", if (role == "send") "sender" else "receiver").put("intent", "create"))
            if (state == "CONNECTED") it.put("connectedAt", "2026-01-01T00:00:01Z")
            if (state == "FAILED") it.put("failureReason", "establishment_timeout")
        }
    private fun created(role: String = "send", requestId: String = id) = body(role = role, requestId = requestId)
        .put("initiatorSecret", initiator).put("recipientCapability", capability)
    private inner class Fixture {
        val queue = Queue()
        val calls = mutableListOf<String>()
        val auths = mutableListOf<String?>()
        val inputs = mutableListOf<String?>()
        var respond: (String, String?) -> ConnectionRequestWireResponse = { path, _ ->
            ConnectionRequestWireResponse(if (path == "/connection-requests") 201 else 200,
                if (path == "/connection-requests") created().toString() else body().toString())
        }
        val api = ConnectionRequestApiClient(ConnectionRequestTransport { _, path, auth, input ->
            calls.add(path); auths.add(auth?.value); inputs.add(input)
            val result = respond(path, input)
            if (path.endsWith("/status") && result.code in listOf(200, 410)) {
                val json = JSONObject(result.body)
                if (auth?.value == capability) for (field in listOf("createdAt", "acceptedAt", "connectBy", "initiatorReady", "recipientReady", "session", "failureReason", "connectedAt")) json.remove(field)
                else if (auth?.value != initiator && json.has("session")) json.getJSONObject("session")
                    .put("intent", "join").put("role", if (json.getString("initiatorRole") == "send") "receiver" else "sender")
                ConnectionRequestWireResponse(result.code, json.toString(), result.retryAfterSeconds)
            } else result
        })
        val controller = ConnectionRequestController(api, queue)
        fun create(role: MediaRole = MediaRole.SEND) { controller.create(role); queue.run() }
        fun response(state: String, code: Int = 200, role: String = "send", modify: (JSONObject) -> Unit = {}) {
            respond = { _, _ -> ConnectionRequestWireResponse(code, body(state, role).also(modify).toString()) }
        }
        fun preview() { controller.preview(id, capability, MediaRole.SEND) }
        fun accept() { preview(); response("ACCEPTED"); controller.accept(); queue.run() }
        val ui get() = controller.state.value
    }
    @Test fun createSend() { val f = Fixture(); f.create(); assertEquals(MediaRole.SEND, f.ui.request?.initiatorRole); assertEquals(ConnectionRequestState.WAITING, f.ui.request?.state) }
    @Test fun createReceive() {
        val f = Fixture(); f.respond = { _, _ -> ConnectionRequestWireResponse(201, created("receive").toString()) }
        f.create(MediaRole.RECEIVE); assertEquals(MediaRole.RECEIVE, f.ui.request?.initiatorRole)
        assertEquals("receive", JSONObject(f.inputs.last()!!).getString("initiatorRole"))
    }
    @Test fun replacementCancelsBeforeCreate() {
        val f = Fixture(); f.create(); f.respond = { path, _ -> if (path.endsWith("cancel")) ConnectionRequestWireResponse(200, body("CANCELED").toString()) else ConnectionRequestWireResponse(201, created().toString()) }
        f.create(); assertTrue(f.calls[f.calls.size - 2].endsWith("cancel")); assertEquals(ConnectionRequestState.WAITING, f.ui.request?.state)
    }
    @Test fun replacementCancelFailureDoesNotRestoreOldContext() {
        val f = Fixture(); f.create(); val newId = token(16, 4)
        f.respond = { path, _ -> if (path.endsWith("cancel")) throw IOException(initiator) else ConnectionRequestWireResponse(201, created(requestId = newId).toString()) }
        f.create(); assertEquals(newId, f.ui.request?.requestId); assertEquals(ConnectionRequestError.NETWORK, f.ui.error)
    }
    @Test fun staleCreateIgnoredAndBestEffortCanceled() {
        val f = Fixture(); var first = true
        f.respond = { path, _ ->
            when {
                path.endsWith("cancel") -> ConnectionRequestWireResponse(200, body("CANCELED").toString())
                first -> { first = false; f.controller.create(MediaRole.RECEIVE); ConnectionRequestWireResponse(201, created().toString()) }
                else -> ConnectionRequestWireResponse(201, created("receive", token(16, 4)).toString())
            }
        }
        f.create(); assertEquals(MediaRole.RECEIVE, f.ui.request?.initiatorRole); assertTrue(f.calls.any { it.endsWith("cancel") })
    }
    @Test fun statusWaitingUsesInitiatorAuth() { val f = Fixture(); f.create(); f.controller.refreshStatus(); f.queue.run(); assertEquals(initiator, f.auths.last()); assertEquals(ConnectionRequestState.WAITING, f.ui.request?.state) }
    @Test fun statusAccepted() { val f = Fixture(); f.create(); f.response("ACCEPTED"); f.controller.refreshStatus(); f.queue.run(); assertEquals(ConnectionRequestState.ACCEPTED, f.ui.request?.state) }
    @Test fun goneFailedParsed() { val f = Fixture(); f.create(); f.response("FAILED", 410) { it.put("failureReason", "recipient_withdrew") }; f.controller.refreshStatus(); f.queue.run(); assertEquals("recipient_withdrew", f.ui.request?.failureReason) }
    @Test fun goneExpiredParsed() { val f = Fixture(); f.create(); f.response("EXPIRED", 410); f.controller.refreshStatus(); f.queue.run(); assertEquals(ConnectionRequestState.EXPIRED, f.ui.request?.state); assertEquals(ConnectionRequestError.EXPIRED, f.ui.error) }
    @Test fun claimGeneratedBeforeAcceptAndRetainedAcrossLostResponse() {
        val f = Fixture(); f.preview(); var seen: String? = null
        f.respond = { _, input -> seen = JSONObject(input!!).getString("claimSecret"); throw IOException() }
        f.controller.accept(); assertEquals(ConnectionRequestOperation.ACCEPT, f.ui.operation); f.queue.run()
        assertTrue(ConnectionRequestSecrets.canonical(seen!!, 32))
        f.response("ACCEPTED"); f.controller.accept(); f.queue.run()
        assertEquals(seen, JSONObject(f.inputs.last()!!).getString("claimSecret")); assertEquals(ConnectionRequestState.ACCEPTED, f.ui.request?.state)
    }
    @Test fun competingClaimDoesNotRotate() {
        val f = Fixture(); f.preview(); f.respond = { _, _ -> ConnectionRequestWireResponse(409, "{}") }
        f.controller.accept(); f.queue.run(); val first = JSONObject(f.inputs.last()!!).getString("claimSecret")
        f.controller.accept(); f.queue.run(); assertEquals(first, JSONObject(f.inputs.last()!!).getString("claimSecret")); assertEquals(ConnectionRequestError.CONFLICT, f.ui.error)
    }
    @Test fun successfulAcceptRetrySameClaim() { val f = Fixture(); f.accept(); val first = f.inputs.last(); f.controller.accept(); f.queue.run(); assertEquals(first, f.inputs.last()) }
    @Test fun cancelWaiting() { val f = Fixture(); f.create(); f.response("CANCELED"); f.controller.cancel(); f.queue.run(); assertEquals(ConnectionRequestState.CANCELED, f.ui.request?.state) }
    @Test fun cancelConnecting() { val f = Fixture(); f.create(); f.response("CONNECTING"); f.controller.refreshStatus(); f.queue.run(); f.response("CANCELED"); f.controller.cancel(); f.queue.run(); assertEquals(ConnectionRequestState.CANCELED, f.ui.request?.state) }
    @Test fun cancelConnectedAndReplacementBlocked() { val f = Fixture(); f.create(); f.response("CONNECTED"); f.controller.refreshStatus(); f.queue.run(); val count = f.calls.size; f.controller.cancel(); f.controller.create(MediaRole.RECEIVE); f.queue.run(); assertEquals(count, f.calls.size); assertEquals(ConnectionRequestError.INVALID_OPERATION, f.ui.error) }
    @Test fun withdrawAccepted() { val f = Fixture(); f.accept(); f.response("FAILED") { it.put("failureReason", "recipient_withdrew") }; f.controller.withdraw(); f.queue.run(); assertEquals("recipient_withdrew", f.ui.request?.failureReason) }
    @Test fun withdrawConnecting() { val f = Fixture(); f.accept(); f.response("CONNECTING"); f.controller.refreshStatus(); f.queue.run(); f.response("FAILED") { it.put("failureReason", "recipient_withdrew") }; f.controller.withdraw(); f.queue.run(); assertEquals(ConnectionRequestState.FAILED, f.ui.request?.state) }
    @Test fun withdrawRetryUsesSameClaim() { val f = Fixture(); f.accept(); f.response("FAILED") { it.put("failureReason", "recipient_withdrew") }; f.controller.withdraw(); f.queue.run(); val auth = f.auths.last(); f.controller.withdraw(); f.queue.run(); assertEquals(auth, f.auths.last()) }
    @Test fun capabilityCannotWithdraw() { val f = Fixture(); f.preview(); f.controller.withdraw(); f.queue.run(); assertTrue(f.calls.isEmpty()); assertEquals(ConnectionRequestError.INVALID_OPERATION, f.ui.error) }
    private fun readiness(recipient: Boolean) {
        val f = Fixture(); if (recipient) f.accept() else { f.create(); f.response("ACCEPTED"); f.controller.refreshStatus(); f.queue.run() }
        val expectedAuth = if (recipient) JSONObject(f.inputs.last()!!).getString("claimSecret") else initiator
        f.response("ACCEPTED") { it.put("ready", true).put("readyUntil", "2026-01-01T00:00:10Z") }
        f.controller.setReady(true); f.queue.run(); assertNotNull(f.ui.request?.readyUntil)
        assertEquals(expectedAuth, f.auths.last())
    }
    @Test fun readinessInitiator() = readiness(false)
    @Test fun readinessRecipient() = readiness(true)
    private fun assignment(role: MediaRole, wireRole: String) {
        val f = Fixture(); val wire = ConnectionRequestRoles.wire(role)
        f.respond = { _, _ -> ConnectionRequestWireResponse(201, created(wire).toString()) }; f.create(role)
        f.response("ACCEPTED", role = wire); f.controller.refreshStatus(); f.queue.run()
        f.response("CONNECTING", role = wire) { it.put("session", JSONObject().put("code", "ABC234").put("role", wireRole).put("intent", "create")) }
        f.controller.createSession(); f.queue.run(); assertEquals(role, f.ui.request?.assignment?.role); assertEquals(PairingIntent.CREATE, f.ui.request?.assignment?.intent)
    }
    @Test fun sessionSendAssignment() = assignment(MediaRole.SEND, "sender")
    @Test fun sessionReceiveAssignment() = assignment(MediaRole.RECEIVE, "receiver")
    @Test fun malformedJsonSafe() { val f = Fixture(); f.respond = { _, _ -> ConnectionRequestWireResponse(201, "not-json-$initiator") }; f.create(); assertEquals(ConnectionRequestError.INVALID_RESPONSE, f.ui.error); assertFalse(f.ui.toString().contains(initiator)) }
    @Test fun wrongAuth404DoesNotInferTerminalState() { val f = Fixture(); f.create(); f.respond = { _, _ -> ConnectionRequestWireResponse(404, "{}") }; f.controller.refreshStatus(); f.queue.run(); assertEquals(ConnectionRequestState.WAITING, f.ui.request?.state); assertEquals(ConnectionRequestError.UNAVAILABLE, f.ui.error) }
    @Test fun rateLimit429() { val f = Fixture(); f.respond = { _, _ -> ConnectionRequestWireResponse(429, "{}", 60) }; f.preview(); f.controller.refreshStatus(); f.queue.run(); assertEquals(ConnectionRequestError.RATE_LIMITED, f.ui.error); assertEquals(60L, f.ui.retryAfterSeconds) }
    @Test fun networkFailureSafe() { val f = Fixture(); f.respond = { _, _ -> throw IOException(initiator) }; f.create(); assertEquals(ConnectionRequestError.NETWORK, f.ui.error); assertFalse(f.ui.toString().contains(initiator)) }
    @Test fun staleStatusIgnored() { val f = Fixture(); f.create(); f.respond = { _, _ -> f.controller.clear(); ConnectionRequestWireResponse(200, body("ACCEPTED").toString()) }; f.controller.refreshStatus(); f.queue.run(); assertTrue(f.ui.idle); assertNull(f.ui.request) }
    @Test fun credentialsAbsentFromPublicStateAndStrings() { val f = Fixture(); f.accept(); val claim = JSONObject(f.inputs.last()!!).getString("claimSecret"); for (value in listOf(claim, initiator, capability)) assertFalse(f.ui.toString().contains(value)); assertFalse(ConnectionCredential(initiator).toString().contains(initiator)) }
    @Test fun previewStatusUsesCapabilityThenClaimAfterAccept() { val f = Fixture(); f.preview(); f.controller.refreshStatus(); f.queue.run(); assertEquals(capability, f.auths.last()); f.response("ACCEPTED"); f.controller.accept(); f.queue.run(); val claim = JSONObject(f.inputs.last()!!).getString("claimSecret"); f.controller.refreshStatus(); f.queue.run(); assertEquals(claim, f.auths.last()) }
    @Test fun invalidResponseStatesDatesAssignment() {
        for (mutate in listOf<(JSONObject) -> Unit>({ it.put("state", "FUTURE") }, { it.put("expiresAt", "bad") },
            { it.put("requestId", "bad") }, { it.put("session", JSONObject().put("code", "bad").put("role", "sender").put("intent", "create")) })) {
            val f = Fixture(); f.respond = { _, _ -> ConnectionRequestWireResponse(201, created().also(mutate).toString()) }; f.create(); assertEquals(ConnectionRequestError.INVALID_RESPONSE, f.ui.error)
        }
    }
    @Test fun safeHttpErrorCategories() { for ((code, expected) in listOf(400 to ConnectionRequestError.INVALID_REQUEST, 409 to ConnectionRequestError.CONFLICT, 503 to ConnectionRequestError.SERVER)) { val f = Fixture(); f.respond = { _, _ -> ConnectionRequestWireResponse(code, initiator) }; f.create(); assertEquals(expected, f.ui.error) } }
    @Test fun noActivityOrStorageNeededForLifetime() { val f = Fixture(); f.create(); val observed = f.controller.state; assertSame(f.controller.state, observed); assertEquals(ConnectionRequestState.WAITING, observed.value.request?.state) }
    @Test fun baseUrlDerivedWithoutChangingSignaling() { assertEquals("https://realtime-relay-k28l.onrender.com", ConnectionRequestConfig.httpBase()); assertTrue(WebRtcConfig.RELAY.startsWith("wss://")) }
    @Test fun claimFactoryRunsBeforeWorkerAndOnlyOnce() {
        val f = Fixture(); var generated = 0
        val c = ConnectionRequestController(f.api, f.queue) { generated++; ConnectionRequestSecrets.claim() }
        c.preview(id, capability, MediaRole.SEND); c.accept()
        assertEquals(1, generated); assertTrue(f.calls.isEmpty())
        f.respond = { _, _ -> throw IOException() }; f.queue.run(); c.accept(); assertEquals(1, generated)
        f.queue.run()
    }
    @Test fun staleReplacementCancelCannotOverwriteNewCreate() {
        val f = Fixture(); f.create(); var once = true
        f.respond = { path, _ ->
            if (path.endsWith("cancel")) {
                if (once) { once = false; f.controller.create(MediaRole.RECEIVE) }
                ConnectionRequestWireResponse(503, "{}")
            } else ConnectionRequestWireResponse(201, created("receive", token(16, 4)).toString())
        }
        f.create(); assertEquals(MediaRole.RECEIVE, f.ui.request?.initiatorRole); assertNull(f.ui.error)
    }
    @Test fun readinessFalseAndRecipientSessionDenied() {
        val f = Fixture(); f.accept(); f.response("ACCEPTED") { it.put("ready", false).put("readyUntil", JSONObject.NULL) }
        f.controller.setReady(false); f.queue.run(); assertEquals(false, f.ui.request?.ready); assertNull(f.ui.request?.readyUntil)
        val count = f.calls.size; f.controller.createSession(); f.queue.run(); assertEquals(count, f.calls.size)
    }
    @Test fun regressingStateAndWrongAssignmentAreRejected() {
        val f = Fixture(); f.create(); f.response("ACCEPTED"); f.controller.refreshStatus(); f.queue.run()
        f.response("WAITING"); f.controller.refreshStatus(); f.queue.run()
        assertEquals(ConnectionRequestError.INVALID_RESPONSE, f.ui.error); assertEquals(ConnectionRequestState.ACCEPTED, f.ui.request?.state)
        f.response("CONNECTING") { it.put("session", JSONObject().put("code", "ABC234").put("role", "receiver").put("intent", "create")) }
        f.controller.createSession(); f.queue.run(); assertEquals(ConnectionRequestError.INVALID_RESPONSE, f.ui.error)
    }
    @Test fun unknownFailureReasonCannotLeakRawBody() {
        val f = Fixture(); f.create(); f.response("FAILED", 410) { it.put("failureReason", initiator) }
        f.controller.refreshStatus(); f.queue.run(); assertEquals("other", f.ui.request?.failureReason); assertFalse(f.ui.toString().contains(initiator))
    }
    @Test fun noQueryAuthAndGetHasNoBody() {
        val f = Fixture(); f.preview(); f.controller.refreshStatus(); f.queue.run()
        assertFalse(f.calls.last().contains('?')); assertNull(f.inputs.last()); assertEquals(capability, f.auths.last())
    }
    @Test fun acceptConcurrentRefreshKeepsWinningPrivilege() {
        val f = Fixture(); f.preview()
        f.respond = { _, _ ->
            val before = f.ui; f.controller.refreshStatus(); assertSame(before, f.ui)
            ConnectionRequestWireResponse(200, body("ACCEPTED").toString())
        }
        f.controller.accept(); f.queue.run(); assertEquals(1, f.calls.size); assertNull(f.ui.operation)
        f.response("ACCEPTED") { it.put("ready", true).put("readyUntil", "2026-01-01T00:00:10Z") }
        f.controller.setReady(true); f.queue.run(); assertEquals(2, f.calls.size); assertEquals(true, f.ui.request?.ready)
        f.response("ACCEPTED"); f.controller.refreshStatus(); f.queue.run()
        assertNotEquals(capability, f.auths.last()); assertEquals(ConnectionRequestState.ACCEPTED, f.ui.request?.state)
    }
    @Test fun twoQuickAcceptsOneClaimAndOneHttp() {
        val f = Fixture(); var generated = 0
        val c = ConnectionRequestController(f.api, f.queue) { generated++; ConnectionRequestSecrets.claim() }
        c.preview(id, capability, MediaRole.SEND); f.response("ACCEPTED")
        c.accept(); c.accept(); assertEquals(1, generated); assertEquals(ConnectionRequestError.OPERATION_IN_PROGRESS, c.state.value.error)
        f.queue.run(); assertEquals(1, f.calls.size); assertEquals(ConnectionRequestState.ACCEPTED, c.state.value.request?.state)
        assertNull(c.state.value.operation); assertNull(c.state.value.error)
    }
    private fun mutationRefresh(op: ConnectionRequestOperation) {
        val f = Fixture()
        if (op == ConnectionRequestOperation.WITHDRAW) f.accept() else f.create()
        if (op in listOf(ConnectionRequestOperation.SESSION, ConnectionRequestOperation.READINESS)) {
            f.response("ACCEPTED"); f.controller.refreshStatus(); f.queue.run()
        }
        val previousCalls = f.calls.size
        val result = when (op) {
            ConnectionRequestOperation.CANCEL -> body("CANCELED")
            ConnectionRequestOperation.WITHDRAW -> body("FAILED").put("failureReason", "recipient_withdrew")
            ConnectionRequestOperation.SESSION -> body("CONNECTING")
            else -> body("ACCEPTED").put("ready", true).put("readyUntil", "2026-01-01T00:00:10Z")
        }
        f.respond = { _, _ ->
            val before = f.ui; assertEquals(op, before.operation)
            f.controller.refreshStatus(); assertSame(before, f.ui)
            ConnectionRequestWireResponse(200, result.toString())
        }
        when (op) {
            ConnectionRequestOperation.CANCEL -> f.controller.cancel()
            ConnectionRequestOperation.WITHDRAW -> f.controller.withdraw()
            ConnectionRequestOperation.SESSION -> f.controller.createSession()
            else -> f.controller.setReady(true)
        }
        f.queue.run(); assertEquals(previousCalls + 1, f.calls.size); assertNull(f.ui.error); assertNull(f.ui.operation)
        when (op) {
            ConnectionRequestOperation.CANCEL -> assertEquals(ConnectionRequestState.CANCELED, f.ui.request?.state)
            ConnectionRequestOperation.WITHDRAW -> assertEquals("recipient_withdrew", f.ui.request?.failureReason)
            ConnectionRequestOperation.SESSION -> assertEquals("ABC234", f.ui.request?.assignment?.code)
            else -> assertNotNull(f.ui.request?.readyUntil)
        }
    }
    @Test fun cancelConcurrentRefresh() = mutationRefresh(ConnectionRequestOperation.CANCEL)
    @Test fun withdrawConcurrentRefresh() = mutationRefresh(ConnectionRequestOperation.WITHDRAW)
    @Test fun sessionConcurrentRefresh() = mutationRefresh(ConnectionRequestOperation.SESSION)
    @Test fun readinessConcurrentRefresh() = mutationRefresh(ConnectionRequestOperation.READINESS)
    @Test fun secondMutationDoesNotQueueOrSend() {
        val f = Fixture(); f.create(); val count = f.calls.size
        f.response("CANCELED"); f.controller.cancel(); f.controller.setReady(true)
        assertEquals(ConnectionRequestOperation.CANCEL, f.ui.operation)
        assertEquals(ConnectionRequestError.OPERATION_IN_PROGRESS, f.ui.error)
        assertEquals(1, f.queue.tasks.size); f.queue.run(); assertEquals(count + 1, f.calls.size)
    }
    @Test fun refreshSingleFlightAndNoPublicError() {
        val f = Fixture(); f.create(); val count = f.calls.size
        f.controller.refreshStatus(); val before = f.ui
        f.controller.refreshStatus(); assertSame(before, f.ui)
        f.respond = { _, _ -> f.controller.refreshStatus(); ConnectionRequestWireResponse(200, body().toString()) }
        f.queue.run(); assertEquals(count + 1, f.calls.size); assertNull(f.ui.operation); assertNull(f.ui.error)
    }
    @Test fun oldStatusDoesNotResetNewRecipientOperation() {
        val f = Fixture(); f.create(); val newId = token(16, 9)
        f.respond = { _, _ ->
            f.controller.clear(); f.controller.preview(newId, capability, MediaRole.SEND); f.controller.refreshStatus()
            ConnectionRequestWireResponse(200, body().toString())
        }
        f.controller.refreshStatus(); f.queue.step()
        assertEquals(ConnectionRequestOperation.STATUS, f.ui.operation); assertNull(f.ui.request)
        f.respond = { _, _ -> ConnectionRequestWireResponse(200, body(requestId = newId).toString()) }
        f.queue.run(); assertEquals(newId, f.ui.request?.requestId); assertNull(f.ui.operation)
    }
    @Test fun allStaleMutationsIgnoredWithoutResettingNewCreate() {
        for (op in listOf(ConnectionRequestOperation.ACCEPT, ConnectionRequestOperation.CANCEL,
            ConnectionRequestOperation.WITHDRAW, ConnectionRequestOperation.READINESS, ConnectionRequestOperation.SESSION)) {
            val f = Fixture()
            if (op in listOf(ConnectionRequestOperation.ACCEPT, ConnectionRequestOperation.WITHDRAW)) {
                if (op == ConnectionRequestOperation.ACCEPT) f.preview() else f.accept()
            } else { f.create(); f.response("ACCEPTED"); f.controller.refreshStatus(); f.queue.run() }
            f.respond = { _, _ ->
                f.controller.clear(); f.controller.create(MediaRole.RECEIVE)
                val value = when (op) {
                    ConnectionRequestOperation.CANCEL -> body("CANCELED")
                    ConnectionRequestOperation.WITHDRAW -> body("FAILED").put("failureReason", "recipient_withdrew")
                    ConnectionRequestOperation.SESSION -> body("CONNECTING")
                    ConnectionRequestOperation.READINESS -> body("ACCEPTED").put("ready", false).put("readyUntil", JSONObject.NULL)
                    else -> body("ACCEPTED")
                }
                ConnectionRequestWireResponse(200, value.toString())
            }
            when (op) {
                ConnectionRequestOperation.ACCEPT -> f.controller.accept()
                ConnectionRequestOperation.CANCEL -> f.controller.cancel()
                ConnectionRequestOperation.WITHDRAW -> f.controller.withdraw()
                ConnectionRequestOperation.SESSION -> f.controller.createSession()
                else -> f.controller.setReady(false)
            }
            f.queue.step(); assertEquals(ConnectionRequestOperation.CREATE, f.ui.operation); assertNull(f.ui.request)
            f.respond = { _, _ -> ConnectionRequestWireResponse(201, created("receive").toString()) }
            f.queue.run(); assertEquals(MediaRole.RECEIVE, f.ui.request?.initiatorRole); assertNull(f.ui.operation)
        }
    }
    @Test fun mutationExceptionReleasesFlightForNextRefresh() {
        val f = Fixture(); f.create(); f.respond = { _, _ -> throw IOException(initiator) }
        f.controller.cancel(); f.controller.refreshStatus(); f.queue.run()
        assertNull(f.ui.operation); assertEquals(ConnectionRequestError.NETWORK, f.ui.error)
        f.response("WAITING"); f.controller.refreshStatus(); f.queue.run(); assertNull(f.ui.error)
    }
    @Test fun safeMutationFailureReleasesFlight() {
        val f = Fixture(); f.preview(); f.respond = { _, _ -> ConnectionRequestWireResponse(409, "{}") }
        f.controller.accept(); f.queue.run(); assertNull(f.ui.operation)
        val claim = JSONObject(f.inputs.last()!!).getString("claimSecret")
        f.response("ACCEPTED"); f.controller.accept(); f.queue.run()
        assertEquals(claim, JSONObject(f.inputs.last()!!).getString("claimSecret")); assertNull(f.ui.operation)
    }
    @Test fun mutationDuringRefreshGetsBusyWithoutCancellingRefresh() {
        val f = Fixture(); f.create(); f.controller.refreshStatus(); f.controller.cancel()
        assertEquals(ConnectionRequestOperation.STATUS, f.ui.operation); assertEquals(1, f.queue.tasks.size)
        f.queue.run(); assertEquals(ConnectionRequestState.WAITING, f.ui.request?.state); assertNull(f.ui.operation)
    }
    private fun parseStatus(json: JSONObject, scope: ConnectionRequestAuthScope = ConnectionRequestAuthScope.INITIATOR,
        code: Int = 200): ConnectionRequestResult = ConnectionRequestApiClient(ConnectionRequestTransport { _, _, _, _ ->
            ConnectionRequestWireResponse(code, json.toString()) }).status(id, ConnectionCredential(initiator), scope)
    @Test fun privilegedAcceptedRequiresAcceptedAt() { val j = body("ACCEPTED"); j.remove("acceptedAt"); assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(j).error) }
    @Test fun privilegedAcceptedRequiresConnectBy() { val j = body("ACCEPTED"); j.remove("connectBy"); assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(j).error) }
    @Test fun privilegedAcceptedRequiresBooleanReadiness() { for (value in listOf<Any>("false", JSONObject.NULL)) { val j = body("ACCEPTED").put("initiatorReady", value); assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(j).error) } }
    @Test fun privilegedConnectingRequiresSession() { val j = body("CONNECTING"); j.remove("session"); assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(j).error) }
    @Test fun connectingInvalidRoleRejected() { val j = body("CONNECTING"); j.getJSONObject("session").put("role", "send"); assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(j).error) }
    @Test fun connectingInvalidIntentRejected() { val j = body("CONNECTING"); j.getJSONObject("session").put("intent", "host"); assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(j).error) }
    @Test fun connectingInvalidCodeOrJsonTypeRejected() { for (value in listOf<Any>("bad", 234567)) { val j = body("CONNECTING"); j.getJSONObject("session").put("code", value); assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(j).error) } }
    @Test fun capabilityAcceptedMinimalMetadataAllowed() {
        val j = body("WAITING").put("state", "ACCEPTED"); j.remove("createdAt")
        assertNull(parseStatus(j, ConnectionRequestAuthScope.CAPABILITY).error)
        assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(body("ACCEPTED"), ConnectionRequestAuthScope.CAPABILITY).error)
    }
    @Test fun malformed410NeverChangesState() {
        for (j in listOf(body("WAITING"), body("FAILED").put("requestId", token(16, 8)), body("FAILED").put("expiresAt", "bad")))
            assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(j, code = 410).error)
    }
    @Test fun failedRequiresReasonAndAcceptedDeadline() {
        for (key in listOf("failureReason", "acceptedAt", "connectBy")) {
            val j = body("FAILED"); j.remove(key); assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(j, code = 410).error)
        }
    }
    @Test fun terminalExpiredCanceledDoNotRequireSetup() {
        for (s in listOf("EXPIRED", "CANCELED")) assertEquals(ConnectionRequestState.valueOf(s), parseStatus(body(s), code = 410).metadata?.state)
    }
    @Test fun connectedRequiresConnectedAtAndAssignment() {
        assertNull(parseStatus(body("CONNECTED")).error)
        for (key in listOf("connectedAt", "session")) { val j = body("CONNECTED"); j.remove(key); assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(j).error) }
    }
    @Test fun unknownFutureStateSafe() { assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(body("NEW_FUTURE")).error) }
    @Test fun claimStatusRequiresPrivilegedFieldsWithoutCreatedAt() {
        val j = body("ACCEPTED"); j.remove("createdAt")
        assertNull(parseStatus(j, ConnectionRequestAuthScope.CLAIM).error)
        j.remove("connectBy"); assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(j, ConnectionRequestAuthScope.CLAIM).error)
    }
    @Test fun wssBaseUrl() { assertEquals("https://example.com", ConnectionRequestConfig.httpBase("wss://example.com/ws")) }
    @Test fun wssBaseUrlPort() { assertEquals("https://example.com:1234", ConnectionRequestConfig.httpBase("wss://example.com:1234/ws")) }
    @Test fun wsBaseUrl() { assertEquals("http://example.com", ConnectionRequestConfig.httpBase("ws://example.com/ws")) }
    @Test fun queryFragmentRejectedSafely() { for (url in listOf("wss://example.com/ws?q=x", "wss://example.com/ws#x")) assertNull(ConnectionRequestConfig.httpBase(url)) }
    @Test fun wrongPathRejectedSafely() { for (url in listOf("wss://example.com/other", "wss://example.com/a/../ws", "wss://example.com/ws/")) assertNull(ConnectionRequestConfig.httpBase(url)) }
    @Test fun wrongSchemeRejectedSafely() { for (url in listOf("https://example.com/ws", "file:///ws", "bad")) assertNull(ConnectionRequestConfig.httpBase(url)) }
    @Test fun badConfigControllerNoCrashOrRawUrl() {
        val queue = Queue(); val url = "wrong://$initiator@host/ws"
        val controller = ConnectionRequestController(ConnectionRequestApiClient(ConnectionRequestOkHttpTransport(url)), queue)
        controller.create(MediaRole.SEND); queue.run()
        assertEquals(ConnectionRequestError.CONFIGURATION, controller.state.value.error)
        assertNull(controller.state.value.operation); assertFalse(controller.state.value.toString().contains(initiator))
    }
    @Test fun controllerWorkerOwnsNetworkCalls() {
        val f = Fixture(); f.controller.create(MediaRole.SEND); assertTrue(f.calls.isEmpty())
        f.queue.run(); assertEquals(1, f.calls.size)
    }
    @Test fun statusHttpTerminalConsistency() {
        assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(body("FAILED"), code = 200).error)
        assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(body("ACCEPTED"), code = 410).error)
    }
    @Test fun statusAssignmentMustMatchParticipantScope() {
        val initiatorBody = body("CONNECTING")
        assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(initiatorBody, ConnectionRequestAuthScope.CLAIM).error)
        initiatorBody.getJSONObject("session").put("role", "receiver").put("intent", "join")
        assertNull(parseStatus(initiatorBody, ConnectionRequestAuthScope.CLAIM).error)
        assertEquals(ConnectionRequestError.INVALID_RESPONSE, parseStatus(initiatorBody, ConnectionRequestAuthScope.INITIATOR).error)
    }
    @Test fun readinessGoneClaimProjectionDoesNotRequireInitiatorFields() {
        val json = body("FAILED"); json.remove("createdAt")
        val api = ConnectionRequestApiClient(ConnectionRequestTransport { _, _, _, _ -> ConnectionRequestWireResponse(410, json.toString()) })
        assertEquals(ConnectionRequestState.FAILED, api.ready(id, ConnectionCredential(initiator), true, ConnectionRequestAuthScope.CLAIM).metadata?.state)
    }
    @Test fun terminalCapabilityProjectionDoesNotExposeFailure() {
        val json = body("WAITING").put("state", "FAILED"); json.remove("createdAt")
        val result = parseStatus(json, ConnectionRequestAuthScope.CAPABILITY, 410)
        assertNull(result.error); assertEquals(ConnectionRequestState.FAILED, result.metadata?.state); assertNull(result.metadata?.failureReason)
    }
}
