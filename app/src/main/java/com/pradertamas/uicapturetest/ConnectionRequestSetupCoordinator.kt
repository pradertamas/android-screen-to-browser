package com.pradertamas.uicapturetest

import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

internal class ConnectionRequestSetupOwner {
    override fun toString() = "ConnectionRequestSetupOwner[redacted]"
}

/** Process-local accepted control plane. One wake-up, no bearer or Android owner retained.
 * Production inputs and role preparation dispatch on Main; the lock also serializes test callers. */
internal class ConnectionRequestSetupCoordinator(
    private val controller: ConnectionRequestController,
    private val scheduler: ConnectionRequestPollScheduler,
    private val elapsedMs: () -> Long,
    private val currentRole: () -> MediaRole,
    private val eligibleMedia: () -> Boolean,
    private val armed: () -> Boolean,
    private val dispatchMain: (() -> Unit) -> Unit,
    private val switchRole: (MediaRole) -> Boolean
) {
    private class Owner(val role: MediaRole? = null, val recipient: ConnectionRequestRecipientFlow? = null)
    private val lock = Any()
    private val owners = IdentityHashMap<ConnectionRequestSetupOwner, Owner>()
    private var target: Any? = null
    private var seen = -1L
    private var readyAt = 0L
    private var statusAt = 0L
    private var backoff = 0L
    private var freshSession: Long? = null
    private var rolePending = false
    private var roleFailed = false
    private var paused: ConnectionRequestRecipientFlow? = null
    private var wasForeground = false
    private var cancel: (() -> Unit)? = null
    private var epoch = 0L

    fun foreground(owner: ConnectionRequestSetupOwner, role: MediaRole) = synchronized(lock) {
        owners[owner] = Owner(role = role); evaluate()
    }
    fun foreground(owner: ConnectionRequestSetupOwner, flow: ConnectionRequestRecipientFlow) = synchronized(lock) {
        owners[owner] = Owner(recipient = flow); evaluate()
    }
    fun background(owner: ConnectionRequestSetupOwner) = synchronized(lock) { owners.remove(owner); evaluate() }
    fun suspendRecipient(flow: ConnectionRequestRecipientFlow) = synchronized(lock) {
        if (controller.setupSnapshot()?.flow === flow) { paused = flow; cancelTimer() }
    }
    fun changed() = synchronized(lock) { evaluate() }
    private fun cancelTimer() { epoch++; cancel?.invoke(); cancel = null }
    private fun after(now: Long, delay: Long): Long = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    private fun millis(seconds: Long?): Long = seconds?.takeIf { it > 0 }?.let {
        if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000
    } ?: 0L
    private fun evaluate() {
        val s = controller.setupSnapshot()
        if (s == null || s.state != ConnectionRequestState.ACCEPTED ||
            (s.side == ConnectionRequestSide.RECIPIENT && !s.won)) {
            cancelTimer(); target = null; freshSession = null; backoff = 0; wasForeground = false; return
        }
        val now = elapsedMs()
        if (target !== s.flow) {
            cancelTimer(); target = s.flow; seen = -1; readyAt = now; statusAt = now
            backoff = 0; freshSession = null; rolePending = false; roleFailed = false; wasForeground = false
        }
        if (seen != s.completion) {
            freshSession = null
            for (completion in s.completions.filter { it.revision > seen }) {
                val wait = millis(completion.retryAfterSeconds)
                // B4A owns initiator STATUS backoff. Its successful GET must not erase
                // an independent participant/session endpoint's active server backoff.
                if (completion.operation in setOf(ConnectionRequestOperation.READINESS, ConnectionRequestOperation.SESSION) ||
                    (s.side == ConnectionRequestSide.RECIPIENT && completion.operation == ConnectionRequestOperation.STATUS)) {
                    backoff = if (wait == 0L) 0L else after(completion.elapsedMs, wait)
                }
                when (completion.operation) {
                    ConnectionRequestOperation.READINESS -> readyAt = after(completion.elapsedMs, if (completion.error == null) 5000 else 2000)
                    ConnectionRequestOperation.STATUS -> {
                        statusAt = after(completion.elapsedMs, 2000)
                        if (s.side == ConnectionRequestSide.INITIATOR && completion.revision == s.completion && completion.error == null &&
                            s.initiatorReady == true && s.recipientReady == true) freshSession = completion.revision
                    }
                    else -> Unit
                }
            }
            seen = s.completion
        }
        val recipient = s.side == ConnectionRequestSide.RECIPIENT
        val foreground = if (recipient) owners.values.any { it.recipient === s.flow } && paused !== s.flow
            else owners.values.any { it.role == s.initiatorRole } && currentRole() == s.initiatorRole
        if (!foreground || !armed()) { cancelTimer(); wasForeground = false; freshSession = null; return }
        if (!wasForeground) { readyAt = now; statusAt = now; roleFailed = false; wasForeground = true; freshSession = null }
        val required = if (!recipient) s.initiatorRole else opposite(s.initiatorRole)
        if (currentRole() != required) {
            if (!rolePending && !roleFailed) {
                rolePending = true
                val flow = s.flow
                dispatchMain {
                    synchronized(lock) {
                        val current = controller.setupSnapshot()
                        if (target !== flow || current?.flow !== flow || current.state != ConnectionRequestState.ACCEPTED ||
                            !owners.values.any { it.recipient === flow } || paused === flow || !armed()) {
                            if (target === flow) rolePending = false
                            return@synchronized
                        }
                        roleFailed = !controller.prepareRecipientSetupRole(flow as ConnectionRequestRecipientFlow) {
                            eligibleMedia() && (currentRole() == required || switchRole(required))
                        }
                        rolePending = false
                        evaluate()
                    }
                }
            }
        }
        val localEligible = currentRole() == required && eligibleMedia() && !roleFailed
        if ((!recipient && !localEligible) || s.error == ConnectionRequestError.INVALID_RESPONSE) { cancelTimer(); return }
        if (s.operation != null) { cancelTimer(); return }
        if (now < backoff) { wake(backoff - now); return }
        val fresh = freshSession
        if (!recipient && localEligible && fresh != null) {
            freshSession = null
            if (controller.createInitiatorSession(s.flow as ConnectionRequestCreateAttempt, fresh)) { evaluate(); return }
        }
        if (localEligible && now >= readyAt) {
            val admitted = if (recipient) controller.setRecipientReady(s.flow as ConnectionRequestRecipientFlow)
                else controller.setInitiatorReady(s.flow as ConnectionRequestCreateAttempt)
            if (admitted) { evaluate(); return }
        }
        if (recipient && now >= statusAt) {
            if (controller.refreshRecipientSetupStatus(s.flow as ConnectionRequestRecipientFlow)) { evaluate(); return }
        }
        val next = if (recipient) { if (localEligible) minOf(readyAt, statusAt) else statusAt } else readyAt
        wake((next - now).coerceAtLeast(1L))
    }
    private fun wake(ms: Long) {
        cancelTimer()
        val ticket = epoch
        val flow = target
        cancel = scheduler.later(ms) {
            synchronized(lock) {
                if (ticket == epoch && target === flow) { cancel = null; evaluate() }
            }
        }
    }
    companion object {
        fun opposite(role: MediaRole) = if (role == MediaRole.SEND) MediaRole.RECEIVE else MediaRole.SEND
    }
}

internal object ConnectionRequestSetup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val coordinator by lazy {
        val scheduler = ConnectionRequestPollScheduler { ms, action ->
            val job = scope.launch { delay(ms); action() }
            val cancel: () -> Unit = { job.cancel() }; cancel
        }
        val controller = ConnectionRequests.controller
        ConnectionRequestSetupCoordinator(controller, scheduler, android.os.SystemClock::elapsedRealtime,
            { AppMediaRoles.role }, AppMediaRoles::pairingAllowed,
            { !ConnectionRequestRuntimeGate.legacyStartAllowed() },
            { action -> scope.launch { action() }; Unit }, { AppMediaRoles.switchRole(it) }).also { coordinator ->
            scope.launch {
                combine(controller.state, controller.ownershipChanges, AppMediaRoles.state,
                    AppMediaRoles.receiverRevision, CaptureState.state, WebRtcState.state, PairingHost.state) { _ -> Unit }
                    .collect { coordinator.changed() }
            }
        }
    }
}
