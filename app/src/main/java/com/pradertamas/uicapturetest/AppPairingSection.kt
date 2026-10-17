package com.pradertamas.uicapturetest

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.runtime.*

/** Control-plane UI only. Does not select media roles, start capture or open signaling. */
@Composable
fun AppPairingSection(model: AppPairingViewModel, allowed: Boolean, compact: Boolean = false) {
    val ui by model.state.collectAsState()
    val link by model.share.collectAsState()
    val shareError by model.shareError.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    LaunchedEffect(link, lifecycle, allowed) {
        val value = link
        if (value != null && lifecycle == Lifecycle.State.RESUMED && allowed) {
            val delivery = model.shared(value) ?: return@LaunchedEffect
            try {
                val payload = AppPairingLink.shareText(delivery, model.config)
                shareLauncher.launch(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, payload)
                }, "Share connection link"))
            } catch (_: Exception) { model.shareFailed(delivery) }
        }
    }
    Column {
        if (!compact) {
            Text("Save a device connection", style = MaterialTheme.typography.titleMedium)
            Text("Create a temporary link to save a connection with another app installation.")
        }
        if (!model.config.configured) Text("Connection service is not configured.")
        val busy = ui.phase in setOf(AppPairingPhase.CREATING_INVITE, AppPairingPhase.INSPECTING_INVITE, AppPairingPhase.ACCEPTING)
        Button(modifier = Modifier.fillMaxWidth(), enabled = model.config.configured && allowed && !busy && ui.phase != AppPairingPhase.AWAITING_CONFIRMATION,
            onClick = { model.create(allowed) }) { Text("Share connection link") }
        Text("Connection links expire after 30 minutes and can be used once.")
        if (busy) Text(if (ui.phase == AppPairingPhase.CREATING_INVITE) "Creating connection link..." else "Please wait...")
        if (ui.message.isNotEmpty()) Text(ConnectionWorkflow.displayStatus(ui.message))
        if (shareError.isNotEmpty()) Text(shareError)
        if (ui.phase == AppPairingPhase.ERROR || shareError.isNotEmpty()) Button(enabled = allowed && !busy, onClick = { model.retry(allowed) }) { Text("Retry") }
        if (ui.phase == AppPairingPhase.AWAITING_CONFIRMATION) AlertDialog(
            onDismissRequest = { model.cancel() },
            title = { Text("Save this device connection?") },
            text = { Text("This saves a connection between the two app installations. Screen sharing does not start automatically.") },
            confirmButton = { TextButton(enabled = model.config.configured && allowed, onClick = { model.accept(allowed) }) { Text("Save connection") } },
            dismissButton = { TextButton(onClick = { model.cancel() }) { Text("Cancel") } })
    }
}
