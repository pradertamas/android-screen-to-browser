package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executor

class ConnectionRequestRecipientTest {
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
        val api = ConnectionRequestApiClient(ConnectionRequestTransport { _, path, auth, input ->
            val action = path.substringAfterLast('/'); calls.add(action)
            when (action) {
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
            { events.add("arm"); gate.armRequestFirst() },
            { assertFalse(gate.legacyStartAllowed()); events.add("stop"); true },
            { events.add("release"); gate.releaseRequestFirst() })
        fun open() = coordinator.open(link(role))
        fun finish() { queue.run(); coordinator.onControllerState(controller.state.value) }
        fun waiting() { assertNull(open()); finish() }
        fun accepted() { waiting(); coordinator.accept(); finish() }
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
    @Test fun invalidLinkHasNoSideEffects() { val f = Fixture(); assertEquals(RecipientEntryError.INVALID, f.coordinator.open("invalid")); f.finish(); assertTrue(f.calls.isEmpty()); assertTrue(f.events.isEmpty()); assertTrue(f.controller.state.value.idle) }
    @Test fun idleOpenArmsBeforeStopBeforeOneStatusAndNeverAccepts() {
        val f = Fixture(); assertNull(f.open()); assertEquals(listOf("arm", "stop"), f.events); assertTrue(f.calls.isEmpty())
        f.finish(); assertEquals(listOf("status"), f.calls); assertEquals(listOf(cap), f.statusAuth); assertTrue(f.claims.isEmpty())
        assertEquals(RecipientScreen.WAITING, f.screen)
    }
    @Test fun receiveIntentPreviewDoesNotStartSender() { val f = Fixture(MediaRole.RECEIVE); f.waiting(); assertEquals(MediaRole.RECEIVE, f.coordinator.state.value.initiatorRole); assertEquals(listOf("status"), f.calls) }
    @Test fun busyMediaCannotArmStopPreviewOrAccept() { val f = Fixture(); f.idle = false; assertEquals(RecipientEntryError.BUSY, f.open()); f.coordinator.accept(); f.finish(); assertTrue(f.events.isEmpty()); assertTrue(f.calls.isEmpty()); assertTrue(f.controller.state.value.idle) }
    @Test fun existingRecipientContextIsNotOverwritten() { val f = Fixture(); f.waiting(); val before = f.controller.state.value; assertEquals(RecipientEntryError.CONFLICT, f.open()); assertEquals(before, f.controller.state.value); assertEquals(listOf("status"), f.calls) }
    @Test fun activeInitiatorCreateConflictDoesNotArmOrStop() { val f = Fixture(); f.controller.create(MediaRole.SEND); assertEquals(RecipientEntryError.CONFLICT, f.open()); assertTrue(f.events.isEmpty()); assertEquals(1, f.queue.tasks.size) }
    @Test fun recreationObservationDoesNotIssueAnotherStatusOrAccept() { val f = Fixture(); f.waiting(); repeat(4) { f.coordinator.onControllerState(f.controller.state.value); assertTrue(f.coordinator.hasContext()) }; assertEquals(listOf("status"), f.calls) }
    @Test fun statusFailureNeedsExplicitRetry() { val f = Fixture(); f.statusError = 503; f.waiting(); assertEquals(RecipientScreen.CHECK_ERROR, f.screen); f.finish(); assertEquals(1, f.calls.size); f.statusError = null; f.coordinator.retryStatus(); f.finish(); assertEquals(RecipientScreen.WAITING, f.screen); assertEquals(2, f.calls.size) }
    @Test fun statusNetworkAndRateLimitFailuresDoNotAccept() {
        for (rateLimit in listOf(false, true)) {
            val f = Fixture(); if (rateLimit) f.statusError = 429 else f.statusNetworkFailure = true
            f.waiting(); assertEquals(RecipientScreen.CHECK_ERROR, f.screen); assertTrue(f.claims.isEmpty())
            f.coordinator.close(); assertTrue(f.controller.state.value.idle); assertTrue(f.gate.legacyStartAllowed())
        }
    }
    @Test fun terminalStatusCannotAccept() {
        for (state in listOf("EXPIRED", "CANCELED", "FAILED")) {
            val f = Fixture(); f.serverState = state; f.waiting(); assertEquals(RecipientScreen.UNAVAILABLE, f.screen)
            f.coordinator.accept(); f.finish(); assertTrue(f.claims.isEmpty()); f.coordinator.close(); assertTrue(f.controller.state.value.idle); assertTrue(f.gate.legacyStartAllowed())
        }
    }
    @Test fun unavailableCannotAccept() { val f = Fixture(); f.statusError = 404; f.waiting(); assertEquals(RecipientScreen.UNAVAILABLE, f.screen); f.coordinator.accept(); assertTrue(f.claims.isEmpty()) }
    @Test fun alreadyHandledCapabilityCannotAcceptOrGenerateClaim() {
        for (state in listOf("ACCEPTED", "CONNECTING", "CONNECTED")) {
            val f = Fixture(); f.serverState = state; f.waiting(); assertEquals(RecipientScreen.HANDLED, f.screen)
            f.coordinator.accept(); f.finish(); assertTrue(f.claims.isEmpty()); assertEquals(listOf("status"), f.calls)
        }
    }
    @Test fun explicitAcceptOnlyAndDoubleTapSingleFlight() { val f = Fixture(); f.waiting(); f.coordinator.accept(); f.coordinator.accept(); assertEquals(RecipientScreen.ACCEPTING, f.screen); f.finish(); assertEquals(1, f.claims.size); assertEquals(RecipientScreen.ACCEPTED, f.screen); assertFalse(f.gate.legacyStartAllowed()); assertEquals(listOf("status", "accept"), f.calls) }
    @Test fun acceptRechecksMediaBusy() { val f = Fixture(); f.waiting(); f.idle = false; f.coordinator.accept(); f.finish(); assertEquals(RecipientScreen.WAITING, f.screen); assertTrue(f.claims.isEmpty()) }
    @Test fun acceptConnectingSuccessRetainsAuthorityWithoutStartingSetup() { val f = Fixture(); f.waiting(); f.acceptConnecting = true; f.coordinator.accept(); f.finish(); assertEquals(RecipientScreen.ACCEPTED, f.screen); assertEquals(ConnectionRequestState.CONNECTING, f.controller.state.value.request?.state); assertEquals(listOf("status", "accept"), f.calls); f.coordinator.close(); f.finish(); assertEquals(RecipientScreen.FINISHED, f.screen) }
    @Test fun lostAcceptRetryUsesSameClaimAndCannotSilentlyClose() { val f = Fixture(); f.waiting(); f.acceptFailure = true; f.coordinator.accept(); f.finish(); assertEquals(RecipientScreen.ACCEPT_ERROR, f.screen); f.coordinator.close(); assertTrue(f.coordinator.hasContext()); assertFalse(f.gate.legacyStartAllowed()); f.coordinator.accept(); f.finish(); assertEquals(2, f.claims.size); assertEquals(f.claims[0], f.claims[1]); assertEquals(43, f.claims[0].length); assertEquals(RecipientScreen.ACCEPTED, f.screen) }
    @Test fun acceptInFlightBackCannotClear() { val f = Fixture(); f.waiting(); f.onAccept = { f.coordinator.close(); assertTrue(f.coordinator.hasContext()); assertFalse(f.gate.legacyStartAllowed()) }; f.coordinator.accept(); f.coordinator.close(); f.finish(); assertEquals(RecipientScreen.ACCEPTED, f.screen) }
    @Test fun waitingDeclineIsLocalOnly() { val f = Fixture(); f.waiting(); f.coordinator.close(); assertEquals(listOf("status"), f.calls); assertEquals(RecipientScreen.FINISHED, f.screen); assertTrue(f.controller.state.value.idle); assertTrue(f.gate.legacyStartAllowed()) }
    @Test fun closeDuringStatusInvalidatesLateResponse() { val f = Fixture(); f.onStatus = { f.coordinator.close() }; f.open(); f.finish(); assertEquals(RecipientScreen.FINISHED, f.screen); assertTrue(f.controller.state.value.idle); assertTrue(f.gate.legacyStartAllowed()) }
    @Test fun acceptedBackWithdrawsBeforeClearAndRelease() { val f = Fixture(); f.accepted(); f.coordinator.close(); assertEquals(RecipientScreen.WITHDRAWING, f.screen); assertTrue(f.coordinator.hasContext()); f.finish(); assertEquals("withdraw", f.calls.last()); assertEquals(RecipientScreen.FINISHED, f.screen); assertTrue(f.controller.state.value.idle); assertTrue(f.gate.legacyStartAllowed()) }
    @Test fun connectingBackWithdraws() { val f = Fixture(); f.accepted(); f.serverState = "CONNECTING"; f.controller.refreshStatus(); f.finish(); f.coordinator.close(); f.finish(); assertEquals(RecipientScreen.FINISHED, f.screen); assertEquals("withdraw", f.calls.last()) }
    @Test fun withdrawFailureRetainsClaimAndGateForRetry() { val f = Fixture(); f.accepted(); val claim = f.claims.single(); f.withdrawFailure = true; f.coordinator.close(); f.finish(); assertEquals(RecipientScreen.WITHDRAW_ERROR, f.screen); assertTrue(f.coordinator.hasContext()); assertFalse(f.gate.legacyStartAllowed()); f.coordinator.close(); f.coordinator.close(); f.finish(); assertEquals(RecipientScreen.FINISHED, f.screen); assertEquals(1, f.claims.size); assertEquals(claim, f.claims.single()); assertEquals(2, f.calls.count { it == "withdraw" }) }
    @Test fun terminalAcceptedContextCanCloseWithoutNewWithdraw() { val f = Fixture(); f.accepted(); f.serverState = "FAILED"; f.controller.refreshStatus(); f.finish(); f.coordinator.close(); assertEquals(RecipientScreen.FINISHED, f.screen); assertFalse(f.calls.contains("withdraw")) }
    @Test fun foreignRecipientTokenCannotReadOrClearCurrentContext() {
        val f = Fixture(); f.waiting(); var released = false
        val foreign = ConnectionRequestRecipientContext()
        assertNull(f.controller.recipientAccess(foreign)); assertFalse(f.controller.clearRecipient(foreign) { released = true })
        assertFalse(released); assertTrue(f.coordinator.hasContext())
    }
    @Test fun contextConflictDuringStatusDoesNotReplaceOrRepeatGet() { val f = Fixture(); f.open(); assertEquals(RecipientEntryError.CONFLICT, f.open()); f.finish(); assertEquals(listOf("status"), f.calls) }
    @Test fun publicRecipientUiHasNoIdentifiersOrCredentials() { val f = Fixture(); f.accepted(); val text = f.coordinator.state.value.toString(); for (secret in listOf(id, cap, f.claims.single(), link())) assertFalse(text.contains(secret)) }
    private fun source(name: String) = listOf(File("src/main/java/com/pradertamas/uicapturetest/$name.kt"), File("app/src/main/java/com/pradertamas/uicapturetest/$name.kt")).first { it.isFile }.readText()
    @Test fun actualActivityClearsBothIncomingIntentsAndDoesNotPersistBearer() {
        val text = source("ConnectionRequestActivity")
        assertTrue(text.indexOf("value.data = null") < text.indexOf("coordinator.open(raw)"))
        assertTrue(text.contains("intent?.data = null")); assertTrue(text.contains("setIntent(value)")); assertTrue(text.contains("override fun onNewIntent"))
        assertTrue(text.contains("savedInstanceState?.getBoolean(\"recipientOwner\")")); assertTrue(text.contains("if (!owns) incoming(intent)"))
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
        assertFalse(activity.contains("pathPrefix")); assertTrue(text.contains("android:name=\".PairingInviteActivity\""))
    }
    @Test fun recipientFlowHasNoMediaSignalingOrPolling() {
        val text = source("ConnectionRequestActivity") + source("ConnectionRequestRecipientCoordinator")
        for (forbidden in listOf("switchRole(", "setReady(", "createSession(", "request-auth", "newWebSocket", "claimConsent(", "postDelayed", "Timer(", "while (", "delay(", "WebRtcService")) assertFalse(text.contains(forbidden))
    }
}
