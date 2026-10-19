package com.pradertamas.uicapturetest

import android.content.Intent
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

internal class RequestShareAction(val busy: Boolean, val message: String, val label: String, val start: () -> Unit)

@Composable
internal fun rememberRequestShare(role: MediaRole, stopLegacy: () -> Unit): RequestShareAction {
    val coordinator = ConnectionRequestSharing.coordinator
    val owner = remember { ConnectionRequestShareOwner() }
    val ui by coordinator.state.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        coordinator.chooserReturned(owner)
    }
    DisposableEffect(owner, role) { onDispose { coordinator.detach(owner) } }
    LaunchedEffect(ui.delivery, lifecycle, role) {
        val delivery = ui.delivery
        if (delivery != null && lifecycle == Lifecycle.State.RESUMED) {
            val link = coordinator.takeLink(owner, delivery, role) ?: return@LaunchedEffect
            try {
                launcher.launch(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link)
                }, "Share connection link"))
            } catch (_: Exception) { coordinator.chooserFailed(owner) }
        }
    }
    val message = when (ui.error) {
        RequestShareError.UNAVAILABLE -> "This connection cannot be shared right now."
        RequestShareError.CREATE_FAILED -> "Couldn't prepare the connection link. Try again."
        RequestShareError.CHOOSER_FAILED -> "Link could not be shared. Please try again."
        RequestShareError.LEGACY_STOP_FAILED -> "Previous connection could not be stopped. Please try again."
        null -> ""
    }
    return RequestShareAction(ui.busy, message, ui.buttonLabel) {
        check(Looper.myLooper() == Looper.getMainLooper())
        coordinator.start(owner, role, stopLegacy)
    }
}
