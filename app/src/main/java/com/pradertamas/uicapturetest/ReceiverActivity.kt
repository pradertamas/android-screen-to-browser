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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pradertamas.uicapturetest.ui.theme.UICaptureTestTheme
import org.webrtc.SurfaceViewRenderer

/** Foreground-only native receiver. Configuration recreation preserves the ViewModel/peer, replaces only the renderer. */
class ReceiverActivity : ComponentActivity() {
    companion object { internal const val REQUEST_MEDIA_HANDOFF = "requestMediaHandoff" }
    private fun takeRequestHandoff(value: Intent): String? {
        val id = runCatching { value.getStringExtra(REQUEST_MEDIA_HANDOFF) }.getOrNull()
        value.removeExtra(REQUEST_MEDIA_HANDOFF); intent?.removeExtra(REQUEST_MEDIA_HANDOFF)
        return id
    }
    private fun deliverRequestHandoff(id: String?) {
        val attached = ::model.isInitialized && model.ensureBoundToTask(taskId)
        if (id == null) return
        RequestMediaLifecycle.coordinator
        if (!attached || !model.startRequest(id))
            ConnectionRequestMediaHandoff.process.reject(id, RequestMediaLifecycle.coordinator::requestExit)
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
                model.stopRequest(owned); finish(); return
            }
        }
        if (::model.isInitialized) model.requestFlow?.let { flow ->
            val current = ConnectionRequests.controller.mediaSnapshot()?.flow
            model.stopRequest(flow)
            if (current != null && current !== flow) { finish(); return }
        }
        if (AppMediaRoles.switchRole(MediaRole.SEND, stopActive)) finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestHandoff = takeRequestHandoff(intent)
        RequestMediaLifecycle.coordinator
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        enableEdgeToEdge()
        if (AppMediaRoles.role != MediaRole.RECEIVE) {
            requestHandoff?.let { ConnectionRequestMediaHandoff.process.reject(it, RequestMediaLifecycle.coordinator::requestExit) }
            // Process recreation defaults to SEND; never infer RECEIVE from the restored Activity type.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish(); return
        }
        try { model = ViewModelProvider(this)[ReceiverViewModel::class.java] }
        catch (_: Exception) {
            requestHandoff?.let { ConnectionRequestMediaHandoff.process.reject(it, RequestMediaLifecycle.coordinator::requestExit) }
            AppMediaRoles.receiverStartFailed()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish(); return
        }
        if (!model.ensureBoundToTask(taskId)) {
            requestHandoff?.let { ConnectionRequestMediaHandoff.process.reject(it, RequestMediaLifecycle.coordinator::requestExit) }
            finish(); return
        }
        deliverRequestHandoff(requestHandoff)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { returnToSend(stopActive = true) }
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
            val phase = ui.home.phase
            var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
            var code by rememberSaveable { mutableStateOf("") }
            val activePath = selectedPath ?: if (phase == ReceiverPhase.CONNECTING) {
                if (ui.home.intent == PairingIntent.CREATE) ConnectionWorkflow.SHOW else ConnectionWorkflow.CONNECT
            } else null
            val taskScroll = remember(activePath) { androidx.compose.foundation.ScrollState(0) }
            var diagnostics by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            val diagnosticsContent: @Composable () -> Unit = {
                        TextButton(onClick = { diagnostics = !diagnostics }) { Text("Advanced diagnostics") }
                        if (diagnostics) Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Text("Media role: "+mediaMode.role.name)
                            Text("Current received video: ${ui.resolution}")
                            Text(ui.metrics)
                        }
            }
            LaunchedEffect(phase) {
                // An external waiting-only scanner/share UI must not enable background receiving.
                if (phase in setOf(ReceiverPhase.CONNECTING, ReceiverPhase.RECEIVING) &&
                    !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) model.stop()
            }
            DisposableEffect(phase) {
                if (phase == ReceiverPhase.RECEIVING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            UICaptureTestTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        RequestCleanupRetry()
                        if (phase == ReceiverPhase.RECEIVING) Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Receiving", style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { returnToSend(stopActive = true) }) { Text("Stop receiving") }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // Keep a surface available behind the opaque pairing/connecting UI so the first frame can render.
                            AndroidView(factory = { context -> SurfaceViewRenderer(context).also { renderer = it; model.attach(it) } },
                                modifier = Modifier.fillMaxSize())
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
                                        shareEnabled = canChoose && !requestShare.busy) { path ->
                                        if (path == ConnectionWorkflow.SHOW) model.ensureOwnCreate()
                                        if (path == ConnectionWorkflow.CONNECT && phase == ReceiverPhase.ENDED) model.retry()
                                        selectedPath = path
                                    }
                                    if (requestShare.message.isNotEmpty()) Text(requestShare.message)
                                    if (selectedPath == null && error.isNotEmpty()) Text(error)
                                    }
                                    if (activePath != null && model.requestFlow == null) WorkflowPanel(activePath, canChoose, closeTask) {
                                        if (phase == ReceiverPhase.PAIRING) {
                                            if (activePath == ConnectionWorkflow.SHOW && ShowConnection.receiverReady(ui.home)) {
                                                WorkflowOwnCode(ui.home.code)
                                            }
                                            if (activePath == ConnectionWorkflow.CONNECT) {
                                                Button(modifier = Modifier.fillMaxWidth(), enabled = !scanning, onClick = { scanCode({ scanned -> code = ""; error = ""; model.join(scanned) }, { error = it }) }) { Text("Scan QR code") }
                                                OutlinedTextField(code, { value ->
                                                    val normalized = AutoPairing.input(value)
                                                    if (normalized != null) { code = normalized; error = "" }
                                                    else error = "Invalid connection code"
                                                }, enabled = !scanning, label = { Text("Connection code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                                Button(enabled = !scanning, modifier = Modifier.fillMaxWidth(), onClick = { if (!model.join(code)) error = "Invalid connection code" }) { Text("Connect") }
                                            }
                                        }
                                        if (phase == ReceiverPhase.CONNECTING) Text(ConnectionWorkflow.displayStatus(ui.home.status))
                                        if (error.isNotEmpty()) Text(error)
                                        if (phase == ReceiverPhase.ERROR || phase == ReceiverPhase.ENDED) {
                                            Button(onClick = { code = ""; error = ""; model.retry() }) { Text("Retry receiving") }
                                        }
                                    }
                                    if (activePath == null) diagnosticsContent()

                                }
                            }
                        }
                        if (phase == ReceiverPhase.RECEIVING && activePath == null) diagnosticsContent()
                    }
                }
            }
        }
    }
    override fun onStop() {
        if (::model.isInitialized && !isChangingConfigurations && !scanning) {
            val flow = model.requestFlow
            if (flow != null) model.stopRequest(flow) else model.stop()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onDestroy() {
        if (::model.isInitialized) renderer?.let(model::detach); renderer = null
        super.onDestroy()
    }
}
