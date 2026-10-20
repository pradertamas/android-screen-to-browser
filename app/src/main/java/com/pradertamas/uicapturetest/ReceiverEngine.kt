package com.pradertamas.uicapturetest

enum class ReceiverPhase { PAIRING, CONNECTING, RECEIVING, ENDED, ERROR }
internal enum class ReceiverMode { LEGACY, REQUEST_BOUND }
enum class ReceiverConnectionSource { ICE, PEER }
internal enum class ReceiverDisconnectEpisode { NONE, FOREGROUND, BACKGROUND }
internal enum class ReceiverMediaAuthority { INACTIVE, FOREGROUND, BACKGROUND }
data class ReceiverHome(val phase: ReceiverPhase = ReceiverPhase.ENDED, val code: String = "",
    val intent: PairingIntent = PairingIntent.CREATE, val status: String = "Ready to receive", val registered: Boolean = false,
    val signalingAvailable: Boolean = false)
data class ReceiverIce(val mid: String?, val line: Int, val candidate: String)
interface ReceiverEvents {
    fun registration(status: String)
    fun present()
    fun offer(sdp: String)
    fun ice(candidate: ReceiverIce)
    fun ended()
    /** Transport loss is distinct from an explicit peer bye or protocol rejection. */
    fun signalingUnavailable() = ended()
}
interface ReceiverWire {
    fun open(code: String, intent: PairingIntent, events: ReceiverEvents): PairingLink
    fun answer(sdp: String): String
    fun candidate(value: ReceiverIce): String
}
interface ReceiverMedia {
    fun offer(sdp: String, remoteReady: () -> Unit, answer: (String) -> Unit, failure: () -> Unit)
    fun ice(candidate: ReceiverIce)
    fun close()
}
/** Answerer-only orchestration. All calls, including native/network callbacks, are serialized by owner. */
class ReceiverEngine(private val wire: ReceiverWire,
    private val mediaFactory: ((ReceiverIce) -> Unit, () -> Unit) -> ReceiverMedia,
    private val later: (Long, () -> Unit) -> (() -> Unit),
    private val changed: (ReceiverHome) -> Unit,
    private val generate: () -> String = PairingCode::generate,
    private val closed: (String) -> Unit = {},
    private val diagnostic: () -> ((String) -> Unit) = { {} },
    private val diagnosticClock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object { const val ROTATE_MS = 300_000L; const val GRACE_MS = 4_000L }
    val mediaRole = MediaRole.RECEIVE
    var home = ReceiverHome(); private set
    private var generation = 0L
    internal val mediaAttempt get() = generation
    private var authority = ReceiverMediaAuthority.INACTIVE
    internal var disconnectEpisode = ReceiverDisconnectEpisode.NONE; private set
    private var graceRevision = 0L
    internal var mediaTrace: MediaAttemptDiagnostics? = null; private set
    private var running = false
    private var peerWasConnected = false
    internal var peerConnected = false; private set
    private var signalingLost = false
    var lastCloseReason: String? = null; private set
    internal var mode = ReceiverMode.LEGACY; private set
    private var requestDisposed: (() -> Unit)? = null
    private var socket: PairingLink? = null
    private var media: ReceiverMedia? = null
    internal val hasSignaling get() = socket != null
    internal val hasMedia get() = media != null
    private var registration: PairingRegistration? = null
    private var accepted = false
    private var offerStarted = false
    private var remoteSet = false
    private val pending = mutableListOf<ReceiverIce>()
    private var deadline: (() -> Unit)? = null
    private var rotation: (() -> Unit)? = null
    private var grace: (() -> Unit)? = null
    private fun publish(value: ReceiverHome) { home = value; changed(value) }
    fun create() { if (mode == ReceiverMode.LEGACY && MediaRolePolicy.receiverSwitchAllowed(home.phase)) begin(generate(), PairingIntent.CREATE) }
    fun join(input: String): Boolean {
        if (mode != ReceiverMode.LEGACY) return false
        val code = PairingCode.normalize(input) ?: return false
        if (home.phase !in setOf(ReceiverPhase.PAIRING, ReceiverPhase.ENDED, ReceiverPhase.ERROR)) return false
        begin(code, PairingIntent.JOIN); return true
    }
    private fun begin(code: String, intent: PairingIntent) {
        stop("session-replaced"); running = true; registration = PairingRegistration(code, intent, generate)
        publish(ReceiverHome(ReceiverPhase.PAIRING, code, intent, "Waiting for a sharing phone..."))
        open()
    }
    internal fun startRequest(intent: PairingIntent, disposed: () -> Unit) {
        stop("session-replaced")
        mode = ReceiverMode.REQUEST_BOUND; requestDisposed = disposed; running = true
        publish(ReceiverHome(ReceiverPhase.PAIRING, "", intent, "Waiting for a sharing phone..."))
        try { open() } catch (_: Exception) { end("Connection failed", true) }
    }
    private fun open() {
        val epoch = ++generation
        val attempt = MediaAttemptDiagnostics("RECEIVE", diagnosticClock, diagnostic(), epoch)
        mediaTrace = attempt
        accepted = false
        if (home.registered) publish(home.copy(registered = false))
        val old = socket; socket = null; old?.close()
        deadline?.invoke(); deadline = later(15_000) { if (current(epoch)) end("Connection failed", true) }
        socket = wire.open(home.code, home.intent, object : ReceiverEvents {
            override fun registration(status: String) {
                if (!current(epoch)) return
                when (status) {
                    "ok" -> {
                        accepted = true; deadline?.invoke(); deadline = null
                        publish(home.copy(registered = true, signalingAvailable = true))
                        if (mode == ReceiverMode.LEGACY && home.intent == PairingIntent.CREATE) rotation = later(ROTATE_MS) {
                            if (current(epoch) && home.phase == ReceiverPhase.PAIRING) create()
                        }
                    }
                    "conflict" -> {
                        val code = if (mode == ReceiverMode.LEGACY) registration?.retry(409) else null
                        if (code == null) end("Pairing code already in use", true)
                        else { publish(home.copy(code = code)); open() }
                    }
                    "not-found" -> end("Pairing code not found or expired", true)
                    "rate-limited" -> end("Too many attempts. Please try again later", true)
                    else -> end("Pairing is unavailable", true)
                }
            }
            override fun present() {
                if (!current(epoch) || !accepted || home.phase != ReceiverPhase.PAIRING) return
                rotation?.invoke(); rotation = null
                publish(home.copy(phase = ReceiverPhase.CONNECTING, status = "Waiting for screen sharing..."))
                deadline = later(60_000) { if (current(epoch)) end("Screen sharing did not start", true) }
                // A receiver NEVER creates an offer, regardless of create/join intent.
            }
            override fun offer(sdp: String) {
                attempt.sdp("RECEIVE_OFFER", "START")
                if (!current(epoch) || !accepted || home.phase != ReceiverPhase.CONNECTING || offerStarted) {
                    attempt.event("CALLBACK_IGNORED", "kind=offer stale=${!current(epoch)}"); return
                }
                attempt.sdp("RECEIVE_OFFER", "SUCCESS")
                offerStarted = true
                try {
                    val receiver = mediaFactory({ candidate -> if (current(epoch)) send(wire.candidate(candidate)) { attempt.sent(candidate.candidate, it) }
                        else attempt.event("STALE_CALLBACK", "kind=local-candidate") },
                        { if (current(epoch)) end("Direct connection could not be established", true) })
                    media = receiver
                    receiver.offer(sdp, {
                        if (current(epoch)) {
                            remoteSet = true; attempt.flush(true, pending.size)
                            pending.forEach { attempt.flushedCandidate(); receiver.ice(it) }; pending.clear()
                            attempt.flush(false, pending.size)
                        } else attempt.event("STALE_CALLBACK", "kind=remote-description-ready")
                    }, { answer -> if (current(epoch)) {
                        attempt.sdp("SEND_ANSWER", "START")
                        send(wire.answer(answer)) { attempt.sdp("SEND_ANSWER", if (it) "SUCCESS" else "FAILURE") }
                    } else attempt.event("STALE_CALLBACK", "kind=answer") },
                        { if (current(epoch)) end("Screen sharing could not start", true) })
                } catch (_: Exception) { end("Screen sharing could not start", true) }
            }
            override fun ice(candidate: ReceiverIce) {
                if (!current(epoch) || !accepted) { attempt.event("CALLBACK_IGNORED", "kind=remote-candidate stale=${!current(epoch)} accepted=$accepted"); return }
                attempt.received(candidate.candidate, remoteSet, pending.size)
                if (remoteSet) media?.ice(candidate)
                else if (pending.size < 256) { pending.add(candidate); attempt.queued(pending.size) } else end("Connection failed", true)
            }
            override fun ended() { if (current(epoch)) end("Screen sharing ended") }
            override fun signalingUnavailable() { if (current(epoch)) this@ReceiverEngine.signalingUnavailable() }
        })
    }
    private fun current(epoch: Long) = running && generation == epoch
    private fun send(text: String, observed: (Boolean) -> Unit = {}) {
        if (running && !signalingLost) {
            val admitted = socket?.send(text) == true
            observed(admitted)
            if (!admitted) signalingUnavailable(sendFailed = true)
        } else observed(false)
    }
    /** One policy for receive-side transport loss and failed signaling sends.
     * Once media is established, the peer (including its existing disconnect grace) owns liveness.
     * Never extend/replace either the peer grace or the independent UI-absence deadline here.
     */
    private fun signalingUnavailable(sendFailed: Boolean = false) {
        if (!running || signalingLost) return
        if (home.phase != ReceiverPhase.RECEIVING || media == null || !peerWasConnected ||
            (!peerConnected && disconnectEpisode == ReceiverDisconnectEpisode.NONE)) {
            end(if (sendFailed) "Connection failed" else "Screen sharing ended", error = sendFailed,
                origin = if (sendFailed) "signaling-send-failed-during-setup" else "signaling-unavailable-during-setup")
            return
        }
        signalingLost = true; accepted = false
        val retired = socket; socket = null
        runCatching { retired?.close() }
        publish(home.copy(signalingAvailable = false))
    }
    fun firstFrame() {
        if (!running || !offerStarted || home.phase != ReceiverPhase.CONNECTING) return
        deadline?.invoke(); deadline = null
        publish(home.copy(phase = ReceiverPhase.RECEIVING, status = "Receiving screen"))
    }
    /** Coordinator grants only current-attempt, accepted, unexpired UI-absence authority. */
    internal fun mediaAuthority(attempt: Long, value: ReceiverMediaAuthority) {
        if (!current(attempt)) return
        authority = value
        if (value == ReceiverMediaAuthority.FOREGROUND && disconnectEpisode == ReceiverDisconnectEpisode.BACKGROUND) {
            disconnectEpisode = ReceiverDisconnectEpisode.FOREGROUND
            mediaTrace?.event("FOREGROUND_RECOVERY_GRACE", "delayMs=$GRACE_MS")
            armNetworkGrace()
        }
    }
    private fun cancelNetworkGrace() {
        graceRevision++
        grace?.invoke(); grace = null
    }
    private fun armNetworkGrace() {
        if (grace != null) return
        val epoch = generation; val ticket = ++graceRevision
        grace = later(GRACE_MS) {
            if (current(epoch) && ticket == graceRevision && disconnectEpisode == ReceiverDisconnectEpisode.FOREGROUND)
                end("Direct connection could not be established", true, "network-grace-expired")
        }
    }
    fun connection(state: String, source: ReceiverConnectionSource = ReceiverConnectionSource.PEER, attempt: Long = generation) {
        if (!current(attempt)) return
        when (state) {
            "CONNECTED", "COMPLETED" -> if (source == ReceiverConnectionSource.PEER && state == "CONNECTED") {
                peerWasConnected = true; peerConnected = true
                disconnectEpisode = ReceiverDisconnectEpisode.NONE; cancelNetworkGrace()
            }
            "DISCONNECTED" -> {
                peerConnected = false
                if (disconnectEpisode == ReceiverDisconnectEpisode.NONE) {
                    if (authority == ReceiverMediaAuthority.BACKGROUND && peerWasConnected && home.phase == ReceiverPhase.RECEIVING && media != null) {
                        disconnectEpisode = ReceiverDisconnectEpisode.BACKGROUND
                        mediaTrace?.event("BACKGROUND_DISCONNECT_RETAINED", "session-retained")
                    } else {
                        disconnectEpisode = ReceiverDisconnectEpisode.FOREGROUND
                        armNetworkGrace()
                    }
                }
            }
            "FAILED", "CLOSED" -> end("Direct connection could not be established", true, "peer-$state")
        }
    }
    private fun end(reason: String, error: Boolean = false, origin: String = reason) {
        stop(origin); publish(home.copy(phase = if (error) ReceiverPhase.ERROR else ReceiverPhase.ENDED, status = reason))
    }
    fun stop(reason: String = "explicit-stop", intentional: Boolean = false) {
        val bye = if (intentional && running && accepted && !signalingLost && home.signalingAvailable) socket else null
        if (running || socket != null || media != null) {
            val source = when (reason) {
                "peer-FAILED" -> "PEER_FAILED"
                "peer-CLOSED" -> "PEER_CLOSED"
                "network-grace-expired" -> "NETWORK_GRACE_EXPIRED"
                "ui-background-timeout" -> "UI_BACKGROUND_TIMEOUT"
                "session-replaced" -> "SESSION_REPLACED"
                "signaling-send-failed-during-setup", "signaling-unavailable-during-setup" -> "SIGNALING_FAILURE"
                else -> "ENGINE_STOP"
            }
            mediaTrace?.terminal(source, source, "running=$running remoteReady=$remoteSet queue=${pending.size}")
            mediaTrace?.summary()
        }
        if (running || socket != null || media != null) { lastCloseReason = reason; closed(reason) }
        running = false; generation++
        // Invalidate callbacks first. A failed best-effort bye cannot interrupt local disposal.
        runCatching { bye?.send("{\"type\":\"bye\"}") }
        peerWasConnected = false; peerConnected = false; signalingLost = false
        authority = ReceiverMediaAuthority.INACTIVE; disconnectEpisode = ReceiverDisconnectEpisode.NONE
        cancelNetworkGrace()
        deadline?.invoke(); rotation?.invoke(); deadline = null; rotation = null
        accepted = false; offerStarted = false; remoteSet = false; pending.clear(); registration = null
        val old = socket; socket = null; runCatching { old?.close() }
        val receiver = media; media = null; runCatching { receiver?.close() }
        val acknowledgement = requestDisposed; requestDisposed = null
        mode = ReceiverMode.LEGACY
        acknowledgement?.invoke()
        publish(ReceiverHome())
    }
}
