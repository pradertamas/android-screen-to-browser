package com.pradertamas.uicapturetest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Start-boundary policy only. No session, credentials, role, Android context or cleanup. */
class LegacyConnectionStartGate {
    private val lock = Any()
    private val mutable = MutableStateFlow(true)
    val allowed = mutable.asStateFlow()
    fun armRequestFirst() = synchronized(lock) { mutable.value = false }
    fun releaseRequestFirst() = synchronized(lock) { mutable.value = true }
    fun legacyStartAllowed(): Boolean = synchronized(lock) { mutable.value }
    /** Atomic check+dispatch: when arm returns, a later caller cannot slip past a stale check.
     * Only synchronous start orchestration belongs here, never blocking I/O or suspension. */
    fun <T> runLegacyStart(start: () -> T): T? = synchronized(lock) {
        if (mutable.value) start() else null
    }
}

/** Fresh process defaults to legacy-enabled. No caller arms this until the Share checkpoint. */
object ConnectionRequestRuntimeGate {
    private val gate = LegacyConnectionStartGate()
    val allowed = gate.allowed
    fun armRequestFirst() = gate.armRequestFirst()
    fun releaseRequestFirst() = gate.releaseRequestFirst()
    fun legacyStartAllowed() = gate.legacyStartAllowed()
    fun <T> runLegacyStart(start: () -> T): T? = if (ReceiverSessions.active) null else gate.runLegacyStart(start)
}
