package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test

class ReceiverVisibilityTest {
    private class Fixture {
        data class Task(val due: Long, val run: () -> Unit, var canceled: Boolean = false)
        var now = 0L; val tasks = mutableListOf<Task>(); val ended = mutableListOf<ReceiverSessionIdentity>()
        val published = mutableListOf<ReceiverPresence>()
        val viewer = Any()
        val life = ReceiverVisibility({ now }, { delay, action ->
            val task = Task(now + delay, action); tasks.add(task)
            val cancel: () -> Unit = { task.canceled = true }; cancel
        }, published::add, ended::add)
        fun live() { life.visible(viewer); life.connection(ReceiverPhase.PAIRING); life.connection(ReceiverPhase.CONNECTING); life.connection(ReceiverPhase.RECEIVING) }
        fun hide() = life.hidden(viewer, false)
    }
    @Test fun hideReturnUsesSameSessionAndCancelsExpiry() {
        val f = Fixture(); f.live(); val id = f.life.state.session
        f.hide(); assertSame(id, f.life.state.session); assertEquals(60000L, f.life.state.absentUntilElapsed)
        f.now = 20000; f.life.visible(Any())
        f.tasks.single().run(); assertSame(id, f.life.state.session)
        assertTrue(f.life.state.visible); assertNull(f.life.state.absentUntilElapsed); assertTrue(f.ended.isEmpty())
    }
    @Test fun exactDeadlineExpiresOnceAndPublishesIdleToObservers() {
        val f = Fixture(); f.live(); val id = f.life.state.session; f.hide()
        f.now = 59999; assertFalse(f.life.checkExpiry())
        f.now = 60000; assertTrue(f.life.checkExpiry()); f.tasks.forEach { it.run() }; f.life.checkExpiry()
        assertEquals(listOf(id), f.ended); assertFalse(f.published.last().live); assertNull(f.life.state.session)
    }
    @Test fun rapidTransitionsAndOldSessionCallbacksCannotStopNewLifetime() {
        val f = Fixture(); f.live(); f.hide(); val old = f.tasks.last()
        f.now = 10000; f.life.visible(f.viewer); f.hide(); val fresh = f.tasks.last()
        f.now = 60000; old.run(); assertTrue(f.life.state.live)
        f.now = 70000; fresh.run(); assertEquals(1, f.ended.size)
        f.live(); val id = f.life.state.session; fresh.run(); assertSame(id, f.life.state.session)
    }
    @Test fun configurationDetachDoesNotStartOrExtendGraceAndStaleHideIsInert() {
        val f = Fixture(); f.live(); val id = f.life.state.session
        f.life.hidden(f.viewer, true); assertNull(f.life.state.absentUntilElapsed)
        val replacement = Any(); f.life.visible(replacement); f.life.hidden(f.viewer, false)
        assertTrue(f.life.state.visible); assertSame(id, f.life.state.session); assertTrue(f.tasks.isEmpty())
        f.life.hidden(replacement, false); val deadline = f.life.state.absentUntilElapsed
        f.now = 10000; f.life.hidden(replacement, true); assertEquals(deadline, f.life.state.absentUntilElapsed)
    }
    @Test fun delayedReturnCannotReviveExpiredPeerAndFreshProcessIsIdle() {
        val f = Fixture(); f.live(); val id = f.life.state.session!!; f.hide(); f.now = 90000
        assertFalse(f.life.matches(id)); f.life.visible(f.viewer)
        assertFalse(f.life.state.live); assertEquals(listOf(id), f.ended)
        assertNull(Fixture().life.state.session)
    }
    @Test fun earlyTimerKeepsDeadlineAndFailureCancelsUiGrace() {
        val f = Fixture(); f.live(); f.hide(); f.now = 1000; f.tasks.last().run()
        assertEquals(60000L, f.life.state.absentUntilElapsed); assertEquals(60000L, f.tasks.last().due)
        f.life.connection(ReceiverPhase.ERROR); f.now = 60000; f.tasks.forEach { it.run() }
        assertTrue(f.ended.isEmpty()); assertFalse(f.life.state.live)
    }
    @Test fun connectingDoesNotAcquireUiGrace() {
        val f = Fixture(); f.life.visible(f.viewer); f.life.connection(ReceiverPhase.CONNECTING); f.hide()
        assertNull(f.life.state.absentUntilElapsed); assertTrue(f.tasks.isEmpty())
    }
    @Test fun explicitStopIsImmediateExactAndCannotStopReplacement() {
        val f = Fixture(); f.live(); val a = f.life.state.session!!; f.hide()
        assertTrue(f.life.stop(a)); assertFalse(f.life.stop(a)); assertEquals(listOf(a), f.ended)
        f.live(); val b = f.life.state.session; assertFalse(f.life.stop(a)); assertSame(b, f.life.state.session)
        f.now = 60000; f.tasks.forEach { it.run() }; assertTrue(f.life.state.live)
    }
    @Test fun rendererReplacementAndStaleDetachDoNotDisposeSession() {
        val a = Any(); val b = Any(); val attached = mutableListOf<Any>(); val detached = mutableListOf<Any>()
        val binding = ReceiverRendererBinding<Any>(attached::add, detached::add)
        binding.attach(a); binding.detach(a); binding.attach(b); binding.detach(a)
        assertSame(b, binding.current()); assertEquals(listOf(a, b), attached); assertEquals(listOf(a), detached)
        binding.detach(b); binding.detach(b); assertNull(binding.current()); assertEquals(listOf(a, b), detached)
    }
    @Test fun liveAndConnectingBlockConflictingActionsUntilRealStop() {
        assertFalse(ReceiverUi().active)
        assertTrue(ReceiverUi(home = ReceiverHome(ReceiverPhase.CONNECTING)).active)
        val f = Fixture(); f.live(); f.hide()
        assertTrue(ReceiverUi(presence = f.life.state).active)
        f.life.connection(ReceiverPhase.ENDED); assertFalse(ReceiverUi(presence = f.life.state).active)
    }
    @Test fun explicitAspectFitContainsPortraitLandscapeEqualAndRotatedFrames() {
        val cases = listOf(Triple(1080f, 1920f, ReceiverFrameSize(1080, 2400)),
            Triple(1080f, 1920f, ReceiverFrameSize(1920, 1080)),
            Triple(1920f, 1080f, ReceiverFrameSize(1080, 2400)),
            Triple(1080f, 1920f, ReceiverFrameSize(1080, 1920)))
        for ((w, h, frame) in cases) {
            val fit = receiverAspectFit(w, h, frame)
            assertTrue(fit.width <= w + .001f); assertTrue(fit.height <= h + .001f)
            assertEquals(frame.width.toDouble() / frame.height, fit.width.toDouble() / fit.height, .00001)
            assertTrue(kotlin.math.abs(fit.width - w) < .001 || kotlin.math.abs(fit.height - h) < .001)
        }
        assertEquals(ReceiverFit(0f, 0f), receiverAspectFit(0f, 100f, ReceiverFrameSize(1, 2)))
    }
}
