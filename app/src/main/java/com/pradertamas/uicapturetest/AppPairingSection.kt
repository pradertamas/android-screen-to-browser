package com.pradertamas.uicapturetest

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Control-plane UI only. Does not select media roles, start capture or open signaling. */
@Composable
fun AppPairingSection(model: AppPairingViewModel, allowed: Boolean) {
    val ui by model.state.collectAsState()
    val link by model.share.collectAsState()
    val context = LocalContext.current
    val lifecycle = (context as? ComponentActivity)?.lifecycle?.currentStateFlow?.collectAsState()?.value
    LaunchedEffect(link, lifecycle, allowed) {
        val value = link
        if (value != null && lifecycle == Lifecycle.State.RESUMED && allowed) {
            model.shared() // Consume before launching chooser, including recreation.
            val token = AppPairingLink.parse(value, model.config)
            if (token != null) runCatching {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, AppPairingLink.shareText(value, model.config))
                }, "Share pairing link"))
            }
        }
    }
    Column {
        Text("Pair another device", style = MaterialTheme.typography.titleMedium)
        if (!model.config.configured) Text("Pairing backend is not configured.")
        Text("Create a temporary link to pair this app with another device.")
        val busy = ui.phase in setOf(AppPairingPhase.CREATING_INVITE, AppPairingPhase.INSPECTING_INVITE, AppPairingPhase.ACCEPTING)
        Button(enabled = model.config.configured && allowed && !busy && ui.phase != AppPairingPhase.AWAITING_CONFIRMATION,
            onClick = { model.create(allowed) }) { Text("Share pairing link") }
        Text("Pairing links expire after 30 minutes and can be used once.")
        if (busy) Text("Please wait...")
        if (ui.message.isNotEmpty()) Text(ui.message)
        if (ui.phase == AppPairingPhase.ERROR) Button(enabled = allowed && !busy, onClick = { model.retry(allowed) }) { Text("Retry") }
        if (ui.phase == AppPairingPhase.AWAITING_CONFIRMATION) AlertDialog(
            onDismissRequest = { model.cancel() },
            title = { Text("Pair devices?") },
            text = { Text("This will create a persistent pairing between the two app installations.") },
            confirmButton = { TextButton(enabled = model.config.configured && allowed, onClick = { model.accept(allowed) }) { Text("Pair") } },
            dismissButton = { TextButton(onClick = { model.cancel() }) { Text("Cancel") } })
    }
}
