package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ShowConnectionTest {
    @Test fun codesRequireOwnAcceptedCreateAndValidFormat() {
        for (phase in PairingPhase.entries) for (intent in PairingIntent.entries) {
            val home = PairingHome(phase, "7K4M2Q", intent)
            assertEquals(intent == PairingIntent.CREATE && phase == PairingPhase.WAITING, ShowConnection.senderReady(home))
        }
        for (phase in ReceiverPhase.entries) for (intent in PairingIntent.entries) for (registered in listOf(false, true)) {
            val home = ReceiverHome(phase, "7K4M2Q", intent, registered = registered)
            assertEquals(intent == PairingIntent.CREATE && phase == ReceiverPhase.PAIRING && registered, ShowConnection.receiverReady(home))
        }
        assertFalse(ShowConnection.senderReady(PairingHome(PairingPhase.WAITING, "bad")))
        assertFalse(ShowConnection.receiverReady(ReceiverHome(ReceiverPhase.PAIRING, "", registered = true)))
    }
    @Test fun uiUsesExistingCreateOnlyOnShowSelectionAndGatesQr() {
        val dir = sequenceOf(File("src/main/java/com/pradertamas/uicapturetest"), File("app/src/main/java/com/pradertamas/uicapturetest")).first { it.isDirectory }
        val main = File(dir, "MainActivity.kt").readText()
        val receiver = File(dir, "ReceiverActivity.kt").readText()
        val vm = File(dir, "ReceiverViewModel.kt").readText()
        assertTrue(main.contains("ShowConnection.ensureSender(PairingHost.state.value) { PairingHost.controller.create() }"))
        assertTrue(main.contains("ShowConnection.senderReady(home)"))
        assertTrue(receiver.contains("ShowConnection.receiverReady(ui.home)"))
        assertTrue(receiver.contains("if (activePath == ConnectionWorkflow.SHOW) model.ensureOwnCreate()"))
        assertTrue(vm.contains("ShowConnection.ensureReceiver(state.value.home) { engine.create() }"))
        val action = main.substringAfter("shareLabel = requestShare.label) { path ->").substringBefore("selectedPath = path")
        assertFalse(action.contains("createScreenCaptureIntent")); assertFalse(action.contains("startForegroundService"))
        assertFalse(receiver.contains("createScreenCaptureIntent"))
    }
}
