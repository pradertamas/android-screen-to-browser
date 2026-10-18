package com.pradertamas.uicapturetest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal enum class RecipientEntryError { INVALID, BUSY, CONFLICT, STALE }
internal enum class RecipientScreen { CHECKING, WAITING, CHECK_ERROR, UNAVAILABLE, HANDLED, ACCEPTING, ACCEPT_ERROR, ACCEPTED, WITHDRAWING, WITHDRAW_ERROR, FINISHED, STALE }
internal data class RecipientUi(val screen: RecipientScreen = RecipientScreen.CHECKING, val initiatorRole: MediaRole? = null)
internal class RecipientAdmission(val flow: ConnectionRequestRecipientFlow? = null, val error: RecipientEntryError? = null) {
    override fun toString() = "RecipientAdmission[redacted]"
}

/** Process-local control plane only. No Android references, links or credentials retained here.
 * User commands run synchronously on the Activity's main thread; observation never starts HTTP. */
internal class ConnectionRequestRecipientCoordinator(
    private val controller: ConnectionRequestController,
    private val idle: () -> Boolean,
    private val arm: () -> Unit,
    private val stopIdle: () -> Boolean,
    private val release: () -> Unit
) {
    private val lock = Any()
    private var recipient: ConnectionRequestRecipientFlow? = null
    private var finished: ConnectionRequestRecipientFlow? = null
    private var last = ConnectionRequestOperation.STATUS
    private val mutable = MutableStateFlow(RecipientUi())
    val state = mutable.asStateFlow()
    fun hasContext(flow: ConnectionRequestRecipientFlow): Boolean = synchronized(lock) {
        recipient === flow && controller.recipientAccess(flow) != null
    }
    /** Never project B's global UI as A. Controller events also invalidate identical-looking UI. */
    fun projection(flow: ConnectionRequestRecipientFlow?): RecipientUi = synchronized(lock) {
        when {
            flow == null -> RecipientUi(RecipientScreen.STALE)
            finished === flow -> RecipientUi(RecipientScreen.FINISHED)
            !hasContext(flow) -> RecipientUi(RecipientScreen.STALE)
            else -> mutable.value
        }
    }
    fun observe(flow: ConnectionRequestRecipientFlow?) = combine(state, controller.state, controller.ownershipChanges) { _, _, _ -> projection(flow) }

    fun open(raw: String?, existingFlow: ConnectionRequestRecipientFlow? = null): RecipientAdmission = synchronized(lock) {
        val link = ConnectionRequestLink.parse(raw) ?: return@synchronized RecipientAdmission(error = RecipientEntryError.INVALID)
        if (existingFlow != null) return@synchronized RecipientAdmission(error =
            if (hasContext(existingFlow)) RecipientEntryError.CONFLICT else RecipientEntryError.STALE)
        if (!controller.state.value.idle) return@synchronized RecipientAdmission(error = RecipientEntryError.CONFLICT)
        if (!idle()) return@synchronized RecipientAdmission(error = RecipientEntryError.BUSY)
        arm()
        if (!stopIdle()) { controller.ifNoRequestOwnership(release); return@synchronized RecipientAdmission(error = RecipientEntryError.BUSY) }
        val context = controller.preview(link.requestId, link.recipientCapability, link.initiatorRole)
            ?: return@synchronized RecipientAdmission(error = RecipientEntryError.CONFLICT)
        recipient = context; last = ConnectionRequestOperation.STATUS
        mutable.value = RecipientUi(RecipientScreen.CHECKING, link.initiatorRole)
        controller.refreshStatus(context) // Exactly one initial capability GET; never Accept on open.
        onControllerState(controller.state.value)
        RecipientAdmission(flow = context)
    }
    fun retryStatus(flow: ConnectionRequestRecipientFlow) = synchronized(lock) {
        if (mutable.value.screen != RecipientScreen.CHECK_ERROR || !hasContext(flow)) return@synchronized
        last = ConnectionRequestOperation.STATUS; controller.refreshStatus(flow); onControllerState(controller.state.value)
    }
    fun accept(flow: ConnectionRequestRecipientFlow) = synchronized(lock) {
        if (!hasContext(flow) || mutable.value.screen !in setOf(RecipientScreen.WAITING, RecipientScreen.ACCEPT_ERROR) ||
            controller.state.value.operation != null || !idle()) return@synchronized
        last = ConnectionRequestOperation.ACCEPT; controller.accept(flow); onControllerState(controller.state.value)
    }
    fun close(flow: ConnectionRequestRecipientFlow) = synchronized(lock) {
        if (!hasContext(flow)) return@synchronized
        val context = flow
        val access = controller.recipientAccess(context) ?: return@synchronized
        val snapshot = controller.state.value
        if (snapshot.operation in setOf(ConnectionRequestOperation.ACCEPT, ConnectionRequestOperation.WITHDRAW)) return@synchronized
        if (snapshot.request?.state?.terminal == true || snapshot.error == ConnectionRequestError.UNAVAILABLE ||
            (!access.won && (!access.acceptAttempted || mutable.value.screen == RecipientScreen.HANDLED))) {
            finishOwned(context); return@synchronized
        }
        if (access.won && snapshot.request?.state in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING)) {
            last = ConnectionRequestOperation.WITHDRAW; controller.withdraw(flow); onControllerState(controller.state.value)
        }
        // Unknown Accept result must first be retried with the SAME claim, never silently abandoned.
    }
    private fun finishOwned(context: ConnectionRequestRecipientFlow) {
        if (controller.clearRecipient(context, release)) {
            recipient = null; finished = context; mutable.value = mutable.value.copy(screen = RecipientScreen.FINISHED)
        }
    }
    fun onControllerState(snapshot: ConnectionRequestUiState) = synchronized(lock) {
        val context = recipient ?: return@synchronized
        if (controller.state.value != snapshot) return@synchronized
        val access = controller.recipientAccess(context) ?: run {
            mutable.value = RecipientUi(RecipientScreen.STALE); return@synchronized
        }
        val state = snapshot.request?.state
        val screen = when {
            snapshot.operation == ConnectionRequestOperation.STATUS -> RecipientScreen.CHECKING
            snapshot.operation == ConnectionRequestOperation.ACCEPT -> RecipientScreen.ACCEPTING
            snapshot.operation == ConnectionRequestOperation.WITHDRAW -> RecipientScreen.WITHDRAWING
            last == ConnectionRequestOperation.WITHDRAW && state == ConnectionRequestState.FAILED &&
                snapshot.request.failureReason == "recipient_withdrew" -> { finishOwned(context); return@synchronized }
            state?.terminal == true || snapshot.error == ConnectionRequestError.UNAVAILABLE -> RecipientScreen.UNAVAILABLE
            last == ConnectionRequestOperation.WITHDRAW && snapshot.error != null -> RecipientScreen.WITHDRAW_ERROR
            access.won -> RecipientScreen.ACCEPTED
            snapshot.error == ConnectionRequestError.CONFLICT -> RecipientScreen.HANDLED
            last == ConnectionRequestOperation.ACCEPT && snapshot.error != null -> RecipientScreen.ACCEPT_ERROR
            snapshot.error != null -> RecipientScreen.CHECK_ERROR
            state == ConnectionRequestState.WAITING -> RecipientScreen.WAITING
            state in setOf(ConnectionRequestState.ACCEPTED, ConnectionRequestState.CONNECTING, ConnectionRequestState.CONNECTED) -> RecipientScreen.HANDLED
            else -> RecipientScreen.CHECK_ERROR
        }
        mutable.value = mutable.value.copy(screen = screen)
    }
}

internal object ConnectionRequestRecipients {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val coordinator by lazy {
        ConnectionRequestRecipientCoordinator(ConnectionRequests.controller, AppMediaRoles::pairingAllowed,
            ConnectionRequestRuntimeGate::armRequestFirst, AppMediaRoles::stopIdleLegacyForRequestFirst,
            ConnectionRequestRuntimeGate::releaseRequestFirst).also { coordinator ->
            scope.launch { ConnectionRequests.controller.state.collect { coordinator.onControllerState(it) } }
        }
    }
}
