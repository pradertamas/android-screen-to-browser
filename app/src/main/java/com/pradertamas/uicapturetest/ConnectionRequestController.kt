package com.pradertamas.uicapturetest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Local identity only; never a wire credential or public UI identifier. */
internal class ConnectionRequestCreateAttempt internal constructor() {
    override fun toString() = "ConnectionRequestCreateAttempt[redacted]"
}
internal class ConnectionRequestRecipientFlow {
    override fun toString() = "ConnectionRequestRecipientFlow[redacted]"
}
internal data class ConnectionRequestRecipientAccess(val won: Boolean, val acceptAttempted: Boolean)
/** Credential-free internal scheduling projection. Attempt is opaque local context identity. */
internal class InitiatorMonitorSnapshot(val flow: ConnectionRequestCreateAttempt, val role: MediaRole,
    val active: Boolean, val operation: ConnectionRequestOperation?, val retryAfterSeconds: Long?)
internal class ConnectionRequestSetupSnapshot(
    val flow: Any, val side: ConnectionRequestSide, val initiatorRole: MediaRole,
    val state: ConnectionRequestState?, val won: Boolean, val operation: ConnectionRequestOperation?,
    val initiatorReady: Boolean?, val recipientReady: Boolean?, val assignment: Boolean,
    val completion: Long, val completedOperation: ConnectionRequestOperation?,
    val error: ConnectionRequestError?, val retryAfterSeconds: Long?,
    val completions: List<ConnectionRequestSetupCompletion>
)
internal class ConnectionRequestSetupCompletion(val revision: Long, val operation: ConnectionRequestOperation,
    val error: ConnectionRequestError?, val retryAfterSeconds: Long?, val elapsedMs: Long)

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
        val recipientFlow: ConnectionRequestRecipientFlow? = null) {
        var completion = 0L
        var mediaOwner: RequestMediaOwner? = null
        var exitRequested = false
        var completedOperation: ConnectionRequestOperation? = null
        var completionError: ConnectionRequestError? = null
        var completionRetry: Long? = null
        val setupCompletions = mutableMapOf<ConnectionRequestOperation, ConnectionRequestSetupCompletion>()
    }
    private val lock = Any()
    private var context: Context? = null
    private var generation = 0L
    private var currentCreateAttempt: ConnectionRequestCreateAttempt? = null
    private var pendingCreateAttempt: ConnectionRequestCreateAttempt? = null
    private var activeOperation: ConnectionRequestOperation? = null
    private val mutable = MutableStateFlow(ConnectionRequestUiState())
    val state = mutable.asStateFlow()
    private var publishedContext: Context? = null
    private val ownershipRevision = MutableStateFlow(0L)
    internal val ownershipChanges = ownershipRevision.asStateFlow()
    private fun rejected() { mutable.value = mutable.value.copy(error = ConnectionRequestError.INVALID_OPERATION) }
    private fun publish(c: Context?, operation: ConnectionRequestOperation? = null, error: ConnectionRequestError? = null) {
        // Identity changes must wake scoped UI even when public snapshots conflate to equal values.
        if (publishedContext !== c) { publishedContext = c; ownershipRevision.value++ }
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
        if (old?.metadata?.state == ConnectionRequestState.CONNECTED || old?.side == ConnectionRequestSide.RECIPIENT ||
            old?.mediaOwner?.phase == RequestMediaPhase.STARTED) {
            rejected(); return@synchronized null
        }
        val token = ++generation
        val attempt = ConnectionRequestCreateAttempt()
        currentCreateAttempt = attempt
        pendingCreateAttempt = attempt
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
                    pendingCreateAttempt = null
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
    internal fun preview(id: String, capability: String, initiatorRole: MediaRole): ConnectionRequestRecipientFlow? = synchronized(lock) {
        if (context != null || mutable.value.operation != null || !ConnectionRequestSecrets.canonical(id, 16) ||
            !ConnectionRequestSecrets.canonical(capability, 16)) { rejected(); return@synchronized null }
        ++generation
        currentCreateAttempt = null
        pendingCreateAttempt = null
        val recipient = ConnectionRequestRecipientFlow()
        context = Context(ConnectionRequestSide.RECIPIENT, null, id, initiatorRole, ConnectionCredential(capability), recipientFlow = recipient)
        publish(context)
        recipient
    }
    internal fun recipientAccess(recipient: ConnectionRequestRecipientFlow): ConnectionRequestRecipientAccess? = synchronized(lock) {
        context?.takeIf { it.recipientFlow === recipient }?.let { ConnectionRequestRecipientAccess(it.won, it.claim != null) }
    }
    internal fun clearRecipient(recipient: ConnectionRequestRecipientFlow, release: () -> Unit): Boolean = synchronized(lock) {
        if (context?.recipientFlow !== recipient) return@synchronized false
        ++generation; context = null; activeOperation = null; currentCreateAttempt = null; pendingCreateAttempt = null; publish(null)
        release(); true
    }
    /** Atomic completion policy: a superseding create cannot slip between validation and release.
     * The callback must be a short synchronous policy update, never I/O. */
    internal fun ifEmptyCurrentCreateAttempt(attempt: ConnectionRequestCreateAttempt, action: () -> Unit) = synchronized(lock) {
        if (currentCreateAttempt === attempt && context == null && activeOperation == null) action()
    }
    /** Pre-preview rollback only. Completed attempt lineage is not pending ownership.
     * Validation and short, non-I/O policy callback share the controller lock. */
    internal fun ifNoRequestOwnership(action: () -> Unit): Boolean = synchronized(lock) {
        if (context != null || activeOperation != null || pendingCreateAttempt != null) return@synchronized false
        action(); true
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
    fun refreshStatus() = refreshStatusFor(null)
    internal fun protectedSignalingBootstrap(flow: ConnectionRequestCreateAttempt): ProtectedSignalingBootstrap? = protectedBootstrap(flow)
    internal fun protectedSignalingBootstrap(flow: ConnectionRequestRecipientFlow): ProtectedSignalingBootstrap? = protectedBootstrap(flow)
    /** Read-only, exact-flow authority export. No socket creation or public state changes here. */
    private fun protectedBootstrap(flow: Any): ProtectedSignalingBootstrap? = synchronized(lock) {
        val c = context ?: return@synchronized null
        val initiator = c.side == ConnectionRequestSide.INITIATOR
        if ((if (initiator) c.originAttempt else c.recipientFlow) !== flow ||
            c.exitRequested || c.metadata?.state != ConnectionRequestState.CONNECTING || (!initiator && (!c.won || c.claim == null))) return@synchronized null
        val assignment = c.metadata?.assignment ?: return@synchronized null
        val role = if (initiator) c.role else if (c.role == MediaRole.SEND) MediaRole.RECEIVE else MediaRole.SEND
        if (assignment.role != role || assignment.intent != (if (initiator) PairingIntent.CREATE else PairingIntent.JOIN) ||
            PairingCode.normalize(assignment.code) != assignment.code) return@synchronized null
        runCatching { ProtectedSignalingBootstrap.create(flow, assignment.code, role, assignment.intent,
            c.id, if (initiator) checkNotNull(c.initiatorSecret) else checkNotNull(c.claim)) }.getOrNull()
    }
    internal fun setupSnapshot(): ConnectionRequestSetupSnapshot? = synchronized(lock) {
        val c = context ?: return@synchronized null
        val flow = c.originAttempt ?: c.recipientFlow ?: return@synchronized null
        ConnectionRequestSetupSnapshot(flow, c.side, c.role, c.metadata?.state, c.won, activeOperation,
            c.metadata?.initiatorReady, c.metadata?.recipientReady, c.metadata?.assignment != null,
            c.completion, c.completedOperation, c.completionError, c.completionRetry, c.setupCompletions.values.sortedBy { it.revision })
    }
    private fun matches(c: Context?, flow: Any) = c != null &&
        (if (c.side == ConnectionRequestSide.INITIATOR) c.originAttempt else c.recipientFlow) === flow
    /** Reservation contains no bearer export. A fresh bootstrap is obtained only at redemption. */
    internal fun reserveMedia(flow: Any, role: MediaRole): RequestMediaOwner? = synchronized(lock) {
        val c = context ?: return@synchronized null
        val localRole = if (c.side == ConnectionRequestSide.INITIATOR) c.role else ConnectionRequestSetupCoordinator.opposite(c.role)
        if (!matches(c, flow) || c.exitRequested || c.mediaOwner != null || activeOperation != null ||
            c.metadata?.state != ConnectionRequestState.CONNECTING || c.metadata?.assignment?.role != role || localRole != role ||
            (c.side == ConnectionRequestSide.RECIPIENT && !c.won)) return@synchronized null
        RequestMediaOwner(flow, role).also { c.mediaOwner = it }
    }
    internal fun redeemMedia(owner: RequestMediaOwner): ProtectedSignalingBootstrap? = synchronized(lock) {
        val c = context
        if (!matches(c, owner.flow) || c?.mediaOwner !== owner || owner.phase != RequestMediaPhase.RESERVED) return@synchronized null
        val bootstrap = protectedBootstrap(owner.flow) ?: return@synchronized null
        owner.phase = RequestMediaPhase.STARTED
        bootstrap
    }
    internal fun mediaDisposed(owner: RequestMediaOwner) = synchronized(lock) {
        owner.phase = RequestMediaPhase.DISPOSED
        // A failed/stopped media owner must never be automatically restarted for this flow.
        if (matches(context, owner.flow) && context?.mediaOwner === owner) context?.exitRequested = true
    }
    internal fun mediaPhase(owner: RequestMediaOwner): RequestMediaPhase = synchronized(lock) { owner.phase }
    internal fun mediaPhaseForFlow(flow: Any): RequestMediaPhase? = synchronized(lock) {
        if (matches(context, flow)) context?.mediaOwner?.phase else null
    }
    /** Local teardown is performed by the media owner BEFORE this HTTP admission. No callbacks under lock. */
    internal fun requestMediaExit(flow: Any): Boolean = synchronized(lock) {
        val c = context ?: return@synchronized false
        if (!matches(c, flow) || (c.side == ConnectionRequestSide.RECIPIENT && !c.won) ||
            c.mediaOwner?.phase == RequestMediaPhase.STARTED) return@synchronized false
        c.exitRequested = true
        if (activeOperation != null) return@synchronized false
        when (flow) {
            is ConnectionRequestCreateAttempt -> cancelInitiator(flow)
            is ConnectionRequestRecipientFlow -> {
                if (!c.won || c.metadata?.state !in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING)) false
                else { withdraw(flow); true }
            }
            else -> false
        }
    }
    internal fun cancelInitiator(flow: ConnectionRequestCreateAttempt): Boolean = synchronized(lock) {
        val c = context
        if (!matches(c, flow) || c?.side != ConnectionRequestSide.INITIATOR || activeOperation != null ||
            c.mediaOwner?.phase == RequestMediaPhase.STARTED || c.metadata?.state?.activeSetup != true) return@synchronized false
        c.exitRequested = true
        operation(ConnectionRequestOperation.CANCEL, { true }) { api.cancel(it.id, checkNotNull(it.initiatorSecret)) }
        true
    }
    /** Explicit reconciliation after a conflict/lost exit response, including server CONNECTED. */
    internal fun reconcileMediaExit(flow: Any): Boolean = synchronized(lock) {
        val c = context
        if (!matches(c, flow) || activeOperation != null || c?.exitRequested != true) return@synchronized false
        operation(ConnectionRequestOperation.STATUS, { true }) {
            api.status(it.id, auth(it), if (it.side == ConnectionRequestSide.INITIATOR)
                ConnectionRequestAuthScope.INITIATOR else ConnectionRequestAuthScope.CLAIM)
        }
        true
    }
    internal fun clearDisposedMedia(flow: Any): Boolean = synchronized(lock) {
        val c = context
        if (!matches(c, flow) || c?.mediaOwner?.phase != RequestMediaPhase.DISPOSED ||
            activeOperation != null || (c.metadata?.state?.terminal != true && c.metadata?.state != ConnectionRequestState.CONNECTED)) return@synchronized false
        clear(); true // Gate release is deliberately left to B4E.
    }
    /** Main-thread synchronous role policy only; never dispatch/wait or HTTP under this lock. */
    internal fun prepareRecipientSetupRole(flow: ConnectionRequestRecipientFlow, action: () -> Boolean): Boolean = synchronized(lock) {
        val c = context
        if (c?.recipientFlow !== flow || c.exitRequested || !c.won || c.metadata?.state != ConnectionRequestState.ACCEPTED) false else action()
    }
    /** Exact local flow admission and operation reservation share the authority lock. */
    internal fun setInitiatorReady(flow: ConnectionRequestCreateAttempt): Boolean = setupOperation(flow, ConnectionRequestOperation.READINESS)
    internal fun setRecipientReady(flow: ConnectionRequestRecipientFlow): Boolean = setupOperation(flow, ConnectionRequestOperation.READINESS)
    internal fun refreshRecipientSetupStatus(flow: ConnectionRequestRecipientFlow): Boolean = setupOperation(flow, ConnectionRequestOperation.STATUS)
    internal fun createInitiatorSession(flow: ConnectionRequestCreateAttempt, freshStatus: Long): Boolean =
        setupOperation(flow, ConnectionRequestOperation.SESSION, freshStatus)
    private fun setupOperation(flow: Any, op: ConnectionRequestOperation, freshStatus: Long? = null): Boolean = synchronized(lock) {
        val c = context ?: return@synchronized false
        val initiator = c.side == ConnectionRequestSide.INITIATOR
        if ((if (initiator) c.originAttempt else c.recipientFlow) !== flow || c.exitRequested || activeOperation != null ||
            (!initiator && (!c.won || c.claim == null))) return@synchronized false
        val state = c.metadata?.state
        if (op == ConnectionRequestOperation.SESSION) {
            if (!initiator || state !in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING) ||
                freshStatus != c.completion || c.completedOperation != ConnectionRequestOperation.STATUS || c.completionError != null ||
                (state == ConnectionRequestState.ACCEPTED && (c.metadata?.initiatorReady != true || c.metadata?.recipientReady != true))) return@synchronized false
        } else if (state != ConnectionRequestState.ACCEPTED) return@synchronized false
        operation(op, { true }) {
            when (op) {
                ConnectionRequestOperation.READINESS -> api.ready(it.id, auth(it), true,
                    if (initiator) ConnectionRequestAuthScope.INITIATOR else ConnectionRequestAuthScope.CLAIM)
                ConnectionRequestOperation.SESSION -> api.session(it.id, checkNotNull(it.initiatorSecret))
                else -> api.status(it.id, checkNotNull(it.claim), ConnectionRequestAuthScope.CLAIM)
            }
        }
        true
    }
    internal fun initiatorMonitorSnapshot(): InitiatorMonitorSnapshot? = synchronized(lock) {
        val c = context ?: return@synchronized null
        if (c.side != ConnectionRequestSide.INITIATOR) return@synchronized null
        val flow = c.originAttempt ?: return@synchronized null
        InitiatorMonitorSnapshot(flow, c.role, c.metadata?.state?.activeSetup == true, activeOperation, mutable.value.retryAfterSeconds)
    }
    /** Matching side/role/flow and operation admission are one authority boundary. */
    internal fun refreshInitiatorStatus(expectedRole: MediaRole, expectedFlow: ConnectionRequestCreateAttempt? = null): Boolean = synchronized(lock) {
        val c = context ?: return@synchronized false
        if (c.side != ConnectionRequestSide.INITIATOR || c.role != expectedRole ||
            (expectedFlow != null && c.originAttempt !== expectedFlow) || c.metadata?.state?.activeSetup != true ||
            activeOperation != null) return@synchronized false
        operation(ConnectionRequestOperation.STATUS, { true }) {
            api.status(it.id, checkNotNull(it.initiatorSecret), ConnectionRequestAuthScope.INITIATOR)
        }
        true
    }
    internal fun refreshStatus(flow: ConnectionRequestRecipientFlow) = refreshStatusFor(flow)
    private fun refreshStatusFor(flow: ConnectionRequestRecipientFlow?) = operation(ConnectionRequestOperation.STATUS, { true }, expectedFlow = flow) {
        api.status(it.id, auth(it), if (it.side == ConnectionRequestSide.INITIATOR) ConnectionRequestAuthScope.INITIATOR
            else if (it.won) ConnectionRequestAuthScope.CLAIM else ConnectionRequestAuthScope.CAPABILITY)
    }
    fun accept() = acceptFor(null)
    internal fun accept(flow: ConnectionRequestRecipientFlow) = acceptFor(flow)
    private fun acceptFor(flow: ConnectionRequestRecipientFlow?) = operation(ConnectionRequestOperation.ACCEPT, {
        it.side == ConnectionRequestSide.RECIPIENT &&
            (it.metadata == null || it.metadata?.state == ConnectionRequestState.WAITING ||
                it.metadata?.state in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING))
    }, expectedFlow = flow, prepare = { if (it.claim == null) it.claim = claimFactory() }) {
        api.accept(it.id, it.capability, checkNotNull(it.claim))
    }
    fun cancel() = operation(ConnectionRequestOperation.CANCEL, {
        it.side == ConnectionRequestSide.INITIATOR && (it.metadata?.state?.activeSetup == true || it.metadata?.state == ConnectionRequestState.CANCELED)
    }) { api.cancel(it.id, checkNotNull(it.initiatorSecret)) }
    fun withdraw() = withdrawFor(null)
    internal fun withdraw(flow: ConnectionRequestRecipientFlow) = withdrawFor(flow)
    private fun withdrawFor(flow: ConnectionRequestRecipientFlow?) = operation(ConnectionRequestOperation.WITHDRAW, {
        it.side == ConnectionRequestSide.RECIPIENT && it.won &&
            (it.metadata?.state in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING) ||
                (it.metadata?.state == ConnectionRequestState.FAILED && it.metadata?.failureReason == "recipient_withdrew"))
    }, expectedFlow = flow) { api.withdraw(it.id, checkNotNull(it.claim)) }
    fun setReady(ready: Boolean) = operation(ConnectionRequestOperation.READINESS, {
        it.metadata?.state == ConnectionRequestState.ACCEPTED && (it.initiatorSecret != null || it.won)
    }) { api.ready(it.id, auth(it), ready, if (it.side == ConnectionRequestSide.INITIATOR)
        ConnectionRequestAuthScope.INITIATOR else ConnectionRequestAuthScope.CLAIM) }
    fun createSession() = operation(ConnectionRequestOperation.SESSION, {
        it.side == ConnectionRequestSide.INITIATOR && it.metadata?.state in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING)
    }) { api.session(it.id, checkNotNull(it.initiatorSecret)) }
    /** Local-only cleanup; callers must explicitly Cancel/withdraw before leaving live setup. */
    fun clear() = synchronized(lock) { ++generation; currentCreateAttempt = null; pendingCreateAttempt = null; context = null; activeOperation = null; publish(null) }
    private fun operation(op: ConnectionRequestOperation, allowed: (Context) -> Boolean,
        expectedFlow: ConnectionRequestRecipientFlow? = null,
        prepare: (Context) -> Unit = {}, call: (Context) -> ConnectionRequestResult) = synchronized(lock) {
        val c = context
        // Check scope BEFORE touching operation/error/claim. A stale caller is a strict no-op.
        if (expectedFlow != null && (c?.side != ConnectionRequestSide.RECIPIENT || c.recipientFlow !== expectedFlow)) return@synchronized
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
                c.completion++
                c.completedOperation = op
                c.completionError = result.error
                c.completionRetry = result.retryAfterSeconds
                fun rememberCompletion() {
                    if (op in setOf(ConnectionRequestOperation.STATUS, ConnectionRequestOperation.READINESS, ConnectionRequestOperation.SESSION))
                        c.setupCompletions[op] = ConnectionRequestSetupCompletion(c.completion, op, c.completionError, result.retryAfterSeconds, elapsedMs())
                }
                val update = result.metadata
                if (update != null && !validUpdate(c, update, op)) {
                    c.completionError = ConnectionRequestError.INVALID_RESPONSE
                    rememberCompletion()
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
                rememberCompletion()
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
