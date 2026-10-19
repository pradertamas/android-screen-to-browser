package com.pradertamas.uicapturetest

import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pure stop dispatch; callers supply the current role and the existing idle policy. */
internal fun stopIdleLegacyOwner(role: MediaRole, canStop: () -> Boolean,
    stopSender: () -> Unit, stopReceiver: () -> Unit): Boolean {
    if (!canStop()) return false
    when (role) { MediaRole.SEND -> stopSender(); MediaRole.RECEIVE -> stopReceiver() }
    return true
}

/** Main-thread owner bookkeeping shared with deterministic JVM tests. */
internal class ReceiverOwnerRegistry {
    internal class Owner(val identity: Any, val stop: () -> Unit, val canLeave: () -> Boolean, val taskId: Int?)
    var current: Owner? = null; private set
    fun bind(role: MediaRole, identity: Any, stop: () -> Unit, canLeave: () -> Boolean, taskId: Int? = null) {
        check(role == MediaRole.RECEIVE)
        check(current == null || current?.identity === identity)
        require(taskId == null || taskId >= 0)
        current = Owner(identity, stop, canLeave, taskId ?: current?.taskId)
    }
    fun unbind(identity: Any) { if (current?.identity === identity) current = null }
    fun clear(): Owner? = current.also { current = null }
    fun task() = RequestReceiverTask(current != null, current?.taskId)
    fun attached(identity: Any) = current?.let { it.identity === identity && it.taskId != null } == true
}

/** Sole app media-role owner. Application lifetime, SEND on fresh process; no saved preference. */
object AppMediaRoles {
    private val mutable = MutableStateFlow(MediaRoleState())
    val state = mutable.asStateFlow()
    private val receiverRevisionMutable = MutableStateFlow(0L)
    val receiverRevision = receiverRevisionMutable.asStateFlow()
    fun receiverPhaseChanged() { receiverRevisionMutable.value++ }
    val role: MediaRole get() = state.value.role
    private val receivers = ReceiverOwnerRegistry()
    private val receiver get() = receivers.current
    private val controller = MediaRoleController({ old ->
        when (old) {
            MediaRole.SEND -> MediaRolePolicy.senderSwitchAllowed(PairingHost.state.value.phase,
                CaptureState.state.value.busy, WebRtcState.state.value.busy)
            MediaRole.RECEIVE -> receiver?.canLeave?.invoke() ?: true
        }
    }, { old ->
        when (old) {
            MediaRole.SEND -> {
                // hide cancels recovery; close invalidates the epoch before socket shutdown.
                PairingHost.controller.hide(); PairingHost.controller.close()
            }
            MediaRole.RECEIVE -> receivers.clear()?.stop?.invoke()
        }
    }, { requested ->
        if (requested == MediaRole.SEND && !CaptureState.state.value.busy)
            ConnectionRequestRuntimeGate.runLegacyStart { PairingHost.controller.show() }
        // RECEIVE starts only when its retained owner is attached by ReceiverActivity.
    }, { mutable.value = it })
    fun switchRole(requested: MediaRole, stopActive: Boolean = false): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (ReceiverSessions.active && requested != role && !stopActive) return false
        return controller.switchRole(requested, stopActive)
    }
    fun pairingAllowed(): Boolean = !ReceiverSessions.active && when (role) {
        MediaRole.SEND -> MediaRolePolicy.senderSwitchAllowed(PairingHost.state.value.phase, CaptureState.state.value.busy, WebRtcState.state.value.busy)
        MediaRole.RECEIVE -> receiver?.canLeave?.invoke() ?: true
    }
    /** Caller ARMs the start gate first; this synchronous main-thread hook never changes role. */
    fun stopIdleLegacyForRequestFirst(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return stopIdleLegacyOwner(role, ::pairingAllowed,
            { PairingHost.controller.hide(); PairingHost.controller.close() }, { receiver?.stop?.invoke() })
    }
    fun receiverStartFailed() {
        switchRole(MediaRole.SEND, stopActive = true)
        controller.reportStartFailure()
    }
    fun bindReceiver(identity: Any, stop: () -> Unit, canLeave: () -> Boolean) {
        receivers.bind(role, identity, stop, canLeave)
    }
    /** Main-thread task routing metadata only; the ViewModel itself never leaves this owner. */
    internal fun receiverTask(): RequestReceiverTask = receivers.task()
    internal fun bindReceiverToTask(identity: Any, taskId: Int, stop: () -> Unit, canLeave: () -> Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        receivers.bind(role, identity, stop, canLeave, taskId)
    }
    internal fun receiverAttached(identity: Any) = receivers.attached(identity)
    internal fun receiverBound(identity: Any) = receivers.current?.identity === identity
    fun unbindReceiver(identity: Any) { receivers.unbind(identity) }
}
