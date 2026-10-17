package com.pradertamas.uicapturetest

import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sole app media-role owner. Application lifetime, SEND on fresh process; no saved preference. */
object AppMediaRoles {
    private val mutable = MutableStateFlow(MediaRoleState())
    val state = mutable.asStateFlow()
    val role: MediaRole get() = state.value.role
    private data class ReceiverOwner(val identity: Any, val stop: () -> Unit, val canLeave: () -> Boolean)
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
        if (requested == MediaRole.SEND && !CaptureState.state.value.busy) PairingHost.controller.show()
        // RECEIVE starts only when its retained owner is attached by ReceiverActivity.
    }, { mutable.value = it })
    fun switchRole(requested: MediaRole, stopActive: Boolean = false): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return controller.switchRole(requested, stopActive)
    }
    fun receiverStartFailed() {
        switchRole(MediaRole.SEND, stopActive = true)
        controller.reportStartFailure()
    }
    fun bindReceiver(identity: Any, stop: () -> Unit, canLeave: () -> Boolean) {
        check(role == MediaRole.RECEIVE)
        check(receiver == null || receiver?.identity === identity)
        receiver = ReceiverOwner(identity, stop, canLeave)
    }
    fun unbindReceiver(identity: Any) { if (receiver?.identity === identity) receiver = null }
}
