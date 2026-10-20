package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test

/** Engine + visibility integration with an adversarial deterministic scheduler; no native/network timing. */
class ReceiverBackgroundRetentionTest {
    private class Fixture {
        data class Task(val due: Long, val delay: Long, val run: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        val tasks = mutableListOf<Task>()
        val closes = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        var peers = 0; var disposed = 0
        var anchorRequests = 0; var anchorForegrounds = 0; var anchorStops = 0; var anchorStartId = 0
        val anchor = ReceiverAnchorLifecycle({ key ->
            life.state.live && life.state.session?.anchorKey == key && engine.hasMedia
        }, { anchorForegrounds++ }, { key ->
            life.state.session?.takeIf { it.anchorKey == key }?.let(life::stop) == true
        }, { anchorStops++ })
        val anchorAdmission = ReceiverAnchorAdmission { id ->
            anchorRequests++; anchor.command("START", id.anchorKey, ++anchorStartId)
        }
        lateinit var events: ReceiverEvents
        private val later: (Long, () -> Unit) -> (() -> Unit) = { delay, action ->
            val task = Task(now + delay, delay, action); tasks.add(task)
            val cancel: () -> Unit = { task.cancelled = true }; cancel
        }
        val engine = ReceiverEngine(object : ReceiverWire {
            override fun open(code: String, intent: PairingIntent, events: ReceiverEvents): PairingLink {
                this@Fixture.events = events
                return object : PairingLink { override fun send(text: String) = true; override fun close() {} }
            }
            override fun answer(sdp: String) = sdp
            override fun candidate(value: ReceiverIce) = value.candidate
        }, { _, _ -> peers++; object : ReceiverMedia {
            override fun offer(sdp: String, remoteReady: () -> Unit, answer: (String) -> Unit, failure: () -> Unit) { remoteReady(); answer("answer") }
            override fun ice(candidate: ReceiverIce) {}
            override fun close() { disposed++ }
        } }, later, { life.connection(it.phase) }, { "ABC234" }, closes::add,
            { diagnostics::add }, { now })
        val life: ReceiverVisibility = ReceiverVisibility({ now }, later, { p ->
            engine.mediaAuthority(engine.mediaAttempt, receiverMediaAuthority(p, now))
            anchorAdmission.changed(ReceiverUi(engine.home, presence = p), MediaRole.RECEIVE, engine.hasMedia)
            anchor.changed()
        }, { endVisibleSession() })
        private fun endVisibleSession() {
            val reason = life.lastStopReason ?: "ui-background-timeout"
            engine.stop(reason, intentional = reason == "user-stop-active-share")
        }
        val viewer = Any()
        fun start(establish: Boolean = true) {
            life.visible(viewer); engine.create(); events.registration("ok"); events.present(); events.offer("offer")
            if (establish) engine.connection("CONNECTED", ReceiverConnectionSource.PEER)
            engine.firstFrame()
        }
        fun hide() { life.hidden(viewer, false) }
        fun show() { life.visible(viewer) }
        fun disconnect() {
            engine.connection("DISCONNECTED", ReceiverConnectionSource.ICE)
            engine.connection("DISCONNECTED", ReceiverConnectionSource.PEER)
        }
        fun advance(target: Long) {
            while (true) {
                val task = tasks.filter { !it.cancelled && it.due <= target }.minByOrNull { it.due } ?: break
                now = task.due; task.cancelled = true; task.run()
            }
            now = target
        }
        fun live() { assertEquals(ReceiverPhase.RECEIVING, engine.home.phase); assertTrue(life.state.live); assertEquals(0, disposed); assertTrue(closes.isEmpty()) }
        fun terminal(reason: String) {
            assertEquals(listOf(reason), closes); assertEquals(1, disposed); assertFalse(life.state.live)
            assertEquals(1, diagnostics.count { it.contains("event=RECEIVER_TERMINAL_ENTER ") })
        }
    }
    @Test fun anchorSurvivesBackgroundDisconnectAndSamePeerForegroundRecovery() {
        val f = Fixture(); f.start(); f.hide(); f.advance(5000); f.events.signalingUnavailable(); f.disconnect()
        f.advance(25000); f.live(); assertEquals(0, f.anchorStops)
        f.show(); f.engine.connection("CONNECTED", ReceiverConnectionSource.PEER)
        assertEquals(1, f.anchorRequests); assertEquals(1, f.anchorForegrounds); assertEquals(0, f.anchorStops)
        assertEquals(1, f.peers)
    }
    @Test fun trueTerminalsStopAnchorExactlyOnceAndNotificationUsesExistingStop() {
        for (cause in listOf("timeout", "FAILED", "CLOSED", "stop", "notification")) {
            val f = Fixture(); f.start(); f.hide(); f.disconnect()
            when (cause) {
                "timeout" -> f.advance(60000)
                "stop" -> f.life.stop(f.life.state.session!!)
                "notification" -> f.anchor.command("STOP", f.life.state.session!!.anchorKey, ++f.anchorStartId)
                else -> f.engine.connection(cause, ReceiverConnectionSource.PEER)
            }
            assertEquals(1, f.disposed); assertEquals(1, f.anchorStops); assertNull(f.anchor.key)
            f.advance(60000); f.anchor.changed(); assertEquals(1, f.anchorStops)
        }
    }
    @Test fun foregroundDisconnectStillExpiresExactlyOnceAfterFourSeconds() {
        val f = Fixture(); f.start(); f.disconnect(); f.advance(3999); f.live()
        val old = f.tasks.last { it.delay == 4000L }; f.advance(4000); old.run()
        f.terminal("network-grace-expired")
    }
    @Test fun backgroundStartedDisconnectIsRetainedPastFourSecondsButNotSixty() {
        val f = Fixture(); f.start(); val id = f.life.state.session; f.hide(); f.advance(12000); f.disconnect()
        f.advance(25000); f.live(); assertSame(id, f.life.state.session); assertEquals(1, f.peers)
        assertFalse(f.tasks.any { !it.cancelled && it.delay == 4000L })
        f.advance(60000); f.terminal("ui-background-timeout")
        assertFalse(f.tasks.any { it.delay == 4000L }) // Expiry is not a foreground return.
        f.tasks.forEach { it.run() }; f.terminal("ui-background-timeout")
    }
    @Test fun thirteenSecondRecoveryKeepsSameSessionAndCancelsForegroundGrace() {
        val f = Fixture(); f.start(); val id = f.life.state.session; f.hide()
        f.advance(5000); f.events.signalingUnavailable(); f.advance(11900); f.disconnect()
        f.advance(13400); f.show(); val old = f.tasks.last { it.delay == 4000L }
        f.advance(13480); f.engine.connection("CONNECTED", ReceiverConnectionSource.PEER)
        old.run(); f.advance(65000); f.live()
        assertSame(id, f.life.state.session); assertEquals(1, f.peers); assertTrue(old.cancelled)
        assertFalse(f.engine.home.signalingAvailable)
    }
    @Test fun foregroundReturnWithoutRecoveryGetsOneFreshNormalGrace() {
        val f = Fixture(); f.start(); f.hide(); f.advance(12000); f.disconnect(); f.advance(25000); f.show()
        f.advance(28999); f.live(); f.advance(29000); f.terminal("network-grace-expired")
    }
    @Test fun foregroundStartedGraceCannotBeShelteredByBackgrounding() {
        val f = Fixture(); f.start(); f.disconnect(); f.advance(1500); f.hide()
        f.advance(4000); f.terminal("network-grace-expired")
    }
    @Test fun hiddenRecoveryDoesNotExtendAbsoluteAbsenceDeadline() {
        val f = Fixture(); f.start(); f.hide(); f.advance(12000); f.disconnect(); f.advance(25000)
        f.engine.connection("CONNECTED", ReceiverConnectionSource.PEER); f.live()
        assertEquals(60000L, f.life.state.absentUntilElapsed)
        f.advance(60000); f.terminal("ui-background-timeout")
    }
    @Test fun connectedBackgroundStillExpiresAtOriginalDeadline() {
        val f = Fixture(); f.start(); f.hide(); f.advance(60000); f.terminal("ui-background-timeout")
    }
    @Test fun failedAndClosedAreImmediateForEitherNativeSourceWhileRetained() {
        for (state in listOf("FAILED", "CLOSED")) for (source in ReceiverConnectionSource.entries) {
            val f = Fixture(); f.start(); f.hide(); f.disconnect(); f.engine.connection(state, source)
            f.terminal("peer-$state"); f.advance(60000); f.terminal("peer-$state")
        }
    }
    @Test fun explicitStopAndRemoteEndBypassRetention() {
        for (stop in listOf(false, true)) {
            val f = Fixture(); f.start(); f.hide(); f.disconnect()
            if (stop) f.engine.stop("user-stop-active-share", intentional = true) else f.events.ended()
            assertEquals(1, f.disposed); assertFalse(f.life.state.live)
        }
    }
    @Test fun signalingLossOrderDoesNotChangeBackgroundRetention() {
        for (socketFirst in listOf(false, true)) {
            val f = Fixture(); f.start(); f.hide()
            if (socketFirst) f.events.signalingUnavailable()
            f.disconnect()
            if (!socketFirst) f.events.signalingUnavailable()
            f.advance(20000); f.live(); assertFalse(f.engine.home.signalingAvailable)
        }
    }
    @Test fun iceConnectedAloneCannotEstablishOrCancelForegroundRecoveryGrace() {
        val f = Fixture(); f.start(establish = false)
        f.engine.connection("CONNECTED", ReceiverConnectionSource.ICE); f.hide(); f.disconnect()
        f.advance(1000); f.engine.connection("CONNECTED", ReceiverConnectionSource.ICE)
        f.advance(4000); f.terminal("network-grace-expired")
    }
    @Test fun cancelledSameAttemptGraceCannotKillRecoveryOrLaterDisconnectEpisode() {
        val f = Fixture(); f.start(); f.disconnect(); val old = f.tasks.last { it.delay == 4000L }
        f.advance(500); f.engine.connection("CONNECTED", ReceiverConnectionSource.PEER); old.run(); f.live()
        f.advance(1000); f.disconnect(); old.run(); f.live()
        f.advance(5000); f.terminal("network-grace-expired")
    }
    @Test fun repeatedHideShowCannotRenewUnresolvedForegroundRecovery() {
        val f = Fixture(); f.start(); f.hide(); f.advance(12000); f.disconnect(); f.advance(20000); f.show()
        val timer = f.tasks.single { !it.cancelled && it.delay == 4000L }
        repeat(3) { f.hide(); f.show() }
        assertSame(timer, f.tasks.single { !it.cancelled && it.delay == 4000L })
        f.advance(24000); f.terminal("network-grace-expired")
    }
    @Test fun retiredAttemptAuthorityAndTimersCannotChangeReplacement() {
        val f = Fixture(); f.start(); f.hide(); f.disconnect(); val attempt = f.engine.mediaAttempt
        val oldEvents = f.events; val oldTimers = f.tasks.toList(); f.engine.stop(); f.closes.clear(); f.disposed = 0
        f.start(); val id = f.life.state.session
        f.engine.mediaAuthority(attempt, ReceiverMediaAuthority.BACKGROUND); oldEvents.ended(); oldTimers.forEach { it.run() }
        f.live(); assertSame(id, f.life.state.session); f.disconnect()
        f.engine.connection("CONNECTED", ReceiverConnectionSource.PEER, attempt)
        f.engine.connection("FAILED", ReceiverConnectionSource.PEER, attempt)
        f.advance(4000)
        assertEquals("network-grace-expired", f.engine.lastCloseReason)
    }
}
