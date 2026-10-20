package com.pradertamas.uicapturetest

import android.util.Log
import java.security.MessageDigest

/** Fixed events/typed fields only. No URL, payload, credential or exception text is accepted.
 * request is a one-way correlation digest shared across phones; flow is process-local.
 * Logcat is diagnostic only and can never change flow admission or cleanup. */
internal object RequestFlowTrace {
    enum class Event {
        HTTP_COMPLETE, CONSENT_REQUESTED, CONSENT_GRANTED, CONSENT_CANCELLED, CONSENT_LAUNCH_FAILED,
        SERVICE_DISPATCHED, RECEIVER_DISPATCH, RECEIVER_DISPATCHED, RECEIVER_DISPATCH_FAILED,
        RECEIVER_HANDOFF_ACCEPTED, RECEIVER_HANDOFF_REJECTED,
        FALLBACK_ROLE_NOT_RECEIVE, FALLBACK_VIEWMODEL_FAILED, RECEIVER_ATTACH_FAILED,
        FINISH_SENDER_TRANSFER, FINISH_RECEIVER_TRANSFER, FINISH_EXIT_COMPLETE, FINISH_CLOSED,
        FINISH_NO_CONTEXT, FINISH_RECEIVER_RETURN,
        OWNERSHIP_RETIRED, RETIRED_EXIT_COMPLETE, STALE_HTTP_COMPLETE,
        SOCKET_OPEN, AUTH_SENT, REGISTER_OK, REGISTER_REJECTED, REGISTER_NOT_FOUND,
        REGISTER_CONFLICT, REGISTER_ROLE_OCCUPIED, REGISTER_RATE_LIMITED, PEER_PRESENT,
        SOCKET_FAILURE, SOCKET_REMOTE_CLOSE, SOCKET_LOCAL_CLOSE, TRANSPORT_FAILED, TRANSPORT_CLOSED
    }
    fun format(event: Event, flow: Any? = null, role: MediaRole? = null,
        state: ConnectionRequestUiState? = null, intent: PairingIntent? = null, code: Int? = null): String {
        val request = state?.request
        val tag = request?.requestId?.let { id ->
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
                .take(6).joinToString("") { "%02x".format(it.toInt() and 255) }
        } ?: "-"
        val effective = role ?: request?.initiatorRole?.let {
            if (state.side == ConnectionRequestSide.RECIPIENT) ConnectionRequestSetupCoordinator.opposite(it) else it
        }
        return "event=$event request=$tag flow=${flow?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "-"}" +
            " side=${state?.side} role=$effective state=${request?.state} operation=${state?.operation}" +
            " error=${state?.error} ready=${request?.ready} initiatorReady=${request?.initiatorReady} recipientReady=${request?.recipientReady}" +
            " assignment=${intent ?: request?.assignment?.intent} code=$code"
    }
    fun record(event: Event, flow: Any? = null, role: MediaRole? = null,
        state: ConnectionRequestUiState? = null, intent: PairingIntent? = null, code: Int? = null) {
        runCatching { Log.i("RequestFlow", format(event, flow, role, state, intent, code)) }
    }
}
