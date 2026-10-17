package com.pradertamas.uicapturetest

import java.net.URI

/** Separate from RemoteDevice (persistent browser lookup token). Never use a data class for bearer secrets. */
class DeviceIdentity(val deviceId: String, val credential: String) {
    override fun toString() = "DeviceIdentity(redacted)"
}
object AppPairingLink {

    fun valid(value: String, bytes: Int = 32): Boolean = try {
        require(value.length == (if (bytes == 32) 43 else 22) && value.matches(Regex("[A-Za-z0-9_-]+")))
        val decoded = java.util.Base64.getUrlDecoder().decode(value)
        decoded.size == bytes && java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value
    } catch (_: Exception) { false }
    fun parse(value: String, config: PairingBackendConfig = PairingBackendConfig.build()): String? = try {
        val uri = URI(value)
        if (uri.scheme != "https" || uri.rawAuthority != config.appLinkHost || uri.rawPath != config.appLinkPath || uri.rawQuery != null) null
        else uri.rawFragment?.takeIf { it.startsWith("invite=") }?.substring(7)?.takeIf { valid(it) }
    } catch (_: Exception) { null }
    fun shareText(link: String, config: PairingBackendConfig = PairingBackendConfig.build()): String {
        require(parse(link, config) != null)
        return "Open this link on the Android device you want to pair:\n\n$link\n\nThis link expires in 30 minutes and can be used once."
    }
}
class DeviceIdentityStore(private val read: () -> DeviceIdentity?, private val write: (DeviceIdentity) -> Boolean) {
    companion object { const val PREFS = "device_identity" }
    @Synchronized fun existing(): DeviceIdentity? = read()?.takeIf { AppPairingLink.valid(it.deviceId, 16) && AppPairingLink.valid(it.credential) }
    @Synchronized fun ensure(register: () -> DeviceIdentity): DeviceIdentity {
        val stored = read()
        if (stored != null && AppPairingLink.valid(stored.deviceId, 16) && AppPairingLink.valid(stored.credential)) return stored
        val identity = register()
        check(AppPairingLink.valid(identity.deviceId, 16) && AppPairingLink.valid(identity.credential))
        check(write(identity)); return identity
    }
}

enum class AppPairingPhase { IDLE, CREATING_INVITE, INSPECTING_INVITE, AWAITING_CONFIRMATION, ACCEPTING, PAIRED, ERROR }
data class AppPairingUi(val phase: AppPairingPhase = AppPairingPhase.IDLE, val message: String = "")
interface AppPairingApi {
    fun register(): DeviceIdentity
    fun create(identity: DeviceIdentity): String // URL, not a loggable model.
    fun inspect(identity: DeviceIdentity, token: String): String
    fun accept(identity: DeviceIdentity, token: String): String
}
/** Isolated control plane. No MediaRole, WebRTC, MediaProjection, remote token or service reference. */
class AppPairingController(private val store: DeviceIdentityStore, private val api: AppPairingApi,
    private val execute: ((() -> Unit) -> Unit), private val post: ((() -> Unit) -> Unit),
    private val changed: (AppPairingUi) -> Unit, private val share: (String) -> Unit,
    private val config: PairingBackendConfig = PairingBackendConfig.build()) {
    var ui = AppPairingUi(); private set
    private var generation = 0L
    private var busy = false
    private var pending: String? = null
    private var processed: String? = null
    private var closed = false
    private fun publish(phase: AppPairingPhase, message: String = "") { ui = AppPairingUi(phase, message); changed(ui) }
    private fun run(phase: AppPairingPhase, failure: String, work: () -> String, done: (String) -> Unit) {
        if (busy || closed) return
        if (!config.configured) { publish(AppPairingPhase.ERROR, "Pairing backend is not configured."); return }
        busy = true; val epoch = ++generation; publish(phase)
        execute {
            val result = runCatching(work)
            post { if (!closed && epoch == generation) {
                busy = false
                result.fold(done, { publish(AppPairingPhase.ERROR, failure) })
            } }
        }
    }
    fun create(allowed: Boolean) {
        if (!allowed || busy || closed) return
        pending = null
        run(AppPairingPhase.CREATING_INVITE, "Pairing temporarily unavailable.", { api.create(store.ensure(api::register)) }) { link ->
            if (AppPairingLink.parse(link, config) == null) publish(AppPairingPhase.ERROR, "Could not create pairing link.")
            else { publish(AppPairingPhase.IDLE, "Pairing link ready."); share(link) }
        }
    }
    fun incoming(link: String, allowed: Boolean) {
        if (closed || busy) return
        val token = AppPairingLink.parse(link, config)
        if (token == null) { publish(AppPairingPhase.ERROR, "Invalid pairing link."); return }
        if (processed == token && ui.phase in setOf(AppPairingPhase.INSPECTING_INVITE, AppPairingPhase.AWAITING_CONFIRMATION, AppPairingPhase.ACCEPTING)) return
        if (!allowed) { publish(AppPairingPhase.ERROR, "Stop screen sharing before pairing another device."); return }
        processed = token; pending = token; inspect()
    }
    private fun inspect() {
        val token = pending ?: return
        run(AppPairingPhase.INSPECTING_INVITE, "Pairing temporarily unavailable.", { api.inspect(store.ensure(api::register), token) }) { status ->
            when (status) {
                "available" -> publish(AppPairingPhase.AWAITING_CONFIRMATION)
                "self-pair" -> publish(AppPairingPhase.ERROR, "You can't pair this device with itself.")
                else -> publish(AppPairingPhase.ERROR, "Pairing link is no longer available.")
            }
        }
    }
    fun accept(allowed: Boolean) {
        if (!allowed || busy || ui.phase != AppPairingPhase.AWAITING_CONFIRMATION) return
        val token = pending ?: return
        run(AppPairingPhase.ACCEPTING, "Pairing temporarily unavailable.", { api.accept(store.ensure(api::register), token) }) { status ->
            when (status) {
                "paired", "already-paired" -> { pending = null; publish(AppPairingPhase.PAIRED, if (status == "paired") "Devices paired." else "Devices are already paired.") }
                "self-pair" -> publish(AppPairingPhase.ERROR, "You can't pair this device with itself.")
                else -> publish(AppPairingPhase.ERROR, "Pairing link is no longer available.")
            }
        }
    }
    fun cancel() { generation++; busy = false; pending = null; publish(AppPairingPhase.IDLE, "Pairing cancelled.") }
    fun retry(allowed: Boolean) { if (allowed && !busy) { if (pending != null) inspect() else create(true) } }
    fun close() { closed = true; generation++; pending = null; processed = null }
}
