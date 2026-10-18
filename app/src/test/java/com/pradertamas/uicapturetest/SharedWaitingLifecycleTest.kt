package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SharedWaitingLifecycleTest {
    private class Fixture {
        class Link(val message: (String, String, String) -> Unit, val ended: () -> Unit) : PairingLink {
            var closes = 0
            override fun send(text: String) = true
            override fun close() { closes++ }
            fun event(type: String, status: String = "") = message(type, status, "")
        }
        data class Timer(val ms: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val links = mutableListOf<Link>()
        val timers = mutableListOf<Timer>()
        var resumed = true
        var launches = 0
        var claimed: Long? = null
        var serial = 0
        val engine = AutoPairing(object : PairingTransport {
            override fun open(code: String, intent: PairingIntent, message: (String, String, String) -> Unit, ended: () -> Unit): PairingLink =
                Link(message, ended).also(links::add)
        }, { ms, action ->
            val timer = Timer(ms, action); timers.add(timer)
            val cancel: () -> Unit = { timer.cancelled = true }; cancel
        }, {}, { if (serial++ % 2 == 0) "7K4M2Q" else "8K4M2Q" })
        init { engine.show(); links.last().event("registration", "ok") }
        fun effect() { if (resumed) engine.claimConsent()?.let { claimed = it; launches++ } }
        fun resume() { resumed = true; engine.show(); effect() }
        fun stop(externalUi: Boolean = false) {
            resumed = false
            if (engine.shouldHideOnStop(externalUi)) engine.hide()
        }
        fun peer() { links.last().event("peer-present"); effect() }
    }
    @Test fun chooserTransientResumeDoesNotReleaseWaitingOrBackgroundConsent() {
        val f = Fixture(); val link = f.links.single(); val epoch = f.engine.home.epoch
        f.engine.protectSharedWaiting()
        f.stop(externalUi = true)
        f.resume() // Transient chooser-to-Messenger handoff, not final return.
        assertTrue(f.engine.sharedWaitingProtected)
        f.stop(externalUi = false)
        assertEquals(0, link.closes); assertEquals(epoch, f.engine.home.epoch)
        f.peer()
        assertEquals(PairingPhase.CONSENT, f.engine.home.phase)
        assertTrue(f.engine.sharedWaitingProtected); assertEquals(0, f.launches)
        f.resume(); f.effect(); f.resume()
        assertEquals(1, f.launches); assertEquals(epoch, f.claimed)
        assertTrue(f.engine.allow(checkNotNull(f.claimed)))
        assertFalse(f.engine.sharedWaitingProtected)
    }
    @Test fun immediateReturnStillAllowsLaterForegroundJoinOnce() {
        val f = Fixture(); f.engine.protectSharedWaiting(); f.stop(); f.resume()
        assertEquals(0, f.launches); assertTrue(f.engine.sharedWaitingProtected)
        f.peer(); f.effect()
        assertEquals(1, f.launches); assertEquals(PairingPhase.CONSENT, f.engine.home.phase)
    }
    @Test fun peerLeftBeforeResumeClearsPendingAndShareProtection() {
        val f = Fixture(); f.engine.protectSharedWaiting(); f.stop(); f.peer()
        val epoch = f.engine.home.epoch
        f.links.last().event("peer-left")
        assertFalse(f.engine.sharedWaitingProtected); assertTrue(f.engine.home.epoch > epoch)
        f.resume(); assertEquals(0, f.launches); assertNull(f.engine.claimConsent())
    }
    @Test fun terminalEventsAndEpochChangesCannotProtectFreshSessions() {
        val actions: List<(Fixture) -> Unit> = listOf(
            { it.engine.close() },
            { it.links.last().ended() },
            { it.links.last().event("registration", "not-found") },
            { it.engine.cancel(it.engine.home.epoch) },
            { it.engine.join("6K4M2Q") },
            { it.timers.last { timer -> timer.ms == AutoPairing.ROTATE_MS && !timer.cancelled }.action() }
        )
        for (action in actions) {
            val f = Fixture(); f.engine.protectSharedWaiting(); action(f)
            assertFalse(f.engine.sharedWaitingProtected)
            f.engine.show(); assertFalse(f.engine.sharedWaitingProtected)
        }
    }
    @Test fun roleSwitchClosesProtectedSenderAndCreatingCanBeProtected() {
        val f = Fixture(); f.engine.protectSharedWaiting()
        val roles = MediaRoleController({ true }, { f.engine.hide(); f.engine.close() }, {})
        assertTrue(roles.switchRole(MediaRole.RECEIVE))
        assertFalse(f.engine.sharedWaitingProtected); assertEquals(PairingPhase.STOPPED, f.engine.home.phase)
        f.engine.show(); assertEquals(PairingPhase.CREATING, f.engine.home.phase)
        f.engine.protectSharedWaiting(); assertFalse(f.engine.shouldHideOnStop(false))
        f.links.last().event("registration", "ok"); assertTrue(f.engine.sharedWaitingProtected)
    }
    @Test fun ordinaryBackgroundAndExternalScannerBehaviorAreUnchanged() {
        val f = Fixture()
        assertTrue(f.engine.shouldHideOnStop(false)); assertFalse(f.engine.shouldHideOnStop(true))
        f.stop(); assertEquals(PairingPhase.STOPPED, f.engine.home.phase)
        f.resume(); assertFalse(f.engine.sharedWaitingProtected)
    }
    @Test fun mainBindsProtectionWithoutResettingOnResumeOrSavingIt() {
        val root = sequenceOf(File("src/main/java/com/pradertamas/uicapturetest"), File("app/src/main/java/com/pradertamas/uicapturetest")).first { it.isDirectory }
        val source = File(root, "MainActivity.kt").readText()
        assertTrue(source.contains("val willCallHide = PairingHost.controller.shouldHideOnStop(externalUi)"))
        assertTrue(source.contains("private val remoteSharing: Boolean get() = PairingHost.controller.sharedWaitingProtected"))
        val resume = source.substringAfter("override fun onResume()").substringBefore("override fun onStart()")
        assertFalse(resume.contains("clearSharedWaitingProtection")); assertFalse(resume.contains("externalUi = false"))
        assertFalse(source.contains("putBoolean(\"remoteSharing\""))
        // Normal Share is now HTTP request-first; the compatibility engine keeps its policy.
        assertFalse(source.contains("PairingHost.controller.protectSharedWaiting()"))
        assertTrue(source.contains("rememberRequestShare(mediaMode.role)"))
        assertTrue(source.contains("lifecycleState == Lifecycle.State.RESUMED && mediaMode.role == MediaRole.SEND"))
        assertTrue(source.contains("claimConsent()")); assertTrue(source.contains("createConfigForUserChoice()"))
    }
}
