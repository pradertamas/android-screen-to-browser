package com.pradertamas.uicapturetest

import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class ConnectionRequestSetupTest {
    private fun token(seed: Int, bytes: Int = 16) = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(bytes) { seed.toByte() })
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { var count = 0; while (tasks.isNotEmpty()) { check(++count < 100); tasks.removeFirst().run() } }
    }
    private class Clock : ConnectionRequestPollScheduler {
        var now = 0L
        class Task(val due: Long, val action: () -> Unit, var canceled: Boolean = false, var fired: Boolean = false)
        val tasks = mutableListOf<Task>()
        val active get() = tasks.filter { !it.canceled && !it.fired }
        override fun later(delayMillis: Long, action: () -> Unit): () -> Unit {
            val task = Task(if (now > Long.MAX_VALUE - delayMillis) Long.MAX_VALUE else now + delayMillis, action)
            tasks.add(task); return { task.canceled = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            var count = 0
            while (true) {
                val next = active.filter { it.due <= end }.minByOrNull { it.due } ?: break
                check(++count < 100); now = next.due; next.fired = true; next.action()
            }
            now = end
        }
    }
    private inner class Fixture {
        val clock = Clock(); val worker = Queue(); val main = Queue()
        val owner = ConnectionRequestSetupOwner()
        var role = MediaRole.SEND; var initiatorRole = MediaRole.SEND
        var allowed = true; var gate = true; var switchOk = true; var switches = 0
        var creates = 0; var readyCalls = 0; var statusCalls = 0; var sessions = 0; var withdraws = 0
        var server = ConnectionRequestState.WAITING
        var ir = false; var rr = false
        var readyError: ConnectionRequestError? = null; var sessionError: ConnectionRequestError? = null
        var statusError: ConnectionRequestError? = null; var retry: Long? = null
        var withdrawError: ConnectionRequestError? = null
        val readyTimes = mutableListOf<Long>(); val statusTimes = mutableListOf<Long>()
        var lostSession = false; var malformed = false; var onSession: (() -> Unit)? = null
        val scopes = mutableListOf<ConnectionRequestAuthScope>(); val auths = mutableListOf<ConnectionCredential>()
        val instant = Instant.parse("2026-01-01T00:00:00Z")
        fun metadata(id: String, recipient: Boolean = false) = ConnectionRequestMetadata(id, server, instant,
            initiatorRole, instant, instant.plusSeconds(300), acceptedAt = instant, connectBy = instant.plusSeconds(120),
            initiatorReady = ir, recipientReady = rr,
            assignment = if (server == ConnectionRequestState.CONNECTING || server == ConnectionRequestState.CONNECTED)
                ConnectionAssignment("ABC234", if (recipient) ConnectionRequestSetupCoordinator.opposite(initiatorRole) else initiatorRole,
                    if (recipient) PairingIntent.JOIN else PairingIntent.CREATE) else null)
        val api = object : ConnectionRequestApi {
            override fun create(role: MediaRole): ConnectionRequestResult {
                creates++; initiatorRole = role; server = ConnectionRequestState.WAITING
                val m = metadata(token(creates))
                return ConnectionRequestResult(m, httpStatus = 201, created = ConnectionRequestCreated(m,
                    ConnectionCredential(token(30, 32)), ConnectionCredential(token(31))))
            }
            override fun status(id: String, auth: ConnectionCredential, scope: ConnectionRequestAuthScope): ConnectionRequestResult {
                statusCalls++; scopes.add(scope); auths.add(auth)
                statusTimes.add(clock.now)
                return if (statusError != null) ConnectionRequestResult(error = statusError, retryAfterSeconds = retry)
                    else ConnectionRequestResult(metadata(id, scope == ConnectionRequestAuthScope.CLAIM))
            }
            override fun accept(id: String, capability: ConnectionCredential, claim: ConnectionCredential): ConnectionRequestResult {
                server = ConnectionRequestState.ACCEPTED
                return ConnectionRequestResult(metadata(id, true), httpStatus = 200)
            }
            override fun ready(id: String, auth: ConnectionCredential, ready: Boolean, scope: ConnectionRequestAuthScope): ConnectionRequestResult {
                assertTrue(ready); readyCalls++; auths.add(auth); scopes.add(scope)
                readyTimes.add(clock.now)
                if (readyError != null) return ConnectionRequestResult(error = readyError, retryAfterSeconds = retry)
                return ConnectionRequestResult(ConnectionRequestMetadata(id, ConnectionRequestState.ACCEPTED, instant,
                    ready = true, readyUntil = instant.plusSeconds(10)))
            }
            override fun session(id: String, auth: ConnectionCredential): ConnectionRequestResult {
                sessions++; auths.add(auth)
                onSession?.also { onSession = null }?.invoke()
                if (lostSession) { server = ConnectionRequestState.CONNECTING; return ConnectionRequestResult(error = ConnectionRequestError.NETWORK) }
                if (sessionError != null) return ConnectionRequestResult(error = sessionError, retryAfterSeconds = retry)
                server = ConnectionRequestState.CONNECTING
                return if (malformed) ConnectionRequestResult(metadata(id).copy(assignment = ConnectionAssignment("ABC234", MediaRole.RECEIVE, PairingIntent.JOIN)))
                    else ConnectionRequestResult(metadata(id))
            }
            override fun cancel(id: String, auth: ConnectionCredential) = ConnectionRequestResult()
            override fun withdraw(id: String, auth: ConnectionCredential): ConnectionRequestResult {
                withdraws++
                if (withdrawError != null) return ConnectionRequestResult(error = withdrawError)
                server = ConnectionRequestState.FAILED
                return ConnectionRequestResult(metadata(id, true).copy(failureReason = "recipient_withdrew"))
            }
        }
        val controller = ConnectionRequestController(api, worker, { clock.now }, { ConnectionCredential(token(40, 32)) })
        val setup = ConnectionRequestSetupCoordinator(controller, clock, { clock.now }, { role }, { allowed }, { gate },
            { action -> main.execute { action() } }, { desired -> switches++; if (switchOk) role = desired; switchOk })
        fun drain() { worker.run(); setup.changed() }
        fun settle() { repeat(4) { main.run(); drain() } }
        fun initiator(media: MediaRole = MediaRole.SEND): ConnectionRequestCreateAttempt {
            val flow = controller.create(media)!!; worker.run(); server = ConnectionRequestState.ACCEPTED
            controller.refreshInitiatorStatus(media, flow); worker.run(); return flow
        }
        fun recipient(media: MediaRole = MediaRole.SEND): ConnectionRequestRecipientFlow {
            initiatorRole = media; server = ConnectionRequestState.WAITING
            val flow = controller.preview(token(1), token(2), media)!!
            controller.refreshStatus(flow); worker.run(); controller.accept(flow); worker.run(); return flow
        }
        fun foregroundInitiator(media: MediaRole = role) { setup.foreground(owner, media); settle() }
        fun foregroundRecipient(flow: ConnectionRequestRecipientFlow) { setup.foreground(owner, flow); settle() }
        fun advance(ms: Long) { clock.advance(ms); settle() }
        fun status() { controller.refreshStatus(); settle() }
    }

    @Test fun recipientStatusAndReadinessNeitherStarveAcrossTwentySeconds() {
        val f = Fixture(); f.role = MediaRole.RECEIVE; val flow = f.recipient()
        f.statusTimes.clear(); f.foregroundRecipient(flow)
        repeat(20) { f.advance(1000) }
        assertEquals(listOf(0L, 5000L, 10000L, 15000L, 20000L), f.readyTimes)
        assertEquals((0L..20000L step 2000).toList(), f.statusTimes)
        assertEquals(1, f.clock.active.size)
    }
    @Test fun withdrawNetworkFailureBlocksStaleRenewalAndQueuedRoleCommand() {
        val f = Fixture(); f.role = MediaRole.RECEIVE; val flow = f.recipient(); f.foregroundRecipient(flow)
        f.advance(4000); val staleReady = f.clock.active.single()
        assertEquals(5000L, staleReady.due)
        f.role = MediaRole.SEND; f.setup.changed(); assertEquals(1, f.main.tasks.size)
        // Same production beforeWithdraw boundary, before the mutation is admitted.
        f.setup.suspendRecipient(flow); f.withdrawError = ConnectionRequestError.NETWORK
        val claim = f.auths.last()
        f.controller.withdraw(flow); f.worker.run(); staleReady.action(); f.settle()
        val ready = f.readyCalls; val status = f.statusCalls; val switches = f.switches
        f.advance(20000)
        assertEquals(ready, f.readyCalls); assertEquals(status, f.statusCalls); assertEquals(switches, f.switches)
        assertTrue(f.clock.active.isEmpty()); assertTrue(f.gate)
        assertTrue(f.controller.recipientAccess(flow)!!.won); assertTrue(f.controller.recipientAccess(flow)!!.acceptAttempted)
        assertEquals(ConnectionRequestState.ACCEPTED, f.controller.state.value.request?.state)
        f.withdrawError = null; f.controller.withdraw(flow); f.worker.run()
        assertEquals(ConnectionRequestState.FAILED, f.controller.state.value.request?.state)
        assertEquals(2, f.withdraws); assertEquals(token(40, 32), claim.value)
    }
    @Test fun matchingInitiatorReadinessUsesPrivateInitiatorScope() {
        val f = Fixture(); val flow = f.initiator(); assertTrue(f.controller.setInitiatorReady(flow)); f.worker.run()
        assertEquals(1, f.readyCalls); assertEquals(ConnectionRequestAuthScope.INITIATOR, f.scopes.last())
        assertEquals(token(30, 32), f.auths.last().value)
    }
    @Test fun staleInitiatorReadinessIsStrictNoOp() {
        val f = Fixture(); val a = f.initiator(); f.initiator(); val before = f.controller.state.value
        assertFalse(f.controller.setInitiatorReady(a)); f.worker.run(); assertEquals(before, f.controller.state.value); assertEquals(0, f.readyCalls)
    }
    @Test fun winningRecipientReadinessUsesClaimScope() {
        val f = Fixture(); val flow = f.recipient(); assertTrue(f.controller.setRecipientReady(flow)); f.worker.run()
        assertEquals(ConnectionRequestAuthScope.CLAIM, f.scopes.last()); assertEquals(token(40, 32), f.auths.last().value)
    }
    @Test fun nonWinningRecipientCannotReady() {
        val f = Fixture(); val flow = f.controller.preview(token(1), token(2), MediaRole.SEND)!!
        assertFalse(f.controller.setRecipientReady(flow)); f.worker.run(); assertEquals(0, f.readyCalls)
    }
    @Test fun arbitraryRecipientCannotReady() {
        val f = Fixture(); f.recipient(); assertFalse(f.controller.setRecipientReady(ConnectionRequestRecipientFlow())); assertEquals(0, f.readyCalls)
    }
    @Test fun waitingCannotReady() {
        val f = Fixture(); val flow = f.controller.create(MediaRole.SEND)!!; f.worker.run()
        assertFalse(f.controller.setInitiatorReady(flow)); assertEquals(0, f.readyCalls)
    }
    @Test fun connectingCannotReady() {
        val f = Fixture(); val flow = f.initiator(); f.server = ConnectionRequestState.CONNECTING; f.status()
        assertFalse(f.controller.setInitiatorReady(flow)); assertEquals(0, f.readyCalls)
    }
    @Test fun freshBothReadyInitiatorSessionAdmitted() {
        val f = Fixture(); val flow = f.initiator(); f.ir = true; f.rr = true; f.status()
        assertTrue(f.controller.createInitiatorSession(flow, f.controller.setupSnapshot()!!.completion)); f.worker.run(); assertEquals(1, f.sessions)
    }
    @Test fun recipientCannotUseInitiatorSessionAuthority() {
        val f = Fixture(); f.recipient(); assertFalse(f.controller.createInitiatorSession(ConnectionRequestCreateAttempt(), 1)); assertEquals(0, f.sessions)
    }
    @Test fun staleInitiatorSessionIsNoOp() {
        val f = Fixture(); val a = f.initiator(); f.initiator(); f.ir = true; f.rr = true; f.status()
        assertFalse(f.controller.createInitiatorSession(a, f.controller.setupSnapshot()!!.completion)); assertEquals(0, f.sessions)
    }
    @Test fun activeOperationBlocksScopedMutationWithoutError() {
        val f = Fixture(); val flow = f.initiator(); f.controller.refreshStatus(); val before = f.controller.state.value
        assertFalse(f.controller.setInitiatorReady(flow)); assertEquals(before, f.controller.state.value); f.worker.run(); assertEquals(0, f.readyCalls)
    }
    @Test fun sendInitiatorRequiresReceiveRecipient() { assertEquals(MediaRole.RECEIVE, ConnectionRequestSetupCoordinator.opposite(MediaRole.SEND)) }
    @Test fun receiveInitiatorRequiresSendRecipient() { assertEquals(MediaRole.SEND, ConnectionRequestSetupCoordinator.opposite(MediaRole.RECEIVE)) }
    @Test fun correctRecipientRoleDoesNotSwitch() {
        val f = Fixture(); f.role = MediaRole.RECEIVE; f.foregroundRecipient(f.recipient()); assertEquals(0, f.switches); assertEquals(1, f.readyCalls)
    }
    @Test fun wrongRoleDispatchesExactlyOneMainCommand() {
        val f = Fixture(); val flow = f.recipient(); f.setup.foreground(f.owner, flow); repeat(4) { f.setup.changed() }
        assertEquals(0, f.switches); assertEquals(1, f.main.tasks.size); f.settle(); assertEquals(1, f.switches); assertEquals(1, f.readyCalls)
    }
    @Test fun switchFailureKeepsContextAndDoesNotReady() {
        val f = Fixture(); f.switchOk = false; val flow = f.recipient(); f.foregroundRecipient(flow)
        assertEquals(0, f.readyCalls); assertNotNull(f.controller.recipientAccess(flow)); assertTrue(f.gate)
        val before = f.statusCalls; f.advance(2000); assertEquals(before + 1, f.statusCalls)
    }
    @Test fun switchSuccessAllowsReadiness() {
        val f = Fixture(); f.foregroundRecipient(f.recipient()); assertEquals(MediaRole.RECEIVE, f.role); assertEquals(1, f.readyCalls)
    }
    @Test fun staleRoleCommandCannotSwitchNewFlow() {
        val f = Fixture(); val a = f.recipient(); f.setup.foreground(f.owner, a)
        f.controller.clear(); val b = f.recipient(MediaRole.RECEIVE); f.settle()
        assertEquals(0, f.switches); assertEquals(0, f.readyCalls); assertNotNull(f.controller.recipientAccess(b))
    }
    @Test fun initialAcceptedForegroundReadies() { val f = Fixture(); f.initiator(); f.foregroundInitiator(); assertEquals(1, f.readyCalls) }
    @Test fun readinessRenewsAtFiveSeconds() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); f.advance(4999); assertEquals(1, f.readyCalls)
        f.advance(1); assertEquals(2, f.readyCalls)
    }
    @Test fun backgroundCancelsRenewalWithoutFalseRequest() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); f.setup.background(f.owner)
        f.advance(10000); assertEquals(1, f.readyCalls); assertTrue(f.clock.active.isEmpty())
    }
    @Test fun resumePromptsReadiness() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); f.setup.background(f.owner); f.foregroundInitiator(); assertEquals(2, f.readyCalls)
    }
    @Test fun canceledCallbackCannotRenew() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); val old = f.clock.active.single()
        f.setup.background(f.owner); old.action(); f.settle(); assertEquals(1, f.readyCalls)
    }
    @Test fun sameRoleReplacementRejectsOldRenewal() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); val old = f.clock.active.single()
        f.initiator(); f.setup.changed(); f.settle(); val count = f.readyCalls; old.action(); f.settle(); assertEquals(count, f.readyCalls)
    }
    @Test fun readinessNetworkFailureRetriesAfterTwoSeconds() {
        val f = Fixture(); f.readyError = ConnectionRequestError.NETWORK; f.initiator(); f.foregroundInitiator()
        f.advance(1999); assertEquals(1, f.readyCalls); f.advance(1); assertEquals(2, f.readyCalls)
    }
    @Test fun readinessBackoffSurvivesBackgroundResume() {
        val f = Fixture(); f.readyError = ConnectionRequestError.RATE_LIMITED; f.retry = 5
        f.initiator(); f.foregroundInitiator(); f.clock.now = 1000; f.setup.background(f.owner); f.foregroundInitiator()
        assertEquals(1, f.readyCalls); assertEquals(5000L, f.clock.active.single().due); f.advance(4000); assertEquals(2, f.readyCalls)
    }
    @Test fun winningRecipientStartsClaimPolling() {
        val f = Fixture(); val flow = f.recipient(); val before = f.statusCalls; f.foregroundRecipient(flow)
        assertEquals(before + 1, f.statusCalls); assertEquals(ConnectionRequestAuthScope.CLAIM, f.scopes.last())
    }
    @Test fun recipientStatusCadenceIsTwoSeconds() {
        val f = Fixture(); f.foregroundRecipient(f.recipient()); val before = f.statusCalls
        f.advance(1999); assertEquals(before, f.statusCalls); f.advance(1); assertEquals(before + 1, f.statusCalls)
    }
    @Test fun backgroundCancelsRecipientStatus() {
        val f = Fixture(); f.foregroundRecipient(f.recipient()); val before = f.statusCalls
        f.setup.background(f.owner); f.advance(10000); assertEquals(before, f.statusCalls)
    }
    @Test fun recipientResumeRefreshesStatus() {
        val f = Fixture(); val flow = f.recipient(); f.foregroundRecipient(flow); val before = f.statusCalls
        f.setup.background(f.owner); f.foregroundRecipient(flow); assertEquals(before + 1, f.statusCalls)
    }
    @Test fun recipientConnectingJoinAssignmentStopsPolling() {
        val f = Fixture(); f.foregroundRecipient(f.recipient()); f.server = ConnectionRequestState.CONNECTING; f.advance(2000)
        assertEquals(PairingIntent.JOIN, f.controller.state.value.request?.assignment?.intent)
        assertEquals(MediaRole.RECEIVE, f.controller.state.value.request?.assignment?.role); assertTrue(f.clock.active.isEmpty())
    }
    @Test fun terminalAndConnectedStopWithoutCleanup() {
        for (state in listOf(ConnectionRequestState.CONNECTED, ConnectionRequestState.CANCELED, ConnectionRequestState.EXPIRED, ConnectionRequestState.FAILED)) {
            val f = Fixture(); f.foregroundRecipient(f.recipient()); f.server = state; f.advance(2000)
            assertTrue(f.clock.active.isEmpty()); assertNotNull(f.controller.state.value.request); assertTrue(f.gate)
        }
    }
    @Test fun staleRecipientCannotRefreshSetupStatus() {
        val f = Fixture(); f.recipient(); val before = f.statusCalls
        assertFalse(f.controller.refreshRecipientSetupStatus(ConnectionRequestRecipientFlow())); f.worker.run(); assertEquals(before, f.statusCalls)
    }
    @Test fun firstParticipantOnlyDoesNotCreateSession() { val f = Fixture(); f.ir = true; f.initiator(); f.foregroundInitiator(); assertEquals(0, f.sessions) }
    @Test fun secondParticipantOnlyDoesNotCreateSession() { val f = Fixture(); f.rr = true; f.initiator(); f.foregroundInitiator(); assertEquals(0, f.sessions) }
    @Test fun freshBothReadyStatusCreatesOneSession() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); f.ir = true; f.rr = true; f.status(); assertEquals(1, f.sessions)
    }
    @Test fun duplicateEmitsCannotRepeatSession() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); f.ir = true; f.rr = true; f.status()
        repeat(10) { f.setup.changed() }; f.settle(); assertEquals(1, f.sessions)
    }
    @Test fun sessionSuccessPublishesCreateAssignment() {
        val f = Fixture(); f.initiator(MediaRole.RECEIVE); f.role = MediaRole.RECEIVE; f.foregroundInitiator()
        f.ir = true; f.rr = true; f.status(); val m = f.controller.state.value.request!!
        assertEquals(ConnectionRequestState.CONNECTING, m.state); assertEquals(PairingIntent.CREATE, m.assignment?.intent)
        assertEquals(MediaRole.RECEIVE, m.assignment?.role); assertTrue(f.clock.active.isEmpty())
    }
    @Test fun lostSessionResponseRecoversThroughFreshStatus() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); f.ir = true; f.rr = true; f.lostSession = true; f.status()
        assertEquals(ConnectionRequestState.ACCEPTED, f.controller.state.value.request?.state); assertEquals(1, f.sessions)
        f.status(); assertEquals(ConnectionRequestState.CONNECTING, f.controller.state.value.request?.state); assertNotNull(f.controller.state.value.request?.assignment)
    }
    @Test fun sessionConflictPreservesAcceptedAndCanRecover() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); f.ir = true; f.rr = true; f.sessionError = ConnectionRequestError.CONFLICT; f.status()
        assertEquals(ConnectionRequestState.ACCEPTED, f.controller.state.value.request?.state); assertEquals(1, f.sessions)
        repeat(5) { f.setup.changed() }; assertEquals(1, f.sessions)
        f.sessionError = null; f.advance(5000); f.status(); assertEquals(2, f.sessions); assertEquals(ConnectionRequestState.CONNECTING, f.controller.state.value.request?.state)
    }
    @Test fun staleSessionCompletionCannotPublishOverReplacement() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); f.ir = true; f.rr = true
        f.onSession = { f.controller.create(MediaRole.SEND) }
        f.status(); assertEquals(ConnectionRequestState.WAITING, f.controller.state.value.request?.state); assertEquals(2, f.creates)
    }
    @Test fun readinessCompletionCannotTriggerSessionFromOldBooleans() {
        val f = Fixture(); val flow = f.initiator(); f.ir = true; f.rr = true
        f.controller.refreshStatus(); f.worker.run(); val old = f.controller.setupSnapshot()!!.completion
        f.controller.setInitiatorReady(flow); f.worker.run()
        assertFalse(f.controller.createInitiatorSession(flow, old)); assertEquals(0, f.sessions)
    }
    @Test fun inFlightReadinessBackgroundCompletionDoesNotSchedule() {
        val f = Fixture(); f.initiator(); f.setup.foreground(f.owner, MediaRole.SEND)
        f.setup.background(f.owner); f.drain(); assertTrue(f.clock.active.isEmpty()); assertEquals(1, f.readyCalls)
    }
    @Test fun inFlightReadinessBackoffIsRememberedInBackground() {
        val f = Fixture(); f.initiator(); f.readyError = ConnectionRequestError.RATE_LIMITED; f.retry = 5
        f.setup.foreground(f.owner, MediaRole.SEND); f.setup.background(f.owner); f.drain()
        f.clock.now = 1000; f.foregroundInitiator(); assertEquals(1, f.readyCalls); assertEquals(5000L, f.clock.active.single().due)
    }
    @Test fun recipientWithdrawSuspensionStopsTimersAndCannotAffectNewFlow() {
        val f = Fixture(); val a = f.recipient(); f.foregroundRecipient(a); f.setup.suspendRecipient(a)
        assertTrue(f.clock.active.isEmpty()); f.controller.withdraw(a); f.settle(); assertEquals(1, f.withdraws)
        f.controller.clear(); val b = f.recipient(); f.foregroundRecipient(b); val before = f.statusCalls
        f.setup.suspendRecipient(a); f.advance(2000); assertEquals(before + 1, f.statusCalls)
    }
    @Test fun gateNotArmedCannotReadyOrSwitch() {
        val f = Fixture(); f.gate = false; f.foregroundRecipient(f.recipient()); assertEquals(0, f.readyCalls); assertEquals(0, f.switches)
    }
    @Test fun activeMediaConflictCannotReadyOrSwitch() {
        val f = Fixture(); f.allowed = false; f.foregroundRecipient(f.recipient()); assertEquals(0, f.readyCalls); assertEquals(0, f.switches)
    }
    @Test fun initiatorWrongRoleDoesNotSwitchOrReady() {
        val f = Fixture(); f.initiator(); f.role = MediaRole.RECEIVE; f.foregroundInitiator(MediaRole.SEND)
        assertEquals(0, f.readyCalls); assertEquals(0, f.switches)
    }
    @Test fun multipleInitiatorOwnersKeepOneRenewal() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); f.setup.foreground(ConnectionRequestSetupOwner(), MediaRole.SEND)
        f.setup.background(f.owner); f.advance(5000); assertEquals(2, f.readyCalls); assertEquals(1, f.clock.active.size)
    }
    @Test fun sessionRateLimitDoesNotHammerOnRepeatedEmits() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); f.ir = true; f.rr = true
        f.sessionError = ConnectionRequestError.RATE_LIMITED; f.retry = 5; f.status()
        repeat(5) { f.setup.changed() }; f.setup.background(f.owner); f.foregroundInitiator()
        assertEquals(1, f.sessions); assertEquals(1, f.clock.active.size)
    }
    @Test fun invalidAssignmentStopsSetupWithoutLegacyFallback() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator(); f.ir = true; f.rr = true; f.malformed = true; f.status()
        assertEquals(ConnectionRequestError.INVALID_RESPONSE, f.controller.state.value.error)
        assertEquals(ConnectionRequestState.ACCEPTED, f.controller.state.value.request?.state); assertTrue(f.clock.active.isEmpty())
    }
    @Test fun b4aStatusCannotEraseReadinessBackoffEvenWhenObserverConflates() {
        val f = Fixture(); f.initiator(); f.readyError = ConnectionRequestError.RATE_LIMITED; f.retry = 5
        f.setup.foreground(f.owner, MediaRole.SEND); f.worker.run() // Readiness completes, setup observer delayed.
        f.controller.refreshStatus(); f.worker.run() // B4A GET completes before setup sees either result.
        f.clock.now = 1000; f.setup.changed(); f.settle()
        assertEquals(1, f.readyCalls); assertEquals(5000L, f.clock.active.single().due)
    }
    @Test fun delayedObservationUsesHttpCompletionTimeForBackoff() {
        val f = Fixture(); f.initiator(); f.readyError = ConnectionRequestError.RATE_LIMITED; f.retry = 5
        f.setup.foreground(f.owner, MediaRole.SEND); f.worker.run(); f.clock.now = 4000
        f.setup.changed(); assertEquals(5000L, f.clock.active.single().due)
    }
    @Test fun consecutiveSetupRateLimitsReplaceDeadlineFromNewCompletion() {
        val f = Fixture(); f.initiator(); f.readyError = ConnectionRequestError.RATE_LIMITED; f.retry = 5
        f.foregroundInitiator(); f.retry = 10; f.advance(5000)
        assertEquals(2, f.readyCalls); assertEquals(15000L, f.clock.active.single().due)
    }
    @Test fun setupBackoffSaturatesAtLongMaxWithoutWrap() {
        val f = Fixture(); f.initiator(); f.clock.now = 100; f.readyError = ConnectionRequestError.RATE_LIMITED; f.retry = Long.MAX_VALUE
        f.foregroundInitiator(); assertEquals(Long.MAX_VALUE, f.clock.active.single().due)
        f.setup.background(f.owner); f.clock.now = 200; f.foregroundInitiator(); assertEquals(1, f.readyCalls)
    }
    @Test fun readinessSuccessClearsBackoffAndReturnsToFiveSecondRenewal() {
        val f = Fixture(); f.initiator(); f.readyError = ConnectionRequestError.RATE_LIMITED; f.retry = 5
        f.foregroundInitiator(); f.readyError = null; f.retry = null; f.advance(5000)
        assertEquals(10000L, f.clock.active.single().due)
    }
    @Test fun replacementCannotInheritSetupBackoff() {
        val f = Fixture(); f.initiator(); f.readyError = ConnectionRequestError.RATE_LIMITED; f.retry = 10; f.foregroundInitiator()
        f.readyError = null; f.retry = null; f.initiator(); f.setup.changed(); f.settle(); assertEquals(2, f.readyCalls)
    }
    @Test fun receiveInitiatorRecipientPreparesSendWithoutProjection() {
        val f = Fixture(); f.role = MediaRole.RECEIVE; f.foregroundRecipient(f.recipient(MediaRole.RECEIVE))
        assertEquals(MediaRole.SEND, f.role); assertEquals(1, f.switches); assertEquals(1, f.readyCalls)
    }
    @Test fun roleCommandPausedBeforeDispatchCanBeRetriedOnResume() {
        val f = Fixture(); val flow = f.recipient(); f.setup.foreground(f.owner, flow)
        f.setup.background(f.owner); f.main.run(); assertEquals(0, f.switches)
        f.foregroundRecipient(flow); assertEquals(1, f.switches); assertEquals(1, f.readyCalls)
    }
    @Test fun oldBothReadyStatusOnResumeDoesNotCreateSession() {
        val f = Fixture(); f.initiator(); f.ir = true; f.rr = true; f.status()
        f.foregroundInitiator(); assertEquals(0, f.sessions)
        f.status(); assertEquals(1, f.sessions)
    }
    @Test fun scopedRolePreparationCannotRunOnReplacementRecipient() {
        val f = Fixture(); val a = f.recipient(); f.controller.clear(); f.recipient()
        var called = false
        assertFalse(f.controller.prepareRecipientSetupRole(a) { called = true; true }); assertFalse(called)
    }
    @Test fun mainRoleCommandRechecksAlreadyPreparedRole() {
        val f = Fixture(); val flow = f.recipient(); f.setup.foreground(f.owner, flow)
        f.role = MediaRole.RECEIVE; f.settle(); assertEquals(0, f.switches); assertEquals(1, f.readyCalls)
    }
    @Test fun setupNeverAddsInitiatorStatusLoop() {
        val f = Fixture(); f.initiator(); val before = f.statusCalls; f.foregroundInitiator(); f.advance(10000)
        assertEquals(before, f.statusCalls); assertEquals(0, f.sessions)
    }
    @Test fun b4aStatusCompletionDrivesSetupSessionWithoutSecondStatusLoop() {
        val f = Fixture(); f.initiator(); f.foregroundInitiator()
        val monitor = ConnectionRequestInitiatorMonitor(f.controller, f.clock, { f.clock.now }) { it == f.role }
        f.ir = true; f.rr = true
        monitor.foreground(ConnectionRequestForegroundOwner(), MediaRole.SEND)
        f.settle(); monitor.changed(); f.settle()
        assertEquals(1, f.sessions); assertEquals(ConnectionRequestState.CONNECTING, f.controller.state.value.request?.state)
        assertTrue(f.scopes.all { it == ConnectionRequestAuthScope.INITIATOR })
    }
    @Test fun scopedConnectingSessionRetryReturnsSameBinding() {
        val f = Fixture(); val flow = f.initiator(); f.server = ConnectionRequestState.CONNECTING; f.status()
        val assignment = f.controller.state.value.request!!.assignment
        assertTrue(f.controller.createInitiatorSession(flow, f.controller.setupSnapshot()!!.completion)); f.worker.run()
        assertEquals(assignment, f.controller.state.value.request!!.assignment)
    }
    private fun source(name: String) = listOf(File("src/main/java/com/pradertamas/uicapturetest/$name.kt"),
        File("app/src/main/java/com/pradertamas/uicapturetest/$name.kt")).first { it.isFile }.readText()
    @Test fun setupNeverStartsSignalingMediaOrCleanup() {
        val text = source("ConnectionRequestSetupCoordinator")
        for (bad in listOf("newWebSocket", "request-auth", "PeerConnection", "claimConsent", "MediaProjection",
            "WebRtcService", "ReceiverEngine", "controller.clear(", "releaseRequestFirst", "controller.join(", "controller.create(")) assertFalse(bad, text.contains(bad))
    }
    @Test fun setupRetainsNoWireIdentifiersOrCredentials() {
        assertFalse(ConnectionRequestSetupCoordinator::class.java.declaredFields.any { it.type == String::class.java || it.type == ConnectionCredential::class.java })
        assertEquals("ConnectionRequestSetupOwner[redacted]", ConnectionRequestSetupOwner().toString())
    }
    @Test fun activityWiringUsesResumedOwners() {
        for (name in listOf("MainActivity", "ReceiverActivity", "ConnectionRequestActivity")) {
            val text = source(name)
            assertTrue(text.substringAfter("override fun onResume()").substringBefore("override fun ").contains("coordinator.foreground("))
            assertTrue(text.substringAfter("override fun onPause()").substringBefore("override fun ").contains("coordinator.background("))
        }
    }
}
