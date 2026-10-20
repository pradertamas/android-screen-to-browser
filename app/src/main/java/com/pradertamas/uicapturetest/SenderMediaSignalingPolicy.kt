package com.pradertamas.uicapturetest

/** Per media owner, serialized on WebRtcControl. Neither diagnostics nor backend state is authority. */
internal class SenderMediaSignalingPolicy {
    var established = false
        private set
    var degraded = false
        private set
    private var stopped = false
    fun connected() { if (!stopped) established = true }
    fun stop() { stopped = true }
    fun retain(source: String, peerState: String?): Boolean {
        if (stopped || !established || peerState !in setOf("CONNECTED", "DISCONNECTED")) return false
        if (source !in setOf("SIGNALING_PEER_LEFT", "SIGNALING_FAILURE", "SIGNALING_REMOTE_CLOSE", "SIGNALING_SEND_FAILURE")) return false
        degraded = true
        return true
    }
}
