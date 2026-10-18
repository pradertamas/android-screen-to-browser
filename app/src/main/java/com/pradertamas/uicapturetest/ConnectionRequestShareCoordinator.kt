package com.pradertamas.uicapturetest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class ConnectionRequestShareOwner
internal enum class RequestShareError { UNAVAILABLE, CREATE_FAILED, CHOOSER_FAILED, LEGACY_STOP_FAILED }
internal data class RequestShareUi(val busy: Boolean = false, val delivery: Long? = null, val error: RequestShareError? = null)

/** No Activity, Context, credentials, stored links, network client or timers.
 * start() must run at the main-thread Activity boundary. Its stop callback is synchronous
 * and is never retained. Controller observations are events, not HTTP polling. */
internal class ConnectionRequestShareCoordinator(
    private val controller: ConnectionRequestController,
    private val arm: () -> Unit = ConnectionRequestRuntimeGate::armRequestFirst,
    private val release: () -> Unit = ConnectionRequestRuntimeGate::releaseRequestFirst
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(RequestShareUi())
    val state = mutable.asStateFlow()
    private var owner: ConnectionRequestShareOwner? = null
    private var role: MediaRole? = null
    private var creating = false
    private var sequence = 0L

    fun start(owner: ConnectionRequestShareOwner, role: MediaRole, stopLegacy: () -> Unit) = synchronized(lock) {
        if (mutable.value.busy) return@synchronized
        val current = controller.state.value
        if (current.operation != null || current.side == ConnectionRequestSide.RECIPIENT ||
            current.request?.state in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING, ConnectionRequestState.CONNECTED)) {
            mutable.value = RequestShareUi(error = RequestShareError.UNAVAILABLE); return@synchronized
        }
        this.owner = owner; this.role = role
        mutable.value = RequestShareUi(busy = true)
        arm()
        try { stopLegacy() } catch (_: Exception) {
            // Quiet state was not established. Keep the gate armed; never start HTTP/chooser.
            this.owner = null; this.role = null
            mutable.value = RequestShareUi(error = RequestShareError.LEGACY_STOP_FAILED); return@synchronized
        }
        if (controller.shareableInitiatorData(role) != null) {
            mutable.value = RequestShareUi(busy = true, delivery = ++sequence)
        } else {
            creating = true
            controller.create(role)
            onRequestState(controller.state.value)
        }
    }
    fun onRequestState(snapshot: ConnectionRequestUiState) = synchronized(lock) {
        if (!creating || snapshot.operation != null) return@synchronized
        // StateFlow collectors may deliver an older event after start(); only consume current.
        if (controller.state.value != snapshot) return@synchronized
        creating = false
        val requested = role
        if (requested != null && controller.shareableInitiatorData(requested) != null) {
            if (owner != null) mutable.value = RequestShareUi(busy = true, delivery = ++sequence)
            else { role = null; mutable.value = RequestShareUi() }
        } else {
            // Existing controller replacement policy deliberately retires the old local context.
            // Release only with no current active request/context/operation; never resurrect it.
            if (snapshot.request == null && snapshot.side == null && snapshot.operation == null) release()
            owner = null; role = null
            mutable.value = RequestShareUi(error = RequestShareError.CREATE_FAILED)
        }
    }
    /** Consume before Intent launch. No replay on recomposition or lifecycle resume. */
    fun takeLink(owner: ConnectionRequestShareOwner, delivery: Long, currentRole: MediaRole): String? = synchronized(lock) {
        if (this.owner !== owner || mutable.value.delivery != delivery) return@synchronized null
        mutable.value = RequestShareUi(busy = true)
        val requested = role
        val data = if (requested == currentRole) controller.shareableInitiatorData(currentRole) else null
        val link = data?.let(ConnectionRequestLink::build)
        if (link == null) {
            this.owner = null; role = null; mutable.value = RequestShareUi(error = RequestShareError.UNAVAILABLE)
        }
        link
    }
    fun chooserFailed(owner: ConnectionRequestShareOwner) = synchronized(lock) {
        if (this.owner !== owner) return@synchronized
        this.owner = null; role = null
        mutable.value = RequestShareUi(error = RequestShareError.CHOOSER_FAILED)
    }
    fun chooserReturned(owner: ConnectionRequestShareOwner) = synchronized(lock) {
        if (this.owner !== owner || creating || mutable.value.delivery != null) return@synchronized
        this.owner = null; role = null; mutable.value = RequestShareUi()
    }
    fun detach(owner: ConnectionRequestShareOwner) = synchronized(lock) {
        if (this.owner !== owner) return@synchronized
        this.owner = null
        if (!creating) { role = null; mutable.value = RequestShareUi() }
        // In-flight create continues in the process owner; completion will not auto-open a chooser.
    }
}

internal object ConnectionRequestSharing {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val coordinator by lazy {
        ConnectionRequestShareCoordinator(ConnectionRequests.controller).also { coordinator ->
            scope.launch { ConnectionRequests.controller.state.collect { coordinator.onRequestState(it) } }
        }
    }
}
