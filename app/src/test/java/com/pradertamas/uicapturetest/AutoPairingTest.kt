package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test

class AutoPairingTest {
    private class Harness : PairingTransport {
        data class Peer(val code: String, val intent: PairingIntent,
            val message: (String, String, String) -> Unit, val ended: () -> Unit) : PairingLink {
            var closed = false
            override fun send(text: String) = !closed
            override fun close() { closed = true }
            fun event(type: String, status: String = "") { message(type, status, "raw") }
        }
        data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val peers = mutableListOf<Peer>()
        val tasks = mutableListOf<Task>()
        var now = 0L
        private var next = 0
        val engine = AutoPairing(this, { delay, action ->
            val task = Task(now + delay, action); tasks.add(task)
            val cancel: () -> Unit = { task.cancelled = true }; cancel
        }, {}, { listOf("7K4M2Q", "8K4M2Q", "9K4M2Q", "6K4M2Q", "5K4M2Q")[next++ % 5] })
        override fun open(code: String, intent: PairingIntent, message: (String, String, String) -> Unit, ended: () -> Unit): PairingLink {
            val peer = Peer(code, intent, message, ended); peers.add(peer); return peer
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                task.cancelled = true; now = task.at; task.action()
            }
            now = end
        }
        fun waiting() { engine.show(); peers.last().event("registration", "ok") }
        fun consent(): Long {
            peers.last().event("peer-present")
            return checkNotNull(engine.claimConsent())
        }
    }
    @Test fun openingCreatesWithoutConsentAndRotatesOnlyWaiting() {
        val h = Harness(); h.waiting()
        assertEquals(6, h.engine.home.code.length)
        assertEquals(PairingIntent.CREATE, h.peers.single().intent)
        assertNull(h.engine.claimConsent())
        val old = h.peers.last(); h.advance(AutoPairing.ROTATE_MS)
        assertTrue(old.closed); assertNotEquals(old.code, h.engine.home.code)
        h.peers.last().event("registration", "ok")
        val epoch = h.consent(); assertNull(h.engine.claimConsent())
        assertTrue(h.engine.allow(epoch)); assertTrue(h.engine.attach(epoch, {}, {}))
        h.engine.streaming(epoch); val count = h.peers.size
        h.advance(AutoPairing.ROTATE_MS * 2)
        assertEquals(count, h.peers.size); assertEquals(PairingPhase.STREAMING, h.engine.home.phase)
        h.engine.mediaStopped(epoch); assertEquals(PairingPhase.CREATING, h.engine.home.phase)
        assertNotEquals(old.code, h.engine.home.code)
    }
    @Test fun typedPasteDebounceInvalidAndDuplicateJoin() {
        assertEquals("6K4M2Q", AutoPairing.input(" 6k4m2q "))
        for (bad in listOf("ßXXXX", "7K4M2O", "123456", "7K4M2Q2")) assertNull(AutoPairing.input(bad))
        val h = Harness(); h.waiting(); val own = h.peers.last()
        h.engine.typed("7K4M2O"); h.advance(300); assertEquals(1, h.peers.size)
        h.engine.typed(" 6k4m2q "); h.advance(200)
        h.engine.typed(" 6k4m2q "); h.advance(249); assertEquals(1, h.peers.size)
        h.advance(1); assertTrue(own.closed); assertEquals(PairingIntent.JOIN, h.peers.last().intent)
        assertEquals("6K4M2Q", h.peers.last().code)
        h.engine.join("5K4M2Q"); h.engine.typed("5K4M2Q"); h.advance(300)
        assertEquals(2, h.peers.size)
    }
    @Test fun scannedPayloadReplacesOwnCreatorAndCancelRecovers() {
        val h = Harness(); h.waiting(); val old = h.peers.last()
        h.engine.join(checkNotNull(PairingCode.parseQr(PairingCode.receiverUrl("6K4M2Q"))))
        assertTrue(old.closed); assertEquals(PairingIntent.JOIN, h.peers.last().intent)
        h.peers.last().event("registration", "ok"); val epoch = h.consent()
        h.engine.cancel(epoch); assertTrue(h.peers.last().closed)
        h.advance(1000); assertEquals(PairingPhase.CREATING, h.engine.home.phase)
        assertFalse(h.engine.allow(epoch)) // Old consent cannot authorize new capture.
        old.ended(); assertEquals(PairingPhase.CREATING, h.engine.home.phase)
    }
    @Test fun collisionsBoundedAndFailedJoinReturnsHome() {
        val h = Harness(); h.engine.show()
        repeat(4) { h.peers.last().event("registration", "conflict") }
        assertEquals(4, h.peers.size); assertEquals(PairingPhase.ERROR, h.engine.home.phase)
        h.advance(1000); h.peers.last().event("registration", "ok")
        h.engine.join("6K4M2Q"); h.peers.last().event("registration", "not-found")
        h.advance(1000); assertEquals(PairingIntent.CREATE, h.engine.home.intent)
        assertTrue(h.engine.home.notice.contains("not found"))
    }
    @Test fun closeIsIdempotentAndReopeningCreatesFreshWaitingCode() {
        val h = Harness(); h.waiting(); val old = h.peers.last()
        h.engine.hide(); h.engine.close(); old.ended()
        assertTrue(old.closed); assertEquals(PairingPhase.STOPPED, h.engine.home.phase)
        h.engine.show(); assertNotEquals(old.code, h.peers.last().code)
    }
    @Test fun repeatedCancelAndNativeDisposalBoundary() {
        val h = Harness(); h.engine.show()
        repeat(3) {
            h.peers.last().event("registration", "ok")
            val epoch = h.consent(); h.engine.cancel(epoch); h.advance(1000)
            assertEquals(PairingPhase.CREATING, h.engine.home.phase)
        }
        h.peers.last().event("registration", "ok")
        val epoch = h.consent(); assertTrue(h.engine.allow(epoch))
        var ended = false; var message = ""
        assertTrue(h.engine.attach(epoch, { message = it }, { ended = true }))
        h.peers.last().event("answer"); assertEquals("raw", message)
        val count = h.peers.size
        h.peers.last().event("peer-left"); assertTrue(ended)
        h.advance(1000); assertEquals(count, h.peers.size)
        h.engine.mediaStopped(epoch); assertEquals(count + 1, h.peers.size)
        h.engine.mediaStopped(epoch); assertEquals(count + 1, h.peers.size)
    }
}
