package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test

class SenderMediaSignalingPolicyTest {
    private val losses = listOf("SIGNALING_PEER_LEFT", "SIGNALING_FAILURE", "SIGNALING_REMOTE_CLOSE", "SIGNALING_SEND_FAILURE")
    @Test fun setupLossRemainsTerminalEvenWhenBackendOrPresenceSaysConnected() {
        for (loss in losses) assertFalse(SenderMediaSignalingPolicy().retain(loss, "CONNECTED"))
    }
    @Test fun establishedLossRetainsMediaAndLateSendFailuresWithoutReestablishing() {
        for (loss in losses) {
            val policy = SenderMediaSignalingPolicy(); policy.connected()
            assertTrue(policy.retain(loss, "CONNECTED")); assertTrue(policy.degraded)
            repeat(3) { assertTrue(policy.retain("SIGNALING_SEND_FAILURE", "CONNECTED")) }
        }
    }
    @Test fun byeAndProtocolFailuresRemainTerminalAfterEstablishment() {
        for (cause in listOf("SIGNALING_REMOTE_BYE", "SIGNALING_PROTOCOL_FAILURE", "SIGNALING_LOCAL_STOP")) {
            val policy = SenderMediaSignalingPolicy(); policy.connected()
            assertFalse(policy.retain(cause, "CONNECTED")); assertFalse(policy.degraded)
        }
    }
    @Test fun disconnectGraceIsAllowedToContinueButFailedClosedPeersAreNotRetained() {
        val policy = SenderMediaSignalingPolicy(); policy.connected()
        assertTrue(policy.retain("SIGNALING_PEER_LEFT", "DISCONNECTED"))
        for (state in listOf("FAILED", "CLOSED", "NEW", "CONNECTING", null))
            assertFalse(policy.retain("SIGNALING_FAILURE", state))
    }
    @Test fun retiredOwnerCannotAffectFreshOwnerAndFreshAttemptResetsState() {
        val old = SenderMediaSignalingPolicy(); old.connected(); old.retain("SIGNALING_PEER_LEFT", "CONNECTED"); old.stop()
        val fresh = SenderMediaSignalingPolicy()
        old.connected(); assertFalse(old.retain("SIGNALING_FAILURE", "CONNECTED"))
        assertFalse(fresh.established); assertFalse(fresh.degraded)
        assertFalse(fresh.retain("SIGNALING_PEER_LEFT", "CONNECTED"))
    }
}
