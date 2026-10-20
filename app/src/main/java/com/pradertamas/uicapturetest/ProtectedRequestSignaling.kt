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
    fun terminalOrigin(source: String) {}
    /** Actual socket/I/O loss; existing consumers keep their fatal default policy. */
    fun transportUnavailable() = failed()
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
    private val traceFlow = checkNotNull(bootstrap).flow
    private var present = false
    private val pending = ArrayDeque<() -> Unit>()
    private var draining = false
    private var effectsInFlight = 0
    private val ended get() = phase == Phase.CLOSED || phase == Phase.FAILED
    /** Phase one is bookkeeping only. Even synchronous nested socket callbacks
     * finish their effects before any consumer drain can begin. */
    private fun change(action: MutableList<() -> Unit>.() -> Unit) {
        val effects = mutableListOf<() -> Unit>()
        synchronized(lock) {
            effects.action()
            effectsInFlight += effects.size
        }
        for (effect in effects) {
            try { effect() } finally { synchronized(lock) { effectsInFlight-- } }
        }
        val start = synchronized(lock) {
            if (effectsInFlight == 0 && !draining && pending.isNotEmpty()) { draining = true; true } else false
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
    /** Called only during phase one; all socket cleanup is a captured external effect. */
    private fun MutableList<() -> Unit>.terminal(failure: Boolean, local: Boolean = false, unavailable: Boolean = false,
        source: String = "SIGNALING_PROTOCOL_FAILURE") {
        if (ended) return
        phase = if (failure) Phase.FAILED else Phase.CLOSED
        bootstrap = null; pending.clear()
        add { runCatching { events.terminalOrigin(source) } }
        add { RequestFlowTrace.record(if (failure) RequestFlowTrace.Event.TRANSPORT_FAILED else RequestFlowTrace.Event.TRANSPORT_CLOSED,
            traceFlow, role, intent = intent) }
        val closing = socket
        if (failure || local) add {
            runCatching { closing?.close(if (failure) 1008 else 1000) }
            if (failure) runCatching { closing?.cancel() }
        }
        pending.add { if (unavailable) events.transportUnavailable() else if (failure) events.failed() else events.closed() }
    }
    override fun opened(socket: ProtectedSocket) = change {
        if (ended) {
            val code = if (phase == Phase.CLOSED) 1000 else 1008
            add { runCatching { socket.close(code) }; runCatching { socket.cancel() } }; return@change
        }
        if (phase != Phase.OPENING) { terminal(true); return@change }
        this@ProtectedRequestSignaling.socket = socket
        val material = bootstrap; bootstrap = null
        phase = Phase.AUTH_SENT
        add {
            val auth = material?.consumeAuthFrame()
            if (auth == null || auth.toByteArray(Charsets.UTF_8).size > 512) failed()
            else {
                val admitted = synchronized(lock) { !ended && this@ProtectedRequestSignaling.socket === socket }
                if (admitted) {
                    RequestFlowTrace.record(RequestFlowTrace.Event.AUTH_SENT, traceFlow, role, intent = intent)
                    if (!runCatching { socket.send(auth) }.getOrDefault(false)) failed()
                }
            }
        }
    }
    override fun text(text: String) {
        val m = decode(text)
        val type = m?.opt("type") as? String
        change {
            if (ended) return@change
            if (m == null || type == null) { terminal(true); return@change }
            if (phase == Phase.AUTH_SENT) {
                if (type != "registration" || m.opt("status") != "ok" || m.opt("remotePresence") != false ||
                    m.opt("role") != role.signalingRole || m.opt("intent") != intent.wire ||
                    m.keys().asSequence().toSet() != setOf("type", "status", "role", "intent", "remotePresence")) {
                    val result = when (m.opt("status")) {
                        "not-found" -> RequestFlowTrace.Event.REGISTER_NOT_FOUND
                        "conflict" -> RequestFlowTrace.Event.REGISTER_CONFLICT
                        "role-occupied" -> RequestFlowTrace.Event.REGISTER_ROLE_OCCUPIED
                        "rate-limited" -> RequestFlowTrace.Event.REGISTER_RATE_LIMITED
                        else -> RequestFlowTrace.Event.REGISTER_REJECTED
                    }
                    add { RequestFlowTrace.record(result, traceFlow, role, intent = intent) }
                    terminal(true); return@change
                }
                phase = Phase.REGISTERED
                add { RequestFlowTrace.record(RequestFlowTrace.Event.REGISTER_OK, traceFlow, role, intent = intent) }
                pending.add { events.registered() }; return@change
            }
            if (phase != Phase.REGISTERED) { terminal(true); return@change }
            when (type) {
                "peer-present" -> {
                    if (m.length() != 1) { terminal(true); return@change }
                    if (!present) {
                        present = true
                        add { RequestFlowTrace.record(RequestFlowTrace.Event.PEER_PRESENT, traceFlow, role, intent = intent) }
                        pending.add { events.peerPresent() }
                    }
                }
                "peer-left", "bye" -> {
                    if (m.length() != 1) { terminal(true); return@change }
                    pending.add { events.payload(text) }
                }
                "offer", "answer", "ice" -> pending.add { events.payload(text) }
                else -> terminal(true) // Includes request-auth, duplicate registration and remote presence.
            }
        }
    }
    /** Atomic admission, not atomic network delivery: true means the admitted socket
     * accepted the frame. A later/concurrent terminal transition may still win. */
    fun send(text: String): Boolean {
        var sent = false
        val type = decode(text)?.opt("type") as? String
        change {
            if (ended) return@change
            if (type == "request-auth") { terminal(true); return@change }
            if (phase != Phase.REGISTERED) return@change // No application queue before authentication.
            if (type !in setOf("offer", "answer", "ice", "peer-left", "bye")) { terminal(true); return@change }
            val sending = socket
            add {
                sent = runCatching { sending?.send(text) == true }.getOrDefault(false)
                if (!sent) fail("SIGNALING_SEND_FAILURE")
            }
        }
        return sent
    }
    override fun binary() = change { terminal(true) }
    override fun failed() = fail("SIGNALING_FAILURE")
    private fun fail(source: String) = change {
        if (!ended) add { RequestFlowTrace.record(RequestFlowTrace.Event.SOCKET_FAILURE, traceFlow, role, intent = intent) }
        terminal(true, unavailable = true, source = source)
    }
    override fun closed(code: Int) = change {
        if (ended) return@change
        add { RequestFlowTrace.record(RequestFlowTrace.Event.SOCKET_REMOTE_CLOSE, traceFlow, role, intent = intent, code = code) }
        terminal(code != 1000 || phase != Phase.REGISTERED, source = "SIGNALING_REMOTE_CLOSE")
    }
    fun close() = change {
        if (!ended) add { RequestFlowTrace.record(RequestFlowTrace.Event.SOCKET_LOCAL_CLOSE, traceFlow, role, intent = intent) }
        terminal(false, local = true, source = "SIGNALING_LOCAL_STOP")
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
            RequestFlowTrace.record(RequestFlowTrace.Event.SOCKET_OPEN, bootstrap.flow, bootstrap.role, intent = bootstrap.intent)
            try {
                val socket = factory.open(bootstrap.url, transport) // Never under the controller lock.
                transport.change {
                    if (transport.ended) {
                        val code = if (transport.phase == Phase.CLOSED) 1000 else 1008
                        add { runCatching { socket.close(code) }; runCatching { socket.cancel() } }
                    }
                    else if (transport.socket == null) transport.socket = socket
                }
            } catch (_: Exception) { transport.failed() }
            return transport
        }
    }
}

/** No logging interceptor and no automatic retry. Constructed only by an explicit future caller. */
internal class OkHttpProtectedSocketFactory(private val trace: (String) -> Unit = {}) : ProtectedSocketFactory {
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    internal class Policy(val followRedirects: Boolean, val followSslRedirects: Boolean,
        val retryOnConnectionFailure: Boolean, val applicationInterceptors: Int, val networkInterceptors: Int)
    internal fun policy() = Policy(http.followRedirects, http.followSslRedirects, http.retryOnConnectionFailure,
        http.interceptors.size, http.networkInterceptors.size)
    override fun open(url: String, listener: ProtectedSocketListener): ProtectedSocket {
        require(runCatching { URI(url).scheme == "wss" }.getOrDefault(false))
        class Adapter : ProtectedSocket {
            lateinit var ws: WebSocket
            override fun send(text: String) = ws.send(text)
            override fun close(code: Int) { trace("websocket-local-close code=$code"); ws.close(code, null) }
            override fun cancel() { trace("websocket-local-cancel"); ws.cancel() }
        }
        val socket = Adapter()
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { trace("websocket-open"); socket.ws = webSocket; listener.opened(socket) }
            override fun onMessage(webSocket: WebSocket, text: String) = listener.text(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.binary()
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                trace("websocket-remote-closing code=$code reason=${ReceiverTrace.safe(reason)}")
                webSocket.close(code, null); listener.closed(code)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trace("websocket-closed code=$code reason=${ReceiverTrace.safe(reason)}"); listener.closed(code)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trace("websocket-failure ${ReceiverTrace.exception(t)}"); response?.close(); listener.failed()
            }
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
