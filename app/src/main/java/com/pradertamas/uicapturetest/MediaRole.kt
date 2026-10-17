package com.pradertamas.uicapturetest

/** Independent of pairing intent, requester identity and session lifetime. Never persisted. */
enum class MediaRole(val signalingRole: String, val offerer: Boolean, val captureAllowed: Boolean) {
    SEND("sender", true, true),
    RECEIVE("receiver", false, false);

    fun registrationUrl(code: String, intent: PairingIntent): String {
        require(PairingCode.normalize(code) == code)
        return "${WebRtcConfig.RELAY}?session=$code&role=$signalingRole&intent=${intent.wire}"
    }
}

data class MediaRoleState(val role: MediaRole = MediaRole.SEND, val generation: Long = 0, val error: String = "")

/** Main-thread domain coordinator. Invalidate before teardown; never infer a role from CREATE/JOIN. */
class MediaRoleController(private val canLeave: (MediaRole) -> Boolean,
    private val stop: (MediaRole) -> Unit, private val start: (MediaRole) -> Unit,
    private val changed: (MediaRoleState) -> Unit = {}) {
    var state = MediaRoleState(); private set
    private var transitioning = false
    fun reportStartFailure() { state = state.copy(error = "This mode could not start. Please retry."); changed(state) }
    fun current(role: MediaRole, generation: Long) = state.role == role && state.generation == generation && !transitioning
    fun switchRole(requested: MediaRole, stopActive: Boolean = false): Boolean {
        if (transitioning) return false
        if (requested == state.role) return true // Idempotent, including during an active session.
        if (!stopActive && !canLeave(state.role)) return false
        val old = state.role
        transitioning = true
        state = state.copy(generation = state.generation + 1, error = "")
        val cleanupFailed = runCatching { stop(old) }.isFailure
        state = state.copy(role = requested, error = if (cleanupFailed) "Previous connection cleanup failed. Please retry." else "")
        // Publish before activating so new callbacks see the requested role, never the retired role.
        changed(state)
        transitioning = false
        if (runCatching { start(requested) }.isFailure) {
            state = state.copy(error = "This mode could not start. Please retry."); changed(state)
        }
        return true
    }
}

object MediaRolePolicy {
    fun senderSwitchAllowed(phase: PairingPhase, captureBusy: Boolean, rtcBusy: Boolean) =
        !captureBusy && !rtcBusy && phase in setOf(PairingPhase.STOPPED, PairingPhase.CREATING, PairingPhase.WAITING, PairingPhase.ERROR)
    fun receiverSwitchAllowed(phase: ReceiverPhase) = phase in setOf(ReceiverPhase.PAIRING, ReceiverPhase.ENDED, ReceiverPhase.ERROR)
}
