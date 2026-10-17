package com.pradertamas.uicapturetest

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Shared selected/disabled semantics, built-in selected checkmark; no independent local role flag. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaRoleSelector(role: MediaRole, enabled: Boolean, onRole: (MediaRole) -> Unit) {
    Column {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            MediaRole.entries.forEachIndexed { index, value ->
                SegmentedButton(selected = role == value, onClick = { onRole(value) }, enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, MediaRole.entries.size),
                    modifier = Modifier.heightIn(min = 56.dp).semantics {
                        contentDescription = if (value == MediaRole.SEND) "Send: share this phone's screen" else "Receive: view another device's screen"
                    }) { Text(value.name) }
            }
        }
        Text(if (role == MediaRole.SEND) "Share this phone's screen" else "View another device's screen")
    }
}
