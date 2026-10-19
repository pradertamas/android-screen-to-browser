package com.pradertamas.uicapturetest

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle

internal fun mediaEligible(s: ConnectionRequestMediaSnapshot?, flow: Any, role: MediaRole,
    resumed: Boolean, currentRole: MediaRole, gate: Boolean) = s?.flow === flow && resumed && gate &&
    currentRole == role && s.localRole == role && s.state == ConnectionRequestState.CONNECTING &&
    !s.exitRequested && s.phase == null && s.operation == null &&
    (s.side == ConnectionRequestSide.INITIATOR || s.won)

internal enum class RequestLaunchResult { IGNORED, CLOSED, TRANSFERRED }
/** Configuration-retained by the ViewModel. Never retains Activity, consent Intent or credentials. */
internal class RequestSenderLaunch(private val controller: ConnectionRequestController,
    private val handoffs: ConnectionRequestMediaHandoff, private val exit: (Any) -> Unit) {
    private var flow: Any? = null
    private var handoff: RequestMediaHandoffId? = null
    var consentPromptInFlight = false; private set
    var serviceHandoffTransferred = false; private set
    private var handled = false
    fun begin(expected: Any, resumed: Boolean, role: MediaRole, gate: Boolean, prompt: () -> Unit): Boolean {
        if (consentPromptInFlight) return false
        if (flow !== expected) { flow = expected; handled = false; serviceHandoffTransferred = false }
        if (handled || !mediaEligible(controller.mediaSnapshot(), expected, MediaRole.SEND, resumed, role, gate)) return false
        handoff = handoffs.reserve(expected, MediaRole.SEND) ?: return false
        handled = true; consentPromptInFlight = true
        try { prompt() } catch (_: Exception) { cancel() }
        return true
    }
    fun result(allowed: Boolean, role: MediaRole, gate: Boolean, start: (RequestMediaHandoffId) -> Unit): RequestLaunchResult {
        if (!consentPromptInFlight) return RequestLaunchResult.IGNORED
        consentPromptInFlight = false
        val current = controller.mediaSnapshot()
        if (current == null || current.flow !== flow) { abandon(); return RequestLaunchResult.IGNORED }
        if (!allowed || role != MediaRole.SEND || !gate || current.exitRequested || current.state != ConnectionRequestState.CONNECTING) {
            cancel(); return RequestLaunchResult.CLOSED
        }
        val id = handoff ?: return RequestLaunchResult.IGNORED
        return try {
            start(id)
            serviceHandoffTransferred = true; handoff = null
            RequestLaunchResult.TRANSFERRED
        } catch (_: Exception) { cancel(); RequestLaunchResult.CLOSED }
    }
    fun cancel() {
        if (serviceHandoffTransferred || (handoff == null && !consentPromptInFlight)) return
        consentPromptInFlight = false; handled = true
        abandon(); flow?.let(exit)
    }
    private fun abandon() { handoff?.let { handoffs.abandon(it.value) }; handoff = null }
    fun ownsCurrentPrompt() = consentPromptInFlight && controller.mediaSnapshot()?.flow === flow
    override fun toString() = "RequestSenderLaunch[redacted]"
}

class RequestSenderLaunchViewModel : ViewModel() {
    internal val launch = RequestSenderLaunch(ConnectionRequests.controller, ConnectionRequestMediaHandoff.process,
        RequestMediaLifecycle.coordinator::requestExit)
    override fun onCleared() {
        val prompt = launch.ownsCurrentPrompt()
        launch.cancel()
        if (prompt && WebRtcState.state.value.status == "Allow screen sharing") WebRtcState.cancelConsent()
    }
}

internal fun requestSenderIntent(activity: ComponentActivity, consent: Intent, id: RequestMediaHandoffId) =
    Intent(activity, WebRtcService::class.java).setAction(WebRtcService.START)
        .putExtra(WebRtcService.MODE, SenderStartMode.REQUEST.name)
        .putExtra(WebRtcService.CONSENT, consent).putExtra(WebRtcService.HANDOFF, id.value)

@Composable
internal fun RequestSenderConsent(activity: ComponentActivity, model: RequestSenderLaunchViewModel,
    expected: Any?, initiator: Boolean = false, transferred: () -> Unit = {}) {
    val c = ConnectionRequests.controller
    val public by c.state.collectAsState()
    val revision by c.mediaChanges.collectAsState()
    val ownership by c.ownershipChanges.collectAsState()
    val role by AppMediaRoles.state.collectAsState()
    val life by activity.lifecycle.currentStateFlow.collectAsState()
    val gate by ConnectionRequestRuntimeGate.allowed.collectAsState()
    val exact = if (initiator) c.mediaSnapshot()?.takeIf { it.side == ConnectionRequestSide.INITIATOR }?.flow else expected
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val outcome = model.launch.result(result.resultCode == Activity.RESULT_OK && result.data != null,
            AppMediaRoles.role, !ConnectionRequestRuntimeGate.legacyStartAllowed()) { id ->
            activity.startForegroundService(requestSenderIntent(activity, checkNotNull(result.data), id))
        }
        when (outcome) {
            RequestLaunchResult.TRANSFERRED -> { WebRtcState.set(WebRtcUi(busy = true, status = "Connecting")); transferred() }
            RequestLaunchResult.CLOSED -> WebRtcState.set(WebRtcUi(status = "Sharing cancelled"))
            else -> Unit
        }
    }
    LaunchedEffect(exact, public, revision, ownership, role.role, life, gate) {
        if (exact != null) model.launch.begin(exact, life == Lifecycle.State.RESUMED, role.role, !gate) {
            WebRtcState.set(WebRtcUi(busy = true, status = "Allow screen sharing"))
            try { launcher.launch(activity.getSystemService(MediaProjectionManager::class.java)
                .createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())) }
            catch (_: Exception) { WebRtcState.set(WebRtcUi(status = "Screen sharing could not start")); throw IllegalStateException() }
        }
    }
}

internal fun startRequestReceiver(controller: ConnectionRequestController, handoffs: ConnectionRequestMediaHandoff,
    flow: Any, resumed: Boolean, role: MediaRole, gate: Boolean, exit: (Any) -> Unit,
    start: (RequestMediaHandoffId) -> Boolean): Boolean {
    if (!mediaEligible(controller.mediaSnapshot(), flow, MediaRole.RECEIVE, resumed, role, gate)) return false
    val id = handoffs.reserve(flow, MediaRole.RECEIVE) ?: return false
    val success = try { start(id) } catch (_: Exception) { false }
    if (!success) handoffs.reject(id.value, exit)
    return success
}

@Composable
internal fun RequestCleanupRetry(expected: Any? = null, scoped: Boolean = false) {
    val lifecycle = RequestMediaLifecycle.coordinator
    val ui by lifecycle.state.collectAsState()
    if (ui.retry && (!scoped || (expected != null && ConnectionRequests.controller.mediaSnapshot()?.flow === expected))) {
        Text("Could not close the connection.")
        Button(onClick = { (if (scoped) expected else ConnectionRequests.controller.mediaSnapshot()?.flow)?.let(lifecycle::retry) }) { Text("Try again") }
    }
}
