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

/** Process-memory media owner. The execution anchor never constructs or replaces this owner. */
internal object ReceiverSessions {
    private val mutable = MutableStateFlow(ReceiverUi())
    val state = mutable.asStateFlow()
    val active get() = state.value.active
    private var owner: ReceiverSessionCoordinator? = null
    private val viewers = ReceiverViewerBindings()
    fun get(application: Application): ReceiverSessionCoordinator {
        check(Looper.myLooper() == Looper.getMainLooper())
        return owner ?: ReceiverSessionCoordinator(application, mutable, viewers).also { owner = it }
    }
    fun checkExpiry() { owner?.checkExpiry() }
    fun mainVisible(identity: Any) { owner?.mainVisible(identity) }
    fun mainAttached(identity: Any) { viewers.attachMain(identity) }
    fun mainDestroyed(identity: Any) { viewers.detachMain(identity) }
    fun mainHidden(identity: Any, changingConfiguration: Boolean) { owner?.mainHidden(identity, changingConfiguration) }
    fun canShow(id: ReceiverSessionIdentity) = owner?.canShow(id) == true
    fun stop(id: ReceiverSessionIdentity) = owner?.stopActive(id) == true
    fun anchorCurrent(key: String) = owner?.anchorCurrent(key) == true
    fun stopAnchoredSession(key: String): Boolean {
        if (!anchorCurrent(key)) return false
        val id = state.value.presence.session ?: return false
        return owner?.stopActive(id) == true
    }
    fun diagnostic(event: String) {
        val current = owner
        if (current != null) current.diagnostic(event)
        else android.util.Log.i("ReceiverLifecycle", "role=${AppMediaRoles.role} session=none pid=${android.os.Process.myPid()} $event receiverOwner=false active=$active")
    }
}

/** Owns the existing receiver stack. Main-thread serialization includes UI absence and renderer binding. */
internal class ReceiverSessionCoordinator(application: Application, private val mutable: MutableStateFlow<ReceiverUi>,
    private val viewers: ReceiverViewerBindings) {
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
    val state = mutable.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val trace = ReceiverTrace { android.util.Log.i("ReceiverLifecycle", it) }
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var native: WebRtcReceiverSession? = null
    private val view = ReceiverRendererBinding<SurfaceViewRenderer>({ native?.attach(it) }, { native?.detach(it) })
    private var lastSession: ReceiverSessionIdentity? = null
    private val anchorAdmission = ReceiverAnchorAdmission { id ->
        trace.record("anchor-start-requested anchor=${id.anchorKey.take(8)}")
        runCatching { ReceiverExecutionAnchorService.request(application, id) }.onFailure {
            trace.record("anchor-start-failed exception=${it.javaClass.simpleName}")
        }
    }
    private val later: (Long, () -> Unit) -> (() -> Unit) = { delay, action ->
        val task = Runnable { action() }; main.postDelayed(task, delay)
        val cancel: () -> Unit = { main.removeCallbacks(task) }; cancel
    }
    private val visibility = ReceiverVisibility(SystemClock::elapsedRealtime, later, { presence ->
        if (presence.session != null) lastSession = presence.session
        mutable.value = mutable.value.copy(presence = presence)
        syncMediaAuthority(presence)
        syncExecutionAnchor()
    }, ::disposeSession, trace::record)
    private val wire = ReceiverWireRouter(ReceiverSignaling(http, main, trace::snapshot))
    private val engine: ReceiverEngine = ReceiverEngine(wire, { candidate, failure ->
        WebRtcReceiverSession(application, candidate, connectionForAttempt(), failure,
            { rendered() }, { w, h -> mutable.value = mutable.value.copy(resolution = "$w × $h", frame = ReceiverFrameSize(w, h)) },
            { mutable.value = mutable.value.copy(metrics = it) }, trace.snapshot(), attemptDiagnostics()).also { session ->
            native = session
            try { view.current()?.let(session::attach) } catch (e: Exception) { session.close(); throw e }
        }
    }, later, { home ->
        if (home.phase == ReceiverPhase.PAIRING && mutable.value.home.phase != ReceiverPhase.PAIRING) trace.begin()
        if (home.phase == ReceiverPhase.RECEIVING && !home.signalingAvailable && mutable.value.home.signalingAvailable)
            trace.record("signaling-degraded phase=RECEIVING peer=${native?.hasPeer == true} session-retained")
        trace.record("engine phase=${home.phase}")
        val wasHiddenLive = mutable.value.presence.let { it.live && !it.visible }
        val previous = lastSession
        mutable.value = if (home.phase == ReceiverPhase.PAIRING) ReceiverUi(home, presence = visibility.state)
            else mutable.value.copy(home = home)
        visibility.connection(home.phase)
        AppMediaRoles.receiverPhaseChanged()
        if (wasHiddenLive && home.phase in setOf(ReceiverPhase.ENDED, ReceiverPhase.ERROR)) main.post {
            if (lastSession === previous && !state.value.active) returnToSend()
        }
    }, closed = { reason -> trace.record("session-close reason=$reason") },
        diagnostic = { trace.snapshot() }, diagnosticClock = SystemClock::elapsedRealtime)
    private val request = RequestReceiverOwner(engine, wire, ConnectionRequestMediaHandoff.process,
        { action -> main.post { action() } }, ProtectedSocketFactory { url, listener ->
            OkHttpProtectedSocketFactory(trace.snapshot()).open(url, listener)
        })
    init { trace.record("coordinator-created device=${android.os.Build.MODEL} pid=${android.os.Process.myPid()}") }
    private var retainedRequestFlow: Any? = null
    val requestIdentity: Any? get() = retainedRequestFlow
    val requestFlow: Any? get() = if (request.active) retainedRequestFlow else null
    private fun syncExecutionAnchor() {
        anchorAdmission.changed(state.value, AppMediaRoles.role, engine.hasMedia)
    }
    internal fun anchorCurrent(key: String): Boolean {
        requireMain()
        return AppMediaRoles.role == MediaRole.RECEIVE && visibility.state.live &&
            visibility.state.session?.anchorKey == key && state.value.home.phase == ReceiverPhase.RECEIVING && engine.hasMedia
    }
    private fun syncMediaAuthority(presence: ReceiverPresence = visibility.state) {
        engine.mediaAuthority(engine.mediaAttempt, if (presence.session === lastSession)
            receiverMediaAuthority(presence, SystemClock.elapsedRealtime()) else ReceiverMediaAuthority.INACTIVE)
    }
    private fun connectionForAttempt(): (String, ReceiverConnectionSource) -> Unit {
        val attempt = engine.mediaAttempt
        return { state, source -> engineConnection(attempt, state, source) }
    }
    private fun engineConnection(attempt: Long, state: String, source: ReceiverConnectionSource) {
        if (attempt != engine.mediaAttempt) { trace.record("stale-peer-state-ignored source=$source"); return }
        trace.record("peer state=$state source=$source")
        visibility.checkExpiry()
        syncMediaAuthority()
        engine.connection(state, source, attempt)
    }
    private fun attemptDiagnostics() = engine.mediaTrace?.also { it.correlate(requestFlow) }
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
    fun viewerVisible(identity: Any) {
        requireMain()
        if (!viewers.receiverCurrent(identity, visibility.state.session)) { diagnostic("stale-viewer-foreground-ignored"); return }
        visibility.visible(identity)
        diagnostic("receiver-visible")
    }
    // Main is also a foreground viewer of the retained session, without attaching a renderer.
    fun mainVisible(identity: Any) {
        requireMain()
        if (viewers.mainCurrent(identity) && state.value.presence.live) visibility.visible(identity)
        diagnostic("main-visible")
    }
    fun mainHidden(identity: Any, changingConfiguration: Boolean) {
        requireMain()
        visibility.hidden(identity, changingConfiguration) // Exact currently-visible token guards stale hides.
        diagnostic("main-hidden config=$changingConfiguration")
    }
    fun viewerAttached(identity: Any) { requireMain(); viewers.attachReceiver(identity, visibility.state.session) }
    fun viewerDestroyed(identity: Any, changingConfiguration: Boolean) {
        requireMain()
        if (!viewers.receiverAttached(identity) || changingConfiguration) return
        viewers.detachReceiver(identity)
        if (!state.value.active && !request.active) {
            if (state.value.home.phase in setOf(ReceiverPhase.ENDED, ReceiverPhase.ERROR)) returnToSend()
            AppMediaRoles.unbindReceiver(this)
        }
    }
    fun viewerHidden(identity: Any, changingConfiguration: Boolean, scanning: Boolean) {
        requireMain()
        if (!viewers.receiverCurrent(identity, visibility.state.session)) return
        val hidden = visibility.hidden(identity, changingConfiguration)
        diagnostic("receiver-hidden accepted=$hidden config=$changingConfiguration")
        if (hidden && !scanning && !state.value.presence.live) stop("ui-left-before-live")
    }
    fun checkExpiry() { requireMain(); visibility.checkExpiry() }
    fun canShow(id: ReceiverSessionIdentity): Boolean { requireMain(); return visibility.matches(id) }
    fun stopActive(id: ReceiverSessionIdentity): Boolean { requireMain(); return visibility.stop(id) }
    private fun disposeSession(id: ReceiverSessionIdentity) {
        if (lastSession !== id) return
        val reason = visibility.lastStopReason ?: "local-stop"
        stop(reason, intentional = reason == "user-stop-active-share"); returnToSend()
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
        if (started) { retainedRequestFlow = flow; viewers.receiverSession(visibility.state.session) }
        return started
    }
    fun stopRequest(flow: Any): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return request.stop(flow)
    }
    fun ensureOwnCreate() {
        if (AppMediaRoles.role == MediaRole.RECEIVE && MediaRolePolicy.receiverSwitchAllowed(state.value.home.phase))
            ConnectionRequestRuntimeGate.runLegacyStart { ShowConnection.ensureReceiver(state.value.home) { engine.create() } }
        viewers.receiverSession(visibility.state.session)
    }
    fun retry() {
        if (AppMediaRoles.role == MediaRole.RECEIVE && MediaRolePolicy.receiverSwitchAllowed(state.value.home.phase))
            ConnectionRequestRuntimeGate.runLegacyStart { engine.create() }
        viewers.receiverSession(visibility.state.session)
    }
    fun join(value: String): Boolean {
        val joined = AppMediaRoles.role == MediaRole.RECEIVE &&
            ConnectionRequestRuntimeGate.runLegacyStart { engine.join(value) } == true
        if (joined) viewers.receiverSession(visibility.state.session)
        return joined
    }
    internal fun diagnostic(event: String) {
        requireMain()
        trace.record("$event phase=${state.value.home.phase} live=${state.value.presence.live} visible=${state.value.presence.visible} deadline=${state.value.presence.absentUntilElapsed} peer=${native?.hasPeer == true} socket=${engine.hasSignaling} signalingAvailable=${state.value.home.signalingAvailable} renderer=${native?.hasRenderer == true} anchorForeground=${ReceiverExecutionAnchorService.foregroundFor(state.value.presence.session)}")
    }
    fun attach(renderer: SurfaceViewRenderer) { requireMain(); view.attach(renderer); diagnostic("renderer-attached") }
    fun detach(renderer: SurfaceViewRenderer) { requireMain(); view.detach(renderer); diagnostic("renderer-detached") }
    fun stop() = stop("owner-stop", intentional = true)
    private fun stop(reason: String, intentional: Boolean = false) { requireMain(); engine.stop(reason, intentional); native = null }
}
