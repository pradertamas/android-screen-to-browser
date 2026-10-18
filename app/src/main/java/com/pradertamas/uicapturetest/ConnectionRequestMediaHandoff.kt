package com.pradertamas.uicapturetest

import java.util.UUID

internal enum class RequestMediaPhase { RESERVED, STARTED, DISPOSED }
internal class RequestMediaHandoffId internal constructor(internal val value: String) {
    override fun toString() = "RequestMediaHandoffId[redacted]"
}
/** Local authority selector and teardown acknowledgement, never a wire credential. */
internal class RequestMediaOwner(val flow: Any, val role: MediaRole) {
    internal var phase = RequestMediaPhase.RESERVED
    override fun toString() = "RequestMediaOwner[redacted]"
}
internal class RequestMediaRedemption(val owner: RequestMediaOwner, val bootstrap: ProtectedSignalingBootstrap) {
    override fun toString() = "RequestMediaRedemption[redacted]"
}
/** Process-only, one-shot mailbox. Only its random key may cross a service Intent boundary.
 * No precomputed bearer bootstrap is cached. The controller revalidates exact flow at redemption.
 * Mailbox lock is never held while entering the controller authority lock. */
internal class ConnectionRequestMediaHandoff(private val controller: ConnectionRequestController) {
    private val entries = mutableMapOf<String, RequestMediaOwner>()
    fun reserve(flow: Any, role: MediaRole): RequestMediaHandoffId? {
        val owner = controller.reserveMedia(flow, role) ?: return null
        return RequestMediaHandoffId(UUID.randomUUID().toString().also { synchronized(entries) { entries[it] = owner } })
    }
    fun consume(id: RequestMediaHandoffId, role: MediaRole) = consume(id.value, role)
    fun consume(id: String, role: MediaRole): RequestMediaRedemption? {
        val owner = synchronized(entries) { entries.remove(id) } ?: return null
        val bootstrap = if (owner.role == role) controller.redeemMedia(owner) else null
        if (bootstrap == null) { controller.mediaDisposed(owner); return null }
        return RequestMediaRedemption(owner, bootstrap)
    }
    fun abandon(id: String) { synchronized(entries) { entries.remove(id) }?.let(controller::abandonReservedMedia) }
    fun disposed(owner: RequestMediaOwner) = controller.mediaDisposed(owner)
    companion object {
        val process by lazy { ConnectionRequestMediaHandoff(ConnectionRequests.controller) }
    }
}

/** Service-start cleanup authority is captured before consent/role/session validation.
 * All subsequent calls belong to the service control thread. Reserved and redeemed
 * resources have distinct cleanup paths; legacy retirement remains at service disposal.
 * No credentials, Android objects, HTTP or media operations are stored here. */
internal class SenderStartCleanup(private val mode: SenderStartMode?, handoff: String?, epoch: Long,
    private val abandon: (String) -> Unit, private val disposed: (RequestMediaOwner) -> Unit,
    retireLegacy: (Long) -> Unit) {
    var handoffId: String? = if (mode == SenderStartMode.REQUEST) handoff else null; private set
    private var owner: RequestMediaOwner? = null
    private var requestDone = false
    private var legacyDone = false
    private var legacyDisposal: () -> Unit = { retireLegacy(epoch) }
    fun redeemed(value: RequestMediaOwner) {
        check(mode == SenderStartMode.REQUEST && !requestDone && owner == null)
        owner = value; handoffId = null
    }
    fun legacyChannel(channel: SenderSignalingChannel) {
        check(mode == SenderStartMode.LEGACY && !legacyDone)
        legacyDisposal = channel::disposed
    }
    fun disposeRequest() {
        if (mode != SenderStartMode.REQUEST || requestDone) return
        requestDone = true
        val started = owner; owner = null
        val reserved = handoffId; handoffId = null
        if (started != null) disposed(started) else reserved?.let(abandon)
    }
    fun retireLegacy() {
        if (mode != SenderStartMode.LEGACY || legacyDone) return
        legacyDone = true
        legacyDisposal()
    }
    override fun toString() = "SenderStartCleanup[redacted]"
}

/** Shared by the service and deterministic tests: capture must succeed before any authority export. */
internal fun <T> captureBeforeRequestSignaling(capture: () -> Unit, redeem: () -> T?, open: (T) -> Unit) {
    capture()
    open(checkNotNull(redeem()) { "Request media unavailable" })
}
