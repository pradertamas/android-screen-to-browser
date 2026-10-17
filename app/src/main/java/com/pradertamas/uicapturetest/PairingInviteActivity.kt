package com.pradertamas.uicapturetest

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.pradertamas.uicapturetest.ui.theme.UICaptureTestTheme

/** Floating control-plane Activity: does not stop an underlying receiver or infer/start a media role. */
class PairingInviteActivity : ComponentActivity() {
    private lateinit var model: AppPairingViewModel
    private fun incoming(value: Intent) {
        model.incoming(if (value.action == Intent.ACTION_VIEW) value.dataString.orEmpty() else "")
        value.data = null // Do not retain bearer URI in Activity saved-intent state.
        model.processIncoming(AppMediaRoles.pairingAllowed())
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[AppPairingViewModel::class.java]
        if (savedInstanceState == null) incoming(intent)
        setContent {
            val mode by AppMediaRoles.state.collectAsState()
            val receiverRevision by AppMediaRoles.receiverRevision.collectAsState()
            val sender by PairingHost.state.collectAsState()
            val rtc by WebRtcState.state.collectAsState()
            val capture by CaptureState.state.collectAsState()
            val mediaIdle = remember(mode.role, sender.phase, rtc.busy, capture.busy, receiverRevision) { AppMediaRoles.pairingAllowed() }
            // Actions also recheck the live guard.
            UICaptureTestTheme {
                Surface {
                    Column(Modifier.widthIn(max = 520.dp).padding(20.dp)) {
                        Text("Device connection", style = MaterialTheme.typography.headlineSmall)
                        AppPairingSection(model, mediaIdle)
                        TextButton(onClick = { model.cancel(); finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); incoming(intent) }
}
