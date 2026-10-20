package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class MediaAttemptDiagnosticsTest {
    private val candidate = "candidate:privateFoundation 1 UDP 123456 192.0.2.42 54321 typ srflx raddr 10.0.0.1 rport 1234 ufrag privateUfrag"

    @Test fun firstTerminalAndSummaryAreExactlyOnceDespiteConcurrentAndLateCallbacks() {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val trace = MediaAttemptDiagnostics("SEND", { 5L }, lines::add)
        val go = CountDownLatch(1)
        val threads = (1..8).map { Thread { go.await(); trace.terminal("PEER_FAILED", "peer-failed") }.also { it.start() } }
        go.countDown(); threads.forEach { it.join() }
        trace.terminal("SERVICE_DESTROY", "destroy"); trace.summary(); trace.summary()
        assertEquals(1, lines.count { it.contains("event=SENDER_TERMINAL_ENTER ") })
        assertEquals(1, lines.count { it.contains("event=TERMINAL_MEDIA_SUMMARY ") })
        assertTrue(lines.last().contains("firstTerminal=PEER_FAILED"))
    }

    @Test fun existingSharedRunnableDistinguishesDisconnectFromInitialWatchdogWithoutSchedulingAnything() {
        var now = 0L; val lines = mutableListOf<String>()
        val trace = MediaAttemptDiagnostics("SEND", { now }, lines::add)
        trace.timeoutArmed(false, 45000)
        now = 200; trace.state("PEER", "DISCONNECTED"); trace.timeoutArmed(true, 15000)
        now = 15200
        assertEquals("DISCONNECT_WATCHDOG", trace.timeoutFired())
        trace.terminal("DISCONNECT_WATCHDOG", "timeout"); trace.timeoutsCancelled("finish"); trace.summary()
        assertEquals(1, lines.count { it.contains("event=SENDER_DISCONNECT_WATCHDOG_ARMED ") })
        assertEquals(1, lines.count { it.contains("event=SENDER_DISCONNECT_WATCHDOG_FIRED ") })
        assertTrue(lines.last().contains("watchdogFired=true"))
    }

    @Test fun reconnectCancelsAllExistingDisconnectOccurrencesAndSetupDeadline() {
        val lines = mutableListOf<String>(); val trace = MediaAttemptDiagnostics("SEND", { 0 }, lines::add)
        trace.timeoutArmed(false, 45000); repeat(2) { trace.timeoutArmed(true, 15000) }
        trace.state("PEER", "CONNECTED"); trace.timeoutsCancelled("peer-connected")
        trace.summary()
        assertEquals(1, lines.count { it.contains("event=SENDER_DISCONNECT_WATCHDOG_CANCELLED ") })
        assertTrue(lines.any { it.contains("reason=peer-connected pending=0") })
        assertTrue(lines.last().contains("watchdogArmed=false watchdogFired=false"))
    }

    @Test fun initialSetupTimeoutIsNotMisreportedAsDisconnectWatchdog() {
        val lines = mutableListOf<String>(); val trace = MediaAttemptDiagnostics("SEND", { 0 }, lines::add)
        trace.timeoutArmed(false, 45000)
        assertEquals("SETUP_WATCHDOG", trace.timeoutFired())
        assertFalse(lines.any { it.contains("event=SENDER_DISCONNECT_WATCHDOG_FIRED ") })
    }

    @Test fun candidateMetadataAndPairSummaryCannotExposeCandidateAddressOrCredentials() {
        val info = MediaAttemptDiagnostics.candidateMetadata(candidate)
        assertEquals("srflx", info.type); assertEquals("udp", info.protocol)
        val lines = mutableListOf<String>(); val trace = MediaAttemptDiagnostics("RECEIVE", { 0 }, lines::add)
        trace.local(candidate); trace.sent(candidate, true); trace.received(candidate, false, 0)
        trace.queued(1); trace.flush(true, 1); trace.flushedCandidate()
        trace.applyAttempt(candidate, true, 1); trace.applyResult(candidate, false, true, 1); trace.flush(false, 0)
        trace.selectedPair(true, "host", "srflx", "udp", true, false); trace.summary()
        val output = lines.joinToString("\n")
        for (private in listOf("candidate:", "privateFoundation", "192.0.2.42", "54321", "10.0.0.1", "privateUfrag", "123456")) {
            assertFalse(private, output.contains(private))
        }
        assertTrue(lines.last().contains("localGenerated=1 localSent=1 localSendFailed=0 remoteReceived=1 remoteQueued=1 queuedFlushed=1 applyAttempted=1 applySuccess=0 applyFailure=1"))
        assertEquals(MediaAttemptDiagnostics.CandidateMetadata("unknown", "unknown"), MediaAttemptDiagnostics.candidateMetadata("malformed private"))
    }

    @Test fun countersAndLateCallbacksBelongToOriginalAttemptNotReplacement() {
        val old = mutableListOf<String>(); val fresh = mutableListOf<String>()
        val a = MediaAttemptDiagnostics("RECEIVE", { 1 }, old::add, 1)
        val b = MediaAttemptDiagnostics("RECEIVE", { 2 }, fresh::add, 2)
        a.local(candidate); a.terminal("PEER_FAILED", "peer-failed"); a.summary()
        a.state("PEER", "CLOSED"); a.local(candidate)
        b.summary()
        assertTrue(old.last().contains("generation=1")); assertTrue(old.last().contains("afterTerminal=true"))
        assertTrue(fresh.single().contains("generation=2")); assertTrue(fresh.single().contains("localGenerated=0"))
        assertNotEquals(old.first().substringAfter("attempt=").substringBefore(' '), fresh.single().substringAfter("attempt=").substringBefore(' '))
    }

    @Test fun sdpMilestonesAndGatheringSummaryUseOnlySafeMetadata() {
        var now = 100L; val lines = mutableListOf<String>()
        val trace = MediaAttemptDiagnostics("SEND", { now }, lines::add)
        trace.peerCreated(); trace.sdp("SET_LOCAL_OFFER", "SUCCESS"); trace.sdp("SET_REMOTE_ANSWER", "SUCCESS")
        trace.local(candidate); trace.state("GATHERING", "COMPLETE"); now = 300; trace.summary()
        assertTrue(lines.any { it.contains("event=ICE_GATHERING_SUMMARY ") && it.contains("srflx=1") })
        assertTrue(lines.last().contains("durationMs=200")); assertTrue(lines.last().contains("localDescription=true remoteDescription=true"))
    }

    @Test fun brokenDiagnosticSinkCannotChangeMediaControlFlow() {
        val trace = MediaAttemptDiagnostics("SEND", { 0 }, { error("sink-failure") })
        trace.local(candidate); trace.terminal("PEER_FAILED", "peer-failed"); trace.summary()
    }
}
