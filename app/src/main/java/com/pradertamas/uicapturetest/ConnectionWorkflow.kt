package com.pradertamas.uicapturetest

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Presentation selection only; never owns a media role or a signaling session. */
object ConnectionWorkflow {
    const val CONNECT = "connect"
    const val SHOW = "show"
    fun title(path: String) = if (path == CONNECT) "3. Connect to another device" else "3. Let another device connect here"
    fun displayStatus(value: String) = value.replace("Pairing", "Connection").replace("pairing", "connection")
        .replace("Waiting for a sharing phone...", "Waiting for another device...")
}

@Composable
fun WorkflowStart(allowed: Boolean, path: String?, share: () -> Unit, shareEnabled: Boolean = allowed,
    shareLabel: String = "Share connection link", select: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("2. Choose how to start", style = MaterialTheme.typography.titleMedium)
            Button(onClick = share, enabled = shareEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(shareLabel)
            }
            WorkflowAction("Connect to another device", "Use a QR code or enter a code from another device.", path == ConnectionWorkflow.CONNECT, allowed) { select(ConnectionWorkflow.CONNECT) }
            WorkflowAction("Let another device connect here", "Show a QR code or connection code on this device.", path == ConnectionWorkflow.SHOW, allowed) { select(ConnectionWorkflow.SHOW) }
        }
    }
}

@Composable
private fun WorkflowAction(title: String, helper: String, selected: Boolean, enabled: Boolean, action: () -> Unit) {
    OutlinedCard(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, minLines = 2)
            Text(helper, style = MaterialTheme.typography.bodyMedium, minLines = 2)
        }
    }
}

@Composable
fun WorkflowPanel(path: String, backEnabled: Boolean, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ConnectionWorkflow.title(path), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = backEnabled, onClick = back) { Text("Back") }
            }
            content()
        }
    }
}

@Composable
fun WorkflowOwnCode(code: String) {
    if (PairingCode.normalize(code) != code) return
    val qr = remember(code) { runCatching { pairingQr(code).asImageBitmap() }.getOrNull() }
    if (qr != null) Image(qr, "Connection QR code", Modifier.fillMaxWidth().height(220.dp))
    Text("Connection code", style = MaterialTheme.typography.labelLarge)
    SelectionContainer { Text(code, style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace) }
}
