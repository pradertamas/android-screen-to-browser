package com.pradertamas.uicapturetest

import java.io.File
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class ConnectionEntryCleanupTest {
    private val main = sequenceOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val android = "http://schemas.android.com/apk/res/android"

    @Test fun canonicalConnectStillParsesAndLegacyInvitePathDoesNot() {
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val capability = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 7 })
        for (role in MediaRole.entries) {
            val link = ConnectionRequestLink.build(ConnectionRequestShareData(id, capability, role))!!
            val parsed = ConnectionRequestLink.parse(link)!!
            assertEquals(id, parsed.requestId); assertEquals(capability, parsed.recipientCapability)
            assertEquals(role, parsed.initiatorRole)
            assertNull(ConnectionRequestLink.parse(link.replace("/connect#", "/pair#")))
        }
    }

    @Test fun onlyConnectActivityHasAnExternalViewRoute() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File(main, "AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        var views = 0
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as Element
            assertNotEquals(".PairingInviteActivity", activity.getAttributeNS(android, "name"))
            val filters = activity.getElementsByTagName("intent-filter")
            for (j in 0 until filters.length) {
                val filter = filters.item(j) as Element
                val actions = filter.getElementsByTagName("action")
                if ((0 until actions.length).none {
                    (actions.item(it) as Element).getAttributeNS(android, "name") == "android.intent.action.VIEW"
                }) continue
                views++
                assertEquals(".ConnectionRequestActivity", activity.getAttributeNS(android, "name"))
                assertEquals("true", activity.getAttributeNS(android, "exported"))
                assertEquals("singleTop", activity.getAttributeNS(android, "launchMode"))
                assertEquals("true", filter.getAttributeNS(android, "autoVerify"))
                val data = filter.getElementsByTagName("data")
                assertEquals(1, data.length)
                val route = data.item(0) as Element
                assertEquals("https", route.getAttributeNS(android, "scheme"))
                assertEquals("\${pairingAppLinkHost}", route.getAttributeNS(android, "host"))
                assertEquals("/connect", route.getAttributeNS(android, "path"))
                assertFalse(route.hasAttributeNS(android, "pathPrefix"))
                assertFalse(route.hasAttributeNS(android, "pathPattern"))
                val categories = filter.getElementsByTagName("category")
                val names = (0 until categories.length).map {
                    (categories.item(it) as Element).getAttributeNS(android, "name")
                }.toSet()
                assertEquals(setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"), names)
            }
        }
        assertEquals(1, views)
    }

    @Test fun productionHasNoLegacyListStateOrNasPairingConsumer() {
        val source = File(main, "java").walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        for (legacy in listOf("class PairedDevice", "class AppPairing", "class DeviceIdentity", "class PairingApiClient",
            "fun refreshDevices", "devicesMutable", "devicesMessage", "loadingDevices", "fun listPairings",
            "/v1/pairings", "/v1/devices/register", "/v1/pairing-invites", "Saved connections", "Paired devices",
            "Save a device connection", "Save this device connection", "Save connection")) {
            assertFalse(legacy, source.contains(legacy))
        }
    }
}
