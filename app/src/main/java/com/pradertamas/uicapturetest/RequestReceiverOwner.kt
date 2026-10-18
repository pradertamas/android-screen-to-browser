package com.pradertamas.uicapturetest

/** Retained alongside Engine by ReceiverViewModel, independent of any renderer/Activity. */
internal class RequestReceiverOwner(private val engine: ReceiverEngine, private val wire: ReceiverWireRouter,
    private val handoffs: ConnectionRequestMediaHandoff, private val dispatch: (() -> Unit) -> Unit,
    private val factory: ProtectedSocketFactory = OkHttpProtectedSocketFactory()) {
    private var owner: RequestMediaOwner? = null
    val active get() = owner != null
    fun start(handoff: RequestMediaHandoffId) = start(handoff.value)
    fun start(handoff: String): Boolean {
        if (active || engine.home.phase !in setOf(ReceiverPhase.PAIRING, ReceiverPhase.ENDED, ReceiverPhase.ERROR)) return false
        engine.stop()
        val redemption = handoffs.consume(handoff, MediaRole.RECEIVE) ?: return false
        owner = redemption.owner
        var fresh: RequestMediaRedemption? = redemption
        wire.request = ProtectedReceiverWire({ fresh.also { fresh = null } }, factory, dispatch)
        engine.startRequest(redemption.bootstrap.intent) {
            handoffs.disposed(redemption.owner)
            owner = null; wire.request = null
        }
        return active
    }
    fun stop(flow: Any): Boolean {
        if (owner?.flow !== flow) return false
        engine.stop() // Socket/media disposal, then acknowledgement; no HTTP or legacy restart.
        return true
    }
}
