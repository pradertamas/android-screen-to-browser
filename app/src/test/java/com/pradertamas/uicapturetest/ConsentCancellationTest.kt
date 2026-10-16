package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test

class ConsentCancellationTest {
    @Test fun cancelClearsBothPairingIntentsAndRepeatedAttempts() {
        for (intent in PairingIntent.entries) {
            repeat(3) {
                WebRtcState.set(WebRtcUi(busy = true, status = "Allow screen sharing",
                    metrics = "old session", pairingCode = "7K4M2Q", intent = intent))
                WebRtcState.cancelConsent()
                assertEquals(WebRtcUi(status = "Sharing cancelled"), WebRtcState.state.value)
                WebRtcState.cancelConsent() // Idempotent; no stale code/busy/diagnostics.
                assertFalse(WebRtcState.state.value.busy)
                assertEquals("", WebRtcState.state.value.pairingCode)
            }
            WebRtcState.set(WebRtcUi(busy = true, status = "Allow screen sharing",
                pairingCode = "8K4M2Q", intent = intent))
            assertEquals("8K4M2Q", WebRtcState.state.value.pairingCode)
            assertEquals(intent, WebRtcState.state.value.intent)
            WebRtcState.cancelConsent()
        }
    }
}
