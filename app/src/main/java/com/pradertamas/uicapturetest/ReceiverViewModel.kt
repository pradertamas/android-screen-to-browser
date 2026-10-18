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
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val mutable = MutableStateFlow(ReceiverUi())
    val state = mutable.asStateFlow()
    private var native: WebRtcReceiverSession? = null
    private var view: WeakReference<SurfaceViewRenderer>? = null
    private val engine: ReceiverEngine = ReceiverEngine(ReceiverSignaling(http, main), { candidate, failure ->
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
    init {
        AppMediaRoles.bindReceiver(this, ::stop, { MediaRolePolicy.receiverSwitchAllowed(state.value.home.phase) })
        ConnectionRequestRuntimeGate.runLegacyStart { engine.create() }
    }
    private fun engineConnection(state: String) { engine.connection(state) }
    private fun rendered() { engine.firstFrame() }
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
