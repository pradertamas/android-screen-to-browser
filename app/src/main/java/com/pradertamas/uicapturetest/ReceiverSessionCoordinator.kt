package com.pradertamas.uicapturetest

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.webrtc.SurfaceViewRenderer
import java.util.concurrent.TimeUnit

data class ReceiverUi(val home: ReceiverHome = ReceiverHome(), val resolution: String = "N/A", val metrics: String = "N/A",
    val frame: ReceiverFrameSize? = null, val presence: ReceiverPresence = ReceiverPresence()) {
    val active get() = presence.live || home.phase == ReceiverPhase.CONNECTING
}

/** Process-memory only. No Activity, consent, persisted live state, or receiver service. */
internal object ReceiverSessions {
    private val mutable = MutableStateFlow(ReceiverUi())
    val state = mutable.asStateFlow()
    val active get() = state.value.active
    private var owner: ReceiverSessionCoordinator? = null
    fun get(application: Application): ReceiverSessionCoordinator {
        check(Looper.myLooper() == Looper.getMainLooper())
        return owner ?: ReceiverSessionCoordinator(application, mutable).also { owner = it }
    }
    fun checkExpiry() { owner?.checkExpiry() }
    fun canShow(id: ReceiverSessionIdentity) = owner?.canShow(id) == true
    fun stop(id: ReceiverSessionIdentity) = owner?.stopActive(id) == true
}

/** Owns the existing receiver stack. Main-thread serialization includes UI absence and renderer binding. */
internal class ReceiverSessionCoordinator(application: Application, private val mutable: MutableStateFlow<ReceiverUi>) {
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
    val state = mutable.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var native: WebRtcReceiverSession? = null
    private val view = ReceiverRendererBinding<SurfaceViewRenderer>({ native?.attach(it) }, { native?.detach(it) })
    private var lastSession: ReceiverSessionIdentity? = null
    private var attachment: Any? = null
    private val later: (Long, () -> Unit) -> (() -> Unit) = { delay, action ->
        val task = Runnable { action() }; main.postDelayed(task, delay)
        val cancel: () -> Unit = { main.removeCallbacks(task) }; cancel
    }
    private val visibility = ReceiverVisibility(SystemClock::elapsedRealtime, later, { presence ->
        if (presence.session != null) lastSession = presence.session
        mutable.value = mutable.value.copy(presence = presence)
    }, ::disposeSession)
    private val wire = ReceiverWireRouter(ReceiverSignaling(http, main))
    private val engine: ReceiverEngine = ReceiverEngine(wire, { candidate, failure ->
        WebRtcReceiverSession(application, candidate, { engineConnection(it) }, failure,
            { rendered() }, { w, h -> mutable.value = mutable.value.copy(resolution = "$w × $h", frame = ReceiverFrameSize(w, h)) },
            { mutable.value = mutable.value.copy(metrics = it) }).also { session ->
            native = session
            try { view.current()?.let(session::attach) } catch (e: Exception) { session.close(); throw e }
        }
    }, later, { home ->
        val wasHiddenLive = mutable.value.presence.let { it.live && !it.visible }
        val previous = lastSession
        mutable.value = if (home.phase == ReceiverPhase.PAIRING) ReceiverUi(home, presence = visibility.state)
            else mutable.value.copy(home = home)
        visibility.connection(home.phase)
        AppMediaRoles.receiverPhaseChanged()
        if (wasHiddenLive && home.phase in setOf(ReceiverPhase.ENDED, ReceiverPhase.ERROR)) main.post {
            if (lastSession === previous && !state.value.active) returnToSend()
        }
    })
    private val request = RequestReceiverOwner(engine, wire, ConnectionRequestMediaHandoff.process,
        { action -> main.post { action() } })
    private var retainedRequestFlow: Any? = null
    val requestIdentity: Any? get() = retainedRequestFlow
    val requestFlow: Any? get() = if (request.active) retainedRequestFlow else null
    private fun engineConnection(state: String) { engine.connection(state) }
    private fun rendered() { engine.firstFrame() }

    fun prepare() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (AppMediaRoles.role != MediaRole.RECEIVE) return
        val alreadyBound = AppMediaRoles.receiverBound(this)
        AppMediaRoles.bindReceiver(this, ::stop, { MediaRolePolicy.receiverSwitchAllowed(state.value.home.phase) })
        if (!alreadyBound && !state.value.active) ConnectionRequestRuntimeGate.runLegacyStart { engine.create() }
    }
    /** Bookkeeping only, independent of handoff success. */
    fun ensureBoundToTask(taskId: Int): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (AppMediaRoles.role != MediaRole.RECEIVE) return false
        return runCatching { AppMediaRoles.bindReceiverToTask(this, taskId, ::stop,
            { MediaRolePolicy.receiverSwitchAllowed(state.value.home.phase) }) }.isSuccess
    }
    fun viewerVisible(identity: Any) { requireMain(); visibility.visible(identity) }
    fun viewerAttached(identity: Any) { requireMain(); attachment = identity }
    fun viewerDestroyed(identity: Any, changingConfiguration: Boolean) {
        requireMain()
        if (attachment !== identity || changingConfiguration) return
        attachment = null
        if (!state.value.active && !request.active) {
            if (state.value.home.phase in setOf(ReceiverPhase.ENDED, ReceiverPhase.ERROR)) returnToSend()
            AppMediaRoles.unbindReceiver(this)
        }
    }
    fun viewerHidden(identity: Any, changingConfiguration: Boolean, scanning: Boolean) {
        requireMain()
        val hidden = visibility.hidden(identity, changingConfiguration)
        if (hidden && !scanning && !state.value.presence.live) stop()
    }
    fun checkExpiry() { requireMain(); visibility.checkExpiry() }
    fun canShow(id: ReceiverSessionIdentity): Boolean { requireMain(); return visibility.matches(id) }
    fun stopActive(id: ReceiverSessionIdentity): Boolean { requireMain(); return visibility.stop(id) }
    private fun disposeSession(id: ReceiverSessionIdentity) {
        if (lastSession !== id) return
        stop(); returnToSend()
    }
    private fun returnToSend() {
        if (AppMediaRoles.role == MediaRole.RECEIVE && AppMediaRoles.receiverBound(this))
            AppMediaRoles.switchRole(MediaRole.SEND, stopActive = true)
    }
    fun startRequest(handoff: String): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (AppMediaRoles.role != MediaRole.RECEIVE || request.active ||
            !MediaRolePolicy.receiverSwitchAllowed(state.value.home.phase)) return false
        if (ConnectionRequestRuntimeGate.legacyStartAllowed()) return false
        val flow = ConnectionRequestMediaHandoff.process.flowFor(handoff) ?: return false
        if (!AppMediaRoles.receiverAttached(this)) return false
        val started = request.start(handoff)
        if (started) retainedRequestFlow = flow
        return started
    }
    fun stopRequest(flow: Any): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return request.stop(flow)
    }
    fun ensureOwnCreate() {
        if (AppMediaRoles.role == MediaRole.RECEIVE && MediaRolePolicy.receiverSwitchAllowed(state.value.home.phase))
            ConnectionRequestRuntimeGate.runLegacyStart { ShowConnection.ensureReceiver(state.value.home) { engine.create() } }
    }
    fun retry() {
        if (AppMediaRoles.role == MediaRole.RECEIVE && MediaRolePolicy.receiverSwitchAllowed(state.value.home.phase))
            ConnectionRequestRuntimeGate.runLegacyStart { engine.create() }
    }
    fun join(value: String) = AppMediaRoles.role == MediaRole.RECEIVE &&
        ConnectionRequestRuntimeGate.runLegacyStart { engine.join(value) } == true
    fun attach(renderer: SurfaceViewRenderer) { requireMain(); view.attach(renderer) }
    fun detach(renderer: SurfaceViewRenderer) { requireMain(); view.detach(renderer) }
    fun stop() { requireMain(); engine.stop(); native = null }
}
