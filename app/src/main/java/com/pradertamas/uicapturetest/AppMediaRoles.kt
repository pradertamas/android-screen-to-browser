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

/** Sole app media-role owner. Application lifetime, SEND on fresh process; no saved preference. */
object AppMediaRoles {
    private val mutable = MutableStateFlow(MediaRoleState())
    val state = mutable.asStateFlow()
    private val receiverRevisionMutable = MutableStateFlow(0L)
    val receiverRevision = receiverRevisionMutable.asStateFlow()
    fun receiverPhaseChanged() { receiverRevisionMutable.value++ }
    val role: MediaRole get() = state.value.role
    private data class ReceiverOwner(val identity: Any, val stop: () -> Unit, val canLeave: () -> Boolean,
        val taskId: Int? = null)
    private var receiver: ReceiverOwner? = null
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
            MediaRole.RECEIVE -> { val old = receiver; receiver = null; old?.stop?.invoke() }
        }
    }, { requested ->
        if (requested == MediaRole.SEND && !CaptureState.state.value.busy)
            ConnectionRequestRuntimeGate.runLegacyStart { PairingHost.controller.show() }
        // RECEIVE starts only when its retained owner is attached by ReceiverActivity.
    }, { mutable.value = it })
    fun switchRole(requested: MediaRole, stopActive: Boolean = false): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return controller.switchRole(requested, stopActive)
    }
    fun pairingAllowed(): Boolean = when (role) {
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
        check(role == MediaRole.RECEIVE)
        check(receiver == null || receiver?.identity === identity)
        receiver = ReceiverOwner(identity, stop, canLeave, receiver?.taskId)
    }
    /** Main-thread task routing metadata only; the ViewModel itself never leaves this owner. */
    internal fun receiverTask(): RequestReceiverTask = RequestReceiverTask(receiver != null, receiver?.taskId)
    internal fun receiverAttachedToTask(identity: Any, taskId: Int) {
        check(Looper.myLooper() == Looper.getMainLooper())
        receiver?.takeIf { it.identity === identity }?.let { receiver = it.copy(taskId = taskId) }
    }
    fun unbindReceiver(identity: Any) { if (receiver?.identity === identity) receiver = null }
}
