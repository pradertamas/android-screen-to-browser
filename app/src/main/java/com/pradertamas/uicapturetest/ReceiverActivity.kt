package com.pradertamas.uicapturetest

import android.os.Bundle
import android.app.Activity
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var model: ReceiverViewModel
    private var renderer: SurfaceViewRenderer? = null
    private var scanning by mutableStateOf(false)
    private var sharingLink = false
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
        if (AppMediaRoles.switchRole(MediaRole.SEND, stopActive)) finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        enableEdgeToEdge()
        if (AppMediaRoles.role != MediaRole.RECEIVE) {
            // Process recreation defaults to SEND; never infer RECEIVE from the restored Activity type.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish(); return
        }
        try { model = ViewModelProvider(this)[ReceiverViewModel::class.java] }
        catch (_: Exception) {
            AppMediaRoles.receiverStartFailed()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish(); return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { returnToSend(stopActive = true) }
        })
        setContent {
            val connectionShare = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { sharingLink = false }
            val ui by model.state.collectAsState()
            val mediaMode by AppMediaRoles.state.collectAsState()
            val phase = ui.home.phase
            var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
            var code by rememberSaveable { mutableStateOf("") }
            val activePath = selectedPath ?: if (phase == ReceiverPhase.CONNECTING) {
                if (ui.home.intent == PairingIntent.CREATE) ConnectionWorkflow.SHOW else ConnectionWorkflow.CONNECT
            } else null
            val taskScroll = remember(activePath) { androidx.compose.foundation.ScrollState(0) }
            var diagnostics by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
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
                                    if (activePath == null) {
                                    Text("Screen Share", style = MaterialTheme.typography.headlineLarge)
                                    Text("1. Choose role", style = MaterialTheme.typography.titleMedium)
                                    MediaRoleSelector(mediaMode.role, MediaRolePolicy.receiverSwitchAllowed(phase) && !scanning) { requested ->
                                        if (requested == MediaRole.SEND) returnToSend()
                                    }
                                    if (mediaMode.error.isNotEmpty()) Text(mediaMode.error)
                                    WorkflowStart(canChoose, selectedPath, share = {
                                        try {
                                            val url = PairingCode.receiverUrl(ui.home.code)
                                            sharingLink = true
                                            connectionShare.launch(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
                                            }, "Share connection link"))
                                        } catch (_: Exception) { sharingLink = false; error = "Link could not be shared. Please try again." }
                                    }, shareEnabled = canChoose && phase == ReceiverPhase.PAIRING && ui.home.intent == PairingIntent.CREATE) { selectedPath = it }
                                    if (selectedPath == null && error.isNotEmpty()) Text(error)
                                    }
                                    if (activePath != null) WorkflowPanel(activePath, canChoose, { code = ""; error = ""; selectedPath = null }) {
                                        if (phase == ReceiverPhase.PAIRING) {
                                            if (activePath == ConnectionWorkflow.SHOW && ui.home.intent == PairingIntent.CREATE) {
                                                WorkflowOwnCode(ui.home.code)
                                            }
                                            if (activePath == ConnectionWorkflow.CONNECT) {
                                                Button(enabled = !scanning, onClick = { scanCode({ scanned -> code = ""; error = ""; model.join(scanned) }, { error = it }) }) { Text("Scan QR code") }
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

                                }
                            }
                        }
                        if (activePath == null) {
                        TextButton(onClick = { diagnostics = !diagnostics }) { Text("Advanced diagnostics") }
                        if (diagnostics) Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Text("Media role: "+mediaMode.role.name)
                            Text("Current received video: ${ui.resolution}")
                            Text(ui.metrics)
                        }
                        }
                    }
                }
            }
        }
    }
    override fun onStop() {
        if (::model.isInitialized && !isChangingConfigurations && !scanning && !sharingLink) model.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onDestroy() {
        if (::model.isInitialized) renderer?.let(model::detach); renderer = null
        super.onDestroy()
    }
}
