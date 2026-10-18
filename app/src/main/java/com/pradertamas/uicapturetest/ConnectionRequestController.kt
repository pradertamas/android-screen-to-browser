package com.pradertamas.uicapturetest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Local identity only; never a wire credential or public UI identifier. */
internal class ConnectionRequestCreateAttempt internal constructor() {
    override fun toString() = "ConnectionRequestCreateAttempt[redacted]"
}
internal class ConnectionRequestRecipientContext {
    override fun toString() = "ConnectionRequestRecipientContext[redacted]"
}
internal data class ConnectionRequestRecipientAccess(val won: Boolean, val acceptAttempted: Boolean)

/** Blocking HTTP lives on a serial worker; all context/publication changes share one lock.
 * No Activity, storage, polling, media-role mutation or automatic session start. */
class ConnectionRequestController(
    private val api: ConnectionRequestApi,
    private val worker: Executor = Executors.newSingleThreadExecutor(),
    private val elapsedMs: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val claimFactory: () -> ConnectionCredential = ConnectionRequestSecrets::claim
) {
    private class Context(val side: ConnectionRequestSide, var metadata: ConnectionRequestMetadata?,
        val id: String, val role: MediaRole, val capability: ConnectionCredential,
        val initiatorSecret: ConnectionCredential? = null, var claim: ConnectionCredential? = null,
        var won: Boolean = false, val expiryElapsedMs: Long? = null,
        val originAttempt: ConnectionRequestCreateAttempt? = null,
        val recipientContext: ConnectionRequestRecipientContext? = null)
    private val lock = Any()
    private var context: Context? = null
    private var generation = 0L
    private var currentCreateAttempt: ConnectionRequestCreateAttempt? = null
    private var activeOperation: ConnectionRequestOperation? = null
    private val mutable = MutableStateFlow(ConnectionRequestUiState())
    val state = mutable.asStateFlow()
    private fun rejected() { mutable.value = mutable.value.copy(error = ConnectionRequestError.INVALID_OPERATION) }
    private fun publish(c: Context?, operation: ConnectionRequestOperation? = null, error: ConnectionRequestError? = null) {
        mutable.value = ConnectionRequestUiState(c?.side, c?.metadata, operation, error)
    }
    private fun auth(c: Context): ConnectionCredential = c.initiatorSecret ?: if (c.won) checkNotNull(c.claim) else c.capability
    private fun validUpdate(c: Context, update: ConnectionRequestMetadata, op: ConnectionRequestOperation): Boolean {
        val old = c.metadata
        if (update.requestId != c.id || (update.initiatorRole != null && update.initiatorRole != c.role)) return false
        if (old?.state == ConnectionRequestState.CONNECTED && update.state != old.state) return false
        if (old?.state?.terminal == true && update.state != old.state) return false
        if (old?.state in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING) && update.state == ConnectionRequestState.WAITING) return false
        if (old?.state == ConnectionRequestState.CONNECTING && update.state == ConnectionRequestState.ACCEPTED) return false
        if (old?.connectBy != null && update.connectBy != null && old.connectBy != update.connectBy) return false
        update.assignment?.let {
            val initiator = c.side == ConnectionRequestSide.INITIATOR
            if (!initiator && !c.won && op != ConnectionRequestOperation.ACCEPT) return false
            val role = if (initiator) c.role else if (c.role == MediaRole.SEND) MediaRole.RECEIVE else MediaRole.SEND
            if (it.role != role || it.intent != if (initiator) PairingIntent.CREATE else PairingIntent.JOIN) return false
        }
        return true
    }
    private fun safe(call: () -> ConnectionRequestResult) = try { call() }
        catch (_: Exception) { ConnectionRequestResult(error = ConnectionRequestError.NETWORK) }

    /** Replacement Cancel is best-effort. Its failure is exposed on the new snapshot,
     * but never restores the old local context. The old server request may await expiry. */
    internal fun create(role: MediaRole): ConnectionRequestCreateAttempt? = synchronized(lock) {
        val old = context
        if (old?.metadata?.state == ConnectionRequestState.CONNECTED || old?.side == ConnectionRequestSide.RECIPIENT) {
            rejected(); return@synchronized null
        }
        val token = ++generation
        val attempt = ConnectionRequestCreateAttempt()
        currentCreateAttempt = attempt
        val started = elapsedMs()
        context = null; activeOperation = ConnectionRequestOperation.CREATE; publish(null, activeOperation)
        worker.execute {
            val cancellation = if (old?.metadata?.state?.activeSetup == true)
                safe { api.cancel(old.id, checkNotNull(old.initiatorSecret)) } else null
            // A newer request may have superseded this queued operation before it started.
            if (!synchronized(lock) { generation == token }) return@execute
            val result = safe { api.create(role) }
            var abandoned: ConnectionRequestCreated? = null
            synchronized(lock) {
                if (generation != token) abandoned = result.created
                else {
                    activeOperation = null
                    val created = result.created
                    if (created != null && result.error == null && created.metadata.initiatorRole == role) {
                        // Create guarantees serverNow = creation time, expiresAt = creation + TTL.
                        // Anchor BEFORE HTTP/cancel latency; never extend validity on status refresh.
                        val deadline = runCatching {
                            Math.addExact(started, connectionRequestCreateTtlMillis(created.metadata))
                        }.getOrNull()
                        if (deadline == null) {
                            publish(null, error = ConnectionRequestError.INVALID_RESPONSE)
                        } else {
                            context = Context(ConnectionRequestSide.INITIATOR, created.metadata, created.metadata.requestId,
                                role, created.capability, created.initiatorSecret, expiryElapsedMs = deadline, originAttempt = attempt)
                            publish(context, error = cancellation?.error)
                        }
                    } else publish(null, error = result.error ?: ConnectionRequestError.INVALID_RESPONSE)
                }
            }
            // A lost local race must not leave a second known active initiator request.
            abandoned?.let { safe { api.cancel(it.metadata.requestId, it.initiatorSecret) } }
        }
        attempt
    }
    internal fun preview(id: String, capability: String, initiatorRole: MediaRole): ConnectionRequestRecipientContext? = synchronized(lock) {
        if (context != null || mutable.value.operation != null || !ConnectionRequestSecrets.canonical(id, 16) ||
            !ConnectionRequestSecrets.canonical(capability, 16)) { rejected(); return@synchronized null }
        ++generation
        currentCreateAttempt = null
        val recipient = ConnectionRequestRecipientContext()
        context = Context(ConnectionRequestSide.RECIPIENT, null, id, initiatorRole, ConnectionCredential(capability), recipientContext = recipient)
        publish(context)
        recipient
    }
    internal fun recipientAccess(recipient: ConnectionRequestRecipientContext): ConnectionRequestRecipientAccess? = synchronized(lock) {
        context?.takeIf { it.recipientContext === recipient }?.let { ConnectionRequestRecipientAccess(it.won, it.claim != null) }
    }
    internal fun clearRecipient(recipient: ConnectionRequestRecipientContext, release: () -> Unit): Boolean = synchronized(lock) {
        if (context?.recipientContext !== recipient) return@synchronized false
        ++generation; context = null; activeOperation = null; currentCreateAttempt = null; publish(null)
        release(); true
    }
    /** Atomic completion policy: a superseding create cannot slip between validation and release.
     * The callback must be a short synchronous policy update, never I/O. */
    internal fun ifEmptyCurrentCreateAttempt(attempt: ConnectionRequestCreateAttempt, action: () -> Unit) = synchronized(lock) {
        if (currentCreateAttempt === attempt && context == null && activeOperation == null) action()
    }
    /** Narrow, memory-only explicit-share access. Never exposes initiator/claim authority.
     * Validity uses the original boot-relative deadline, never the phone's wall clock. */
    internal fun shareableInitiatorData(role: MediaRole,
        expectedCreateAttempt: ConnectionRequestCreateAttempt? = null): ConnectionRequestShareData? = synchronized(lock) {
        val c = context ?: return@synchronized null
        val m = c.metadata ?: return@synchronized null
        if (activeOperation != null || c.side != ConnectionRequestSide.INITIATOR || c.role != role ||
            m.state != ConnectionRequestState.WAITING || m.expiresAt == null) return@synchronized null
        if (expectedCreateAttempt != null && c.originAttempt !== expectedCreateAttempt) return@synchronized null
        val deadline = c.expiryElapsedMs ?: return@synchronized null
        if (elapsedMs() >= deadline) return@synchronized null
        ConnectionRequestShareData(c.id, c.capability.value, c.role)
    }
    fun refreshStatus() = operation(ConnectionRequestOperation.STATUS, { true }) {
        api.status(it.id, auth(it), if (it.side == ConnectionRequestSide.INITIATOR) ConnectionRequestAuthScope.INITIATOR
            else if (it.won) ConnectionRequestAuthScope.CLAIM else ConnectionRequestAuthScope.CAPABILITY)
    }
    fun accept() = operation(ConnectionRequestOperation.ACCEPT, {
        it.side == ConnectionRequestSide.RECIPIENT &&
            (it.metadata == null || it.metadata?.state == ConnectionRequestState.WAITING ||
                it.metadata?.state in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING))
    }, prepare = { if (it.claim == null) it.claim = claimFactory() }) {
        api.accept(it.id, it.capability, checkNotNull(it.claim))
    }
    fun cancel() = operation(ConnectionRequestOperation.CANCEL, {
        it.side == ConnectionRequestSide.INITIATOR && (it.metadata?.state?.activeSetup == true || it.metadata?.state == ConnectionRequestState.CANCELED)
    }) { api.cancel(it.id, checkNotNull(it.initiatorSecret)) }
    fun withdraw() = operation(ConnectionRequestOperation.WITHDRAW, {
        it.side == ConnectionRequestSide.RECIPIENT && it.won &&
            (it.metadata?.state in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING) ||
                (it.metadata?.state == ConnectionRequestState.FAILED && it.metadata?.failureReason == "recipient_withdrew"))
    }) { api.withdraw(it.id, checkNotNull(it.claim)) }
    fun setReady(ready: Boolean) = operation(ConnectionRequestOperation.READINESS, {
        it.metadata?.state == ConnectionRequestState.ACCEPTED && (it.initiatorSecret != null || it.won)
    }) { api.ready(it.id, auth(it), ready, if (it.side == ConnectionRequestSide.INITIATOR)
        ConnectionRequestAuthScope.INITIATOR else ConnectionRequestAuthScope.CLAIM) }
    fun createSession() = operation(ConnectionRequestOperation.SESSION, {
        it.side == ConnectionRequestSide.INITIATOR && it.metadata?.state in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING)
    }) { api.session(it.id, checkNotNull(it.initiatorSecret)) }
    /** Local-only cleanup; callers must explicitly Cancel/withdraw before leaving live setup. */
    fun clear() = synchronized(lock) { ++generation; currentCreateAttempt = null; context = null; activeOperation = null; publish(null) }
    private fun operation(op: ConnectionRequestOperation, allowed: (Context) -> Boolean,
        prepare: (Context) -> Unit = {}, call: (Context) -> ConnectionRequestResult) = synchronized(lock) {
        val c = context
        if (activeOperation != null) {
            if (op != ConnectionRequestOperation.STATUS)
                mutable.value = mutable.value.copy(error = ConnectionRequestError.OPERATION_IN_PROGRESS)
            return@synchronized
        }
        if (c == null || !allowed(c)) { rejected(); return@synchronized }
        try { prepare(c) } catch (_: Exception) { publish(c, error = ConnectionRequestError.INVALID_RESPONSE); return@synchronized }
        val token = generation
        activeOperation = op
        publish(c, op)
        worker.execute {
            if (!synchronized(lock) { generation == token && context === c }) return@execute
            val result = safe { call(c) }
            synchronized(lock) {
                if (generation != token || context !== c) return@synchronized
                activeOperation = null
                val update = result.metadata
                if (update != null && !validUpdate(c, update, op)) {
                    publish(c, error = ConnectionRequestError.INVALID_RESPONSE); return@synchronized
                }
                if (update != null) {
                    val old = c.metadata
                    // Cancel/withdraw/readiness responses intentionally contain only a small projection.
                    c.metadata = update.copy(initiatorRole = c.role, createdAt = update.createdAt ?: old?.createdAt,
                        expiresAt = update.expiresAt ?: old?.expiresAt, acceptedAt = update.acceptedAt ?: old?.acceptedAt,
                        connectBy = update.connectBy ?: old?.connectBy,
                        assignment = if (update.state.terminal) null else update.assignment ?: old?.assignment)
                    if (op == ConnectionRequestOperation.ACCEPT && result.httpStatus == 200 &&
                        update.state in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING)) c.won = true
                }
                publish(c, error = result.error)
                mutable.value = mutable.value.copy(retryAfterSeconds = result.retryAfterSeconds)
            }
        }
    }
}

/** Lazy process-lifetime owner. No initialization or network call from any Activity in CP5-B1. */
object ConnectionRequests {
    val controller: ConnectionRequestController by lazy { ConnectionRequestController(ConnectionRequestApiClient()) }
}
