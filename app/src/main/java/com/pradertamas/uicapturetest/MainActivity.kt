package com.pradertamas.uicapturetest

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import com.pradertamas.uicapturetest.ui.theme.UICaptureTestTheme
import java.util.Locale
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.common.MlKitException

class MainActivity : ComponentActivity() {
    private val receiverViewerIdentity = Any()
    private lateinit var requestSender: RequestSenderLaunchViewModel
    private val requestMonitorOwner = ConnectionRequestForegroundOwner()
    private val requestSetupOwner = ConnectionRequestSetupOwner()
    private var externalUi = false
    private val remoteSharing: Boolean get() = PairingHost.controller.sharedWaitingProtected
    private var consentEpoch = -1L
    private fun openReceiver() {
        try { startActivity(Intent(this, ReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        catch (_: Exception) { AppMediaRoles.receiverStartFailed() }
    }
    private fun traceActivity(event: String, willCallHide: Boolean? = null) {
        ReceiverSessions.diagnostic("$event component=MainActivity activity=${System.identityHashCode(receiverViewerIdentity)} actualRole=${AppMediaRoles.role}")
        val home = PairingHost.state.value
        ScreenShareLifecycleDiag.write("event=$event lifecycle=${lifecycle.currentState} phase=${home.phase} epoch=${home.epoch} externalUi=$externalUi remoteSharing=$remoteSharing role=${AppMediaRoles.role} captureBusy=${CaptureState.state.value.busy}" + (willCallHide?.let { " willCallHide=$it" } ?: ""))
    }
    override fun onPause() {
        ConnectionRequestInitiatorMonitoring.monitor.background(requestMonitorOwner)
        ConnectionRequestSetup.coordinator.background(requestSetupOwner)
        traceActivity("activity-onPause"); super.onPause()
    }
    override fun onResume() {
        super.onResume()
        ReceiverSessions.checkExpiry()
        ConnectionRequestInitiatorMonitoring.monitor.foreground(requestMonitorOwner, MediaRole.SEND)
        ConnectionRequestSetup.coordinator.foreground(requestSetupOwner, MediaRole.SEND)
        traceActivity("activity-onResume")
    }
    override fun onStart() {
        super.onStart()
        traceActivity("activity-onStart")
        ReceiverSessions.checkExpiry()
        ReceiverSessions.mainVisible(receiverViewerIdentity)
        if (AppMediaRoles.role == MediaRole.RECEIVE) {
            if (!ReceiverSessions.state.value.presence.live) openReceiver()
        }
        else if (!CaptureState.state.value.busy) ConnectionRequestRuntimeGate.runLegacyStart { PairingHost.controller.show() }
    }
    override fun onStop() {
        ReceiverSessions.mainHidden(receiverViewerIdentity, isChangingConfigurations)
        val willCallHide = PairingHost.controller.shouldHideOnStop(externalUi)
        traceActivity("activity-onStop", willCallHide)
        if (willCallHide) PairingHost.controller.hide()
        super.onStop()
    }
    override fun onDestroy() {
        ReceiverSessions.mainDestroyed(receiverViewerIdentity)
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("consentEpoch", consentEpoch)
        outState.putBoolean("externalUi", externalUi)
        super.onSaveInstanceState(outState)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReceiverSessions.mainAttached(receiverViewerIdentity)
        requestSender = ViewModelProvider(this)[RequestSenderLaunchViewModel::class.java]
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        PairingHost.initialize(this)
        consentEpoch = savedInstanceState?.getLong("consentEpoch", -1L) ?: -1L
        externalUi = savedInstanceState?.getBoolean("externalUi", false) ?: false
        enableEdgeToEdge()
        setContent {
            RequestSenderConsent(this, requestSender, null, initiator = true)
            val state by CaptureState.state.collectAsState()
            val rtc by WebRtcState.state.collectAsState()
            val mediaMode by AppMediaRoles.state.collectAsState()
            val receiver by ReceiverSessions.state.collectAsState()
            val requestShare = rememberRequestShare(mediaMode.role) {
                PairingHost.controller.hide()
                PairingHost.controller.close()
            }
            val legacyAllowed by ConnectionRequestRuntimeGate.allowed.collectAsState()
            var diagnostics by remember { mutableStateOf(false) }
            var scanning by remember { mutableStateOf(false) }
            val home by PairingHost.state.collectAsState()
            val lifecycleState by lifecycle.currentStateFlow.collectAsState()
            var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
            var joinCode by rememberSaveable { mutableStateOf("") }
            LaunchedEffect(receiver.presence.live) {
                if (receiver.presence.live) { selectedPath = null; joinCode = "" }
            }
            var regenerateRemote by remember { mutableStateOf(false) }
            var remoteFeedback by remember { mutableStateOf("Remote link ready") }
            val rtcConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                externalUi = false
                val data = result.data
                if (AppMediaRoles.role == MediaRole.SEND && result.resultCode == Activity.RESULT_OK && data != null && PairingHost.controller.allow(consentEpoch)) {
                    try {
                        startForegroundService(Intent(this, WebRtcService::class.java).apply {
                            action = WebRtcService.START
                            putExtra(WebRtcService.CONSENT, data)
                            putExtra(WebRtcService.SESSION, PairingHost.state.value.code)
                            putExtra(WebRtcService.INTENT, PairingHost.state.value.intent.name)
                            putExtra(WebRtcService.EPOCH, consentEpoch)
                        })
                    } catch (_: Exception) { WebRtcState.cancelConsent(); PairingHost.controller.cancel(consentEpoch) }
                } else {
                    scanning = false
                    joinCode = ""
                    WebRtcState.cancelConsent()
                    PairingHost.controller.cancel(consentEpoch)
                }
            }
            LaunchedEffect(home.phase, lifecycleState, state.busy, mediaMode.role) {
                ScreenShareLifecycleDiag.write("event=consent-effect phase=${home.phase} epoch=${home.epoch} lifecycle=$lifecycleState role=${mediaMode.role} busy=${state.busy}")
                if (home.phase == PairingPhase.JOINING) joinCode = ""
                if (state.busy) { ScreenShareLifecycleDiag.write("event=consent-effect-blocked reason=busy"); PairingHost.controller.hide(); return@LaunchedEffect }
                if (lifecycleState == Lifecycle.State.RESUMED && mediaMode.role == MediaRole.SEND) {
                    ConnectionRequestRuntimeGate.runLegacyStart {
                        PairingHost.controller.show()
                        val epoch = PairingHost.controller.claimConsent()
                        if (epoch != null) {
                            ScreenShareLifecycleDiag.write("event=consent-launch-requested epoch=$epoch")
                            consentEpoch = epoch
                            joinCode = ""
                            externalUi = true
                            WebRtcState.set(WebRtcUi(busy = true, status = "Allow screen sharing",
                                pairingCode = home.code, intent = home.intent))
                            try {
                                ScreenShareLifecycleDiag.write("event=consent-launch epoch=$epoch")
                                rtcConsent.launch(getSystemService(MediaProjectionManager::class.java)
                                    .createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice()))
                                ScreenShareLifecycleDiag.write("event=consent-launch-returned epoch=$epoch")
                            } catch (_: Exception) {
                                ScreenShareLifecycleDiag.write("event=consent-launch-failed epoch=$epoch")
                                externalUi = false; WebRtcState.cancelConsent(); PairingHost.controller.cancel(epoch)
                            }
                        } else {
                            ScreenShareLifecycleDiag.write("event=consent-effect-blocked reason=claim-null phase=${PairingHost.state.value.phase} epoch=${PairingHost.state.value.epoch}")
                        }
                    }
                } else {
                    val reason = if (lifecycleState != Lifecycle.State.RESUMED) "not-resumed" else "wrong-role"
                    ScreenShareLifecycleDiag.write("event=consent-effect-blocked reason=$reason")
                }
            }
            val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val data = result.data
                if (AppMediaRoles.role == MediaRole.SEND && result.resultCode == Activity.RESULT_OK && data != null) {
                    CaptureState.set(CaptureUi(status = "Starting", busy = true))
                    try {
                        startForegroundService(Intent(this, CaptureService::class.java).apply {
                            action = CaptureService.START
                            putExtra(CaptureService.CONSENT, data)
                        })
                    } catch (e: Exception) {
                        CaptureState.set(CaptureUi(status = "Error: ${e.message}"))
                    }
                } else CaptureState.set(CaptureUi(status = "Permission cancelled"))
            }
            UICaptureTestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                        val activePath = if (receiver.presence.live) null else selectedPath ?: if (rtc.busy || home.phase in setOf(PairingPhase.JOINING, PairingPhase.CONSENT, PairingPhase.CONNECTING, PairingPhase.STREAMING)) {
                            if (home.intent == PairingIntent.CREATE) ConnectionWorkflow.SHOW else ConnectionWorkflow.CONNECT
                        } else null
                    val taskScroll = remember(activePath) { androidx.compose.foundation.ScrollState(0) }
                    Column(Modifier.padding(padding).padding(24.dp).verticalScroll(taskScroll),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RequestCleanupRetry()
                        val canChoose = MediaRolePolicy.senderSwitchAllowed(home.phase, state.busy, rtc.busy) && !scanning && !receiver.active
                        val closeTask: () -> Unit = { PairingHost.controller.typed(""); joinCode = ""; selectedPath = null }
                        BackHandler(enabled = activePath != null && canChoose, onBack = closeTask)
                        if (activePath == null) {
                        Text("Screen Share", style = MaterialTheme.typography.headlineLarge)
                        Text("1. Choose role", style = MaterialTheme.typography.titleMedium)
                        MediaRoleSelector(mediaMode.role,
                            canChoose) { requested ->
                            if (requested != mediaMode.role && AppMediaRoles.switchRole(requested)) {
                                selectedPath = null; joinCode = ""
                                if (requested == MediaRole.RECEIVE) openReceiver()
                            }
                        }
                        if (mediaMode.error.isNotEmpty()) Text(mediaMode.error)
                        if (!rtc.busy) WorkflowStart(canChoose, selectedPath, share = requestShare.start,
                            shareEnabled = canChoose && !requestShare.busy, shareLabel = requestShare.label,
                            activeShare = receiver.presence.session?.takeIf { receiver.presence.live }?.let { id ->
                                { navigateActiveReceiver(this@MainActivity, id); Unit }
                            }, stopActiveShare = {
                                receiver.presence.session?.let(ReceiverSessions::stop)
                            }) { path ->
                            if (!requestShare.busy) {
                                val entry = enterManualConnection(ConnectionRequests.controller, MediaRole.SEND,
                                    RequestMediaLifecycle.coordinator::requestExit)
                                if (entry == ManualConnectionEntry.BLOCKED)
                                    remoteFeedback = "Another connection request is active. Close it before using a code."
                                else selectedPath = path
                            }
                        }
                        if (requestShare.message.isNotEmpty()) Text(requestShare.message)
                        if (remoteFeedback != "Remote link ready") Text(remoteFeedback)
                        }
                        if (activePath != null) WorkflowPanel(activePath, canChoose, closeTask) {
                            LaunchedEffect(activePath, legacyAllowed, lifecycleState, mediaMode.role) {
                                if (selectedPath != null && canChoose && legacyAllowed && lifecycleState == Lifecycle.State.RESUMED &&
                                    mediaMode.role == MediaRole.SEND && ConnectionRequests.controller.mediaSnapshot() == null) {
                                    ConnectionRequestRuntimeGate.runLegacyStart {
                                        if (activePath == ConnectionWorkflow.SHOW)
                                            ShowConnection.ensureSender(PairingHost.state.value) { PairingHost.controller.create() }
                                        else if (PairingHost.state.value.phase == PairingPhase.STOPPED) PairingHost.controller.show()
                                    }
                                }
                            }
                            if (!legacyAllowed) Text("Closing the shared connection link before using a code...")
                            if (activePath == ConnectionWorkflow.SHOW && ShowConnection.senderReady(home) && !rtc.busy) {
                                WorkflowOwnCode(home.code)
                            }
                            if (activePath == ConnectionWorkflow.SHOW && canChoose && !ShowConnection.senderReady(home)) {
                                Text("Connection code")
                                Text("Preparing connection code...")
                            }
                            if (activePath == ConnectionWorkflow.CONNECT && !rtc.busy) {
                            val editable = legacyAllowed && PairingHost.controller.editable()
                            Button(modifier = Modifier.fillMaxWidth(), enabled = editable && !state.busy && !scanning, onClick = {
                                PairingHost.controller.typed("")
                                scanning = true
                                externalUi = true
                                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                                try { GmsBarcodeScanning.getClient(this@MainActivity, options).startScan()
                                    // Activity-scoped Task listeners are removed onStop when the scanner UI opens.
                                    .addOnSuccessListener { barcode ->
                                        if (isDestroyed || isFinishing) return@addOnSuccessListener
                                        scanning = false
                                        externalUi = false
                                        val code = PairingCode.parseQr(barcode.rawValue.orEmpty())
                                        if (code != null) ConnectionRequestRuntimeGate.runLegacyStart { PairingHost.controller.join(code) }
                                        else PairingHost.controller.notice("QR code is not a valid connection code.")
                                    }
                                    .addOnCanceledListener { if (!isDestroyed && !isFinishing) { scanning = false; externalUi = false; PairingHost.controller.notice("Scan cancelled") } }
                                    .addOnFailureListener { error ->
                                        if (!isDestroyed && !isFinishing) {
                                            scanning = false
                                            val message = when ((error as? MlKitException)?.errorCode) {
                                                MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED -> "Camera permission denied. Enter the connection code instead."
                                                MlKitException.CODE_SCANNER_CANCELLED -> "Scan cancelled"
                                                else -> "Camera is not available. Enter the connection code instead."
                                            }
                                            externalUi = false; PairingHost.controller.notice(message)
                                        }
                                    }
                                } catch (_: Exception) { scanning = false; externalUi = false; PairingHost.controller.notice("Camera is not available. Enter the connection code instead.") }
                            }) { Text(if (scanning) "Scanning..." else "Scan QR code") }
                            Text("OR")
                            OutlinedTextField(value = joinCode, onValueChange = { value ->
                                val normalized = AutoPairing.input(value)
                                if (normalized != null) {
                                    joinCode = normalized
                                    ConnectionRequestRuntimeGate.runLegacyStart { PairingHost.controller.typed(normalized) }
                                } else {
                                    PairingHost.controller.typed("")
                                    PairingHost.controller.notice("Invalid connection code")
                                }
                            }, enabled = editable && !scanning, label = { Text("Connection code") }, singleLine = true)
                            Button(modifier = Modifier.fillMaxWidth(), enabled = editable && !scanning && !state.busy, onClick = {
                                val code = PairingCode.normalize(joinCode)
                                if (code != null) ConnectionRequestRuntimeGate.runLegacyStart { PairingHost.controller.join(code) }
                                else PairingHost.controller.notice("Invalid connection code")
                            }) { Text("Connect") }
                            }
                            if (rtc.busy) Text(if (home.phase == PairingPhase.STREAMING) "Sharing your screen" else when (home.phase) {
                                PairingPhase.CREATING, PairingPhase.WAITING -> "Waiting for another device..."
                                PairingPhase.CONSENT -> "Allow screen sharing"
                                PairingPhase.STOPPED, PairingPhase.ERROR -> "Getting ready..."
                                else -> "Connecting..."
                            }, style = MaterialTheme.typography.bodyLarge)
                            if (activePath == ConnectionWorkflow.CONNECT && home.notice.isNotEmpty()) Text(ConnectionWorkflow.displayStatus(home.notice))
                            if (rtc.busy) Button(enabled = rtc.status != "Allow screen sharing", onClick = {
                                startService(Intent(this@MainActivity, WebRtcService::class.java).setAction(WebRtcService.STOP))
                            }) { Text("Stop") }
                        }

                        if (activePath == null) {
                        TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Hide advanced diagnostics" else "Advanced diagnostics") }
                        if (diagnostics) {
                        HorizontalDivider()
                        Text("Remote connection", style = MaterialTheme.typography.titleMedium)
                        Text("Use this link when the receiving device is far away.")
                        Text(remoteFeedback)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                                try {
                                    val clip = ClipData.newPlainText("Screen sharing link", PairingHost.remoteLink())
                                    clip.description.extras = android.os.PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                                    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                                    remoteFeedback = "Link copied"
                                } catch (_: Exception) { remoteFeedback = "Link could not be copied. Please try again." }
                            }) { Text("Copy connection link") }
                        }
                        TextButton(onClick = { regenerateRemote = true }) { Text("Regenerate link") }
                        Text("Anyone with this link can request a connection while this phone is waiting. Screen sharing still requires approval on the phone.")
                        if(regenerateRemote) AlertDialog(
                            onDismissRequest = { regenerateRemote = false },
                            title = { Text("Regenerate remote link?") },
                            text = { Text("Existing shared links will stop working.") },
                            confirmButton = { TextButton(onClick = {
                                regenerateRemote = false
                                try { PairingHost.regenerateRemoteLink(); remoteFeedback = "New remote link saved" }
                                catch (_: Exception) { remoteFeedback = "Link could not be replaced. Please try again." }
                            }) { Text("Regenerate") } },
                            dismissButton = { TextButton(onClick = { regenerateRemote = false }) { Text("Cancel") } })

                        Text("Media role: "+mediaMode.role.name)
                        Text("WebRTC diagnostics", style = MaterialTheme.typography.titleMedium)
                        Text(rtc.metrics)
                        Text("Direct P2P / STUN only. 720 x 1560, target 30 FPS, max ~2 Mbps, video only.")
                        HorizontalDivider()
                        Text("POC 3B CAPTURE TEST", style = MaterialTheme.typography.headlineSmall)
                        Text("Status: ${state.status}")
                        Text("Frames received: ${state.frames}")
                        Text("Last frame: ${state.size}")
                        Text("Shared content visible: ${state.visibility}")
                        Button(enabled = mediaMode.role == MediaRole.SEND && !state.busy && !rtc.busy && !scanning, onClick = {
                            CaptureState.set(CaptureUi(status = "Waiting for permission", busy = true))
                            try {
                                consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                            } catch (e: Exception) {
                                CaptureState.set(CaptureUi(status = "Error: ${e.message}"))
                            }
                        }) { Text("Start capture") }
                        Button(enabled = state.capturing && state.frames > 0 && !state.saving, onClick = {
                            startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.SAVE))
                        }) { Text("Save sample frame") }
                        Button(enabled = state.capturing && state.frames > 0 && !state.saving, onClick = {
                            startService(Intent(this@MainActivity, CaptureService::class.java)
                                .setAction(CaptureService.SAVE_DELAYED))
                        }) { Text("Save sample in 5 seconds") }
                        Button(enabled = state.capturing, onClick = {
                            stopService(Intent(this@MainActivity, CaptureService::class.java))
                        }) { Text("Stop capture") }
                        Text(if (state.capturing) "Session measurements" else "Last session result / Session summary",
                            style = MaterialTheme.typography.titleMedium)
                        val m = state.metrics
                        val tenths = (m.durationMs / 100).toLong()
                        Text("Duration: %02d:%02d.%d".format(Locale.US,
                            tenths / 600, (tenths / 10) % 60, tenths % 10))
                        Text("Recent FPS (~1 s): %.2f\nAverage FPS: %.2f".format(Locale.US,
                            m.recentFps, m.averageFps))
                        Text("Last frame gap: %.2f ms\nAverage frame gap: %.2f ms\nMaximum frame gap: %.2f ms\nLast frame age / waiting: %.1f ms"
                            .format(Locale.US, m.lastGapMs, m.averageGapMs, m.maxGapMs, m.lastFrameAgeMs))
                        Text("Gaps (cumulative):\n>50 ms: ${m.gaps[0]}\n>100 ms: ${m.gaps[1]}\n>250 ms: ${m.gaps[2]}\n>500 ms: ${m.gaps[3]}\n>1000 ms: ${m.gaps[4]}")
                        Text("Changed frames: ${m.changed}\nUnchanged frames: ${m.unchanged}\nReference frames: ${m.baselines}\nChanged: %.2f%%"
                            .format(Locale.US, m.changedPercent))
                        Text("Average processing: %.3f ms\nMaximum processing: %.3f ms"
                            .format(Locale.US, m.averageProcessingMs, m.maxProcessingMs))
                        Text("Visibility changes: ${m.visibilityChanges}\nVisible: %.1f s\nHidden: %.1f s\nUnknown: %.1f s\nSample save attempts: ${m.sampleSaves}"
                            .format(Locale.US, m.visibleMs / 1000, m.hiddenMs / 1000, m.unknownMs / 1000))
                        Text("16×16 RGB sample; changes outside the grid may be missed. FPS counts acquired images. Hidden time and explicit PNG saves remain part of the session.")
                        Text("Saved sample:\n${state.path.ifEmpty { "-" }}")
                        if (state.diagnostic.isNotEmpty()) Text(state.diagnostic)
                        if (state.saveError.isNotEmpty()) Text(state.saveError)
                        }
                        }

                    }
                }
            }
        }
    }
}


