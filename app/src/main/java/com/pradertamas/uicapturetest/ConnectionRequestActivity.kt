package com.pradertamas.uicapturetest

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pradertamas.uicapturetest.ui.theme.UICaptureTestTheme

/** Separate from NAS /pair. Intent bearer is consumed once and never saved. */
class ConnectionRequestActivity : ComponentActivity() {
    private val coordinator get() = ConnectionRequestRecipients.coordinator
    private var owns by mutableStateOf(false)
    private var entryError by mutableStateOf<RecipientEntryError?>(null)
    private fun incoming(value: Intent) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val raw = if (value.action == Intent.ACTION_VIEW) value.dataString else null
        value.data = null
        intent?.data = null
        setIntent(value) // Retain only the scrubbed intent, including onNewIntent.
        entryError = coordinator.open(raw)
        if (entryError == null) owns = true
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false) // A floating window must not abandon an in-flight Accept.
        owns = savedInstanceState?.getBoolean("recipientOwner") == true && coordinator.hasContext()
        if (!owns) incoming(intent) else { intent.data = null }
        setContent {
            val ui by coordinator.state.collectAsState()
            val media by AppMediaRoles.state.collectAsState()
            val receiverRevision by AppMediaRoles.receiverRevision.collectAsState()
            val sender by PairingHost.state.collectAsState()
            val capture by CaptureState.state.collectAsState()
            val rtc by WebRtcState.state.collectAsState()
            val idle = remember(media.role, receiverRevision, sender.phase, capture.busy, rtc.busy) { AppMediaRoles.pairingAllowed() }
            LaunchedEffect(owns, ui.screen) { if (owns && ui.screen == RecipientScreen.FINISHED) finish() }
            BackHandler { if (owns) coordinator.close() else finish() }
            UICaptureTestTheme {
                Surface {
                    Column(Modifier.widthIn(max = 520.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Connection request", style = MaterialTheme.typography.headlineSmall)
                        if (!owns) {
                            Text(entryText(entryError ?: RecipientEntryError.INVALID))
                            TextButton(onClick = { finish() }) { Text("Close") }
                        } else {
                            Text(if (ui.initiatorRole == MediaRole.SEND)
                                "Another device wants to share its screen with this device."
                            else "Another device wants this device to share its screen.")
                            Text(screenText(ui.screen))
                            when (ui.screen) {
                                RecipientScreen.WAITING -> Button(onClick = { coordinator.accept() }, enabled = idle) { Text("Accept") }
                                RecipientScreen.CHECK_ERROR -> Button(onClick = { coordinator.retryStatus() }) { Text("Try again") }
                                RecipientScreen.ACCEPT_ERROR -> Button(onClick = { coordinator.accept() }, enabled = idle) { Text("Try again") }
                                RecipientScreen.WITHDRAW_ERROR -> Button(onClick = { coordinator.close() }) { Text("Try again") }
                                else -> Unit
                            }
                            val waiting = ui.screen in setOf(RecipientScreen.ACCEPTING, RecipientScreen.WITHDRAWING, RecipientScreen.ACCEPT_ERROR)
                            TextButton(onClick = { coordinator.close() }, enabled = !waiting) {
                                Text(if (ui.screen == RecipientScreen.WAITING || ui.screen == RecipientScreen.CHECK_ERROR) "Not now"
                                    else if (ui.screen == RecipientScreen.ACCEPTED) "Cancel" else "Close")
                            }
                        }
                    }
                    if (owns && entryError != null) AlertDialog(onDismissRequest = { entryError = null },
                        text = { Text(entryText(entryError!!)) }, confirmButton = { TextButton(onClick = { entryError = null }) { Text("OK") } })
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("recipientOwner", owns) // Only a non-secret UI ownership flag.
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); incoming(intent) }
    private fun entryText(error: RecipientEntryError) = when (error) {
        RecipientEntryError.INVALID -> "Invalid connection link"
        RecipientEntryError.BUSY -> "This device is busy"
        RecipientEntryError.CONFLICT -> "Another connection request is already active."
    }
    private fun screenText(screen: RecipientScreen) = when (screen) {
        RecipientScreen.CHECKING -> "Checking request..."
        RecipientScreen.WAITING -> "Accept to continue."
        RecipientScreen.CHECK_ERROR -> "Could not check the request."
        RecipientScreen.UNAVAILABLE -> "This connection request is no longer available."
        RecipientScreen.HANDLED -> "This connection request is already being handled."
        RecipientScreen.ACCEPTING -> "Accepting request. Please wait."
        RecipientScreen.ACCEPT_ERROR -> "Could not accept the request. Try again to resolve acceptance before leaving."
        RecipientScreen.ACCEPTED -> "Request accepted. Preparing connection..."
        RecipientScreen.WITHDRAWING -> "Canceling accepted request. Please wait."
        RecipientScreen.WITHDRAW_ERROR -> "Could not cancel the accepted request."
        RecipientScreen.FINISHED -> "Request closed."
    }
}
