package com.pradertamas.uicapturetest

enum class PairingPhase { STOPPED, CREATING, WAITING, JOINING, CONSENT, CONNECTING, STREAMING, ERROR }
data class PairingHome(val phase: PairingPhase = PairingPhase.STOPPED, val code: String = "",
    val intent: PairingIntent = PairingIntent.CREATE, val epoch: Long = 0, val notice: String = "")
interface PairingLink {
    val remotePresenceSupported: Boolean get() = false
    fun send(text: String): Boolean
    fun close()
}
interface PairingTransport {
    fun open(code: String, intent: PairingIntent, message: (String, String, String) -> Unit, ended: () -> Unit): PairingLink
}

/** Main-thread controller; no capture or Android objects. One registration per epoch. */
class AutoPairing(private val transport: PairingTransport,
    private val later: (Long, () -> Unit) -> (() -> Unit),
    private val changed: (PairingHome) -> Unit,
    private val generate: () -> String = PairingCode::generate,
    private val remoteToken: () -> String? = { null }) {
    companion object {
        const val ROTATE_MS = 300_000L
        const val DEBOUNCE_MS = 250L
        fun input(value: String): String? {
            val raw = value.trim()
            if (raw.length > 6 || !raw.matches(Regex("[A-Za-z2-9]*"))) return null
            return raw.uppercase(java.util.Locale.ROOT).takeIf { text -> text.all { it in PairingCode.ALPHABET } }
        }
    }
    var home = PairingHome(); private set
    private var link: PairingLink? = null
    private var rotation: (() -> Unit)? = null
    private var timeout: (() -> Unit)? = null
    private var input: (() -> Unit)? = null
    private var recovery: (() -> Unit)? = null
    private var registration: PairingRegistration? = null
    private var consumer: ((String) -> Unit)? = null
    private var mediaEnd: (() -> Unit)? = null
    private var consentRequested = false
    private var visible = false
    private fun publish(value: PairingHome) { home = value; changed(home) }
    fun show() { visible = true; if (home.phase == PairingPhase.STOPPED) create() }
    fun hide() {
        visible = false
        // Scanner/consent overlays are handled by the Activity, not treated as navigation.
        if (home.phase !in setOf(PairingPhase.CONNECTING, PairingPhase.STREAMING) && consumer == null) close()
    }
    fun editable() = home.phase in setOf(PairingPhase.CREATING, PairingPhase.WAITING, PairingPhase.ERROR)
    fun notice(text: String) { publish(home.copy(notice = text)) }
    fun typed(value: String) {
        input?.invoke(); input = null
        if (!editable()) return
        val code = PairingCode.normalize(value) ?: return
        val epoch = home.epoch
        input = later(DEBOUNCE_MS) { input = null; if (epoch == home.epoch && editable()) join(code) }
    }
    fun join(code: String) {
        if (!editable() || PairingCode.normalize(code) != code) return
        begin(code, PairingIntent.JOIN, "")
    }
    fun create(notice: String = home.notice) { begin(generate(), PairingIntent.CREATE, notice) }
    private fun begin(code: String, intent: PairingIntent, notice: String) {
        close()
        registration = PairingRegistration(code, intent, generate)
        publish(home.copy(code = code, intent = intent, notice = notice,
            phase = if (intent == PairingIntent.CREATE) PairingPhase.CREATING else PairingPhase.JOINING))
        open()
    }
    private fun open() {
        val epoch = home.epoch
        val old = link; link = null; old?.close()
        val attempt = registration!!.attempts
        timeout?.invoke()
        timeout = later(15_000) { if (epoch == home.epoch) failed("Connection failed. Please try again.") }
        link = transport.open(home.code, home.intent, { type, status, raw ->
            if (epoch == home.epoch && registration?.attempts == attempt) {
                when (type) {
                    "registration" -> when (status) {
                        "ok" -> {
                            timeout?.invoke(); timeout = null
                            publish(home.copy(phase = PairingPhase.WAITING))
                            refreshRemotePresence()
                            if (home.intent == PairingIntent.CREATE) rotation = later(ROTATE_MS) {
                                if (epoch == home.epoch && home.phase == PairingPhase.WAITING) create()
                            }
                        }
                        "conflict" -> {
                            val next = registration?.retry(409)
                            if (next != null) { publish(home.copy(code = next)); open() }
                            else failed("Pairing code already in use. Please try again.")
                        }
                        "not-found" -> failed("Pairing code not found or expired.")
                        "rate-limited" -> failed("Too many attempts. Please wait and try again.")
                        else -> failed("Pairing code already in use.")
                    }
                    "peer-present" -> if (home.phase == PairingPhase.WAITING) {
                        rotation?.invoke(); rotation = null
                        publish(home.copy(phase = PairingPhase.CONSENT, notice = ""))
                    }
                    "peer-left", "bye" -> failed("Disconnected")
                    else -> consumer?.invoke(raw)
                }
            }
        }, { if (epoch == home.epoch && registration?.attempts == attempt) failed("Connection failed. Please try again.") })
    }
    fun claimConsent(): Long? {
        if (home.phase != PairingPhase.CONSENT || consentRequested) return null
        consentRequested = true; return home.epoch
    }
    fun refreshRemotePresence() {
        if (visible && home.phase == PairingPhase.WAITING && home.intent == PairingIntent.CREATE && link?.remotePresenceSupported == true) {
            val token = runCatching { remoteToken() }.getOrNull()
            if (token != null && RemoteDevice.valid(token))
                link?.send("{\"type\":\"remote-presence-register\",\"token\":\"$token\"}")
        }
    }
    fun allow(epoch: Long): Boolean {
        if (epoch != home.epoch || home.phase != PairingPhase.CONSENT || !consentRequested) return false
        publish(home.copy(phase = PairingPhase.CONNECTING)); return true
    }
    fun cancel(epoch: Long) { if (epoch == home.epoch) failed("Sharing cancelled") }
    fun attach(epoch: Long, message: (String) -> Unit, ended: () -> Unit): Boolean {
        if (epoch != home.epoch || home.phase != PairingPhase.CONNECTING || link == null) return false
        consumer = message; mediaEnd = ended; return true
    }
    fun send(epoch: Long, text: String): Boolean = epoch == home.epoch && link?.send(text) == true
    fun streaming(epoch: Long) { if (epoch == home.epoch) publish(home.copy(phase = PairingPhase.STREAMING)) }
    fun mediaStopped(epoch: Long) {
        if (epoch != home.epoch) return
        val text = home.notice
        close(); if (visible) create(text)
    }
    private fun failed(text: String) {
        val end = mediaEnd
        if (end != null) {
            publish(home.copy(phase = PairingPhase.ERROR, notice = text))
            end(); return // Service owns native disposal; restart only after disposal.
        }
        close(); publish(home.copy(phase = PairingPhase.ERROR, notice = text))
        if (visible) recovery = later(1000) { recovery = null; create(text) }
    }
    fun close() {
        rotation?.invoke(); timeout?.invoke(); input?.invoke(); recovery?.invoke()
        rotation = null; timeout = null; input = null; recovery = null
        consumer = null; mediaEnd = null; registration = null; consentRequested = false
        val old = link; link = null
        publish(PairingHome(epoch = home.epoch + 1, notice = home.notice))
        old?.close()
    }
}
