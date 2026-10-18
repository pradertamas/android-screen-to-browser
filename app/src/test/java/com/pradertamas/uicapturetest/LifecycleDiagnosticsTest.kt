package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test

class LifecycleDiagnosticsTest {
    private fun engine(log: (String) -> Unit, bind: ((String, String, String) -> Unit) -> Unit): AutoPairing {
        val transport = object : PairingTransport {
            override fun open(code: String, intent: PairingIntent, message: (String, String, String) -> Unit, ended: () -> Unit): PairingLink {
                bind(message)
                return object : PairingLink { override fun send(text: String) = true; override fun close() {} }
            }
        }
        return AutoPairing(transport, { _, _ -> {} }, {}, { "7K4M2Q" }, diagnostic = log)
    }
    @Test fun loggingDescribesExistingConsentAndHideCleanupWithoutSecrets() {
        val logs = mutableListOf<String>()
        lateinit var signal: (String, String, String) -> Unit
        val controller = engine(logs::add) { signal = it }
        controller.show(); signal("registration", "ok", "secret-raw-message")
        val epoch = controller.home.epoch
        signal("peer-present", "", "secret-raw-message")
        assertEquals(PairingPhase.CONSENT, controller.home.phase)
        assertTrue(logs.any { it.contains("event=peer-present-applied") && it.contains("phase_after=CONSENT") })
        controller.hide()
        assertEquals(PairingPhase.STOPPED, controller.home.phase)
        assertTrue(controller.home.epoch > epoch)
        assertTrue(logs.any { it.contains("event=hide-closing") && it.contains("phase=CONSENT") })
        assertTrue(logs.any { it.contains("event=closed") })
        assertNull(controller.claimConsent())
        controller.show(); signal("registration", "arbitrary-secret-status", "secret-raw-message")
        val output = logs.joinToString()
        for (secret in listOf("7K4M2Q", "secret-raw-message", "arbitrary-secret-status")) assertFalse(output.contains(secret))
        assertTrue(output.contains("result=other"))
    }
    @Test fun diagnosticFailureCannotAlterClaimOrAllowSemantics() {
        lateinit var signal: (String, String, String) -> Unit
        val controller = engine({ error("Diagnostic sink failure") }) { signal = it }
        controller.show(); signal("registration", "ok", "")
        signal("peer-present", "", "")
        val epoch = checkNotNull(controller.claimConsent())
        assertNull(controller.claimConsent()); assertFalse(controller.allow(epoch + 1))
        assertTrue(controller.allow(epoch)); assertEquals(PairingPhase.CONNECTING, controller.home.phase)
        controller.close(); assertFalse(controller.allow(epoch))
    }
}
