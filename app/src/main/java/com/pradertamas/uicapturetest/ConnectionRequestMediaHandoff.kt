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
    fun abandon(id: String) { synchronized(entries) { entries.remove(id) }?.let(controller::mediaDisposed) }
    fun disposed(owner: RequestMediaOwner) = controller.mediaDisposed(owner)
    companion object {
        val process by lazy { ConnectionRequestMediaHandoff(ConnectionRequests.controller) }
    }
}

/** Shared by the service and deterministic tests: capture must succeed before any authority export. */
internal fun <T> captureBeforeRequestSignaling(capture: () -> Unit, redeem: () -> T?, open: (T) -> Unit) {
    capture()
    open(checkNotNull(redeem()) { "Request media unavailable" })
}
