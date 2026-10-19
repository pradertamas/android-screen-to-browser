package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test

class MediaRoleTest {
    @Test fun defaultNoOpsAndRoleAreNotPersistentPreferences() {
        val events = mutableListOf<String>()
        val c = MediaRoleController({ true }, { events.add("stop:$it") }, { events.add("start:$it") })
        assertEquals(MediaRole.SEND, c.state.role)
        assertTrue(c.switchRole(MediaRole.SEND)); assertTrue(events.isEmpty())
        assertTrue(c.switchRole(MediaRole.RECEIVE)); val generation = c.state.generation
        assertTrue(c.switchRole(MediaRole.RECEIVE)); assertEquals(generation, c.state.generation)
        assertEquals(listOf("stop:SEND", "start:RECEIVE"), events)
        assertTrue(c.switchRole(MediaRole.SEND)); assertEquals(MediaRole.SEND, c.state.role)
        assertEquals(MediaRole.SEND, MediaRoleController({ true }, {}, {}).state.role)
    }
    @Test fun allFourRoleIntentCombinationsAreOrthogonal() {
        for (role in MediaRole.entries) for (intent in PairingIntent.entries) {
            val query = java.net.URI(role.registrationUrl("7K4M2Q", intent)).query
            assertTrue(query.contains("role=${role.signalingRole}")); assertTrue(query.contains("intent=${intent.wire}"))
            assertEquals(role == MediaRole.SEND, role.offerer)
            assertEquals(role == MediaRole.SEND, role.captureAllowed)
        }
        assertEquals(MediaRole.RECEIVE.registrationUrl("7K4M2Q", PairingIntent.CREATE), ReceiverRegistration.url("7K4M2Q", PairingIntent.CREATE))
    }
    @Test fun activeSessionGuardsAndExplicitBackStop() {
        for (phase in PairingPhase.entries) {
            val expected = phase in setOf(PairingPhase.STOPPED, PairingPhase.CREATING, PairingPhase.WAITING, PairingPhase.ERROR)
            assertEquals(expected, MediaRolePolicy.senderSwitchAllowed(phase, false, false))
            assertFalse(MediaRolePolicy.senderSwitchAllowed(phase, true, false))
            assertFalse(MediaRolePolicy.senderSwitchAllowed(phase, false, true))
        }
        for (phase in ReceiverPhase.entries)
            assertEquals(phase !in setOf(ReceiverPhase.CONNECTING, ReceiverPhase.RECEIVING), MediaRolePolicy.receiverSwitchAllowed(phase))
        var allowed = true; var stopped = 0
        val c = MediaRoleController({ allowed }, { stopped++ }, {})
        c.switchRole(MediaRole.RECEIVE); allowed = false
        assertFalse(c.switchRole(MediaRole.SEND)); assertEquals(1, stopped)
        assertEquals(MediaRole.RECEIVE, c.state.role)
        assertTrue(c.switchRole(MediaRole.SEND, stopActive = true)); assertEquals(2, stopped)
    }
    @Test fun generationInvalidatesBeforeTeardownAndFailureLeavesOneRequestedRole() {
        lateinit var c: MediaRoleController
        val oldGeneration = 0L; var staleCouldRun = true; var started = 0
        c = MediaRoleController({ true }, {
            staleCouldRun = c.current(MediaRole.SEND, oldGeneration)
            assertFalse(c.switchRole(MediaRole.SEND)) // No reentrant switch from cleanup.
            throw IllegalStateException("private details must not be exposed")
        }, { started++ })
        assertTrue(c.switchRole(MediaRole.RECEIVE)); assertFalse(staleCouldRun)
        assertEquals(MediaRole.RECEIVE, c.state.role); assertEquals(1, started)
        assertFalse(c.state.error.contains("private")); assertTrue(c.state.error.isNotEmpty())
        assertFalse(c.current(MediaRole.SEND, oldGeneration)); assertTrue(c.current(MediaRole.RECEIVE, c.state.generation))
        val failed = MediaRoleController({ true }, {}, { throw IllegalStateException("secret") })
        failed.switchRole(MediaRole.RECEIVE); assertEquals(MediaRole.RECEIVE, failed.state.role)
        assertTrue(failed.state.error.isNotEmpty()); assertFalse(failed.state.error.contains("secret"))
        assertTrue(failed.switchRole(MediaRole.SEND))
    }
    @Test fun realWaitingControllersSwitchCleanlyPreservingTokenAndIgnoringRetiredTimers() {
        data class Timer(val task: () -> Unit, var cancelled: Boolean = false)
        val timers = mutableListOf<Timer>(); var sequence = 0
        val later: (Long, () -> Unit) -> (() -> Unit) = { _, action ->
            val timer = Timer(action); timers.add(timer)
            val cancel: () -> Unit = { timer.cancelled = true }; cancel
        }
        fun code() = PairingCode.ALPHABET[sequence++ % PairingCode.ALPHABET.length] + "K4M2Q"
        class Link(val message: (String, String, String) -> Unit, val ended: () -> Unit) : PairingLink {
            override val remotePresenceSupported = true
            var closes = 0; val sent = mutableListOf<String>()
            override fun send(text: String): Boolean { sent.add(text); return true }
            override fun close() { closes++ }
        }
        val sendLinks = mutableListOf<Link>(); var saved: String? = null; var generated = 0
        val store = RemoteTokenStore({ saved }, { saved = it; true }, { generated++; "AAAAAAAAAAAAAAAAAAAAAA" })
        val sender = AutoPairing(object : PairingTransport {
            override fun open(code: String, intent: PairingIntent, message: (String, String, String) -> Unit, ended: () -> Unit): PairingLink =
                Link(message, ended).also(sendLinks::add)
        }, later, {}, ::code, store::get)
        val receiveEvents = mutableListOf<ReceiverEvents>(); val receiveLinks = mutableListOf<Link>()
        val receiver = ReceiverEngine(object : ReceiverWire {
            override fun open(code: String, intent: PairingIntent, events: ReceiverEvents): PairingLink {
                receiveEvents.add(events); return Link({ _, _, _ -> }, events::ended).also(receiveLinks::add)
            }
            override fun answer(sdp: String) = "answer"
            override fun candidate(value: ReceiverIce) = "ice"
        }, { _, _ -> error("Waiting must not allocate a peer/renderer") }, later, {}, ::code)
        val roles = MediaRoleController({ role -> if (role == MediaRole.SEND)
            MediaRolePolicy.senderSwitchAllowed(sender.home.phase, false, false)
            else MediaRolePolicy.receiverSwitchAllowed(receiver.home.phase)
        }, { if (it == MediaRole.SEND) { sender.hide(); sender.close() } else receiver.stop() },
            { if (it == MediaRole.SEND) sender.show() else receiver.create() })
        sender.show(); val first = sendLinks.single(); first.message("registration", "ok", "")
        val token = store.get(); assertTrue(first.sent.single().contains(token))
        val retiredTimers = timers.toList()
        assertTrue(roles.switchRole(MediaRole.RECEIVE)); assertEquals(1, first.closes)
        assertEquals(PairingPhase.STOPPED, sender.home.phase); assertTrue(retiredTimers.all { it.cancelled })
        first.ended(); first.message("registration", "ok", ""); first.message("peer-present", "", "")
        retiredTimers.forEach { it.task() } // Even already-enqueued old tasks cannot create a session.
        assertEquals(1, sendLinks.size); assertEquals(1, receiveLinks.size)
        receiveEvents.last().registration("ok"); val receiverTimers = timers.toList()
        assertTrue(roles.switchRole(MediaRole.SEND)); assertEquals(1, receiveLinks.single().closes)
        receiveEvents.last().present(); receiveEvents.last().ended(); receiverTimers.forEach { it.task() }
        assertEquals(ReceiverPhase.ENDED, receiver.home.phase)
        assertEquals(2, sendLinks.size); sendLinks.last().message("registration", "ok", "")
        assertEquals(token, store.get()); assertEquals(1, generated)
        assertTrue(sendLinks.last().sent.single().contains(token))
        sender.close()
    }
    @Test fun sharedUiAndLifecycleHaveOneRoleOwnerAndNoReceivePermissionPath() {
        val source = sequenceOf(java.io.File("src/main/java/com/pradertamas/uicapturetest"), java.io.File("app/src/main/java/com/pradertamas/uicapturetest")).first { it.isDirectory }
        fun text(name: String) = java.io.File(source, name).readText()
        val selector = text("MediaRoleSelector.kt")
        assertTrue(selector.contains("SingleChoiceSegmentedButtonRow")); assertTrue(selector.contains("selected = role == value"))
        assertTrue(selector.contains("enabled = enabled")); assertTrue(selector.contains("contentDescription"))
        for (name in listOf("MainActivity.kt", "ReceiverActivity.kt")) {
            assertTrue(text(name).contains("AppMediaRoles.state.collectAsState()"))
            assertTrue(text(name).contains("MediaRoleSelector(mediaMode.role"))
        }
        val receive = text("ReceiverActivity.kt")
        assertTrue(receive.contains("handleOnBackPressed() { viewerBack() }"))
        val back = receive.substringAfter("private fun viewerBack()").substringBefore("private fun finishReceiver")
        assertTrue(back.contains("presence.live")); assertTrue(back.contains("viewerHidden(viewerIdentity, false, false)"))
        assertTrue(receive.contains("if (requested == MediaRole.SEND) returnToSend()"))
        assertTrue(receive.contains("isChangingConfigurations")); assertFalse(receive.contains("configChanges"))
        assertTrue(text("ReceiverSessionCoordinator.kt").contains("bindReceiver(this"))
        assertFalse(text("ReceiverViewModel.kt").contains("onCleared"))
        assertFalse(receive.contains("createScreenCaptureIntent(")); assertFalse(receive.contains("startForegroundService("))
        assertTrue(text("MainActivity.kt").contains("mediaMode.role == MediaRole.SEND"))
        assertFalse(text("AppMediaRoles.kt").contains("regenerate"))
        assertTrue(text("PairingHost.kt").contains("mediaRole.registrationUrl(code, intent)"))
        assertTrue(text("WebRtcService.kt").contains("check(MediaRole.SEND.offerer)"))
        assertFalse(text("WebRtcReceiverSession.kt").contains("createOffer("))
    }
}
