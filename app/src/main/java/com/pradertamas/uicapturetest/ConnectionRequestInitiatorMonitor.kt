package com.pradertamas.uicapturetest

import java.util.IdentityHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class ConnectionRequestForegroundOwner {
    override fun toString() = "ConnectionRequestForegroundOwner[redacted]"
}
internal fun interface ConnectionRequestPollScheduler {
    /** Returns a cancellation action. Production dispatch is asynchronous. */
    fun later(delayMillis: Long, action: () -> Unit): () -> Unit
}

/** Process-local initiator status timing only. No Android owners, credentials or identifiers.
 * One cancelable future, plus the controller's existing single-flight HTTP operation. */
internal class ConnectionRequestInitiatorMonitor(
    private val controller: ConnectionRequestController,
    private val scheduler: ConnectionRequestPollScheduler,
    private val roleAllowed: (MediaRole) -> Boolean = { true }
) {
    private val lock = Any()
    private val owners = IdentityHashMap<ConnectionRequestForegroundOwner, MediaRole>()
    private var target: ConnectionRequestCreateAttempt? = null
    private var cancel: (() -> Unit)? = null
    private var timerEpoch = 0L
    private var immediate = false
    private var awaiting = false
    private var observedOperation: ConnectionRequestOperation? = null

    fun foreground(owner: ConnectionRequestForegroundOwner, role: MediaRole) = synchronized(lock) {
        owners[owner] = role; evaluate()
    }
    fun background(owner: ConnectionRequestForegroundOwner) = synchronized(lock) {
        owners.remove(owner); evaluate()
    }
    fun changed() = synchronized(lock) { evaluate() }
    private fun cancelFuture() {
        timerEpoch++; cancel?.invoke(); cancel = null
    }
    private fun stop() {
        cancelFuture(); target = null; immediate = false; awaiting = false; observedOperation = null
    }
    private fun evaluate() {
        val snapshot = controller.initiatorMonitorSnapshot()
        if (snapshot == null || !snapshot.active || !roleAllowed(snapshot.role) || !owners.containsValue(snapshot.role)) {
            stop(); return
        }
        if (target !== snapshot.flow) {
            cancelFuture(); target = snapshot.flow; immediate = true; awaiting = false; observedOperation = null
        }
        if (snapshot.operation != null) {
            cancelFuture(); awaiting = true; observedOperation = snapshot.operation
            // An already running GET satisfies the immediate refresh; other mutations do not.
            immediate = snapshot.operation != ConnectionRequestOperation.STATUS
            return
        }
        if (awaiting) {
            awaiting = false
            val wasStatus = observedOperation == ConnectionRequestOperation.STATUS
            observedOperation = null
            if (wasStatus) { schedule(nextDelay(snapshot.retryAfterSeconds)); return }
            immediate = true
        }
        if (immediate) {
            immediate = false; cancelFuture()
            if (controller.refreshInitiatorStatus(snapshot.role, snapshot.flow)) {
                awaiting = true; observedOperation = ConnectionRequestOperation.STATUS
                evaluate() // Also handles a synchronous test transport completion without another emit.
            } else {
                // A concurrent context/operation won admission. Its state event will re-evaluate.
                target = null
            }
        } else if (cancel == null) schedule(nextDelay(snapshot.retryAfterSeconds))
    }
    private fun schedule(delayMillis: Long) {
        if (cancel != null) return
        val ticket = ++timerEpoch
        cancel = scheduler.later(delayMillis) {
            synchronized(lock) {
                if (ticket != timerEpoch) return@synchronized
                cancel = null; immediate = true; evaluate()
            }
        }
    }
    private fun nextDelay(seconds: Long?): Long {
        val retry = seconds?.takeIf { it > 0 }?.let { if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000 } ?: 0
        return maxOf(2000, retry)
    }
}

internal object ConnectionRequestInitiatorMonitoring {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val monitor by lazy {
        val executor = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "request-status-monitor").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
        val scheduler = ConnectionRequestPollScheduler { ms, action ->
            val future = executor.schedule({ action() }, ms, TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { future.cancel(false); Unit }; cancel
        }
        val controller = ConnectionRequests.controller
        ConnectionRequestInitiatorMonitor(controller, scheduler) { it == AppMediaRoles.role }.also { monitor ->
            scope.launch {
                combine(controller.state, controller.ownershipChanges, AppMediaRoles.state) { _, _, _ -> Unit }
                    .collect { monitor.changed() }
            }
        }
    }
}
