package com.pradertamas.uicapturetest

enum class ReceiverPhase { PAIRING, CONNECTING, RECEIVING, ENDED, ERROR }
internal enum class ReceiverMode { LEGACY, REQUEST_BOUND }
data class ReceiverHome(val phase: ReceiverPhase = ReceiverPhase.ENDED, val code: String = "",
    val intent: PairingIntent = PairingIntent.CREATE, val status: String = "Ready to receive", val registered: Boolean = false)
data class ReceiverIce(val mid: String?, val line: Int, val candidate: String)
interface ReceiverEvents {
    fun registration(status: String)
    fun present()
    fun offer(sdp: String)
    fun ice(candidate: ReceiverIce)
    fun ended()
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
    private val generate: () -> String = PairingCode::generate) {
    companion object { const val ROTATE_MS = 300_000L; const val GRACE_MS = 4_000L }
    val mediaRole = MediaRole.RECEIVE
    var home = ReceiverHome(); private set
    private var generation = 0L
    private var running = false
    internal var mode = ReceiverMode.LEGACY; private set
    private var requestDisposed: (() -> Unit)? = null
    private var socket: PairingLink? = null
    private var media: ReceiverMedia? = null
    private var registration: PairingRegistration? = null
    private var accepted = false
    private var offerStarted = false
    private var remoteSet = false
    private val pending = mutableListOf<ReceiverIce>()
    private var deadline: (() -> Unit)? = null
    private var rotation: (() -> Unit)? = null
    private var grace: (() -> Unit)? = null
    private fun publish(value: ReceiverHome) { home = value; changed(value) }
    fun create() { if (mode == ReceiverMode.LEGACY) begin(generate(), PairingIntent.CREATE) }
    fun join(input: String): Boolean {
        if (mode != ReceiverMode.LEGACY) return false
        val code = PairingCode.normalize(input) ?: return false
        if (home.phase !in setOf(ReceiverPhase.PAIRING, ReceiverPhase.ENDED, ReceiverPhase.ERROR)) return false
        begin(code, PairingIntent.JOIN); return true
    }
    private fun begin(code: String, intent: PairingIntent) {
        stop(); running = true; registration = PairingRegistration(code, intent, generate)
        publish(ReceiverHome(ReceiverPhase.PAIRING, code, intent, "Waiting for a sharing phone..."))
        open()
    }
    internal fun startRequest(intent: PairingIntent, disposed: () -> Unit) {
        stop()
        mode = ReceiverMode.REQUEST_BOUND; requestDisposed = disposed; running = true
        publish(ReceiverHome(ReceiverPhase.PAIRING, "", intent, "Waiting for a sharing phone..."))
        try { open() } catch (_: Exception) { end("Connection failed", true) }
    }
    private fun open() {
        val epoch = ++generation
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
                        publish(home.copy(registered = true))
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
                if (!current(epoch) || !accepted || home.phase != ReceiverPhase.CONNECTING || offerStarted) return
                offerStarted = true
                try {
                    val receiver = mediaFactory({ candidate -> if (current(epoch)) send(wire.candidate(candidate)) },
                        { if (current(epoch)) end("Direct connection could not be established", true) })
                    media = receiver
                    receiver.offer(sdp, {
                        if (current(epoch)) { remoteSet = true; pending.forEach(receiver::ice); pending.clear() }
                    }, { answer -> if (current(epoch)) send(wire.answer(answer)) },
                        { if (current(epoch)) end("Screen sharing could not start", true) })
                } catch (_: Exception) { end("Screen sharing could not start", true) }
            }
            override fun ice(candidate: ReceiverIce) {
                if (!current(epoch) || !accepted) return
                if (remoteSet) media?.ice(candidate)
                else if (pending.size < 256) pending.add(candidate) else end("Connection failed", true)
            }
            override fun ended() { if (current(epoch)) end("Screen sharing ended") }
        })
    }
    private fun current(epoch: Long) = running && generation == epoch
    private fun send(text: String) { if (running && socket?.send(text) != true) end("Connection failed", true) }
    fun firstFrame() {
        if (!running || !offerStarted || home.phase != ReceiverPhase.CONNECTING) return
        deadline?.invoke(); deadline = null
        publish(home.copy(phase = ReceiverPhase.RECEIVING, status = "Receiving screen"))
    }
    fun connection(state: String) {
        if (!running) return
        when (state) {
            "CONNECTED", "COMPLETED" -> { grace?.invoke(); grace = null }
            "DISCONNECTED" -> if (grace == null) { val epoch = generation
                grace = later(GRACE_MS) { if (current(epoch)) end("Direct connection could not be established", true) }
            }
            "FAILED", "CLOSED" -> end("Direct connection could not be established", true)
        }
    }
    private fun end(reason: String, error: Boolean = false) {
        stop(); publish(home.copy(phase = if (error) ReceiverPhase.ERROR else ReceiverPhase.ENDED, status = reason))
    }
    fun stop() {
        running = false; generation++
        deadline?.invoke(); rotation?.invoke(); grace?.invoke(); deadline = null; rotation = null; grace = null
        accepted = false; offerStarted = false; remoteSet = false; pending.clear(); registration = null
        val old = socket; socket = null; runCatching { old?.close() }
        val receiver = media; media = null; runCatching { receiver?.close() }
        val acknowledgement = requestDisposed; requestDisposed = null
        mode = ReceiverMode.LEGACY
        acknowledgement?.invoke()
        publish(ReceiverHome())
    }
}
