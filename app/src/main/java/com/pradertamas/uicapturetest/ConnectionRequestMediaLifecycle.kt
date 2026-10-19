package com.pradertamas.uicapturetest

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class RequestCleanupUi(val retry: Boolean = false)
/** Owner-serialized, event driven. No media operations or automatic network retry. */
internal class ConnectionRequestMediaLifecycle(private val controller: ConnectionRequestController,
    private val release: () -> Unit) {
    private var target: Any? = null
    private enum class Stage { NEW, EXIT, RECONCILE, ERROR }
    private var stage = Stage.NEW
    private var baseline = 0L
    private var retryReconcile = false
    private val mutable = MutableStateFlow(RequestCleanupUi())
    val state = mutable.asStateFlow()
    fun requestExit(flow: Any) { if (controller.markMediaExitRequested(flow)) changed() }
    fun retry(flow: Any) {
        if (target !== flow || stage != Stage.ERROR) return
        stage = Stage.NEW; mutable.value = RequestCleanupUi()
        changed()
    }
    fun changed() {
        val s = controller.mediaSnapshot()
        if (s?.flow !== target) { target = s?.flow; stage = Stage.NEW; retryReconcile = false; mutable.value = RequestCleanupUi() }
        if (s == null || s.phase == RequestMediaPhase.STARTED) return
        if (s.state?.terminal == true && s.phase == null && !s.exitRequested) {
            controller.markMediaExitRequested(s.flow)
        }
        if (!s.exitRequested && s.phase != RequestMediaPhase.DISPOSED && s.state?.terminal != true) return
        if (s.operation != null) return
        if (s.state?.terminal == true || s.state == ConnectionRequestState.CONNECTED) {
            controller.finishMediaFlow(s.flow, release); return
        }
        when (stage) {
            Stage.NEW -> {
                if (retryReconcile) reconcile(s)
                else {
                    baseline = s.exitCompletion?.revision ?: 0
                    stage = Stage.EXIT
                    if (!controller.requestMediaExit(s.flow)) stage = Stage.NEW
                }
            }
            Stage.EXIT -> {
                val c = s.exitCompletion ?: return
                if (c.revision <= baseline) return
                if (c.error in setOf(ConnectionRequestError.CONFLICT, ConnectionRequestError.INVALID_OPERATION,
                        ConnectionRequestError.INVALID_RESPONSE)) reconcile(s)
                else fail(false)
            }
            Stage.RECONCILE -> {
                val c = s.statusCompletion ?: return
                if (c.revision > baseline) fail(true) // A nonterminal or failed reconciliation requires explicit action.
            }
            Stage.ERROR -> Unit
        }
    }
    private fun reconcile(s: ConnectionRequestMediaSnapshot) {
        baseline = s.statusCompletion?.revision ?: 0
        stage = Stage.RECONCILE; retryReconcile = true
        if (!controller.reconcileMediaExit(s.flow)) fail(true)
    }
    private fun fail(reconcile: Boolean) {
        retryReconcile = reconcile; stage = Stage.ERROR; mutable.value = RequestCleanupUi(true)
    }
}

internal object RequestMediaLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    fun localStartFailed(flow: Any) { scope.launch { coordinator.requestExit(flow) } }
    val coordinator by lazy {
        val c = ConnectionRequests.controller
        ConnectionRequestMediaLifecycle(c, ConnectionRequestRuntimeGate::releaseRequestFirst).also { owner ->
            scope.launch { combine(c.state, c.ownershipChanges, c.mediaChanges) { _, _, _ -> Unit }.collect { owner.changed() } }
        }
    }
}
