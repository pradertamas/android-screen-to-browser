package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test

class PairingBackendConfigTest {
    @Test fun safeDefaultDisablesPersistentPairingWithoutNetwork() {
        val disabled = PairingBackendConfig("https://pairing.invalid", "pairing.invalid", "/pair")
        assertFalse(disabled.configured)
        var touched = false
        val api = object : AppPairingApi {
            override fun register(): DeviceIdentity { touched = true; error("No request expected") }
            override fun create(identity: DeviceIdentity): String { touched = true; error("No request expected") }
            override fun inspect(identity: DeviceIdentity, token: String): String { touched = true; error("No request expected") }
            override fun accept(identity: DeviceIdentity, token: String): String { touched = true; error("No request expected") }
        }
        val controller = AppPairingController(DeviceIdentityStore({ null }, { touched = true; true }), api,
            { touched = true; it() }, { it() }, {}, {}, disabled)
        controller.create(true)
        assertEquals("Pairing backend is not configured.", controller.ui.message)
        controller.incoming("https://pairing.invalid/pair#invite=" + "A".repeat(43), true)
        assertFalse(touched)
    }
    @Test fun injectedHostAndPathAreStrictAndSeparateFromSignaling() {
        val config = PairingBackendConfig("https://pairing.example.test", "pairing.example.test", "/invite")
        assertTrue(config.configured)
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        val link = "https://pairing.example.test/invite#invite=$token"
        assertEquals(token, AppPairingLink.parse(link, config))
        assertNull(AppPairingLink.parse(link.replace("/invite#", "/pair#"), config))
        assertNull(AppPairingLink.parse(link.replace("pairing.example.test", "other.example.test"), config))
        assertEquals(config.apiBaseUrl, "https://pairing.example.test")
        assertTrue(AppPairingLink.shareText(link, config).contains(link))
        assertFalse(config.copy(apiBaseUrl = "http://pairing.example.test").configured)
        assertFalse(config.copy(apiBaseUrl = "https://user:pass@pairing.example.test").configured)
    }
    @Test fun persistentClientAndParserHaveNoRenderDependency() {
        val source = sequenceOf(java.io.File("src/main/java/com/pradertamas/uicapturetest"), java.io.File("app/src/main/java/com/pradertamas/uicapturetest")).first { it.isDirectory }
        for (file in listOf("AppPairing.kt", "PairingApiClient.kt", "PairingBackendConfig.kt")) {
            val text = java.io.File(source, file).readText()
            assertFalse(text.contains("onrender.com"))
            assertFalse(text.contains("MariaDB")); assertFalse(text.contains("Synology"))
        }
        assertTrue(java.io.File(source, "PairingApiClient.kt").readText().contains("check(config.configured)"))
    }
}