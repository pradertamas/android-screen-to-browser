package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RemoteDeviceTest {
    private val fake = "AAAAAAAAAAAAAAAAAAAAAA"
    private val replacement = "BBBBBBBBBBBBBBBBBBBBBA"
    @Test fun randomFormatAndPersistenceAdapter() {
        repeat(100) {
            val token = RemoteDevice.generate()
            assertTrue(RemoteDevice.valid(token))
            assertTrue(token.length == 22)
            assertTrue(Base64.getUrlDecoder().decode(token).size == 16)
        }
        var saved: String? = null; var generated = 0
        val store = RemoteTokenStore({ saved }, { saved = it; true }, { generated++; fake })
        assertEquals(fake, store.get()); assertEquals(fake, store.get()); assertEquals(1, generated)
        val reopened = RemoteTokenStore({ saved }, { saved = it; true }, { replacement })
        assertEquals(fake, reopened.get()); assertEquals(replacement, reopened.regenerate()); assertEquals(replacement, store.get())
        assertEquals("${PairingCode.RECEIVER_URL}#device=$replacement", RemoteDevice.url(replacement))
        for (token in listOf(fake, replacement)) {
            val link = java.net.URI(RemoteDevice.url(token)) // Shared by Copy and Share.
            assertNull(link.rawQuery)
            assertEquals("device=$token", link.rawFragment)
            assertTrue(RemoteDevice.shareText(token).endsWith(link.toString()))
        }
        assertNotEquals(RemoteDevice.url(fake), RemoteDevice.url(replacement))
        assertTrue(RemoteDevice.shareText(fake).endsWith(RemoteDevice.url(fake)))
        assertEquals("text/plain", RemoteDevice.MIME_TYPE); assertEquals("Screen sharing link", RemoteDevice.SUBJECT)
        assertFalse(RemoteDevice.valid("7K4M2Q")); assertFalse(RemoteDevice.valid(fake + "="))
        assertFalse(RemoteDevice.valid("AAAAAAAAAAAAAAAAAAAAAB")) // Noncanonical trailing bits.
    }
    @Test fun presenceOnlyOwnWaitingAndFreshTokenAfterRotation() {
        class Link(val message: (String, String, String) -> Unit): PairingLink {
            override val remotePresenceSupported = true
            val sent = mutableListOf<String>(); var closed = false
            override fun send(text: String): Boolean { sent.add(text); return true }
            override fun close() { closed = true }
        }
        val links = mutableListOf<Link>(); var token = fake; var count = 0
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val transport = object : PairingTransport {
            override fun open(code: String, intent: PairingIntent, message: (String, String, String) -> Unit, ended: () -> Unit): PairingLink =
                Link(message).also { links.add(it) }
        }
        val engine = AutoPairing(transport, { delay, task ->
            var cancelled = false
            timers.add(delay to { if(!cancelled) task() })
            val cancel: () -> Unit = { cancelled = true }; cancel
        }, {}, { if(count++ % 2 == 0) "7K4M2Q" else "8K4M2Q" }, { token })
        engine.show(); assertTrue(links.last().sent.isEmpty())
        links.last().message("registration", "ok", "")
        assertTrue(links.last().sent.single().contains(fake))
        val old = links.last(); token = replacement; engine.refreshRemotePresence()
        assertTrue(old.sent.last().contains(replacement))
        timers.last { it.first == AutoPairing.ROTATE_MS }.second()
        assertTrue(old.closed); links.last().message("registration", "ok", "")
        assertTrue(links.last().sent.single().contains(replacement))
        assertFalse(links.last().sent.single().contains(fake))
        links.last().message("peer-present", "", "")
        val current = links.last(); val sent = current.sent.size
        engine.refreshRemotePresence(); assertEquals(sent, current.sent.size)
        val epoch = checkNotNull(engine.claimConsent()); assertTrue(engine.allow(epoch))
        engine.streaming(epoch); engine.refreshRemotePresence(); assertEquals(sent, current.sent.size)
        engine.mediaStopped(epoch); links.last().message("registration", "ok", "")
        assertTrue(links.last().sent.single().contains(replacement))
        links.last().message("peer-present", "", "")
        val cancelled = checkNotNull(engine.claimConsent()); engine.cancel(cancelled)
        timers.last { it.first == 1000L }.second(); links.last().message("registration", "ok", "")
        assertTrue(links.last().sent.single().contains(replacement))
        engine.join("6K4M2Q"); links.last().message("registration", "ok", "")
        assertTrue(links.last().sent.isEmpty())
    }
    @Test fun dedicatedPreferencesExcludedFromBackupAndTransfer() {
        val xml = sequenceOf(java.io.File("src/main/res/xml"), java.io.File("app/src/main/res/xml")).first { it.isDirectory }
        val parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val legacy = parser.parse(java.io.File(xml, "backup_rules.xml"))
        val extraction = parser.parse(java.io.File(xml, "data_extraction_rules.xml"))
        fun excluded(node: org.w3c.dom.Node): Boolean {
            val children = node.childNodes
            return (0 until children.length).any { i ->
                val entry = children.item(i)
                entry.nodeName == "exclude" && entry.attributes?.getNamedItem("domain")?.nodeValue == "sharedpref" &&
                    entry.attributes?.getNamedItem("path")?.nodeValue == RemoteDevice.PREFS_FILE + ".xml"
            }
        }
        assertTrue(excluded(legacy.documentElement))
        for (section in listOf("cloud-backup", "device-transfer"))
            assertTrue(excluded(extraction.getElementsByTagName(section).item(0)))
    }
    @Test fun oldRelayNeverReceivesPersistentToken() {
        var callback: ((String, String, String) -> Unit)? = null
        var sent = false; var generated = false
        val engine = AutoPairing(object : PairingTransport {
            override fun open(code: String, intent: PairingIntent, message: (String, String, String) -> Unit, ended: () -> Unit): PairingLink {
                callback = message
                return object : PairingLink {
                    override fun send(text: String): Boolean { sent = true; return true }
                    override fun close() {}
                }
            }
        }, { _, _ -> {} }, {}, { "7K4M2Q" }, { generated = true; fake })
        engine.show(); checkNotNull(callback)("registration", "ok", "")
        assertFalse(sent); assertFalse(generated)
    }
}
