package com.pradertamas.uicapturetest

internal enum class ManualConnectionEntry { READY, CLOSING_LINK, BLOCKED }

/** Explicit navigation away from an idle initiator link. Never releases the gate or
 * adopts/exits a recipient, accepted request or active media owner. */
internal fun enterManualConnection(controller: ConnectionRequestController, role: MediaRole,
    exit: (Any) -> Unit): ManualConnectionEntry {
    val snapshot = controller.mediaSnapshot() ?: return if (controller.state.value.idle)
        ManualConnectionEntry.READY else ManualConnectionEntry.BLOCKED
    if (snapshot.side != ConnectionRequestSide.INITIATOR || snapshot.localRole != role ||
        snapshot.state != ConnectionRequestState.WAITING || snapshot.phase != null) return ManualConnectionEntry.BLOCKED
    if (!snapshot.exitRequested) exit(snapshot.flow)
    return ManualConnectionEntry.CLOSING_LINK
}
