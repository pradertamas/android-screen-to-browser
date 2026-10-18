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
import androidx.lifecycle.ViewModelProvider
import com.pradertamas.uicapturetest.ui.theme.UICaptureTestTheme

/** Separate from NAS /pair. Intent bearer is consumed once and never saved. */
class ConnectionRequestActivity : ComponentActivity() {
    private val coordinator get() = ConnectionRequestRecipients.coordinator
    private lateinit var model: ConnectionRequestActivityViewModel
    private var entryError by mutableStateOf<RecipientEntryError?>(null)
    private fun incoming(value: Intent) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val raw = if (value.action == Intent.ACTION_VIEW) value.dataString else null
        value.data = null
        intent?.data = null
        setIntent(value) // Retain only the scrubbed intent, including onNewIntent.
        val admission = coordinator.open(raw, model.flow)
        entryError = admission.error
        model.opened(admission)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false) // A floating window must not abandon an in-flight Accept.
        model = ViewModelProvider(this)[ConnectionRequestActivityViewModel::class.java]
        if (model.flow == null) incoming(intent) else { intent.data = null }
        setContent {
            val flow = model.flow
            val scoped = remember(flow) { coordinator.observe(flow) }
            val ui by scoped.collectAsState(initial = coordinator.projection(flow))
            val hasFlow = flow != null
            val media by AppMediaRoles.state.collectAsState()
            val receiverRevision by AppMediaRoles.receiverRevision.collectAsState()
            val sender by PairingHost.state.collectAsState()
            val capture by CaptureState.state.collectAsState()
            val rtc by WebRtcState.state.collectAsState()
            val idle = remember(media.role, receiverRevision, sender.phase, capture.busy, rtc.busy) { AppMediaRoles.pairingAllowed() }
            LaunchedEffect(hasFlow, ui.screen) { if (hasFlow && ui.screen == RecipientScreen.FINISHED) finish() }
            BackHandler { closeOwned() }
            UICaptureTestTheme {
                Surface {
                    Column(Modifier.widthIn(max = 520.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Connection request", style = MaterialTheme.typography.headlineSmall)
                        if (!hasFlow) {
                            Text(entryText(entryError ?: RecipientEntryError.INVALID))
                            TextButton(onClick = { finish() }) { Text("Close") }
                        } else {
                            ui.initiatorRole?.let { role ->
                                Text(if (role == MediaRole.SEND) "Another device wants to share its screen with this device."
                                    else "Another device wants this device to share its screen.")
                            }
                            Text(screenText(ui.screen))
                            when (ui.screen) {
                                RecipientScreen.WAITING -> Button(onClick = { flow?.let(coordinator::accept) }, enabled = idle) { Text("Accept") }
                                RecipientScreen.CHECK_ERROR -> Button(onClick = { flow?.let(coordinator::retryStatus) }) { Text("Try again") }
                                RecipientScreen.ACCEPT_ERROR -> Button(onClick = { flow?.let(coordinator::accept) }, enabled = idle) { Text("Try again") }
                                RecipientScreen.WITHDRAW_ERROR -> Button(onClick = { closeOwned() }) { Text("Try again") }
                                else -> Unit
                            }
                            val waiting = ui.screen in setOf(RecipientScreen.ACCEPTING, RecipientScreen.WITHDRAWING, RecipientScreen.ACCEPT_ERROR)
                            TextButton(onClick = { closeOwned() }, enabled = !waiting) {
                                Text(if (ui.screen == RecipientScreen.WAITING || ui.screen == RecipientScreen.CHECK_ERROR) "Not now"
                                    else if (ui.screen == RecipientScreen.ACCEPTED) "Cancel" else "Close")
                            }
                        }
                    }
                    if (hasFlow && entryError != null) AlertDialog(onDismissRequest = { entryError = null },
                        text = { Text(entryText(entryError!!)) }, confirmButton = { TextButton(onClick = { entryError = null }) { Text("OK") } })
                }
            }
        }
    }
    private fun closeOwned() {
        val flow = model.flow
        if (flow == null || !coordinator.hasContext(flow)) finish() else coordinator.close(flow)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); incoming(intent) }
    private fun entryText(error: RecipientEntryError) = when (error) {
        RecipientEntryError.INVALID -> "Invalid connection link"
        RecipientEntryError.BUSY -> "This device is busy"
        RecipientEntryError.CONFLICT -> "Another connection request is already active."
        RecipientEntryError.STALE -> "This connection request is no longer active."
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
        RecipientScreen.STALE -> "This connection request is no longer active."
    }
}
