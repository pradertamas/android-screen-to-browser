package com.pradertamas.uicapturetest

import android.os.Bundle
import android.app.Activity
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pradertamas.uicapturetest.ui.theme.UICaptureTestTheme
import org.webrtc.SurfaceViewRenderer

/** Foreground-only native receiver. Configuration recreation preserves the ViewModel/peer, replaces only the renderer. */
class ReceiverActivity : ComponentActivity() {
    private val viewerIdentity = Any()
    companion object { internal const val REQUEST_MEDIA_HANDOFF = "requestMediaHandoff" }
    private fun takeRequestHandoff(value: Intent): String? {
        val id = runCatching { value.getStringExtra(REQUEST_MEDIA_HANDOFF) }.getOrNull()
        value.removeExtra(REQUEST_MEDIA_HANDOFF); intent?.removeExtra(REQUEST_MEDIA_HANDOFF)
        return id
    }
    private fun deliverRequestHandoff(id: String?) {
        val attached = ::model.isInitialized && model.ensureBoundToTask(taskId)
        if (attached) model.owner.viewerAttached(viewerIdentity)
        if (id == null) return
        RequestMediaLifecycle.coordinator
        if (!attached || !model.startRequest(id)) {
            RequestFlowTrace.record(RequestFlowTrace.Event.RECEIVER_HANDOFF_REJECTED, role = AppMediaRoles.role,
                state = ConnectionRequests.controller.state.value)
            ConnectionRequestMediaHandoff.process.reject(id, RequestMediaLifecycle.coordinator::requestExit)
        } else RequestFlowTrace.record(RequestFlowTrace.Event.RECEIVER_HANDOFF_ACCEPTED, model.requestIdentity,
            AppMediaRoles.role, ConnectionRequests.controller.state.value)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = takeRequestHandoff(intent); setIntent(intent); deliverRequestHandoff(id)
    }
    private val requestMonitorOwner = ConnectionRequestForegroundOwner()
    private val requestSetupOwner = ConnectionRequestSetupOwner()
    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.ensureBoundToTask(taskId)
        ConnectionRequestInitiatorMonitoring.monitor.foreground(requestMonitorOwner, MediaRole.RECEIVE)
        ConnectionRequestSetup.coordinator.foreground(requestSetupOwner, MediaRole.RECEIVE)
    }
    override fun onPause() {
        ConnectionRequestInitiatorMonitoring.monitor.background(requestMonitorOwner)
        ConnectionRequestSetup.coordinator.background(requestSetupOwner)
        super.onPause()
    }
    private lateinit var model: ReceiverViewModel
    override fun onStart() {
        super.onStart()
        if (::model.isInitialized) {
            model.owner.viewerVisible(viewerIdentity)
            if (AppMediaRoles.role != MediaRole.RECEIVE) { showMain(); return }
            renderer?.let(model::attach)
            if (model.state.value.presence.live) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    private fun showMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finishReceiver()
    }
    private fun viewerBack() {
        if (::model.isInitialized && model.state.value.presence.live) {
            model.owner.viewerHidden(viewerIdentity, false, false)
            renderer?.let(model::detach)
            showMain()
        } else returnToSend(stopActive = true)
    }
    private fun finishReceiver() {
        RequestFlowTrace.record(RequestFlowTrace.Event.FINISH_RECEIVER_RETURN,
            if (::model.isInitialized) model.requestIdentity else null,
            AppMediaRoles.role, ConnectionRequests.controller.state.value)
        finish()
    }
    private var renderer: SurfaceViewRenderer? = null
    private var scanning by mutableStateOf(false)
    private fun scanCode(done: (String) -> Unit, failure: (String) -> Unit) {
        if (scanning || AppMediaRoles.role != MediaRole.RECEIVE || model.state.value.home.phase != ReceiverPhase.PAIRING) return
        scanning = true
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
        try { com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { result ->
                scanning = false
                if (!isDestroyed && !isFinishing && AppMediaRoles.role == MediaRole.RECEIVE) {
                    val code = PairingCode.parseQr(result.rawValue.orEmpty())
                    if (code != null) done(code) else failure("QR code is not a valid connection code.")
                }
            }
            .addOnCanceledListener { scanning = false }
            .addOnFailureListener { error ->
                scanning = false
                if (!isDestroyed && !isFinishing) failure(if ((error as? com.google.mlkit.common.MlKitException)?.errorCode ==
                    com.google.mlkit.common.MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED)
                    "Camera permission denied. Enter the connection code instead." else "Camera is not available. Enter the connection code instead.")
            }
        } catch (_: Exception) { scanning = false; failure("Camera is not available. Enter the connection code instead.") }
    }
    private fun returnToSend(stopActive: Boolean = false) {
        if (::model.isInitialized) model.requestIdentity?.let { owned ->
            val current = ConnectionRequests.controller.mediaSnapshot()
            if (current != null && current.flow !== owned &&
                current.phase in setOf(RequestMediaPhase.RESERVED, RequestMediaPhase.STARTED)) {
                model.stopRequest(owned); finishReceiver(); return
            }
        }
        if (::model.isInitialized) model.requestFlow?.let { flow ->
            val current = ConnectionRequests.controller.mediaSnapshot()?.flow
            model.stopRequest(flow)
            if (current != null && current !== flow) { finishReceiver(); return }
        }
        if (AppMediaRoles.switchRole(MediaRole.SEND, stopActive)) finishReceiver()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestHandoff = takeRequestHandoff(intent)
        RequestMediaLifecycle.coordinator
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        enableEdgeToEdge()
        if (AppMediaRoles.role != MediaRole.RECEIVE) {
            RequestFlowTrace.record(RequestFlowTrace.Event.FALLBACK_ROLE_NOT_RECEIVE, role = AppMediaRoles.role,
                state = ConnectionRequests.controller.state.value)
            requestHandoff?.let { ConnectionRequestMediaHandoff.process.reject(it, RequestMediaLifecycle.coordinator::requestExit) }
            // Process recreation defaults to SEND; never infer RECEIVE from the restored Activity type.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finishReceiver(); return
        }
        try { model = ViewModelProvider(this)[ReceiverViewModel::class.java] }
        catch (_: Exception) {
            RequestFlowTrace.record(RequestFlowTrace.Event.FALLBACK_VIEWMODEL_FAILED, role = AppMediaRoles.role,
                state = ConnectionRequests.controller.state.value)
            requestHandoff?.let { ConnectionRequestMediaHandoff.process.reject(it, RequestMediaLifecycle.coordinator::requestExit) }
            AppMediaRoles.receiverStartFailed()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finishReceiver(); return
        }
        if (!model.ensureBoundToTask(taskId)) {
            RequestFlowTrace.record(RequestFlowTrace.Event.RECEIVER_ATTACH_FAILED, role = AppMediaRoles.role,
                state = ConnectionRequests.controller.state.value)
            requestHandoff?.let { ConnectionRequestMediaHandoff.process.reject(it, RequestMediaLifecycle.coordinator::requestExit) }
            finishReceiver(); return
        }
        deliverRequestHandoff(requestHandoff)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { viewerBack() }
        })
        setContent {
            val ui by model.state.collectAsState()
            val mediaMode by AppMediaRoles.state.collectAsState()
            val requestState by ConnectionRequests.controller.state.collectAsState()
            val requestRevision by ConnectionRequests.controller.mediaChanges.collectAsState()
            val lifecycleState by lifecycle.currentStateFlow.collectAsState()
            LaunchedEffect(requestState, requestRevision, lifecycleState, mediaMode.role) {
                val s = ConnectionRequests.controller.mediaSnapshot()
                if (s?.side == ConnectionRequestSide.INITIATOR) startRequestReceiver(ConnectionRequests.controller,
                    ConnectionRequestMediaHandoff.process, s.flow, lifecycleState == androidx.lifecycle.Lifecycle.State.RESUMED,
                    mediaMode.role, !ConnectionRequestRuntimeGate.legacyStartAllowed(), RequestMediaLifecycle.coordinator::requestExit) {
                    model.startRequest(it.value)
                }
            }
            val requestShare = rememberRequestShare(mediaMode.role) { model.stop() }
            val legacyAllowed by ConnectionRequestRuntimeGate.allowed.collectAsState()
            val phase = ui.home.phase
            var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
            var code by rememberSaveable { mutableStateOf("") }
            val activePath = selectedPath ?: if (phase == ReceiverPhase.CONNECTING) {
                if (ui.home.intent == PairingIntent.CREATE) ConnectionWorkflow.SHOW else ConnectionWorkflow.CONNECT
            } else null
            val taskScroll = remember(activePath) { androidx.compose.foundation.ScrollState(0) }
            var showStop by remember { mutableStateOf(false) }
            var stopReveal by remember { mutableIntStateOf(0) }
            LaunchedEffect(showStop, stopReveal) {
                if (showStop) { kotlinx.coroutines.delay(3000); showStop = false }
            }
            var error by remember { mutableStateOf("") }
            DisposableEffect(phase) {
                if (phase == ReceiverPhase.RECEIVING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            UICaptureTestTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        RequestCleanupRetry()
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black)
                            .pointerInput(phase) { detectTapGestures { if (phase == ReceiverPhase.RECEIVING) { showStop = true; stopReveal++ } } },
                            contentAlignment = Alignment.Center) {
                            // Keep a surface available behind the opaque pairing/connecting UI so the first frame can render.
                            val frame = ui.frame
                            val fit = frame?.let { receiverAspectFit(maxWidth.value, maxHeight.value, it) }
                            val videoSize = if (fit != null) Modifier.size(fit.width.dp, fit.height.dp) else Modifier.fillMaxSize()
                            AndroidView(factory = { context -> SurfaceViewRenderer(context).also { renderer = it; model.attach(it) } },
                                modifier = videoSize.alpha(if (frame == null) 0f else 1f), onReset = null,
                                onRelease = { model.detach(it); if (renderer === it) renderer = null })
                            if (phase == ReceiverPhase.RECEIVING && showStop) {
                                val shownSession = ui.presence.session
                                Button(onClick = { if (shownSession != null && model.owner.stopActive(shownSession)) showMain() },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) { Text("Stop receiving") }
                            }
                            if (phase != ReceiverPhase.RECEIVING) {
                                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                                    .padding(24.dp).verticalScroll(taskScroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val canChoose = MediaRolePolicy.receiverSwitchAllowed(phase) && !scanning
                                    val closeTask: () -> Unit = { code = ""; error = ""; selectedPath = null }
                                    BackHandler(enabled = activePath != null && canChoose && model.requestFlow == null, onBack = closeTask)
                                    if (model.requestFlow != null) {
                                        Text(if (phase == ReceiverPhase.PAIRING) "Connecting" else ui.home.status)
                                        TextButton(onClick = { returnToSend(stopActive = true) }) { Text("Stop receiving") }
                                    }
                                    if (activePath == null && model.requestFlow == null) {
                                    Text("Screen Share", style = MaterialTheme.typography.headlineLarge)
                                    Text("1. Choose role", style = MaterialTheme.typography.titleMedium)
                                    MediaRoleSelector(mediaMode.role, MediaRolePolicy.receiverSwitchAllowed(phase) && !scanning) { requested ->
                                        if (requested == MediaRole.SEND) returnToSend()
                                    }
                                    if (mediaMode.error.isNotEmpty()) Text(mediaMode.error)
                                    WorkflowStart(canChoose, selectedPath, share = requestShare.start,
                                        shareEnabled = canChoose && !requestShare.busy, shareLabel = requestShare.label) { path ->
                                        if (!requestShare.busy) {
                                            val entry = enterManualConnection(ConnectionRequests.controller, MediaRole.RECEIVE,
                                                RequestMediaLifecycle.coordinator::requestExit)
                                            if (entry == ManualConnectionEntry.BLOCKED)
                                                error = "Another connection request is active. Close it before using a code."
                                            else selectedPath = path
                                        }
                                    }
                                    if (requestShare.message.isNotEmpty()) Text(requestShare.message)
                                    if (selectedPath == null && error.isNotEmpty()) Text(error)
                                    }
                                    if (activePath != null && model.requestFlow == null) WorkflowPanel(activePath, canChoose, closeTask) {
                                        LaunchedEffect(activePath, legacyAllowed, lifecycleState, mediaMode.role) {
                                            if (selectedPath != null && canChoose && legacyAllowed && lifecycleState == androidx.lifecycle.Lifecycle.State.RESUMED &&
                                                mediaMode.role == MediaRole.RECEIVE && ConnectionRequests.controller.mediaSnapshot() == null) {
                                                if (activePath == ConnectionWorkflow.SHOW) model.ensureOwnCreate()
                                                if (activePath == ConnectionWorkflow.CONNECT && model.state.value.home.phase == ReceiverPhase.ENDED) model.retry()
                                            }
                                        }
                                        if (!legacyAllowed) Text("Closing the shared connection link before using a code...")
                                        if (activePath == ConnectionWorkflow.SHOW && canChoose && !ShowConnection.receiverReady(ui.home)) {
                                            Text("Connection code")
                                            Text("Preparing connection code...")
                                        }
                                        if (phase != ReceiverPhase.RECEIVING) {
                                            if (activePath == ConnectionWorkflow.SHOW && ShowConnection.receiverReady(ui.home)) {
                                                WorkflowOwnCode(ui.home.code)
                                            }
                                            if (activePath == ConnectionWorkflow.CONNECT) {
                                                val editable = legacyAllowed && phase == ReceiverPhase.PAIRING
                                                Button(modifier = Modifier.fillMaxWidth(), enabled = editable && !scanning, onClick = { scanCode({ scanned -> code = ""; error = ""; model.join(scanned) }, { error = it }) }) { Text("Scan QR code") }
                                                Text("OR")
                                                OutlinedTextField(code, { value ->
                                                    val normalized = AutoPairing.input(value)
                                                    if (normalized != null) { code = normalized; error = "" }
                                                    else error = "Invalid connection code"
                                                }, enabled = editable && !scanning, label = { Text("Connection code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                                Button(enabled = editable && !scanning, modifier = Modifier.fillMaxWidth(), onClick = { if (!model.join(code)) error = "Invalid connection code" }) { Text("Connect") }
                                            }
                                        }
                                        if (phase == ReceiverPhase.CONNECTING) Text(ConnectionWorkflow.displayStatus(ui.home.status))
                                        if (error.isNotEmpty()) Text(error)
                                        if (phase == ReceiverPhase.ERROR || phase == ReceiverPhase.ENDED) {
                                            Button(onClick = { code = ""; error = ""; model.retry() }) { Text("Retry receiving") }
                                        }
                                    }

                                }
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onStop() {
        if (::model.isInitialized) {
            model.owner.viewerHidden(viewerIdentity, isChangingConfigurations, scanning)
            renderer?.let(model::detach)
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onDestroy() {
        if (::model.isInitialized) renderer?.let(model::detach); renderer = null
        if (::model.isInitialized) model.owner.viewerDestroyed(viewerIdentity, isChangingConfigurations)
        super.onDestroy()
    }
}

/** Retained developer UI; deliberately not invoked by the normal receiver screen. */
@Composable
internal fun ReceiverDiagnostics(ui: ReceiverUi, role: MediaRole) {
    var diagnostics by remember { mutableStateOf(false) }
    TextButton(onClick = { diagnostics = !diagnostics }) { Text("Advanced diagnostics") }
    if (diagnostics) Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Media role: " + role.name)
        Text("Current received video: ${ui.resolution}")
        Text(ui.metrics)
    }
}
