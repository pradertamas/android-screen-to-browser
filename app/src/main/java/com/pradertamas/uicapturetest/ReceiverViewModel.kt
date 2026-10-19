package com.pradertamas.uicapturetest

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.webrtc.SurfaceViewRenderer
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

data class ReceiverUi(val home: ReceiverHome = ReceiverHome(), val resolution: String = "N/A", val metrics: String = "N/A")

/** Retains only native session/application, never an Activity across recreation. View detaches in onDestroy. */
class ReceiverViewModel(application: Application) : AndroidViewModel(application) {
    /** Lifecycle bookkeeping only. No media/signaling operations; false preserves another owner. */
    internal fun ensureBoundToTask(taskId: Int): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (AppMediaRoles.role != MediaRole.RECEIVE) return false
        return runCatching { AppMediaRoles.bindReceiverToTask(this, taskId, ::stop,
            { MediaRolePolicy.receiverSwitchAllowed(state.value.home.phase) }) }.isSuccess
    }
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val mutable = MutableStateFlow(ReceiverUi())
    val state = mutable.asStateFlow()
    private var native: WebRtcReceiverSession? = null
    private var view: WeakReference<SurfaceViewRenderer>? = null
    private val wire = ReceiverWireRouter(ReceiverSignaling(http, main))
    private val engine: ReceiverEngine = ReceiverEngine(wire, { candidate, failure ->
        WebRtcReceiverSession(application, candidate, { engineConnection(it) }, failure,
            { rendered() }, { w, h -> mutable.value = mutable.value.copy(resolution = "$w × $h") },
            { mutable.value = mutable.value.copy(metrics = it) }).also { session ->
                native = session
                try { view?.get()?.let(session::attach) } catch (e: Exception) { session.close(); throw e }
            }
    }, { delay, action ->
        val task = Runnable { action() }; main.postDelayed(task, delay)
        val cancel: () -> Unit = { main.removeCallbacks(task) }; cancel
    }, { home -> mutable.value = if (home.phase == ReceiverPhase.PAIRING) ReceiverUi(home) else mutable.value.copy(home = home); AppMediaRoles.receiverPhaseChanged() })
    private val request = RequestReceiverOwner(engine, wire, ConnectionRequestMediaHandoff.process,
        { action -> main.post { action() } })
    private var retainedRequestFlow: Any? = null
    internal val requestIdentity: Any? get() = retainedRequestFlow
    internal val requestFlow: Any? get() = if (request.active) retainedRequestFlow else null
    init {
        AppMediaRoles.bindReceiver(this, ::stop, { MediaRolePolicy.receiverSwitchAllowed(state.value.home.phase) })
        ConnectionRequestRuntimeGate.runLegacyStart { engine.create() }
    }
    private fun engineConnection(state: String) { engine.connection(state) }
    private fun rendered() { engine.firstFrame() }
    /** Explicit B4E entry point only. Renderer detach/reattach does not redeem or restart it. */
    internal fun startRequest(handoff: String): Boolean {
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
    internal fun stopRequest(flow: Any): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return request.stop(flow)
    }
    fun ensureOwnCreate() {
        if (AppMediaRoles.role == MediaRole.RECEIVE && MediaRolePolicy.receiverSwitchAllowed(state.value.home.phase))
            ConnectionRequestRuntimeGate.runLegacyStart {
                ShowConnection.ensureReceiver(state.value.home) { engine.create() }
            }
    }
    fun retry() { if (AppMediaRoles.role == MediaRole.RECEIVE) ConnectionRequestRuntimeGate.runLegacyStart { engine.create() } }
    fun join(value: String) = AppMediaRoles.role == MediaRole.RECEIVE &&
        ConnectionRequestRuntimeGate.runLegacyStart { engine.join(value) } == true
    fun attach(renderer: SurfaceViewRenderer) { view = WeakReference(renderer); native?.attach(renderer) }
    fun detach(renderer: SurfaceViewRenderer) { if (view?.get() === renderer) { native?.detach(); view = null } }
    fun stop() { engine.stop(); native = null }
    override fun onCleared() {
        stop(); AppMediaRoles.unbindReceiver(this); view = null; main.removeCallbacksAndMessages(null)
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
}
