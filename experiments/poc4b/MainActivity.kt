package com.pradertamas.uicapturetest

import android.app.Activity
import android.content.Intent
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pradertamas.uicapturetest.ui.theme.UICaptureTestTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by CaptureState.state.collectAsState()
            val rtc by WebRtcState.state.collectAsState()
            var session by remember { mutableStateOf("poc4b_" + java.util.UUID.randomUUID().toString().take(12)) }
            var mode by remember { mutableStateOf(ConnectionMode.AUTO) }
            // No saved state, preferences or source-stored access tokens.
            var endpointToken by remember { mutableStateOf("") }
            val rtcConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val data = result.data
                if (result.resultCode == Activity.RESULT_OK && data != null) {
                    try {
                        startForegroundService(Intent(this, WebRtcService::class.java).apply {
                            action = WebRtcService.START
                            putExtra(WebRtcService.CONSENT, data)
                            putExtra(WebRtcService.SESSION, session.trim())
                            putExtra(WebRtcService.MODE, mode.name)
                            putExtra(WebRtcService.ACCESS_TOKEN, endpointToken)
                        })
                        endpointToken = ""
                    } catch (e: Exception) { WebRtcState.set(WebRtcUi(status = "Start error: ${e.message}")) }
                } else WebRtcState.set(WebRtcUi(status = "Permission cancelled"))
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
                        Text("POC 3B CAPTURE TEST", style = MaterialTheme.typography.headlineSmall)
                        Text("Status: ${state.status}")
                        Text("Frames received: ${state.frames}")
                        Text("Last frame: ${state.size}")
                        Text("Shared content visible: ${state.visibility}")
                        Button(enabled = !state.busy && !rtc.busy, onClick = {
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
                        HorizontalDivider()
                        Text("POC 4B WEBRTC NETWORK TEST", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(value = session, onValueChange = { session = it }, label = { Text("Session ID (6–64 ASCII letters/digits/_/-)") }, enabled = !rtc.busy)
                        Text("Connection mode (browser mirrors Android):")
                        ConnectionMode.entries.forEach { option ->
                            Row { RadioButton(selected = mode == option, enabled = !rtc.busy, onClick = { mode = option }); Text(option.label) }
                        }
                        OutlinedTextField(value = endpointToken, onValueChange = { endpointToken = it },
                            label = { Text("Credential endpoint access token (NOT Cloudflare token)") },
                            visualTransformation = PasswordVisualTransformation(), enabled = !rtc.busy, singleLine = true)
                        Text("Access token stays in memory and clears after Start. Configured USD/GB comes from the credential server.")
                        Button(enabled = !state.busy && !rtc.busy && endpointToken.matches(Regex("[A-Za-z0-9_-]{32,256}")) && session.trim().matches(Regex("[A-Za-z0-9_-]{6,64}")), onClick = {
                            WebRtcState.set(WebRtcUi(true, "Waiting for fresh screen capture consent"))
                            try { rtcConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
                            catch (e: Exception) { WebRtcState.set(WebRtcUi(status = "Consent error: ${e.message}")) }
                        }) { Text("Start WebRTC share") }
                        Button(enabled = rtc.busy && !rtc.status.startsWith("Waiting for fresh"), onClick = {
                            startService(Intent(this@MainActivity, WebRtcService::class.java).setAction(WebRtcService.STOP))
                        }) { Text("Stop WebRTC share") }
                        Text(rtc.status)
                        Text(rtc.metrics)
                        Text("Video only. AUTO = STUN + TURN / ALL; FORCE TURN = TURN / RELAY. Keep portrait. Hardware encoding is requested via EGL; verify runtime stats. Start receiver first. A new share requires fresh consent and temporary credentials.")
                    }
                }
            }
        }
    }
}


