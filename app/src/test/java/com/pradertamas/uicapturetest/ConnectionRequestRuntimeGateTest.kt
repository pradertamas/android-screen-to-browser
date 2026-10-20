package com.pradertamas.uicapturetest

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConnectionRequestRuntimeGateTest {
    @Test fun defaultAllowsLegacy() { assertTrue(LegacyConnectionStartGate().legacyStartAllowed()) }
    @Test fun armBlocksLegacy() { val gate = LegacyConnectionStartGate(); gate.armRequestFirst(); assertFalse(gate.legacyStartAllowed()) }
    @Test fun repeatedArmIsIdempotent() { val gate = LegacyConnectionStartGate(); repeat(3) { gate.armRequestFirst() }; assertFalse(gate.allowed.value) }
    @Test fun releaseAllowsLegacy() { val gate = LegacyConnectionStartGate(); gate.armRequestFirst(); gate.releaseRequestFirst(); assertTrue(gate.legacyStartAllowed()) }
    @Test fun repeatedReleaseIsIdempotent() { val gate = LegacyConnectionStartGate(); repeat(3) { gate.releaseRequestFirst() }; assertTrue(gate.allowed.value) }
    @Test fun armReleaseDoNotChangeMediaRole() {
        val roles = MediaRoleController({ true }, {}, {})
        roles.switchRole(MediaRole.RECEIVE); val before = roles.state
        val gate = LegacyConnectionStartGate(); gate.armRequestFirst(); gate.releaseRequestFirst()
        assertEquals(before, roles.state)
    }
    @Test fun armDoesNotDispatchNetworkOrCleanup() {
        val gate = LegacyConnectionStartGate(); var starts = 0; var closes = 0
        val start = { starts++ }; val close = { closes++ }
        gate.armRequestFirst(); assertEquals(0, starts); assertEquals(0, closes)
        gate.runLegacyStart(start); assertEquals(0, starts)
        close(); assertEquals(1, closes)
    }
    @Test fun releaseDoesNotRestart() {
        val gate = LegacyConnectionStartGate(); var starts = 0
        gate.armRequestFirst(); gate.runLegacyStart { starts++ }; gate.releaseRequestFirst()
        assertEquals(0, starts); gate.runLegacyStart { starts++ }; assertEquals(1, starts)
    }
    private fun blocked(vararg operations: String) {
        val gate = LegacyConnectionStartGate(); val calls = mutableListOf<String>()
        gate.armRequestFirst()
        assertNull(gate.runLegacyStart { operations.forEach(calls::add); true })
        assertTrue(calls.isEmpty())
    }
    @Test fun blockedSenderOnStart() = blocked("show")
    @Test fun blockedConsentShowClaimAndLaunch() = blocked("show", "claimConsent", "launch")
    @Test fun blockedSenderRoleActivation() = blocked("show")
    @Test fun blockedReceiverInitialization() = blocked("create")
    @Test fun blockedReceiverEnsureOwnCreate() = blocked("ensureOwnCreate", "create")
    @Test fun blockedReceiverRetry() = blocked("create")
    @Test fun blockedReceiverJoin() = blocked("join")
    @Test fun blockedSenderManualCreateJoinAndTyped() = blocked("create", "qrJoin", "join", "typed")
    @Test fun inactiveGatePreservesOperationsAndReturnValues() {
        val gate = LegacyConnectionStartGate(); val calls = mutableListOf<String>()
        for (call in listOf("show", "claimConsent", "create", "join", "typed")) assertEquals(call, gate.runLegacyStart { calls.add(call); call })
        assertEquals(5, calls.size)
    }
    private fun source(name: String): String {
        val path = "src/main/java/com/pradertamas/uicapturetest/$name.kt"
        return listOf(File(path), File("app/$path")).first { it.isFile }.readText()
    }
    /** Match lexical gate regions, including nested lambdas, to verify actual wiring. */
    private fun guardedRegions(text: String): List<IntRange> {
        val needle = "ConnectionRequestRuntimeGate.runLegacyStart {"
        return Regex(Regex.escape(needle)).findAll(text).map {
            var index = it.range.last + 1; var depth = 1
            while (depth > 0 && index < text.length) {
                when (text[index++]) { '{' -> depth++; '}' -> depth-- }
            }
            assertEquals(0, depth); it.range.first until index
        }.toList()
    }
    private fun assertGuarded(text: String, expression: String) {
        val matches = Regex(Regex.escape(expression)).findAll(text).toList()
        assertTrue("Missing production call site", matches.isNotEmpty())
        for (match in matches) assertTrue("Unguarded production start", guardedRegions(text).any { match.range.first in it })
    }
    @Test fun mainProductionAutoConsentAndManualCallsActuallyUseGate() {
        val text = source("MainActivity")
        for (call in listOf("PairingHost.controller.show()", "PairingHost.controller.claimConsent()", "rtcConsent.launch(",
            "PairingHost.controller.create()", "PairingHost.controller.join(code)", "PairingHost.controller.typed(normalized)")) assertGuarded(text, call)
        assertTrue(text.contains("if (!ReceiverSessions.state.value.presence.live) openReceiver()"))
        assertFalse(text.substringAfter("LaunchedEffect(").substringBefore(") {").contains("Gate"))
    }
    @Test fun roleActivationActuallyUsesGate() { assertGuarded(source("AppMediaRoles"), "PairingHost.controller.show()") }
    @Test fun receiverProductionStartsUseGateButBindingAndStopDoNot() {
        val text = source("ReceiverSessionCoordinator")
        assertGuarded(text, "engine.create()"); assertGuarded(text, "engine.join(value)")
        val regions = guardedRegions(text)
        for (call in listOf("AppMediaRoles.bindReceiver(", "engine.stop(reason, intentional)")) {
            val index = text.indexOf(call); assertTrue(index >= 0); assertFalse(regions.any { index in it })
        }
    }
    @Test fun hideCloseAndReceiverStopRemainOutsideGate() {
        for ((name, calls) in listOf("MainActivity" to listOf("PairingHost.controller.hide()"),
            "AppMediaRoles" to listOf("PairingHost.controller.hide()", "PairingHost.controller.close()"),
            "ReceiverSessionCoordinator" to listOf("engine.stop(reason, intentional)"))) {
            val text = source(name); val regions = guardedRegions(text)
            for (call in calls) for (match in Regex(Regex.escape(call)).findAll(text)) assertFalse(regions.any { match.range.first in it })
        }
    }
    @Test fun gateHasNoBackendMediaOrAndroidDependencies() {
        val text = source("ConnectionRequestRuntimeGate")
        for (forbidden in listOf("android.", "PairingHost", "ReceiverEngine", "MediaRole", "OkHttp", "ConnectionRequestController", "Log.", "println", "SharedPreferences"))
            assertFalse(text.contains(forbidden))
    }
    @Test fun armAndStartDispatchAreSerializedAcrossThreads() {
        val gate = LegacyConnectionStartGate(); val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val arming = CountDownLatch(1)
        try {
            val start = pool.submit { gate.runLegacyStart { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val arm = pool.submit { arming.countDown(); gate.armRequestFirst() }
            assertTrue(arming.await(5, TimeUnit.SECONDS)); release.countDown()
            start.get(5, TimeUnit.SECONDS); arm.get(5, TimeUnit.SECONDS)
            var lateStarts = 0; repeat(10) { gate.runLegacyStart { lateStarts++ } }
            assertEquals(0, lateStarts)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
