package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect

class ConnectionRequestRecipientTest {
    @Test fun staleActivityFlowCanOpenFreshLinkAfterTerminalOwnershipIsRetired() {
        val f = Fixture(); val model = ConnectionRequestActivityViewModel()
        model.opened(f.coordinator.open(link())); f.finish(); val old = model.flow!!
        model.mediaTransferred = true; model.exiting = true
        f.serverState = "FAILED"; f.controller.refreshStatus(old); f.finish()
        f.serverState = "WAITING"
        val admitted = f.coordinator.open(link(), model.flow)
        assertNull(admitted.error); model.opened(admitted); f.finish()
        val fresh = model.flow!!; assertNotSame(old, fresh)
        assertFalse(model.mediaTransferred); assertFalse(model.exiting); assertFalse(model.receiverTransfer.transferred)
        assertEquals(RecipientScreen.WAITING, f.coordinator.projection(fresh).screen)
        assertEquals(1, f.events.count { it == "release" }); assertFalse(f.gate.legacyStartAllowed())
        val current = f.controller.state.value
        f.coordinator.finishWithoutContext(old); f.coordinator.close(old); f.controller.refreshStatus(old); f.finish()
        assertEquals(current, f.controller.state.value); assertTrue(f.coordinator.hasContext(fresh))
    }
    @Test fun missingServerRequestCanBeRetiredBeforeOpeningNextLinkWithoutClearingIdentity() {
        val f = Fixture(); f.statusError = 404; f.open(); f.finish()
        assertEquals(ConnectionRequestError.UNAVAILABLE, f.controller.state.value.error)
        f.statusError = null
        assertNull(f.open()); f.finish()
        assertEquals(RecipientScreen.WAITING, f.coordinator.state.value.screen)
        assertEquals(1, f.events.count { it == "release" })
    }
    private fun token(bytes: Int, seed: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes) { seed.toByte() })
    private val id = token(16, 1)
    private val cap = token(16, 2)
    private fun link(role: MediaRole = MediaRole.SEND) = ConnectionRequestLink.build(ConnectionRequestShareData(id, cap, role))!!
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private inner class Fixture(val role: MediaRole = MediaRole.SEND) {
        val queue = Queue(); val gate = LegacyConnectionStartGate(); val events = mutableListOf<String>()
        val calls = mutableListOf<String>(); val claims = mutableListOf<String>(); val statusAuth = mutableListOf<String?>()
        var idle = true; var serverState = "WAITING"; var reason = "establishment_timeout"
        var statusError: Int? = null; var acceptFailure = false; var withdrawFailure = false
        var statusNetworkFailure = false; var acceptConnecting = false
        var onStatus: (() -> Unit)? = null; var onAccept: (() -> Unit)? = null
        var onWithdraw: (() -> Unit)? = null
        var onArm: (() -> Unit)? = null
        var onStop: (() -> Boolean)? = null
        val api = ConnectionRequestApiClient(ConnectionRequestTransport { _, path, auth, input ->
            val action = path.substringAfterLast('/'); calls.add(action)
            when (action) {
                "connection-requests" -> ConnectionRequestWireResponse(201, snapshot(false)
                    .put("initiatorSecret", token(32, 3)).put("recipientCapability", cap).toString())
                "status" -> {
                    statusAuth.add(auth?.value); onStatus?.also { onStatus = null }?.invoke()
                    if (statusNetworkFailure) { statusNetworkFailure = false; throw IOException("synthetic") }
                    val code = statusError ?: if (serverState in listOf("FAILED", "CANCELED", "EXPIRED")) 410 else 200
                    ConnectionRequestWireResponse(code, snapshot(auth?.value != cap).toString())
                }
                "accept" -> {
                    claims.add(JSONObject(input!!).getString("claimSecret"))
                    onAccept?.also { onAccept = null }?.invoke()
                    if (acceptFailure) { acceptFailure = false; throw IOException("synthetic") }
                    serverState = if (acceptConnecting) "CONNECTING" else "ACCEPTED"
                    ConnectionRequestWireResponse(200, snapshot(true).toString())
                }
                "withdraw" -> {
                    onWithdraw?.also { onWithdraw = null }?.invoke()
                    if (withdrawFailure) { withdrawFailure = false; throw IOException("synthetic") }
                    serverState = "FAILED"; reason = "recipient_withdrew"
                    ConnectionRequestWireResponse(200, JSONObject().put("requestId", id).put("state", serverState)
                        .put("serverNow", "2026-01-01T00:00:00Z").put("failureReason", reason).toString())
                }
                else -> error("Unexpected control-plane operation")
            }
        })
        fun snapshot(privileged: Boolean): JSONObject = JSONObject().put("requestId", id).put("state", serverState)
            .put("initiatorRole", ConnectionRequestRoles.wire(role)).put("serverNow", "2026-01-01T00:00:00Z")
            .put("expiresAt", "2026-01-01T00:05:00Z").also {
                if (privileged && serverState in listOf("ACCEPTED", "CONNECTING", "CONNECTED", "FAILED")) {
                    it.put("acceptedAt", "2026-01-01T00:00:00Z").put("connectBy", "2026-01-01T00:02:00Z")
                        .put("initiatorReady", false).put("recipientReady", false)
                    if (serverState == "FAILED") it.put("failureReason", reason)
                    if (serverState in listOf("CONNECTING", "CONNECTED")) it.put("session", JSONObject().put("code", "ABC234")
                        .put("role", if (role == MediaRole.SEND) "receiver" else "sender").put("intent", "join"))
                    if (serverState == "CONNECTED") it.put("connectedAt", "2026-01-01T00:00:01Z")
                }
            }
        val controller = ConnectionRequestController(api, queue, elapsedMs = { 0 })
        val coordinator = ConnectionRequestRecipientCoordinator(controller, { idle },
            { events.add("arm"); gate.armRequestFirst(); onArm?.invoke() },
            { assertFalse(gate.legacyStartAllowed()); events.add("stop"); onStop?.invoke() ?: true },
            { events.add("release"); gate.releaseRequestFirst() })
        var flow: ConnectionRequestRecipientFlow? = null
        private val foreign = ConnectionRequestRecipientFlow()
        fun open(): RecipientEntryError? {
            val admission = coordinator.open(link(role))
            if (admission.flow != null) flow = admission.flow
            return admission.error
        }
        fun accept() = coordinator.accept(flow ?: foreign)
        fun close() = coordinator.close(flow ?: foreign)
        fun retryStatus() = coordinator.retryStatus(flow ?: foreign)
        fun hasContext() = flow?.let(coordinator::hasContext) == true
        fun finish() { queue.run(); coordinator.onControllerState(controller.state.value) }
        fun waiting() { assertNull(open()); finish() }
        fun accepted() { waiting(); accept(); finish() }
        val screen get() = coordinator.state.value.screen
    }
    @Test fun canonicalSendRoundTrip() { val p = ConnectionRequestLink.parse(link())!!; assertEquals(id, p.requestId); assertEquals(cap, p.recipientCapability); assertEquals(MediaRole.SEND, p.initiatorRole) }
    @Test fun canonicalReceiveRoundTrip() { assertEquals(MediaRole.RECEIVE, ConnectionRequestLink.parse(link(MediaRole.RECEIVE))!!.initiatorRole) }
    @Test fun parserRejectsWrongOriginAndPath() {
        for (raw in listOf(link().replace("https:", "http:"), link().replace("pairing.pradert.synology.me", "evil.example"),
            link().replace("/connect", "/pair"), link().replace("/connect", "/connect/"),
            link().replace("/connect", ":443/connect"), link().replace("https://", "https://user@"))) assertNull(ConnectionRequestLink.parse(raw))
    }
    @Test fun parserRejectsQueryAndMissingFragment() { assertNull(ConnectionRequestLink.parse(link().replace("#", "?x=1#"))); assertNull(ConnectionRequestLink.parse(link().substringBefore('#'))) }
    @Test fun parserRejectsEmptyAndEncodedPaths() { for (raw in listOf(null, "", link().replace("/connect", "/%63onnect"), link() + "&", link().replace("https://", "HTTPS://"))) assertNull(ConnectionRequestLink.parse(raw)) }
    @Test fun parserRejectsVersionMissingDuplicateAndExtraFields() {
        for (fragment in listOf("v=2&r=$id&c=$cap&i=send", "v=1&c=$cap&i=send", "v=1&r=$id&i=send", "v=1&r=$id&c=$cap",
            "v=1&r=$id&r=$id&c=$cap&i=send", "v=1&r=$id&c=$cap&i=send&x=1", "v=1&r=&c=$cap&i=send"))
            assertNull(ConnectionRequestLink.parse("https://pairing.pradert.synology.me/connect#$fragment"))
    }
    @Test fun parserRejectsNoncanonicalCredentialsAndEncoding() {
        for (bad in listOf("bad", "B".repeat(22), "$id=", "%41" + id.drop(1), "%2541" + id.drop(1), id.dropLast(1) + "+")) {
            assertNull(ConnectionRequestLink.parse(link().replace("r=$id", "r=$bad")))
            assertNull(ConnectionRequestLink.parse(link().replace("c=$cap", "c=$bad")))
        }
    }
    @Test fun parserRejectsUnknownRoleAndNoncanonicalOrder() { assertNull(ConnectionRequestLink.parse(link().replace("i=send", "i=sender"))); assertNull(ConnectionRequestLink.parse("https://pairing.pradert.synology.me/connect#r=$id&v=1&c=$cap&i=send")) }
    @Test fun parsedStringIsRedacted() { val text = ConnectionRequestLink.parse(link()).toString(); assertFalse(text.contains(id)); assertFalse(text.contains(cap)) }
    @Test fun invalidLinkHasNoSideEffects() { val f = Fixture(); assertEquals(RecipientEntryError.INVALID, f.coordinator.open("invalid").error); f.finish(); assertTrue(f.calls.isEmpty()); assertTrue(f.events.isEmpty()); assertTrue(f.controller.state.value.idle) }
    @Test fun idleOpenArmsBeforeStopBeforeOneStatusAndNeverAccepts() {
        val f = Fixture(); assertNull(f.open()); assertEquals(listOf("arm", "stop"), f.events); assertTrue(f.calls.isEmpty())
        f.finish(); assertEquals(listOf("status"), f.calls); assertEquals(listOf(cap), f.statusAuth); assertTrue(f.claims.isEmpty())
        assertEquals(RecipientScreen.WAITING, f.screen)
    }
    @Test fun receiveIntentPreviewDoesNotStartSender() { val f = Fixture(MediaRole.RECEIVE); f.waiting(); assertEquals(MediaRole.RECEIVE, f.coordinator.state.value.initiatorRole); assertEquals(listOf("status"), f.calls) }
    @Test fun busyMediaCannotArmStopPreviewOrAccept() { val f = Fixture(); f.idle = false; assertEquals(RecipientEntryError.BUSY, f.open()); f.accept(); f.finish(); assertTrue(f.events.isEmpty()); assertTrue(f.calls.isEmpty()); assertTrue(f.controller.state.value.idle) }
    @Test fun existingRecipientContextIsNotOverwritten() { val f = Fixture(); f.waiting(); val before = f.controller.state.value; assertEquals(RecipientEntryError.CONFLICT, f.open()); assertEquals(before, f.controller.state.value); assertEquals(listOf("status"), f.calls) }
    @Test fun activeInitiatorCreateConflictDoesNotArmOrStop() { val f = Fixture(); f.controller.create(MediaRole.SEND); assertEquals(RecipientEntryError.CONFLICT, f.open()); assertTrue(f.events.isEmpty()); assertEquals(1, f.queue.tasks.size) }
    @Test fun recreationObservationDoesNotIssueAnotherStatusOrAccept() { val f = Fixture(); f.waiting(); repeat(4) { f.coordinator.onControllerState(f.controller.state.value); assertTrue(f.hasContext()) }; assertEquals(listOf("status"), f.calls) }
    @Test fun statusFailureNeedsExplicitRetry() { val f = Fixture(); f.statusError = 503; f.waiting(); assertEquals(RecipientScreen.CHECK_ERROR, f.screen); f.finish(); assertEquals(1, f.calls.size); f.statusError = null; f.retryStatus(); f.finish(); assertEquals(RecipientScreen.WAITING, f.screen); assertEquals(2, f.calls.size) }
    @Test fun statusNetworkAndRateLimitFailuresDoNotAccept() {
        for (rateLimit in listOf(false, true)) {
            val f = Fixture(); if (rateLimit) f.statusError = 429 else f.statusNetworkFailure = true
            f.waiting(); assertEquals(RecipientScreen.CHECK_ERROR, f.screen); assertTrue(f.claims.isEmpty())
            f.close(); assertTrue(f.controller.state.value.idle); assertTrue(f.gate.legacyStartAllowed())
        }
    }
    @Test fun terminalStatusCannotAccept() {
        for (state in listOf("EXPIRED", "CANCELED", "FAILED")) {
            val f = Fixture(); f.serverState = state; f.waiting(); assertEquals(RecipientScreen.UNAVAILABLE, f.screen)
            f.accept(); f.finish(); assertTrue(f.claims.isEmpty()); f.close(); assertTrue(f.controller.state.value.idle); assertTrue(f.gate.legacyStartAllowed())
        }
    }
    @Test fun unavailableCannotAccept() { val f = Fixture(); f.statusError = 404; f.waiting(); assertEquals(RecipientScreen.UNAVAILABLE, f.screen); f.accept(); assertTrue(f.claims.isEmpty()) }
    @Test fun alreadyHandledCapabilityCannotAcceptOrGenerateClaim() {
        for (state in listOf("ACCEPTED", "CONNECTING", "CONNECTED")) {
            val f = Fixture(); f.serverState = state; f.waiting(); assertEquals(RecipientScreen.HANDLED, f.screen)
            f.accept(); f.finish(); assertTrue(f.claims.isEmpty()); assertEquals(listOf("status"), f.calls)
        }
    }
    @Test fun explicitAcceptOnlyAndDoubleTapSingleFlight() { val f = Fixture(); f.waiting(); f.accept(); f.accept(); assertEquals(RecipientScreen.ACCEPTING, f.screen); f.finish(); assertEquals(1, f.claims.size); assertEquals(RecipientScreen.ACCEPTED, f.screen); assertFalse(f.gate.legacyStartAllowed()); assertEquals(listOf("status", "accept"), f.calls) }
    @Test fun acceptRechecksMediaBusy() { val f = Fixture(); f.waiting(); f.idle = false; f.accept(); f.finish(); assertEquals(RecipientScreen.WAITING, f.screen); assertTrue(f.claims.isEmpty()) }
    @Test fun acceptConnectingSuccessRetainsAuthorityWithoutStartingSetup() { val f = Fixture(); f.waiting(); f.acceptConnecting = true; f.accept(); f.finish(); assertEquals(RecipientScreen.ACCEPTED, f.screen); assertEquals(ConnectionRequestState.CONNECTING, f.controller.state.value.request?.state); assertEquals(listOf("status", "accept"), f.calls); f.close(); f.finish(); assertEquals(RecipientScreen.FINISHED, f.screen) }
    @Test fun lostAcceptRetryUsesSameClaimAndCannotSilentlyClose() { val f = Fixture(); f.waiting(); f.acceptFailure = true; f.accept(); f.finish(); assertEquals(RecipientScreen.ACCEPT_ERROR, f.screen); f.close(); assertTrue(f.hasContext()); assertFalse(f.gate.legacyStartAllowed()); f.accept(); f.finish(); assertEquals(2, f.claims.size); assertEquals(f.claims[0], f.claims[1]); assertEquals(43, f.claims[0].length); assertEquals(RecipientScreen.ACCEPTED, f.screen) }
    @Test fun acceptInFlightBackCannotClear() { val f = Fixture(); f.waiting(); f.onAccept = { f.close(); assertTrue(f.hasContext()); assertFalse(f.gate.legacyStartAllowed()) }; f.accept(); f.close(); f.finish(); assertEquals(RecipientScreen.ACCEPTED, f.screen) }
    @Test fun waitingDeclineIsLocalOnly() { val f = Fixture(); f.waiting(); f.close(); assertEquals(listOf("status"), f.calls); assertEquals(RecipientScreen.FINISHED, f.screen); assertTrue(f.controller.state.value.idle); assertTrue(f.gate.legacyStartAllowed()) }
    @Test fun closeDuringStatusInvalidatesLateResponse() { val f = Fixture(); f.onStatus = { f.close() }; f.open(); f.finish(); assertEquals(RecipientScreen.FINISHED, f.screen); assertTrue(f.controller.state.value.idle); assertTrue(f.gate.legacyStartAllowed()) }
    @Test fun acceptedBackWithdrawsBeforeClearAndRelease() { val f = Fixture(); f.accepted(); f.close(); assertEquals(RecipientScreen.WITHDRAWING, f.screen); assertTrue(f.hasContext()); f.finish(); assertEquals("withdraw", f.calls.last()); assertEquals(RecipientScreen.FINISHED, f.screen); assertTrue(f.controller.state.value.idle); assertTrue(f.gate.legacyStartAllowed()) }
    @Test fun connectingBackWithdraws() { val f = Fixture(); f.accepted(); f.serverState = "CONNECTING"; f.controller.refreshStatus(); f.finish(); f.close(); f.finish(); assertEquals(RecipientScreen.FINISHED, f.screen); assertEquals("withdraw", f.calls.last()) }
    @Test fun withdrawFailureRetainsClaimAndGateForRetry() { val f = Fixture(); f.accepted(); val claim = f.claims.single(); f.withdrawFailure = true; f.close(); f.finish(); assertEquals(RecipientScreen.WITHDRAW_ERROR, f.screen); assertTrue(f.hasContext()); assertFalse(f.gate.legacyStartAllowed()); f.close(); f.close(); f.finish(); assertEquals(RecipientScreen.FINISHED, f.screen); assertEquals(1, f.claims.size); assertEquals(claim, f.claims.single()); assertEquals(2, f.calls.count { it == "withdraw" }) }
    @Test fun terminalAcceptedContextCanCloseWithoutNewWithdraw() { val f = Fixture(); f.accepted(); f.serverState = "FAILED"; f.controller.refreshStatus(); f.finish(); f.close(); assertEquals(RecipientScreen.FINISHED, f.screen); assertFalse(f.calls.contains("withdraw")) }
    @Test fun foreignRecipientTokenCannotReadOrClearCurrentContext() {
        val f = Fixture(); f.waiting(); var released = false
        val foreign = ConnectionRequestRecipientFlow()
        assertNull(f.controller.recipientAccess(foreign)); assertFalse(f.controller.clearRecipient(foreign) { released = true })
        assertFalse(released); assertTrue(f.hasContext())
    }
    @Test fun contextConflictDuringStatusDoesNotReplaceOrRepeatGet() { val f = Fixture(); f.open(); assertEquals(RecipientEntryError.CONFLICT, f.open()); f.finish(); assertEquals(listOf("status"), f.calls) }
    @Test fun publicRecipientUiHasNoIdentifiersOrCredentials() { val f = Fixture(); f.accepted(); val text = f.coordinator.state.value.toString(); for (secret in listOf(id, cap, f.claims.single(), link())) assertFalse(text.contains(secret)) }
    @Test fun previewReturnsOpaqueFlowAndTagsOnlyThatContext() {
        val f = Fixture(); val a = f.controller.preview(id, cap, MediaRole.SEND)!!
        assertEquals("ConnectionRequestRecipientFlow[redacted]", a.toString())
        assertEquals(ConnectionRequestRecipientAccess(false, false), f.controller.recipientAccess(a))
        assertNull(f.controller.recipientAccess(ConnectionRequestRecipientFlow()))
    }
    @Test fun foreignFlowStatusAcceptWithdrawAndClearAreStrictNoOps() {
        val f = Fixture(); f.waiting(); val foreign = ConnectionRequestRecipientFlow(); val before = f.controller.state.value
        f.controller.refreshStatus(foreign); f.controller.accept(foreign); f.controller.withdraw(foreign)
        assertFalse(f.controller.clearRecipient(foreign) { error("No release") })
        f.finish(); assertEquals(before, f.controller.state.value); assertEquals(listOf("status"), f.calls); assertTrue(f.claims.isEmpty())
    }
    @Test fun ownFlowClearWorksAndCannotReleaseTwice() {
        val f = Fixture(); f.waiting(); val a = f.flow!!; var releases = 0
        assertTrue(f.controller.clearRecipient(a) { releases++ })
        assertFalse(f.controller.clearRecipient(a) { releases++ }); assertEquals(1, releases); assertTrue(f.controller.state.value.idle)
    }
    private fun replaceRecipient(f: Fixture): ConnectionRequestRecipientFlow {
        val a = f.flow!!; assertTrue(f.controller.clearRecipient(a) {})
        f.gate.armRequestFirst()
        return f.controller.preview(id, cap, f.role)!!
    }
    @Test fun staleControllerFlowCannotControlLaterWaitingFlow() {
        val f = Fixture(); f.waiting(); val a = f.flow!!; val b = replaceRecipient(f)
        val before = f.controller.state.value
        f.controller.refreshStatus(a); f.controller.accept(a); f.controller.withdraw(a)
        assertFalse(f.controller.clearRecipient(a) { f.gate.releaseRequestFirst() })
        f.finish(); assertEquals(before, f.controller.state.value); assertNotNull(f.controller.recipientAccess(b))
        assertFalse(f.gate.legacyStartAllowed()); assertTrue(f.claims.isEmpty())
    }
    @Test fun staleFlowCannotWithdrawCurrentWinningRecipient() {
        val f = Fixture(); f.waiting(); val a = f.flow!!; val b = replaceRecipient(f)
        f.controller.accept(b); f.finish(); val before = f.controller.state.value; val calls = f.calls.toList()
        f.controller.withdraw(a); f.coordinator.close(a); f.controller.accept(a); f.finish()
        assertEquals(before, f.controller.state.value); assertEquals(calls, f.calls); assertFalse(f.gate.legacyStartAllowed())
    }
    @Test fun staleCoordinatorCommandsNeverControlOrRenderNewFlow() {
        val f = Fixture(); f.waiting(); val a = f.flow!!; f.close()
        assertNull(f.open()); val b = f.flow!!; assertNotSame(a, b); f.finish()
        val before = f.controller.state.value; val calls = f.calls.toList()
        f.coordinator.accept(a); f.coordinator.retryStatus(a); f.coordinator.close(a); f.finish()
        assertEquals(before, f.controller.state.value); assertEquals(calls, f.calls)
        assertFalse(f.coordinator.hasContext(a)); assertTrue(f.coordinator.hasContext(b))
        assertTrue(f.coordinator.projection(a).screen in setOf(RecipientScreen.STALE, RecipientScreen.FINISHED))
        assertNull(f.coordinator.projection(a).initiatorRole); assertEquals(RecipientScreen.WAITING, f.coordinator.projection(b).screen)
        assertFalse(f.gate.legacyStartAllowed())
    }
    @Test fun scopedObservationInvalidatesAWhenBHasIdenticalPublicSnapshot() = runBlocking {
        val f = Fixture(); f.waiting(); val a = f.flow!!
        val events = Channel<RecipientUi>(Channel.UNLIMITED)
        val job = launch { f.coordinator.observe(a).collect { events.send(it) } }
        assertEquals(RecipientScreen.WAITING, withTimeout(2000) { events.receive() }.screen)
        val old = f.controller.state.value
        f.controller.clearRecipient(a) {}; f.open(); f.finish()
        assertEquals(old, f.controller.state.value) // Deliberately equal metadata after coalesced transitions.
        assertEquals(RecipientScreen.STALE, withTimeout(2000) { events.receive() }.screen)
        job.cancelAndJoin(); events.close(); Unit
    }
    private fun retained(store: ViewModelStore): ConnectionRequestActivityViewModel = ViewModelProvider(store,
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.getDeclaredConstructor().newInstance()
        })[ConnectionRequestActivityViewModel::class.java]
    @Test fun rotationRetainsExactFlowInActivityViewModelWithoutAnotherGet() {
        val f = Fixture(); val store = ViewModelStore(); val model = retained(store)
        model.opened(f.coordinator.open(link())); f.finish(); val a = model.flow!!
        val rotated = retained(store); assertSame(model, rotated); assertSame(a, rotated.flow)
        assertEquals(RecipientScreen.WAITING, f.coordinator.projection(rotated.flow).screen)
        assertEquals(listOf("status"), f.calls); store.clear()
    }
    @Test fun freshViewModelCannotAdoptExistingContext() {
        val f = Fixture(); f.waiting(); val model = ConnectionRequestActivityViewModel()
        model.opened(f.coordinator.open(link()))
        assertNull(model.flow); assertEquals(RecipientScreen.STALE, f.coordinator.projection(model.flow).screen)
        assertFalse(f.gate.legacyStartAllowed()); assertTrue(f.hasContext())
    }
    @Test fun staleViewModelNeverAdoptsLaterFlowViaNewIntent() {
        val f = Fixture(); val model = ConnectionRequestActivityViewModel()
        model.opened(f.coordinator.open(link())); f.finish(); val a = model.flow!!
        f.coordinator.close(a); f.open(); f.finish(); val b = f.flow!!
        val admission = f.coordinator.open(link(), model.flow)
        assertEquals(RecipientEntryError.STALE, admission.error); assertNull(admission.flow)
        model.opened(admission); assertSame(a, model.flow); assertTrue(f.coordinator.hasContext(b))
    }
    @Test fun twoActivitiesDoNotShareOwnership() {
        val f = Fixture(); val a = ConnectionRequestActivityViewModel(); val b = ConnectionRequestActivityViewModel()
        a.opened(f.coordinator.open(link())); f.finish()
        val admission = f.coordinator.open(link()); b.opened(admission)
        assertEquals(RecipientEntryError.CONFLICT, admission.error); assertNull(b.flow); assertNotNull(a.flow)
        assertEquals(RecipientScreen.STALE, f.coordinator.projection(b.flow).screen)
    }
    @Test fun uncertainAcceptRotationKeepsFlowClaimAndBackProtection() {
        val f = Fixture(); val store = ViewModelStore(); val model = retained(store)
        model.opened(f.coordinator.open(link())); f.finish(); val a = model.flow!!
        f.acceptFailure = true; f.coordinator.accept(a); f.finish()
        val rotated = retained(store); assertSame(a, rotated.flow)
        assertEquals(RecipientScreen.ACCEPT_ERROR, f.coordinator.projection(rotated.flow).screen)
        f.coordinator.close(rotated.flow!!); assertNotNull(f.controller.recipientAccess(a)); assertFalse(f.gate.legacyStartAllowed())
        f.coordinator.accept(rotated.flow!!); f.finish(); assertEquals(2, f.claims.size); assertEquals(f.claims[0], f.claims[1])
        store.clear()
    }
    @Test fun noTokenProcessRestoreDoesNotAdoptOrRequestAnything() {
        val f = Fixture(); val model = ConnectionRequestActivityViewModel()
        assertNull(model.flow); assertEquals(RecipientScreen.STALE, f.coordinator.projection(model.flow).screen)
        assertEquals(RecipientEntryError.INVALID, f.coordinator.open(null).error)
        assertTrue(f.calls.isEmpty()); assertTrue(f.events.isEmpty())
    }
    @Test fun idleEarlyStopFailureMayReleaseGate() {
        val f = Fixture(); f.onStop = { false }
        assertEquals(RecipientEntryError.BUSY, f.open())
        assertEquals(listOf("arm", "stop", "release"), f.events); assertTrue(f.gate.legacyStartAllowed()); assertTrue(f.calls.isEmpty())
    }
    @Test fun workerContextAppearingBeforeFailedStopPreventsRollback() {
        val f = Fixture(); f.onArm = { f.controller.create(MediaRole.SEND); f.queue.run() }; f.onStop = { false }
        assertEquals(RecipientEntryError.BUSY, f.open())
        assertEquals(ConnectionRequestSide.INITIATOR, f.controller.state.value.side)
        assertFalse(f.gate.legacyStartAllowed()); assertFalse(f.events.contains("release")); assertNull(f.flow)
    }
    @Test fun pendingCreateBeforeFailedStopPreventsRollback() {
        val f = Fixture(); f.onArm = { f.controller.create(MediaRole.SEND) }; f.onStop = { false }
        assertEquals(RecipientEntryError.BUSY, f.open()); assertEquals(ConnectionRequestOperation.CREATE, f.controller.state.value.operation)
        assertFalse(f.gate.legacyStartAllowed()); f.queue.run(); assertEquals(ConnectionRequestSide.INITIATOR, f.controller.state.value.side)
    }
    @Test fun previewRejectAfterWorkerContextAppearsDoesNotAcquireFlowOrRelease() {
        val f = Fixture(); f.onStop = { f.controller.create(MediaRole.SEND); f.queue.run(); true }
        assertEquals(RecipientEntryError.CONFLICT, f.open()); assertNull(f.flow)
        assertEquals(ConnectionRequestSide.INITIATOR, f.controller.state.value.side); assertFalse(f.gate.legacyStartAllowed())
        assertFalse(f.events.contains("release")); assertEquals(listOf("connection-requests"), f.calls)
    }
    @Test fun idleRollbackCheckAndCallbackAreAtomicAgainstConcurrentCreate() {
        val f = Fixture(); val attempted = CountDownLatch(1); val done = CountDownLatch(1)
        lateinit var thread: Thread
        assertTrue(f.controller.ifNoRequestOwnership {
            thread = Thread { attempted.countDown(); f.controller.create(MediaRole.SEND); done.countDown() }.also { it.start() }
            assertTrue(attempted.await(2, TimeUnit.SECONDS)); assertFalse(done.await(50, TimeUnit.MILLISECONDS))
        })
        assertTrue(done.await(2, TimeUnit.SECONDS)); thread.join(2000)
        assertFalse(f.controller.ifNoRequestOwnership { error("Pending operation owns controller") })
    }
    @Test fun scopedOperationsCannotChangeOtherFlowsActiveOperationOrError() {
        val f = Fixture(); f.waiting(); val a = f.flow!!; val b = replaceRecipient(f)
        f.controller.accept(b); val before = f.controller.state.value
        f.controller.accept(a); f.controller.withdraw(a); f.controller.refreshStatus(a)
        assertEquals(before, f.controller.state.value); f.finish(); assertEquals(1, f.claims.size)
    }
    @Test fun staleWithdrawSuccessCannotClearReplacementContext() {
        val f = Fixture(); f.accepted(); val a = f.flow!!
        // Complete B inside A's transport, before A's late successful withdraw response.
        f.onWithdraw = {
            f.controller.clearRecipient(a) {}
            f.flow = f.controller.preview(id, cap, MediaRole.SEND)!!
        }
        f.close(); f.finish()
        assertNotNull(f.controller.recipientAccess(f.flow!!)); assertFalse(f.gate.legacyStartAllowed())
        assertEquals(RecipientScreen.STALE, f.coordinator.projection(a).screen)
    }
    @Test fun flowHandleIsNeverSavedOrPublishedByActivity() {
        val activity = source("ConnectionRequestActivity"); val model = source("ConnectionRequestActivityViewModel")
        assertFalse(activity.contains("putBoolean")); assertFalse(activity.contains("onSaveInstanceState")); assertFalse(activity.contains("recipientOwner"))
        for (text in listOf(activity, model)) for (forbidden in listOf("SavedStateHandle", "rememberSaveable", "SharedPreferences", "DataStore")) assertFalse(text.contains(forbidden))
        // B4E permits only an opaque one-shot media handoff in navigation, never the flow/bearer.
        assertEquals(0, Regex("\\.putExtra\\(").findAll(activity).count())
        assertTrue(activity.contains("navigateRequestReceiver(this@ConnectionRequestActivity, id)"))
        val navigation = java.io.File("src/main/java/com/pradertamas/uicapturetest/RequestReceiverNavigation.kt").readText()
        assertEquals(1, Regex("\\.putExtra\\(").findAll(navigation).count())
        assertTrue(navigation.contains("putExtra(ReceiverActivity.REQUEST_MEDIA_HANDOFF, id.value)"))
        assertTrue(activity.contains("coordinator.observe(flow)")); assertTrue(activity.contains("coordinator.close(flow)"))
    }
    private fun source(name: String) = listOf(File("src/main/java/com/pradertamas/uicapturetest/$name.kt"), File("app/src/main/java/com/pradertamas/uicapturetest/$name.kt")).first { it.isFile }.readText()
    @Test fun actualActivityClearsBothIncomingIntentsAndDoesNotPersistBearer() {
        val text = source("ConnectionRequestActivity")
        assertTrue(text.indexOf("value.data = null") < text.indexOf("coordinator.open(raw, model.flow)"))
        assertTrue(text.contains("intent?.data = null")); assertTrue(text.contains("setIntent(value)")); assertTrue(text.contains("override fun onNewIntent"))
        assertTrue(text.contains("ViewModelProvider(this)")); assertTrue(text.contains("if (model.flow == null) incoming(intent)"))
        assertFalse(text.contains("recipientOwner")); assertFalse(text.contains("onSaveInstanceState"))
        assertFalse(text.contains("putString")); assertFalse(text.contains("Log.")); assertFalse(text.contains("println"))
        assertTrue(text.contains("setFinishOnTouchOutside(false)"))
    }
    @Test fun actualStopHookUsesCurrentOwnerWithoutRoleSwitch() {
        val text = source("AppMediaRoles").substringAfter("fun stopIdleLegacyForRequestFirst()").substringBefore("fun receiverStartFailed")
        assertTrue(text.contains("stopIdleLegacyOwner(role, ::pairingAllowed"))
        assertTrue(text.contains("controller.hide(); PairingHost.controller.close()")); assertTrue(text.contains("receiver?.stop?.invoke()"))
        assertFalse(text.contains("switchRole")); assertTrue(text.contains("Looper.getMainLooper()"))
    }
    @Test fun idleSenderStopDispatchesOnlySender() {
        var sender = 0; var receiver = 0
        assertTrue(stopIdleLegacyOwner(MediaRole.SEND, { true }, { sender++ }, { receiver++ }))
        assertEquals(1, sender); assertEquals(0, receiver)
    }
    @Test fun idleReceiverStopDispatchesOnlyRegisteredReceiver() {
        var sender = 0; var receiver = 0
        assertTrue(stopIdleLegacyOwner(MediaRole.RECEIVE, { true }, { sender++ }, { receiver++ }))
        assertEquals(0, sender); assertEquals(1, receiver)
    }
    @Test fun busyPolicyNeverDispatchesEitherStop() {
        for (role in MediaRole.entries) assertFalse(stopIdleLegacyOwner(role, { false }, { error("No sender stop") }, { error("No receiver stop") }))
    }
    @Test fun manifestHasSeparateExactVerifiedHttpsConnectActivity() {
        val file = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }
        val text = file.readText(); val activity = text.substringAfter("android:name=\".ConnectionRequestActivity\"").substringBefore("</activity>")
        for (required in listOf("android:exported=\"true\"", "android:launchMode=\"singleTop\"", "android:autoVerify=\"true\"", "android.intent.action.VIEW", "android.intent.category.BROWSABLE", "android.intent.category.DEFAULT", "android:scheme=\"https\"", "android:path=\"/connect\"")) assertTrue(activity.contains(required))
        assertFalse(activity.contains("pathPrefix")); assertFalse(text.contains("android:name=\".PairingInviteActivity\""))
    }
    @Test fun recipientFlowHasNoMediaSignalingOrPolling() {
        val text = source("ConnectionRequestActivity") + source("ConnectionRequestRecipientCoordinator")
        for (forbidden in listOf("switchRole(", "setReady(", "createSession(", "request-auth", "newWebSocket", "claimConsent(", "postDelayed", "Timer(", "while (", "delay(", "WebRtcService")) assertFalse(text.contains(forbidden))
    }
}
