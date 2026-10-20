package com.pradertamas.uicapturetest

import java.util.Locale

/** Diagnostic state only. Never consulted for admission, scheduling, signaling or media decisions.
 * Each native attempt retains its own recorder, including late callbacks after replacement.
 * Raw SDP, candidate fields and native error messages must never be passed to event(). */
class MediaAttemptDiagnostics(
    private val side: String,
    private val elapsed: () -> Long,
    private val write: (String) -> Unit,
    private val generation: Long = 0,
    private val schedulingClock: () -> Long = elapsed
) {
    data class CandidateMetadata(val type: String, val protocol: String)
    companion object {
        fun candidateMetadata(candidate: String): CandidateMetadata {
            val parts = candidate.trim().split(Regex("\\s+"))
            val marker = parts.indexOfFirst { it.equals("typ", true) }
            return CandidateMetadata(type(parts.getOrNull(marker + 1).takeIf { marker >= 0 }), protocol(parts.getOrNull(2)))
        }
        fun type(value: Any?): String = value?.toString()?.lowercase(Locale.ROOT)
            ?.takeIf { it in setOf("host", "srflx", "prflx", "relay") } ?: "unknown"
        fun protocol(value: Any?): String = value?.toString()?.lowercase(Locale.ROOT)
            ?.takeIf { it in setOf("udp", "tcp") } ?: "unknown"
    }
    private val attempt = Integer.toHexString(System.identityHashCode(this))
    private var flow = "-"
    private var peerAt: Long? = null
    private var gathering = "NEW"
    private var ice = "NEW"
    private var peer = "NEW"
    private var signaling = "STABLE"
    private var terminal: String? = null
    private var summarized = false
    private var localDescription = false
    private var remoteDescription = false
    private var watchdogPending = 0
    private var watchdogFired = false
    private val timeouts = mutableListOf<Pair<Long, Boolean>>()
    private var generated = 0
    private var sent = 0
    private var sendFailed = 0
    private var received = 0
    private var queued = 0
    private var flushed = 0
    private var applied = 0
    private var applySuccess = 0
    private var applyFailure = 0
    private val types = mutableMapOf<String, Int>()
    private var lastPair: String? = null
    private fun emit(event: String, fields: String = "") {
        runCatching { write("side=$side attempt=$attempt flow=$flow generation=$generation elapsedMs=${elapsed()} event=$event $fields") }
    }
    @Synchronized fun correlate(identity: Any?) { flow = identity?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "-" }
    @Synchronized fun event(event: String, fields: String = "") = emit(event, fields)
    @Synchronized fun peerCreated() {
        peerAt = elapsed(); emit("PEER_CREATED", states())
    }
    @Synchronized fun state(kind: String, value: String) {
        when (kind) { "GATHERING" -> gathering = value; "ICE" -> ice = value; "PEER" -> peer = value; "SIGNALING" -> signaling = value }
        emit("${kind}_STATE", "state=$value afterTerminal=${terminal != null} ${states()}")
        if (kind == "GATHERING" && value == "COMPLETE") emit("ICE_GATHERING_SUMMARY", counters())
    }
    @Synchronized fun sdp(operation: String, result: String, error: String? = null) {
        if (result == "SUCCESS") {
            if (operation.startsWith("SET_LOCAL")) localDescription = true
            if (operation.startsWith("SET_REMOTE")) remoteDescription = true
        }
        // Error categories only, never libwebrtc's arbitrary error text (which can contain SDP).
        emit("SDP_$result", "operation=$operation error=${error ?: "none"} afterTerminal=${terminal != null}")
    }
    @Synchronized fun watchdog(action: String, reason: String) {
        when (action) { "ARMED" -> watchdogPending++; "CANCELLED" -> watchdogPending = 0
            "FIRED" -> { watchdogFired = true; watchdogPending = (watchdogPending - 1).coerceAtLeast(0) } }
        emit("SENDER_DISCONNECT_WATCHDOG_$action", "reason=$reason pending=$watchdogPending ${states()}")
    }
    @Synchronized fun timeoutArmed(disconnect: Boolean, delayMs: Long) {
        timeouts.add((schedulingClock() + delayMs) to disconnect)
        if (disconnect) watchdog("ARMED", "peer-disconnected")
        else emit("SENDER_SETUP_WATCHDOG_ARMED", "delayMs=$delayMs ${states()}")
    }
    /** Mirrors the order of the existing shared Runnable's scheduled occurrences; never schedules work. */
    @Synchronized fun timeoutFired(): String {
        val next = timeouts.withIndex().minByOrNull { it.value.first }
        val disconnect = next?.value?.second == true
        if (next != null) timeouts.removeAt(next.index)
        if (disconnect) watchdog("FIRED", "existing-runnable")
        else emit("SENDER_SETUP_WATCHDOG_FIRED", states())
        return if (disconnect) "DISCONNECT_WATCHDOG" else "SETUP_WATCHDOG"
    }
    @Synchronized fun timeoutsCancelled(reason: String) {
        if (watchdogPending > 0) watchdog("CANCELLED", reason)
        if (timeouts.any { !it.second }) emit("SENDER_SETUP_WATCHDOG_CANCELLED", "reason=$reason")
        timeouts.clear()
    }
    @Synchronized fun terminal(source: String, reason: String, lifecycle: String = "") {
        if (terminal != null) { emit("TERMINAL_LATE", "source=$source first=$terminal"); return }
        terminal = source
        emit(if (side == "SEND") "SENDER_TERMINAL_ENTER" else "RECEIVER_TERMINAL_ENTER",
            "source=$source reason=$reason ${states()} watchdogArmed=${watchdogPending > 0} watchdogFired=$watchdogFired $lifecycle")
    }
    @Synchronized fun local(candidate: String) {
        val info = candidateMetadata(candidate); generated++; types[info.type] = (types[info.type] ?: 0) + 1
        emit("LOCAL_CANDIDATE_GENERATED", "type=${info.type} protocol=${info.protocol} count=$generated afterTerminal=${terminal != null}")
    }
    @Synchronized fun sent(candidate: String, admitted: Boolean) {
        if (admitted) sent++ else sendFailed++
        val info = candidateMetadata(candidate)
        emit(if (admitted) "LOCAL_CANDIDATE_SEND_ADMITTED" else "LOCAL_CANDIDATE_SEND_FAILURE",
            "type=${info.type} protocol=${info.protocol} sent=$sent sendFailed=$sendFailed")
    }
    @Synchronized fun received(candidate: String, ready: Boolean, queue: Int) {
        received++; val info = candidateMetadata(candidate)
        emit("REMOTE_CANDIDATE_RECEIVED", "type=${info.type} protocol=${info.protocol} remoteReady=$ready queue=$queue received=$received")
    }
    @Synchronized fun queued(queue: Int) { queued++; emit("REMOTE_CANDIDATE_QUEUED", "queue=$queue queued=$queued") }
    @Synchronized fun flush(begin: Boolean, count: Int) {
        emit(if (begin) "CANDIDATE_FLUSH_BEGIN" else "CANDIDATE_FLUSH_COMPLETE", "queue=$count remoteReady=$remoteDescription")
    }
    @Synchronized fun flushedCandidate() { flushed++ }
    @Synchronized fun applyAttempt(candidate: String, ready: Boolean, queue: Int? = null) {
        applied++; val info = candidateMetadata(candidate)
        emit("REMOTE_CANDIDATE_APPLY_ATTEMPT", "type=${info.type} protocol=${info.protocol} remoteReady=$ready queue=${queue ?: "engine-owned"} attempted=$applied")
    }
    @Synchronized fun applyResult(candidate: String, success: Boolean, ready: Boolean, queue: Int? = null) {
        if (success) applySuccess++ else applyFailure++
        val info = candidateMetadata(candidate)
        emit(if (success) "REMOTE_CANDIDATE_APPLY_SUCCESS" else "REMOTE_CANDIDATE_APPLY_FAILURE",
            "type=${info.type} protocol=${info.protocol} remoteReady=$ready queue=${queue ?: "engine-owned"} success=$applySuccess failure=$applyFailure")
    }
    @Synchronized fun selectedPair(exists: Boolean, local: Any?, remote: Any?, protocol: Any?, nominated: Boolean, succeeded: Boolean) {
        val safe = "exists=$exists localType=${type(local)} remoteType=${type(remote)} protocol=${MediaAttemptDiagnostics.protocol(protocol)} nominated=$nominated succeeded=$succeeded"
        if (safe != lastPair) { lastPair = safe; emit("SELECTED_CANDIDATE_PAIR", safe) }
    }
    private fun states() = "gathering=$gathering ice=$ice peer=$peer signaling=$signaling"
    private fun counters() = "localGenerated=$generated localSent=$sent localSendFailed=$sendFailed remoteReceived=$received remoteQueued=$queued queuedFlushed=$flushed applyAttempted=$applied applySuccess=$applySuccess applyFailure=$applyFailure " +
        "host=${types["host"] ?: 0} srflx=${types["srflx"] ?: 0} relay=${types["relay"] ?: 0} prflx=${types["prflx"] ?: 0} unknown=${types["unknown"] ?: 0}"
    @Synchronized fun summary() {
        if (summarized) return
        summarized = true
        emit("TERMINAL_MEDIA_SUMMARY", "durationMs=${peerAt?.let { elapsed() - it } ?: 0} ${states()} ${counters()} localDescription=$localDescription remoteDescription=$remoteDescription firstTerminal=${terminal ?: "unknown"} watchdogArmed=${watchdogPending > 0} watchdogFired=$watchdogFired selectedPair=${lastPair ?: "unobserved"}")
    }
}
