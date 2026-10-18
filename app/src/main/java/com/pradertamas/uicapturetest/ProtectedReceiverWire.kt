package com.pradertamas.uicapturetest

import org.json.JSONObject

/** Owner dispatch MUST enqueue, so Engine.socket is installed before any callback is delivered. */
internal class ProtectedReceiverWire(private val redeem: () -> RequestMediaRedemption?,
    private val factory: ProtectedSocketFactory = OkHttpProtectedSocketFactory(),
    private val dispatch: (() -> Unit) -> Unit) : ReceiverWire {
    override fun open(code: String, intent: PairingIntent, events: ReceiverEvents): PairingLink {
        val redemption = checkNotNull(redeem())
        check(redemption.bootstrap.role == MediaRole.RECEIVE && redemption.bootstrap.intent == intent)
        var transport: ProtectedRequestSignaling? = null
        var stopped = false
        var ended = false
        fun end() { if (!stopped && !ended) { ended = true; transport?.close(); events.ended() } }
        transport = ProtectedRequestSignaling.open(redemption.bootstrap, factory, object : ProtectedSignalingEvents {
            override fun registered() { if (!stopped) events.registration("ok") }
            override fun peerPresent() { if (!stopped) events.present() }
            override fun payload(text: String) {
                if (stopped || ended) return
                try {
                    val m = JSONObject(text)
                    when (m.getString("type")) {
                        "offer" -> events.offer(m.getString("sdp"))
                        "ice" -> m.getJSONObject("candidate").let { c -> events.ice(ReceiverIce(
                            if (c.isNull("sdpMid")) null else c.getString("sdpMid"),
                            c.getInt("sdpMLineIndex"), c.getString("candidate"))) }
                        else -> end() // answer is never valid for this answerer.
                    }
                } catch (_: Exception) { end() }
            }
            override fun failed() = end()
            override fun closed() = end()
        }, dispatch)
        if (ended) transport.close()
        return object : PairingLink {
            override fun send(text: String) = !stopped && !ended && transport.send(text)
            override fun close() { stopped = true; transport.close() }
        }
    }
    override fun answer(sdp: String) = JSONObject().put("type", "answer").put("sdp", sdp).toString()
    override fun candidate(value: ReceiverIce) = JSONObject().put("type", "ice").put("candidate", JSONObject()
        .put("candidate", value.candidate).put("sdpMid", value.mid ?: JSONObject.NULL).put("sdpMLineIndex", value.line)).toString()
}

internal class ReceiverWireRouter(private val legacy: ReceiverWire) : ReceiverWire {
    var request: ReceiverWire? = null
    override fun open(code: String, intent: PairingIntent, events: ReceiverEvents) = (request ?: legacy).open(code, intent, events)
    override fun answer(sdp: String) = (request ?: legacy).answer(sdp)
    override fun candidate(value: ReceiverIce) = (request ?: legacy).candidate(value)
}
