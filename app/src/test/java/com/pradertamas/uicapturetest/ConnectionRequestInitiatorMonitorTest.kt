package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executor

class ConnectionRequestInitiatorMonitorTest {
    private fun token(bytes: Int, seed: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes) { seed.toByte() })
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Clock : ConnectionRequestPollScheduler {
        data class Task(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false, var fired: Boolean = false)
        var now = 0L
        val tasks = mutableListOf<Task>()
        val active get() = tasks.filter { !it.cancelled && !it.fired }
        override fun later(delayMillis: Long, action: () -> Unit): () -> Unit {
            val task = Task(delayMillis, action); tasks.add(task)
            return { task.cancelled = true }
        }
        fun fire() { val task = active.single(); now = Math.addExact(now, task.delay); task.fired = true; task.action() }
    }
    private inner class Fixture(initialRole: MediaRole = MediaRole.SEND, synchronous: Boolean = false) {
        val queue = Queue(); val clock = Clock(); val owner = ConnectionRequestForegroundOwner()
        var appRole = initialRole; var currentRole = initialRole
        var state = ConnectionRequestState.WAITING
        var creates = 0; var statuses = 0; var cancels = 0
        var retry: Long? = null; var statusError: ConnectionRequestError? = null; var cancelError: ConnectionRequestError? = null
        var onStatus: (() -> Unit)? = null
        val auths = mutableListOf<ConnectionCredential>(); val scopes = mutableListOf<ConnectionRequestAuthScope>()
        val api = object : ConnectionRequestApi {
            override fun create(role: MediaRole): ConnectionRequestResult {
                creates++; currentRole = role; state = ConnectionRequestState.WAITING
                val metadata = metadata(token(16, creates), role)
                return ConnectionRequestResult(metadata, httpStatus = 201, created = ConnectionRequestCreated(metadata,
                    ConnectionCredential(token(32, 33)), ConnectionCredential(token(16, 34))))
            }
            override fun status(id: String, auth: ConnectionCredential, scope: ConnectionRequestAuthScope): ConnectionRequestResult {
                statuses++; auths.add(auth); scopes.add(scope)
                val result = if (statusError != null) ConnectionRequestResult(error = statusError, retryAfterSeconds = retry)
                    else ConnectionRequestResult(metadata(id, currentRole), httpStatus = if (state.terminal) 410 else 200, retryAfterSeconds = retry)
                onStatus?.also { onStatus = null }?.invoke()
                return result
            }
            override fun cancel(id: String, auth: ConnectionCredential): ConnectionRequestResult {
                cancels++
                return if (cancelError != null) ConnectionRequestResult(error = cancelError)
                    else ConnectionRequestResult(metadata(id, currentRole).copy(state = ConnectionRequestState.CANCELED), httpStatus = 200)
            }
            override fun accept(id: String, capability: ConnectionCredential, claim: ConnectionCredential): ConnectionRequestResult = error("Monitor cannot Accept")
            override fun withdraw(id: String, auth: ConnectionCredential): ConnectionRequestResult = error("Monitor cannot withdraw")
            override fun ready(id: String, auth: ConnectionCredential, ready: Boolean, scope: ConnectionRequestAuthScope): ConnectionRequestResult = error("Monitor cannot ready")
            override fun session(id: String, auth: ConnectionCredential): ConnectionRequestResult = error("Monitor cannot create session")
        }
        fun metadata(id: String, role: MediaRole): ConnectionRequestMetadata {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            return ConnectionRequestMetadata(id, state, now, role, now, now.plusSeconds(300),
                failureReason = if (state == ConnectionRequestState.FAILED) "establishment_timeout" else null)
        }
        val worker = if (synchronous) Executor { it.run() } else queue
        val controller = ConnectionRequestController(api, worker, elapsedMs = { 0 })
        val monitor = ConnectionRequestInitiatorMonitor(controller, clock, elapsedMs = { clock.now }) { it == appRole }
        fun create(role: MediaRole = appRole): ConnectionRequestCreateAttempt {
            val attempt = controller.create(role)!!; queue.run(); monitor.changed(); return attempt
        }
        fun foreground(role: MediaRole = appRole, o: ConnectionRequestForegroundOwner = owner) { monitor.foreground(o, role) }
        fun finish() { queue.run(); monitor.changed() }
        fun start(role: MediaRole = appRole) { create(role); foreground(role); finish() }
        fun poll() { clock.fire(); finish() }
    }
    @Test fun earlyResumeWaitsRemainingBackoffThenGets() {
        val f = Fixture(); f.retry = 5; f.start(); f.clock.now = 1000
        val stale = f.clock.active.single(); f.monitor.background(f.owner); stale.action()
        f.foreground(); f.finish(); assertEquals(1, f.statuses)
        assertEquals(4000L, f.clock.active.single().delay)
        f.poll(); assertEquals(2, f.statuses)
    }
    @Test fun resumeAfterBackoffExpiryGetsImmediately() {
        val f = Fixture(); f.retry = 5; f.start(); f.monitor.background(f.owner)
        f.clock.now = 6000; f.foreground(); f.finish(); assertEquals(2, f.statuses)
    }
    @Test fun backgroundInFlightCompletionPreservesBackoffWithoutTimer() {
        val f = Fixture(); f.retry = 5; f.create(); f.foreground(); f.monitor.background(f.owner)
        f.finish(); assertTrue(f.clock.active.isEmpty()); f.clock.now = 1000
        f.foreground(); f.finish(); assertEquals(1, f.statuses)
        assertEquals(4000L, f.clock.active.single().delay)
    }
    @Test fun successClearsBackoffAndRestoresExactCadenceAndImmediateResume() {
        val f = Fixture(); f.retry = 5; f.statusError = ConnectionRequestError.RATE_LIMITED; f.start()
        f.retry = null; f.statusError = null; f.poll()
        assertEquals(2000L, f.clock.active.single().delay)
        f.monitor.background(f.owner); f.foreground(); f.finish(); assertEquals(3, f.statuses)
    }
    @Test fun zeroBackoffDoesNotSurvivePause() {
        val f = Fixture(); f.retry = 0; f.start(); f.monitor.background(f.owner)
        f.foreground(); f.finish(); assertEquals(2, f.statuses)
    }
    @Test fun errorsWithoutRetryDoNotBlockResume() {
        for (error in listOf(ConnectionRequestError.NETWORK, ConnectionRequestError.SERVER)) {
            val f = Fixture(); f.statusError = error; f.start(); f.monitor.background(f.owner)
            f.foreground(); f.finish(); assertEquals(2, f.statuses)
        }
    }
    @Test fun sameRoleReplacementDoesNotInheritBackoff() {
        val f = Fixture(); f.retry = 10; f.start(); val old = f.clock.active.single()
        f.retry = null; f.create(); f.finish(); old.action(); f.finish()
        assertEquals(2, f.statuses); assertEquals(2000L, f.clock.active.single().delay)
    }
    @Test fun differentRoleReplacementDoesNotInheritBackoff() {
        val f = Fixture(); f.retry = 10; f.start(); f.retry = null
        f.create(MediaRole.RECEIVE); f.appRole = MediaRole.RECEIVE
        f.foreground(MediaRole.RECEIVE); f.finish(); assertEquals(2, f.statuses)
    }
    @Test fun inactiveContextsDiscardBackoff() {
        for (state in listOf(ConnectionRequestState.CONNECTED, ConnectionRequestState.EXPIRED,
            ConnectionRequestState.CANCELED, ConnectionRequestState.FAILED)) {
            val f = Fixture(); f.retry = 10; f.start(); f.state = state; f.poll()
            assertTrue(f.clock.active.isEmpty()); f.controller.clear(); f.monitor.changed()
            f.retry = null; f.create(); f.finish()
            assertEquals(3, f.statuses)
        }
    }
    @Test fun recipientAndNoContextCannotActivateOldBackoff() {
        val f = Fixture(); f.retry = 10; f.start(); val stale = f.clock.active.single()
        f.controller.clear(); f.monitor.changed()
        f.controller.preview(token(16, 1), token(16, 2), MediaRole.SEND); f.monitor.changed()
        stale.action(); f.finish(); assertEquals(1, f.statuses); assertTrue(f.clock.active.isEmpty())
    }
    @Test fun deadlineAdditionSaturatesWithoutEarlyResume() {
        val f = Fixture(); f.clock.now = Long.MAX_VALUE - 10000; f.retry = 20; f.start()
        assertEquals(10000L, f.clock.active.single().delay)
        f.monitor.background(f.owner); f.clock.now += 1000; f.foreground(); f.finish()
        assertEquals(1, f.statuses); assertEquals(9000L, f.clock.active.single().delay)
    }
    @Test fun productionBackoffClockUsesElapsedRealtime() {
        assertTrue(source("ConnectionRequestInitiatorMonitor").contains("android.os.SystemClock.elapsedRealtime()"))
    }
    @Test fun noRequestForegroundDoesNotGet() { val f = Fixture(); f.foreground(); f.finish(); assertEquals(0, f.statuses); assertTrue(f.clock.active.isEmpty()) }
    @Test fun recipientContextNeverPolled() { val f = Fixture(); f.controller.preview(token(16, 1), token(16, 2), MediaRole.SEND); f.foreground(); f.finish(); assertEquals(0, f.statuses); assertTrue(f.clock.active.isEmpty()) }
    @Test fun sendForegroundImmediatelyAdmitsGet() { val f = Fixture(); f.create(); f.foreground(); assertEquals(ConnectionRequestOperation.STATUS, f.controller.state.value.operation); assertTrue(f.clock.active.isEmpty()); f.finish(); assertEquals(1, f.statuses) }
    @Test fun receiveForegroundImmediatelyGets() { val f = Fixture(MediaRole.RECEIVE); f.start(); assertEquals(1, f.statuses); assertEquals(ConnectionRequestAuthScope.INITIATOR, f.scopes.single()) }
    @Test fun wrongRoleOwnerDoesNotPoll() { val f = Fixture(); f.create(); f.foreground(MediaRole.RECEIVE); f.finish(); assertEquals(0, f.statuses); assertTrue(f.clock.active.isEmpty()) }
    @Test fun cadenceStartsTwoSecondsAfterCompletion() { val f = Fixture(); f.start(); assertEquals(2000L, f.clock.active.single().delay); f.poll(); assertEquals(2, f.statuses); assertEquals(2000L, f.clock.active.single().delay) }
    @Test fun synchronousStatusCompletionSchedulesRatherThanRecursesGet() { val f = Fixture(synchronous = true); f.start(); assertEquals(1, f.statuses); assertEquals(2000L, f.clock.active.single().delay) }
    @Test fun backgroundCancelsFutureAndStaleCallbackCannotGet() { val f = Fixture(); f.start(); val old = f.clock.active.single(); f.monitor.background(f.owner); assertTrue(old.cancelled); old.action(); f.finish(); assertEquals(1, f.statuses); assertTrue(f.clock.active.isEmpty()) }
    @Test fun resumeImmediatelyGetsWithoutOldDeadline() { val f = Fixture(); f.start(); f.monitor.background(f.owner); f.foreground(); assertEquals(ConnectionRequestOperation.STATUS, f.controller.state.value.operation); f.finish(); assertEquals(2, f.statuses) }
    @Test fun inFlightBackgroundCompletionDoesNotSchedule() { val f = Fixture(); f.create(); f.foreground(); f.monitor.background(f.owner); f.finish(); assertEquals(1, f.statuses); assertTrue(f.clock.active.isEmpty()) }
    @Test fun resumeDuringInFlightGetDoesNotDuplicate() { val f = Fixture(); f.create(); f.foreground(); f.monitor.background(f.owner); f.foreground(); f.finish(); assertEquals(1, f.statuses); assertEquals(2000L, f.clock.active.single().delay) }
    @Test fun waitingAcceptedConnectingContinueThenConnectedStops() {
        val f = Fixture(); f.start()
        for (state in listOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING)) {
            f.state = state; f.poll(); assertEquals(state, f.controller.state.value.request?.state); assertEquals(1, f.clock.active.size)
        }
        f.state = ConnectionRequestState.CONNECTED; f.poll(); assertTrue(f.clock.active.isEmpty()); assertNotNull(f.controller.state.value.request)
    }
    @Test fun everyTerminalStopsWithoutClearing() {
        for (state in listOf(ConnectionRequestState.EXPIRED, ConnectionRequestState.CANCELED, ConnectionRequestState.FAILED)) {
            val f = Fixture(); f.start(); f.state = state; f.poll()
            assertTrue(f.clock.active.isEmpty()); assertEquals(state, f.controller.state.value.request?.state)
            f.foreground(); f.finish(); assertEquals(2, f.statuses)
        }
    }
    @Test fun retryAfterFiveSecondsOverridesCadence() { val f = Fixture(); f.statusError = ConnectionRequestError.RATE_LIMITED; f.retry = 5; f.start(); assertEquals(5000L, f.clock.active.single().delay); f.poll(); assertEquals(2, f.statuses) }
    @Test fun smallZeroNegativeAndNullRetryUseMinimumCadence() { for (retry in listOf(null, -1L, 0L, 1L)) { val f = Fixture(); f.retry = retry; f.start(); assertEquals(2000L, f.clock.active.single().delay) } }
    @Test fun retryAfterOverflowSaturatesSafely() { val f = Fixture(); f.retry = Long.MAX_VALUE; f.start(); assertEquals(Long.MAX_VALUE, f.clock.active.single().delay) }
    @Test fun networkFailureStillSchedulesWithoutCleanup() { val f = Fixture(); f.statusError = ConnectionRequestError.NETWORK; f.start(); assertEquals(ConnectionRequestError.NETWORK, f.controller.state.value.error); assertEquals(2000L, f.clock.active.single().delay); assertNotNull(f.controller.state.value.request) }
    @Test fun repeatedStateEventsDoNotGetOrReschedule() { val f = Fixture(); f.start(); val timer = f.clock.active.single(); repeat(5) { f.monitor.changed() }; assertSame(timer, f.clock.active.single()); assertEquals(1, f.statuses) }
    @Test fun activeCreateNeverGetsUntilCompletion() { val f = Fixture(); f.controller.create(MediaRole.SEND); f.foreground(); f.monitor.changed(); assertEquals(0, f.statuses); assertTrue(f.clock.active.isEmpty()); f.finish(); f.finish(); assertEquals(1, f.statuses) }
    @Test fun activeCancelPreventsStatusAndTerminalCompletionStops() { val f = Fixture(); f.start(); f.controller.cancel(); f.monitor.changed(); assertTrue(f.clock.active.isEmpty()); f.finish(); assertEquals(1, f.statuses); assertEquals(1, f.cancels); assertTrue(f.clock.active.isEmpty()) }
    @Test fun failedMutationCompletionReevaluatesImmediately() { val f = Fixture(); f.start(); f.cancelError = ConnectionRequestError.NETWORK; f.controller.cancel(); f.monitor.changed(); f.finish(); f.finish(); assertEquals(2, f.statuses) }
    @Test fun statusAlreadyActiveIsNotDuplicated() { val f = Fixture(); f.create(); f.controller.refreshStatus(); f.foreground(); repeat(4) { f.monitor.changed() }; f.finish(); assertEquals(1, f.statuses); assertEquals(1, f.clock.active.size) }
    @Test fun replacementCancelsOldTimerAndGetsOnlyNewFlow() { val f = Fixture(); f.start(); val old = f.clock.active.single(); f.create(); f.finish(); old.action(); f.finish(); assertEquals(2, f.statuses); assertEquals(1, f.clock.active.size) }
    @Test fun replacementWrongRoleStopsUntilMatchingOwnerAndAppRole() { val f = Fixture(); f.start(); f.create(MediaRole.RECEIVE); f.finish(); assertEquals(1, f.statuses); assertTrue(f.clock.active.isEmpty()); f.appRole = MediaRole.RECEIVE; f.foreground(MediaRole.RECEIVE, ConnectionRequestForegroundOwner()); f.finish(); assertEquals(2, f.statuses) }
    @Test fun lateOldStatusCannotCorruptReplacementOrScheduleOldFlow() {
        val f = Fixture(); f.create(); f.onStatus = { f.controller.create(MediaRole.RECEIVE); f.queue.run() }
        f.foreground(); f.finish(); assertEquals(MediaRole.RECEIVE, f.controller.state.value.request?.initiatorRole)
        assertTrue(f.clock.active.isEmpty()); assertEquals(1, f.statuses)
        f.appRole = MediaRole.RECEIVE; f.foreground(MediaRole.RECEIVE); f.finish(); assertEquals(2, f.statuses)
    }
    @Test fun multipleMatchingOwnersShareOneLoop() { val f = Fixture(); f.start(); val other = ConnectionRequestForegroundOwner(); f.foreground(o = other); f.finish(); assertEquals(1, f.statuses); f.monitor.background(f.owner); assertEquals(1, f.clock.active.size); f.poll(); assertEquals(2, f.statuses); f.monitor.background(other); assertTrue(f.clock.active.isEmpty()) }
    @Test fun mismatchingOwnerDoesNotKeepLoopAlive() { val f = Fixture(); f.start(); f.foreground(MediaRole.RECEIVE, ConnectionRequestForegroundOwner()); f.monitor.background(f.owner); assertTrue(f.clock.active.isEmpty()) }
    @Test fun currentAppRoleChangeSuppressesStaleActivityOwner() { val f = Fixture(); f.start(); f.appRole = MediaRole.RECEIVE; f.monitor.changed(); assertTrue(f.clock.active.isEmpty()); f.appRole = MediaRole.SEND; f.monitor.changed(); f.finish(); assertEquals(2, f.statuses) }
    @Test fun scopedControllerUsesInitiatorSecretAndScope() { val f = Fixture(); f.create(); assertTrue(f.controller.refreshInitiatorStatus(MediaRole.SEND)); f.finish(); assertEquals(token(32, 33), f.auths.single().value); assertEquals(ConnectionRequestAuthScope.INITIATOR, f.scopes.single()) }
    @Test fun scopedControllerWrongRoleIsStrictNoOp() { val f = Fixture(); f.create(); val before = f.controller.state.value; assertFalse(f.controller.refreshInitiatorStatus(MediaRole.RECEIVE)); f.finish(); assertEquals(before, f.controller.state.value); assertEquals(0, f.statuses) }
    @Test fun scopedControllerRecipientIsStrictNoOp() { val f = Fixture(); f.controller.preview(token(16, 1), token(16, 2), MediaRole.SEND); val before = f.controller.state.value; assertFalse(f.controller.refreshInitiatorStatus(MediaRole.SEND)); f.finish(); assertEquals(before, f.controller.state.value); assertEquals(0, f.statuses) }
    @Test fun scopedControllerActiveOperationDoesNotDuplicateOrPublishError() { val f = Fixture(); f.create(); assertTrue(f.controller.refreshInitiatorStatus(MediaRole.SEND)); val before = f.controller.state.value; assertFalse(f.controller.refreshInitiatorStatus(MediaRole.SEND)); assertEquals(before, f.controller.state.value); f.finish(); assertEquals(1, f.statuses) }
    @Test fun scopedControllerOldAttemptCannotRefreshNewSameRoleContext() { val f = Fixture(); val a = f.create(); f.create(); val before = f.controller.state.value; assertFalse(f.controller.refreshInitiatorStatus(MediaRole.SEND, a)); f.finish(); assertEquals(before, f.controller.state.value); assertEquals(0, f.statuses) }
    @Test fun scopedControllerCancelOperationBlocksStatusWithoutNewError() {
        val f = Fixture(); f.create(); f.controller.cancel(); val before = f.controller.state.value
        assertFalse(f.controller.refreshInitiatorStatus(MediaRole.SEND)); assertEquals(before, f.controller.state.value)
        f.finish(); assertEquals(0, f.statuses)
    }
    @Test fun scopedControllerRejectsConnectedAndTerminalContexts() {
        for (state in listOf(ConnectionRequestState.CONNECTED, ConnectionRequestState.CANCELED, ConnectionRequestState.EXPIRED, ConnectionRequestState.FAILED)) {
            val f = Fixture(); f.create(); f.state = state; f.controller.refreshStatus(); f.finish()
            val before = f.controller.state.value
            assertFalse(f.controller.refreshInitiatorStatus(MediaRole.SEND)); assertEquals(before, f.controller.state.value); assertEquals(1, f.statuses)
        }
    }
    @Test fun monitorGetBetweenCreateAndShareObservationDoesNotLoseShareDelivery() {
        val f = Fixture(); val gate = LegacyConnectionStartGate()
        val share = ConnectionRequestShareCoordinator(f.controller, gate::armRequestFirst, gate::releaseRequestFirst, gate::legacyStartAllowed)
        val owner = ConnectionRequestShareOwner(); f.foreground()
        share.start(owner, MediaRole.SEND) {}; f.queue.run() // Create completes before either observer runs.
        f.monitor.changed(); share.onRequestState(f.controller.state.value)
        assertNull(share.state.value.delivery) // Busy GET delays delivery, rather than invalidating creation.
        f.finish(); share.onRequestState(f.controller.state.value)
        val delivery = share.state.value.delivery!!
        assertNotNull(share.takeLink(owner, delivery, MediaRole.SEND))
        assertEquals(1, f.creates); assertEquals(1, f.statuses); assertFalse(gate.legacyStartAllowed())
    }
    @Test fun monitorRetainsNoStringOrCredentialFields() { val names = ConnectionRequestInitiatorMonitor::class.java.declaredFields; assertFalse(names.any { it.type == String::class.java || it.type == ConnectionCredential::class.java }); assertEquals("ConnectionRequestForegroundOwner[redacted]", ConnectionRequestForegroundOwner().toString()) }
    private fun source(name: String) = listOf(File("src/main/java/com/pradertamas/uicapturetest/$name.kt"), File("app/src/main/java/com/pradertamas/uicapturetest/$name.kt")).first { it.isFile }.readText()
    @Test fun activitiesWireResumedAndPausedOpaqueOwners() {
        for ((activity, role) in listOf("MainActivity" to "SEND", "ReceiverActivity" to "RECEIVE")) {
            val text = source(activity)
            assertTrue(text.contains("private val requestMonitorOwner = ConnectionRequestForegroundOwner()"))
            val resume = text.substringAfter("override fun onResume()").substringBefore("override fun ")
            val pause = text.substringAfter("override fun onPause()").substringBefore("override fun ")
            assertTrue(resume.contains("monitor.foreground(requestMonitorOwner, MediaRole.$role)"))
            assertTrue(pause.contains("monitor.background(requestMonitorOwner)"))
        }
    }
    @Test fun monitorDoesNotStartSetupOrCleanup() {
        val text = source("ConnectionRequestInitiatorMonitor")
        for (forbidden in listOf("setReady(", "createSession(", "request-auth", "newWebSocket", "claimConsent(", "controller.clear(", "releaseRequestFirst", "switchRole(", "Log.", "println")) assertFalse(text.contains(forbidden))
    }
}
