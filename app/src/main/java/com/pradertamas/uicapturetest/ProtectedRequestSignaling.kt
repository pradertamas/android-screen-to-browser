package com.pradertamas.uicapturetest

import java.net.URI
import java.util.ArrayDeque
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

internal object ProtectedSignalingUrl {
    fun build(code: String, role: MediaRole, intent: PairingIntent, relay: String = WebRtcConfig.RELAY): String {
        require(PairingCode.normalize(code) == code)
        val base = URI(relay)
        require(base.scheme == "wss" && !base.host.isNullOrEmpty() && base.rawPath == "/ws" &&
            base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null)
        return URI(base.scheme, null, base.host, base.port, "/ws",
            "session=$code&role=${role.signalingRole}&intent=${intent.wire}", null).toASCIIString()
    }
}

/** Bearer-sensitive, short-lived export. Never a UI model or a saved authority. */
internal class ProtectedSignalingBootstrap private constructor(
    val flow: Any, val url: String, val role: MediaRole, val intent: PairingIntent,
    private var auth: String?
) {
    @Synchronized fun consumeAuthFrame(): String? = auth.also { auth = null }
    override fun toString() = "ProtectedSignalingBootstrap[redacted]"
    companion object {
        fun create(flow: Any, code: String, role: MediaRole, intent: PairingIntent,
            requestId: String, secret: ConnectionCredential): ProtectedSignalingBootstrap {
            require(ConnectionRequestSecrets.canonical(requestId, 16) && ConnectionRequestSecrets.canonical(secret.value, 32))
            val frame = JSONObject().put("type", "request-auth").put("requestId", requestId).put("secret", secret.value).toString()
            require(frame.toByteArray(Charsets.UTF_8).size <= 512)
            return ProtectedSignalingBootstrap(flow, ProtectedSignalingUrl.build(code, role, intent), role, intent, frame)
        }
    }
}

internal interface ProtectedSocket {
    fun send(text: String): Boolean
    fun close(code: Int)
    fun cancel()
}
internal interface ProtectedSocketListener {
    fun opened(socket: ProtectedSocket)
    fun text(text: String)
    fun binary()
    fun closed(code: Int)
    fun failed()
}
internal fun interface ProtectedSocketFactory {
    fun open(url: String, listener: ProtectedSocketListener): ProtectedSocket
}
internal interface ProtectedSignalingEvents {
    fun registered()
    fun peerPresent()
    fun payload(text: String)
    fun failed()
    fun closed()
}

/** Explicit open only. No singleton observer, automatic retry or legacy downgrade.
 * Socket calls are nonblocking; ordered application events drain outside the state lock. */
internal class ProtectedRequestSignaling private constructor(
    private var bootstrap: ProtectedSignalingBootstrap?,
    private val events: ProtectedSignalingEvents,
    private val dispatch: (() -> Unit) -> Unit
) : ProtectedSocketListener {
    private enum class Phase { OPENING, AUTH_SENT, REGISTERED, CLOSED, FAILED }
    private val lock = Any()
    private var phase = Phase.OPENING
    private var socket: ProtectedSocket? = null
    private val role = checkNotNull(bootstrap).role
    private val intent = checkNotNull(bootstrap).intent
    private var present = false
    private val pending = ArrayDeque<() -> Unit>()
    private var draining = false
    private val ended get() = phase == Phase.CLOSED || phase == Phase.FAILED
    private fun change(action: () -> Unit) {
        val start = synchronized(lock) {
            action()
            if (!draining && pending.isNotEmpty()) { draining = true; true } else false
        }
        if (start) dispatch { drain() }
    }
    private fun drain() {
        while (true) {
            val event = synchronized(lock) {
                if (pending.isEmpty()) { draining = false; null } else pending.removeFirst()
            } ?: return
            try { event() } catch (_: Exception) { /* Consumer errors never expose socket/bearer diagnostics. */ }
        }
    }
    private fun fail() {
        if (ended) return
        phase = Phase.FAILED; bootstrap = null; pending.clear()
        runCatching { socket?.close(1008); socket?.cancel() }
        pending.add { events.failed() }
    }
    override fun opened(socket: ProtectedSocket) = change {
        if (ended) { socket.close(if (phase == Phase.CLOSED) 1000 else 1008); socket.cancel(); return@change }
        if (phase != Phase.OPENING) { fail(); return@change }
        this.socket = socket
        val auth = bootstrap?.consumeAuthFrame(); bootstrap = null
        if (auth == null || auth.toByteArray(Charsets.UTF_8).size > 512) { fail(); return@change }
        phase = Phase.AUTH_SENT // Reserve before send, including synchronous fake callbacks.
        if (!runCatching { socket.send(auth) }.getOrDefault(false)) fail()
    }
    override fun text(text: String) = change {
        if (ended) return@change
        val m = decode(text) ?: run { fail(); return@change }
        val type = m.opt("type") as? String ?: run { fail(); return@change }
        if (phase == Phase.AUTH_SENT) {
            if (type != "registration" || m.opt("status") != "ok" || m.opt("remotePresence") != false ||
                m.opt("role") != role.signalingRole || m.opt("intent") != intent.wire ||
                m.keys().asSequence().toSet() != setOf("type", "status", "role", "intent", "remotePresence")) { fail(); return@change }
            phase = Phase.REGISTERED; pending.add { events.registered() }; return@change
        }
        if (phase != Phase.REGISTERED) { fail(); return@change }
        when (type) {
            "peer-present" -> {
                if (m.length() != 1) { fail(); return@change }
                if (!present) { present = true; pending.add { events.peerPresent() } }
            }
            "peer-left", "bye" -> {
                if (m.length() != 1) { fail(); return@change }
                pending.add { events.payload(text) }
            }
            "offer", "answer", "ice" -> pending.add { events.payload(text) }
            else -> fail() // Includes request-auth, duplicate registration and remote presence.
        }
    }
    fun send(text: String): Boolean {
        var sent = false
        change {
            if (ended) return@change
            val m = decode(text)
            val type = m?.opt("type") as? String
            if (type == "request-auth") { fail(); return@change }
            if (phase != Phase.REGISTERED) return@change // No application queue before authentication.
            if (type !in setOf("offer", "answer", "ice", "peer-left", "bye")) { fail(); return@change }
            sent = runCatching { socket?.send(text) == true }.getOrDefault(false)
            if (!sent) fail()
        }
        return sent
    }
    override fun binary() = change { fail() }
    override fun failed() = change { fail() }
    override fun closed(code: Int) = change {
        if (ended) return@change
        if (code != 1000 || phase != Phase.REGISTERED) fail()
        else { phase = Phase.CLOSED; bootstrap = null; pending.add { events.closed() } }
    }
    fun close() = change {
        if (ended) return@change
        phase = Phase.CLOSED; bootstrap = null; pending.clear()
        runCatching { socket?.close(1000) }
        pending.add { events.closed() }
    }
    override fun toString() = "ProtectedRequestSignaling[redacted]"
    companion object {
        private fun decode(text: String): JSONObject? = try {
            require(text.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
            StrictSignalingJson.validate(text)
            JSONObject(text)
        } catch (_: Exception) { null }
        fun open(bootstrap: ProtectedSignalingBootstrap, factory: ProtectedSocketFactory,
            events: ProtectedSignalingEvents, dispatch: (() -> Unit) -> Unit = { it() }): ProtectedRequestSignaling {
            val transport = ProtectedRequestSignaling(bootstrap, events, dispatch)
            try {
                val socket = factory.open(bootstrap.url, transport) // Never under the controller lock.
                synchronized(transport.lock) {
                    if (transport.ended) { socket.close(if (transport.phase == Phase.CLOSED) 1000 else 1008); socket.cancel() }
                    else if (transport.socket == null) transport.socket = socket
                }
            } catch (_: Exception) { transport.failed() }
            return transport
        }
    }
}

/** No logging interceptor and no automatic retry. Constructed only by an explicit future caller. */
internal class OkHttpProtectedSocketFactory(private val http: OkHttpClient = OkHttpClient.Builder()
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()) : ProtectedSocketFactory {
    override fun open(url: String, listener: ProtectedSocketListener): ProtectedSocket {
        class Adapter : ProtectedSocket {
            lateinit var ws: WebSocket
            override fun send(text: String) = ws.send(text)
            override fun close(code: Int) { ws.close(code, null) }
            override fun cancel() = ws.cancel()
        }
        val socket = Adapter()
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { socket.ws = webSocket; listener.opened(socket) }
            override fun onMessage(webSocket: WebSocket, text: String) = listener.text(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.binary()
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); listener.closed(code) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.closed(code)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); listener.failed() }
        })
        socket.ws = ws
        return socket
    }
}

/** JSONObject accepts non-JSON extensions. Validate standard JSON, duplicate keys and depth first. */
internal object StrictSignalingJson {
    fun validate(text: String) {
        var i = 0
        fun whitespace() { while (i < text.length && text[i] in " \r\n\t") i++ }
        fun take(c: Char) { require(i < text.length && text[i++] == c) }
        fun string(): String {
            val start = i; take('"')
            while (true) {
                require(i < text.length)
                val c = text[i++]
                if (c == '"') break
                require(c >= ' ')
                if (c == '\\') {
                    require(i < text.length)
                    val escaped = text[i++]
                    if (escaped == 'u') repeat(4) { require(i < text.length && text[i++] in "0123456789abcdefABCDEF") }
                    else require(escaped in "\"\\/bfnrt")
                }
            }
            return org.json.JSONTokener(text.substring(start, i)).nextValue() as String
        }
        fun value(depth: Int) {
            require(depth < 64); whitespace(); require(i < text.length)
            when (text[i]) {
                '{' -> {
                    i++; whitespace(); val keys = hashSetOf<String>()
                    if (i < text.length && text[i] != '}') while (true) {
                        whitespace(); require(keys.add(string())); whitespace(); take(':'); value(depth + 1); whitespace()
                        if (i >= text.length || text[i] != ',') break
                        i++
                    }
                    take('}')
                }
                '[' -> {
                    i++; whitespace()
                    if (i < text.length && text[i] != ']') while (true) {
                        value(depth + 1); whitespace(); if (i >= text.length || text[i] != ',') break; i++
                    }
                    take(']')
                }
                '"' -> { string() }
                't', 'f', 'n' -> {
                    val word = when (text[i]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                    require(text.startsWith(word, i)); i += word.length
                }
                else -> {
                    val number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text, i)
                    require(number != null && number.range.first == i); i += number.value.length
                }
            }
        }
        whitespace(); require(i < text.length && text[i] == '{'); value(0); whitespace(); require(i == text.length)
    }
}
