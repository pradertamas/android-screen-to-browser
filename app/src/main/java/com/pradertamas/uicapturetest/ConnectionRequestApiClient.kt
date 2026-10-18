package com.pradertamas.uicapturetest

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

interface ConnectionRequestApi {
    fun create(role: MediaRole): ConnectionRequestResult
    fun status(id: String, auth: ConnectionCredential): ConnectionRequestResult
    fun accept(id: String, capability: ConnectionCredential, claim: ConnectionCredential): ConnectionRequestResult
    fun cancel(id: String, auth: ConnectionCredential): ConnectionRequestResult
    fun withdraw(id: String, auth: ConnectionCredential): ConnectionRequestResult
    fun ready(id: String, auth: ConnectionCredential, ready: Boolean): ConnectionRequestResult
    fun session(id: String, auth: ConnectionCredential): ConnectionRequestResult
}
class ConnectionRequestWireResponse(val code: Int, internal val body: String, val retryAfterSeconds: Long? = null) {
    override fun toString() = "ConnectionRequestWireResponse[status=$code]"
}
fun interface ConnectionRequestTransport {
    /** Blocking: controller always executes this on its worker. Never log arguments. */
    fun exchange(method: String, path: String, auth: ConnectionCredential?, body: String?): ConnectionRequestWireResponse
}
object ConnectionRequestConfig {
    /** Single existing relay configuration; derive the HTTPS origin, never change signaling. */
    fun httpBase(relay: String = WebRtcConfig.RELAY): String {
        require(relay.startsWith("wss://")) { "Invalid relay configuration" }
        val url = ("https://" + relay.removePrefix("wss://")).toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/ws") {
            "Invalid relay configuration"
        }
        return url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
    }
}
class ConnectionRequestOkHttpTransport : ConnectionRequestTransport {
    private val base = ConnectionRequestConfig.httpBase()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS).build()
    override fun exchange(method: String, path: String, auth: ConnectionCredential?, body: String?): ConnectionRequestWireResponse {
        val request = Request.Builder().url(base + path).header("Cache-Control", "no-store")
        auth?.let { request.header("Authorization", "Bearer ${it.value}") }
        if (method == "GET") request.get() else request.post((body ?: "{}").toRequestBody("application/json".toMediaType()))
        http.newCall(request.build()).execute().use { response ->
            val source = response.body?.source() ?: throw IOException()
            if (source.request(16385)) return ConnectionRequestWireResponse(response.code, "")
            return ConnectionRequestWireResponse(response.code, source.readUtf8(),
                response.header("Retry-After")?.toLongOrNull()?.takeIf { it >= 0 })
        }
    }
}
class ConnectionRequestApiClient(private val transport: ConnectionRequestTransport = ConnectionRequestOkHttpTransport()) : ConnectionRequestApi {
    override fun create(role: MediaRole) = call("create", null, null,
        JSONObject().put("version", 1).put("initiatorRole", ConnectionRequestRoles.wire(role)), role)
    override fun status(id: String, auth: ConnectionCredential) = call("status", id, auth)
    override fun accept(id: String, capability: ConnectionCredential, claim: ConnectionCredential) =
        call("accept", id, capability, JSONObject().put("claimSecret", claim.value))
    override fun cancel(id: String, auth: ConnectionCredential) = call("cancel", id, auth)
    override fun withdraw(id: String, auth: ConnectionCredential) = call("withdraw", id, auth)
    override fun ready(id: String, auth: ConnectionCredential, ready: Boolean) = call("participant-state", id, auth, JSONObject().put("ready", ready))
    override fun session(id: String, auth: ConnectionCredential) = call("session", id, auth)
    private fun call(action: String, id: String?, auth: ConnectionCredential?, input: JSONObject = JSONObject(), role: MediaRole? = null): ConnectionRequestResult {
        if (id != null && !ConnectionRequestSecrets.canonical(id, 16)) return ConnectionRequestResult(error = ConnectionRequestError.INVALID_REQUEST)
        return try {
            val wire = transport.exchange(if (action == "status") "GET" else "POST",
                if (id == null) "/connection-requests" else "/connection-requests/$id/$action", auth,
                if (action == "status") null else input.toString())
            val error = when (wire.code) {
                200, 201, 410 -> null
                400 -> ConnectionRequestError.INVALID_REQUEST
                404 -> ConnectionRequestError.UNAVAILABLE
                409 -> ConnectionRequestError.CONFLICT
                429 -> ConnectionRequestError.RATE_LIMITED
                in 500..599 -> ConnectionRequestError.SERVER
                else -> ConnectionRequestError.INVALID_RESPONSE
            }
            if (error != null) return ConnectionRequestResult(error = error, httpStatus = wire.code, retryAfterSeconds = wire.retryAfterSeconds)
            require(wire.code == (if (action == "create") 201 else 200) || wire.code == 410)
            val json = JSONObject(wire.body)
            fun text(key: String): String = json.get(key).let { require(it is String); it }
            fun date(key: String): Instant? = if (!json.has(key) || json.isNull(key)) null else Instant.parse(text(key))
            val requestId = text("requestId"); require(ConnectionRequestSecrets.canonical(requestId, 16) && (id == null || requestId == id))
            val state = ConnectionRequestState.entries.firstOrNull { it.name == text("state") } ?: error("Unknown state")
            require(wire.code != 410 || state.terminal)
            val initiator = if (json.has("initiatorRole")) ConnectionRequestRoles.parse(text("initiatorRole")) ?: error("Invalid role") else role
            val assignment = if (json.has("session")) json.getJSONObject("session").let {
                val code = it.getString("code"); require(PairingCode.normalize(code) == code)
                val media = when (it.getString("role")) { "sender" -> MediaRole.SEND; "receiver" -> MediaRole.RECEIVE; else -> error("Invalid role") }
                val intent = when (it.getString("intent")) { "create" -> PairingIntent.CREATE; "join" -> PairingIntent.JOIN; else -> error("Invalid intent") }
                ConnectionAssignment(code, media, intent)
            } else null
            val metadata = ConnectionRequestMetadata(requestId, state, Instant.parse(text("serverNow")), initiator,
                date("createdAt"), date("expiresAt"), date("acceptedAt"), date("connectBy"), assignment,
                if (json.has("failureReason")) when (text("failureReason")) {
                    "establishment_timeout" -> "establishment_timeout"; "recipient_withdrew" -> "recipient_withdrew"; else -> "other"
                } else null,
                if (json.has("ready")) json.get("ready").let { require(it is Boolean); it } else null,
                date("readyUntil"), date("connectedAt"))
            if (wire.code != 410) when (action) {
                "create" -> require(state == ConnectionRequestState.WAITING && metadata.expiresAt != null)
                "status" -> require(initiator != null && metadata.expiresAt != null)
                "accept" -> require(state in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING) && metadata.acceptedAt != null && metadata.connectBy != null)
                "cancel" -> require(state == ConnectionRequestState.CANCELED)
                "withdraw" -> require(state == ConnectionRequestState.FAILED && metadata.failureReason == "recipient_withdrew")
                "participant-state" -> require(state == ConnectionRequestState.ACCEPTED && metadata.ready != null && (metadata.ready != true || metadata.readyUntil != null))
                "session" -> require(state == ConnectionRequestState.CONNECTING && assignment != null && assignment.intent == PairingIntent.CREATE)
            }
            val created = if (action == "create" && wire.code == 201) {
                val secret = text("initiatorSecret"); val cap = text("recipientCapability")
                require(ConnectionRequestSecrets.canonical(secret, 32) && ConnectionRequestSecrets.canonical(cap, 16))
                ConnectionRequestCreated(metadata, ConnectionCredential(secret), ConnectionCredential(cap))
            } else null
            ConnectionRequestResult(metadata, if (state == ConnectionRequestState.EXPIRED) ConnectionRequestError.EXPIRED else null, wire.code, created = created)
        } catch (_: IOException) { ConnectionRequestResult(error = ConnectionRequestError.NETWORK) }
        catch (_: Exception) { ConnectionRequestResult(error = ConnectionRequestError.INVALID_RESPONSE) }
    }
}
