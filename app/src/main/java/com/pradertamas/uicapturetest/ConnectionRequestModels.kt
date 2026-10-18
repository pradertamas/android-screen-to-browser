package com.pradertamas.uicapturetest

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

enum class ConnectionRequestState {
    WAITING, ACCEPTED, CONNECTING, CONNECTED, CANCELED, EXPIRED, FAILED;
    val activeSetup get() = this in setOf(WAITING, ACCEPTED, CONNECTING)
    val terminal get() = this in setOf(CANCELED, EXPIRED, FAILED)
}

/** Request metadata, never a second owner of the app's current MediaRole. */
object ConnectionRequestRoles {
    fun wire(role: MediaRole) = if (role == MediaRole.SEND) "send" else "receive"
    fun parse(value: String) = when (value) { "send" -> MediaRole.SEND; "receive" -> MediaRole.RECEIVE; else -> null }
}
data class ConnectionAssignment(val code: String, val role: MediaRole, val intent: PairingIntent)
data class ConnectionRequestMetadata(
    val requestId: String, val state: ConnectionRequestState, val serverNow: Instant,
    val initiatorRole: MediaRole? = null, val createdAt: Instant? = null, val expiresAt: Instant? = null,
    val acceptedAt: Instant? = null, val connectBy: Instant? = null,
    val assignment: ConnectionAssignment? = null, val failureReason: String? = null,
    val ready: Boolean? = null, val readyUntil: Instant? = null, val connectedAt: Instant? = null,
    val initiatorReady: Boolean? = null, val recipientReady: Boolean? = null
)
enum class ConnectionRequestError { NETWORK, UNAVAILABLE, CONFLICT, EXPIRED, RATE_LIMITED, SERVER, INVALID_REQUEST, INVALID_RESPONSE, INVALID_OPERATION, OPERATION_IN_PROGRESS, CONFIGURATION }
enum class ConnectionRequestAuthScope { INITIATOR, CAPABILITY, CLAIM }
enum class ConnectionRequestOperation { CREATE, PREVIEW, STATUS, ACCEPT, CANCEL, WITHDRAW, READINESS, SESSION }
enum class ConnectionRequestSide { INITIATOR, RECIPIENT }
data class ConnectionRequestUiState(
    val side: ConnectionRequestSide? = null, val request: ConnectionRequestMetadata? = null,
    val operation: ConnectionRequestOperation? = null, val error: ConnectionRequestError? = null,
    val retryAfterSeconds: Long? = null
) { val idle get() = side == null && operation == null }

/** Opaque memory-only credential; diagnostics must never render the raw value. */
class ConnectionCredential internal constructor(internal val value: String) {
    override fun toString() = "ConnectionCredential[redacted]"
}
class ConnectionRequestCreated(val metadata: ConnectionRequestMetadata,
    internal val initiatorSecret: ConnectionCredential, internal val capability: ConnectionCredential) {
    override fun toString() = "ConnectionRequestCreated[redacted]"
}
class ConnectionRequestResult(val metadata: ConnectionRequestMetadata? = null,
    val error: ConnectionRequestError? = null, val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null, internal val created: ConnectionRequestCreated? = null) {
    override fun toString() = "ConnectionRequestResult[status=$httpStatus,error=$error]"
}
internal object ConnectionRequestSecrets {
    private val random = SecureRandom()
    fun claim() = ConnectionCredential(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes)))
    fun canonical(value: String, bytes: Int): Boolean = try {
        value.matches(Regex("[A-Za-z0-9_-]+")) && Base64.getUrlDecoder().decode(value).size == bytes &&
            Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)) == value
    } catch (_: Exception) { false }
}
