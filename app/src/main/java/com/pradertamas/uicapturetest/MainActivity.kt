package com.pradertamas.uicapturetest

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by CaptureState.state.collectAsState()
            val rtc by WebRtcState.state.collectAsState()
            var diagnostics by remember { mutableStateOf(false) }
            var scanning by remember { mutableStateOf(false) }
            var entering by remember { mutableStateOf(false) }
            var joinCode by remember { mutableStateOf("") }
            val rtcConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val data = result.data
                if (result.resultCode == Activity.RESULT_OK && data != null) {
                    try {
                        startForegroundService(Intent(this, WebRtcService::class.java).apply {
                            action = WebRtcService.START
                            putExtra(WebRtcService.CONSENT, data)
                            putExtra(WebRtcService.SESSION, WebRtcState.state.value.pairingCode)
                            putExtra(WebRtcService.INTENT, WebRtcState.state.value.intent.name)
                        })
                    } catch (e: Exception) { WebRtcState.set(WebRtcUi(status = "Start error: ${e.message}")) }
                } else {
                    entering = false
                    scanning = false
                    joinCode = ""
                    WebRtcState.cancelConsent()
                }
            }
            val launchMediaProjectionConsent: (String, PairingIntent) -> Unit = { code, intent ->
                WebRtcState.set(WebRtcUi(busy = true, status = "Allow screen sharing", pairingCode = code, intent = intent))
                entering = false
                // Pairing origin affects signaling only. Every sender session requests a fresh
                // system choice between an application and the entire screen; never retain a token.
                try {
                    rtcConsent.launch(getSystemService(MediaProjectionManager::class.java)
                        .createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice()))
                }
                catch (_: Exception) { WebRtcState.set(WebRtcUi(status = "Screen sharing could not start.")) }
            }
            val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val data = result.data
                if (result.resultCode == Activity.RESULT_OK && data != null) {
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
                    Column(Modifier.padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Screen Share", style = MaterialTheme.typography.headlineLarge)
                        if (rtc.pairingCode.isNotEmpty()) {
                            Text("Pairing code", style = MaterialTheme.typography.titleMedium)
                            SelectionContainer {
                                Text(rtc.pairingCode, style = MaterialTheme.typography.displayLarge, fontFamily = FontFamily.Monospace)
                            }
                            val qr = remember(rtc.pairingCode) { runCatching { pairingQr(rtc.pairingCode).asImageBitmap() }.getOrNull() }
                            if (qr != null) Image(qr, contentDescription = "Scan to open Screen Receiver with this pairing code", modifier = Modifier.size(220.dp))
                            else Text("QR unavailable. Enter the pairing code on the receiver.")
                            Text(if (rtc.intent == PairingIntent.CREATE) "Enter this code in Screen Receiver, or scan the QR." else "Joining the receiving screen.")
                        } else Text("Start a share to get a new pairing code.")
                        Text(rtc.status, style = MaterialTheme.typography.titleMedium)
                        if (!rtc.busy) {
                            Button(enabled = !state.busy && !scanning, onClick = { launchMediaProjectionConsent(PairingCode.generate(), PairingIntent.CREATE) }) { Text("Create pairing") }
                            Text("Join a pairing", style = MaterialTheme.typography.titleMedium)
                            Button(enabled = !state.busy && !scanning, onClick = {
                                scanning = true
                                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                                try { GmsBarcodeScanning.getClient(this@MainActivity, options).startScan()
                                    // Activity-scoped Task listeners are removed onStop when the scanner UI opens.
                                    .addOnSuccessListener { barcode ->
                                        if (isDestroyed || isFinishing) return@addOnSuccessListener
                                        scanning = false
                                        val code = PairingCode.parseQr(barcode.rawValue.orEmpty())
                                        if (code != null) launchMediaProjectionConsent(code, PairingIntent.JOIN)
                                        else WebRtcState.set(WebRtcUi(status = "QR code is not a valid pairing code."))
                                    }
                                    .addOnCanceledListener { if (!isDestroyed && !isFinishing) { scanning = false; WebRtcState.set(WebRtcUi(status = "Scan cancelled")) } }
                                    .addOnFailureListener { error ->
                                        if (!isDestroyed && !isFinishing) {
                                            scanning = false
                                            val message = when ((error as? MlKitException)?.errorCode) {
                                                MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED -> "Camera permission denied. Enter the pairing code instead."
                                                MlKitException.CODE_SCANNER_CANCELLED -> "Scan cancelled"
                                                else -> "Camera is not available. Enter the pairing code instead."
                                            }
                                            WebRtcState.set(WebRtcUi(status = message))
                                        }
                                    }
                                } catch (_: Exception) { scanning = false; WebRtcState.set(WebRtcUi(status = "Camera is not available. Enter the pairing code instead.")) }
                            }) { Text(if (scanning) "Scanning..." else "Scan QR code") }
                            TextButton(enabled = !state.busy && !scanning, onClick = { entering = !entering }) { Text("Enter pairing code") }
                            if (entering) {
                                OutlinedTextField(value = joinCode, onValueChange = { joinCode = it }, label = { Text("Pairing code") }, singleLine = true)
                                Button(enabled = !state.busy && !scanning, onClick = {
                                    val code = PairingCode.normalize(joinCode)
                                    if (code != null) launchMediaProjectionConsent(code, PairingIntent.JOIN)
                                    else WebRtcState.set(WebRtcUi(status = "Invalid pairing code"))
                                }) { Text("Continue") }
                            }
                        }
                        if (rtc.busy) Button(enabled = rtc.status != "Allow screen sharing", onClick = {
                            startService(Intent(this@MainActivity, WebRtcService::class.java).setAction(WebRtcService.STOP))
                        }) { Text("Stop") }
                        TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Hide advanced diagnostics" else "Advanced diagnostics") }
                        if (diagnostics) {
                        Text("WebRTC diagnostics", style = MaterialTheme.typography.titleMedium)
                        Text(rtc.metrics)
                        Text("Direct P2P / STUN only. 720 x 1560, target 30 FPS, max ~2 Mbps, video only.")
                        HorizontalDivider()
                        Text("POC 3B CAPTURE TEST", style = MaterialTheme.typography.headlineSmall)
                        Text("Status: ${state.status}")
                        Text("Frames received: ${state.frames}")
                        Text("Last frame: ${state.size}")
                        Text("Shared content visible: ${state.visibility}")
                        Button(enabled = !state.busy && !rtc.busy && !scanning, onClick = {
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


