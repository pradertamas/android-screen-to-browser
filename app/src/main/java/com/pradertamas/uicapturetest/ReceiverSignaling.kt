package com.pradertamas.uicapturetest

import android.os.Handler
import okhttp3.*
import org.json.JSONObject

/** Existing signaling schema, fixed media receiver role. No remote-token/presence support. */
class ReceiverSignaling(private val http: OkHttpClient, private val main: Handler) : ReceiverWire {
    override fun open(code: String, intent: PairingIntent, events: ReceiverEvents): PairingLink {
        val ws = http.newWebSocket(Request.Builder().url(ReceiverRegistration.url(code, intent)).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { main.post {
                try {
                    require(text.toByteArray(Charsets.UTF_8).size < 64 * 1024)
                    val m = JSONObject(text)
                    when (m.optString("type")) {
                        "registration" -> events.registration(m.optString("status"))
                        "peer-present" -> events.present()
                        "offer" -> events.offer(m.getString("sdp"))
                        "ice" -> {
                            val c = m.getJSONObject("candidate")
                            events.ice(ReceiverIce(if (c.isNull("sdpMid")) null else c.getString("sdpMid"),
                                c.getInt("sdpMLineIndex"), c.getString("candidate")))
                        }
                        "peer-left", "bye" -> events.ended()
                    }
                } catch (_: Exception) { events.ended() }
            } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); main.post(events::ended) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.post(events::ended) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); main.post(events::ended) }
        })
        return object : PairingLink {
            override fun send(text: String) = text.toByteArray(Charsets.UTF_8).size < 64 * 1024 && ws.send(text)
            override fun close() { ws.close(1000, "Receiving ended"); ws.cancel() }
        }
    }
    override fun answer(sdp: String) = JSONObject().put("type", "answer").put("sdp", sdp).toString()
    override fun candidate(value: ReceiverIce) = JSONObject().put("type", "ice").put("candidate", JSONObject()
        .put("candidate", value.candidate).put("sdpMid", value.mid ?: JSONObject.NULL).put("sdpMLineIndex", value.line)).toString()
}
