package com.pradertamas.uicapturetest

import org.json.JSONObject

internal enum class SenderStartMode { LEGACY, REQUEST }
internal interface SenderSignalingEvents {
    fun registered()
    fun peerPresent()
    fun payload(text: String)
    fun ended()
}
internal interface SenderSignalingChannel {
    fun open(events: SenderSignalingEvents)
    fun send(text: String): Boolean
    fun close()
    fun streaming() {}
    fun disposed() {}
}
internal interface LegacySenderHost {
    fun attach(epoch: Long, payload: (String) -> Unit, ended: () -> Unit, result: (Boolean) -> Unit)
    fun send(epoch: Long, text: String, result: (Boolean) -> Unit)
    fun streaming(epoch: Long)
    fun disposed(epoch: Long)
}
private object ProcessLegacySenderHost : LegacySenderHost {
    override fun attach(epoch: Long, payload: (String) -> Unit, ended: () -> Unit, result: (Boolean) -> Unit) {
        PairingHost.post { result(it.attach(epoch, payload, ended)) }
    }
    override fun send(epoch: Long, text: String, result: (Boolean) -> Unit) { PairingHost.post { result(it.send(epoch, text)) } }
    override fun streaming(epoch: Long) { PairingHost.post { it.streaming(epoch) } }
    override fun disposed(epoch: Long) { PairingHost.post { it.mediaStopped(epoch) } }
}
/** Wraps the existing pre-consent PairingHost registration, including its synthetic presence. */
internal class LegacySenderSignalingChannel(private val epoch: Long,
    private val dispatch: (() -> Unit) -> Unit,
    private val host: LegacySenderHost = ProcessLegacySenderHost) : SenderSignalingChannel {
    @Volatile private var closed = false
    private var events: SenderSignalingEvents? = null
    override fun open(events: SenderSignalingEvents) {
        if (closed) return
        this.events = events
        host.attach(epoch, { text -> dispatch { if (!closed) events.payload(text) } },
            { dispatch { if (!closed) events.ended() } }, { attached -> dispatch { if (!closed) {
                if (attached) { events.registered(); if (!closed) events.peerPresent() } else events.ended()
            } } })
    }
    override fun send(text: String): Boolean {
        if (closed) return false
        host.send(epoch, text) { sent -> if (!sent) dispatch { if (!closed) events?.ended() } }
        return true // Legacy send is asynchronously admitted by the existing main-thread host.
    }
    override fun close() { closed = true }
    override fun streaming() { host.streaming(epoch) }
    override fun disposed() { host.disposed(epoch) }
    companion object {
        fun current(epoch: Long) = PairingHost.state.value.let { it.epoch == epoch && it.phase == PairingPhase.CONNECTING }
    }
}

/** Real protected presence only. Owner dispatch enqueues onto its serialized control thread. */
internal class ProtectedSenderSignalingChannel(private val bootstrap: ProtectedSignalingBootstrap,
    private val factory: ProtectedSocketFactory = OkHttpProtectedSocketFactory(),
    private val dispatch: (() -> Unit) -> Unit) : SenderSignalingChannel {
    private var transport: ProtectedRequestSignaling? = null
    private var opened = false
    private var closed = false
    override fun open(events: SenderSignalingEvents) {
        check(!opened && !closed && bootstrap.role == MediaRole.SEND); opened = true
        fun end() { if (!closed) { closed = true; transport?.close(); events.ended() } }
        transport = ProtectedRequestSignaling.open(bootstrap, factory, object : ProtectedSignalingEvents {
            override fun registered() { if (!closed) events.registered() }
            override fun peerPresent() { if (!closed) events.peerPresent() }
            override fun payload(text: String) {
                if (closed) return
                when (JSONObject(text).getString("type")) {
                    "answer", "ice" -> events.payload(text)
                    else -> end()
                }
            }
            override fun failed() = end()
            override fun closed() = end()
        }, dispatch)
        if (closed) transport?.close()
    }
    override fun send(text: String) = !closed && transport?.send(text) == true
    override fun close() { closed = true; transport?.close() }
}
