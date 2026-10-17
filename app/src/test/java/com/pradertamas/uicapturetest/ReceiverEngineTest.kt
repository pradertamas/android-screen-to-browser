package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test

class ReceiverEngineTest {
    private class Fixture {
        data class Timer(val ms: Long, val task: () -> Unit, var cancelled: Boolean = false)
        val timers = mutableListOf<Timer>()
        inner class Link(val code: String, val intent: PairingIntent, val events: ReceiverEvents) : PairingLink {
            var closes = 0; val sent = mutableListOf<String>()
            override fun close() { closes++ }
            override fun send(text: String): Boolean { sent.add(text); return true }
        }
        inner class Media(val candidate: (ReceiverIce) -> Unit, val fail: () -> Unit) : ReceiverMedia {
            var offers = 0; var closes = 0; val applied = mutableListOf<ReceiverIce>()
            lateinit var ready: () -> Unit
            lateinit var answer: (String) -> Unit
            lateinit var failure: () -> Unit
            override fun offer(sdp: String, remoteReady: () -> Unit, answer: (String) -> Unit, failure: () -> Unit) {
                offers++; ready = remoteReady; this.answer = answer; this.failure = failure
            }
            override fun ice(candidate: ReceiverIce) { applied.add(candidate) }
            override fun close() { closes++ }
        }
        val links = mutableListOf<Link>(); val media = mutableListOf<Media>()
        var serial = 0
        val engine = ReceiverEngine(object : ReceiverWire {
            override fun open(code: String, intent: PairingIntent, events: ReceiverEvents): PairingLink = Link(code, intent, events).also(links::add)
            override fun answer(sdp: String) = "answer:$sdp"
            override fun candidate(value: ReceiverIce) = "ice:${value.candidate}"
        }, { candidate, fail -> Media(candidate, fail).also(media::add) }, { ms, task ->
            val timer = Timer(ms, task); timers.add(timer)
            val cancel: () -> Unit = { timer.cancelled = true }; cancel
        }, {}, { if (serial++ % 2 == 0) "7K4M2Q" else "8K4M2Q" })
        fun pair(): Link {
            engine.create(); return links.last().also { it.events.registration("ok"); it.events.present() }
        }
        fun fire(ms: Long) { timers.last { it.ms == ms && !it.cancelled }.let { it.cancelled = true; it.task() } }
    }
    private val ice = ReceiverIce("0", 0, "fake-candidate")
    @Test fun receiverRegistrationAndBothPairingOrdersNeverOffer() {
        val f = Fixture(); val creator = f.pair()
        assertEquals(PairingIntent.CREATE, creator.intent)
        assertEquals(ReceiverPhase.CONNECTING, f.engine.home.phase)
        assertTrue(f.media.isEmpty()); assertTrue(creator.sent.isEmpty())
        assertTrue(f.engine.join("bad").not())
        f.engine.stop(); assertTrue(f.engine.join(" 7k4m2q "))
        assertEquals(PairingIntent.JOIN, f.links.last().intent)
        assertEquals(1, creator.closes)
        f.links.last().events.registration("ok"); f.links.last().events.present()
        assertTrue(f.media.isEmpty())
        for (intent in PairingIntent.entries) {
            val url = java.net.URI(ReceiverRegistration.url("7K4M2Q", intent))
            assertTrue(url.query.contains("role=receiver")); assertTrue(url.query.contains("intent=${intent.wire}"))
        }
    }
    @Test fun incomingOfferAnswersOnceAndDrainsEarlyIceAfterRemoteSet() {
        val f = Fixture(); val link = f.pair()
        link.events.ice(ice); link.events.offer("offer")
        val media = f.media.single(); assertEquals(1, media.offers); assertTrue(media.applied.isEmpty())
        link.events.offer("duplicate"); assertEquals(1, media.offers)
        media.ready(); assertEquals(listOf(ice), media.applied)
        media.answer("answer"); assertEquals(listOf("answer:answer"), link.sent)
        link.events.ice(ice); assertEquals(2, media.applied.size)
        assertEquals(ReceiverPhase.CONNECTING, f.engine.home.phase)
        f.engine.firstFrame(); assertEquals(ReceiverPhase.RECEIVING, f.engine.home.phase)
    }
    @Test fun stopIsIdempotentAndAllLateCallbacksAreIgnoredAcrossNewSession() {
        val f = Fixture(); val old = f.pair(); old.events.offer("offer"); val m = f.media.single()
        f.engine.stop(); f.engine.stop()
        assertEquals(1, old.closes); assertEquals(1, m.closes)
        f.engine.create(); val new = f.links.last()
        old.events.registration("ok"); old.events.present(); old.events.offer("stale"); old.events.ice(ice); old.events.ended()
        m.ready(); m.answer("late"); m.candidate(ice); m.failure(); m.fail()
        assertEquals(ReceiverPhase.PAIRING, f.engine.home.phase); assertTrue(old.sent.isEmpty())
        assertEquals(0, new.closes)
        new.events.registration("ok"); new.events.present(); new.events.offer("new")
        assertEquals(2, f.media.size); assertNotSame(m, f.media.last())
        assertTrue(f.media.last().applied.isEmpty())
    }
    @Test fun cancelStopAndFailuresAllowFreshReceiverAndSenderHome() {
        val f = Fixture()
        repeat(5) { f.pair().events.ended(); assertEquals(ReceiverPhase.ENDED, f.engine.home.phase) }
        f.pair().events.offer("offer"); f.engine.firstFrame(); f.links.last().events.ended()
        assertEquals(ReceiverPhase.ENDED, f.engine.home.phase)
        f.engine.create(); f.links.last().events.registration("not-found")
        assertEquals(ReceiverPhase.ERROR, f.engine.home.phase)
        f.engine.create(); assertEquals(ReceiverPhase.PAIRING, f.engine.home.phase)
        f.engine.stop()
        var senderOpened = false
        val sender = AutoPairing(object : PairingTransport {
            override fun open(code: String, intent: PairingIntent, message: (String, String, String) -> Unit, ended: () -> Unit): PairingLink {
                senderOpened = true; return object : PairingLink { override fun close() {}; override fun send(text: String) = true }
            }
        }, { _, _ -> {} }, {})
        sender.show(); assertTrue(senderOpened); sender.close()
    }
    @Test fun waitingRotationCollisionAndSingleDisconnectGrace() {
        val f = Fixture(); f.engine.create(); val first = f.links.last()
        first.events.registration("conflict"); val retry = f.links.last()
        assertNotEquals(first.code, retry.code); assertEquals(1, first.closes)
        first.events.ended(); assertEquals(ReceiverPhase.PAIRING, f.engine.home.phase)
        retry.events.registration("ok"); f.fire(ReceiverEngine.ROTATE_MS)
        assertEquals(1, retry.closes)
        val current = f.links.last(); current.events.registration("ok"); current.events.present(); current.events.offer("offer")
        assertTrue(f.timers.filter { it.ms == ReceiverEngine.ROTATE_MS }.all { it.cancelled })
        f.engine.firstFrame()
        repeat(3) { f.engine.connection("DISCONNECTED") }
        assertEquals(1, f.timers.count { it.ms == ReceiverEngine.GRACE_MS && !it.cancelled })
        f.engine.connection("CONNECTED"); assertTrue(f.timers.filter { it.ms == ReceiverEngine.GRACE_MS }.all { it.cancelled })
        assertEquals(ReceiverPhase.RECEIVING, f.engine.home.phase)
        f.engine.connection("DISCONNECTED"); f.fire(ReceiverEngine.GRACE_MS)
        assertEquals(ReceiverPhase.ERROR, f.engine.home.phase)
        assertTrue(f.timers.all { it.cancelled })
    }
    @Test fun privacyAndNoCaptureContract() {
        val source = sequenceOf(java.io.File("src/main/java/com/pradertamas/uicapturetest"), java.io.File("app/src/main/java/com/pradertamas/uicapturetest")).first { it.isDirectory }
        for (name in listOf("ReceiverActivity.kt", "ReceiverViewModel.kt", "ReceiverSignaling.kt", "WebRtcReceiverSession.kt")) {
            val text = java.io.File(source, name).readText()
            assertFalse(text.contains("startForegroundService(")); assertFalse(text.contains("createScreenCaptureIntent("))
            assertFalse(text.contains("createOffer(")); assertFalse(text.contains("Log.")); assertFalse(text.contains("println("))
        }
        val f = Fixture(); f.pair()
        assertFalse(f.engine.home.status.contains(f.engine.home.code))
    }
    @Test fun boundedCollisionAndIceQueuesCannotLeakIntoRetry() {
        val f = Fixture(); f.engine.create()
        repeat(4) { f.links.last().events.registration("conflict") }
        assertEquals(ReceiverPhase.ERROR, f.engine.home.phase)
        assertTrue(f.links.size <= 4)
        assertTrue(f.links.all { it.closes == 1 })
        val link = f.pair()
        repeat(257) { link.events.ice(ice) }
        assertEquals(ReceiverPhase.ERROR, f.engine.home.phase)
        val fresh = f.pair(); fresh.events.offer("fresh")
        f.media.last().ready(); assertTrue(f.media.last().applied.isEmpty())
    }
    @Test fun failedClosedAndPreStreamTimeoutPermitRetry() {
        for (state in listOf("FAILED", "CLOSED")) {
            val f = Fixture(); f.pair(); f.engine.connection(state)
            assertEquals(ReceiverPhase.ERROR, f.engine.home.phase)
            f.engine.create(); assertEquals(ReceiverPhase.PAIRING, f.engine.home.phase)
        }
        val f = Fixture(); f.pair(); f.fire(60_000)
        assertEquals(ReceiverPhase.ERROR, f.engine.home.phase)
        f.engine.create(); f.fire(15_000)
        assertEquals(ReceiverPhase.ERROR, f.engine.home.phase)
        f.engine.create(); assertEquals(ReceiverPhase.PAIRING, f.engine.home.phase)
    }
}
