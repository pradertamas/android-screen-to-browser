package com.pradertamas.uicapturetest

import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
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
    private lateinit var model: ReceiverViewModel
    private var renderer: SurfaceViewRenderer? = null
    private fun returnToSend(stopActive: Boolean = false) {
        if (AppMediaRoles.switchRole(MediaRole.SEND, stopActive)) finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val appPairing = ViewModelProvider(this)[AppPairingViewModel::class.java]
        setContent {
            val ui by model.state.collectAsState()
            val mediaMode by AppMediaRoles.state.collectAsState()
            val phase = ui.home.phase
            var code by rememberSaveable { mutableStateOf("") }
            var diagnostics by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            DisposableEffect(phase) {
                if (phase == ReceiverPhase.RECEIVING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            UICaptureTestTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Receive screen", style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { returnToSend(stopActive = true) }) { Text("Stop receiving") }
                        }
                        Column(Modifier.padding(horizontal = 12.dp)) {
                            MediaRoleSelector(mediaMode.role, MediaRolePolicy.receiverSwitchAllowed(phase)) { requested ->
                                if (requested == MediaRole.SEND) returnToSend()
                            }
                            if (mediaMode.error.isNotEmpty()) Text(mediaMode.error)
                            if (phase != ReceiverPhase.RECEIVING) AppPairingSection(appPairing, MediaRolePolicy.receiverSwitchAllowed(phase))
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // Keep a surface available behind the opaque pairing/connecting UI so the first frame can render.
                            AndroidView(factory = { context -> SurfaceViewRenderer(context).also { renderer = it; model.attach(it) } },
                                modifier = Modifier.fillMaxSize())
                            if (phase != ReceiverPhase.RECEIVING) {
                                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                                    .padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(ui.home.status)
                                    if (phase == ReceiverPhase.PAIRING) {
                                        if (ui.home.intent == PairingIntent.CREATE) {
                                            Text("Pair with this screen")
                                            Text(ui.home.code, style = MaterialTheme.typography.displayLarge)
                                            val qr = remember(ui.home.code) { pairingQr(ui.home.code).asImageBitmap() }
                                            Image(qr, "Pairing QR code", Modifier.size(220.dp))
                                            Text("Scan this QR with the sharing phone or enter the code there.")
                                        }
                                        OutlinedTextField(code, { value ->
                                            val normalized = AutoPairing.input(value)
                                            if (normalized != null) { code = normalized; error = "" }
                                            else error = "Invalid pairing code"
                                        }, label = { Text("Join a sharing phone's code") }, singleLine = true)
                                        Button(onClick = { if (!model.join(code)) error = "Invalid pairing code" }) { Text("Join") }
                                        if (error.isNotEmpty()) Text(error)
                                    }
                                    if (phase == ReceiverPhase.ERROR || phase == ReceiverPhase.ENDED) {
                                        Button(onClick = { code = ""; error = ""; model.retry() }) { Text("Retry receiving") }
                                    }
                                }
                            }
                        }
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
    override fun onStop() {
        if (::model.isInitialized && !isChangingConfigurations) model.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onDestroy() {
        if (::model.isInitialized) renderer?.let(model::detach); renderer = null
        super.onDestroy()
    }
}
