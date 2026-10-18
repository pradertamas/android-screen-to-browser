package com.pradertamas.uicapturetest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Activity-scoped, configuration-retained local identity only. No SavedState or credentials. */
class ConnectionRequestActivityViewModel : ViewModel() {
    internal var flow: ConnectionRequestRecipientFlow? by mutableStateOf(null)
        private set
    internal fun opened(admission: RecipientAdmission) {
        if (flow == null && admission.error == null) flow = admission.flow
    }
}
