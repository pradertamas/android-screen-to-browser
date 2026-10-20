package com.pradertamas.uicapturetest

import java.lang.ref.WeakReference

/** Weak Activity-view reference; a stale release cannot detach a replacement renderer. */
internal class ReceiverRendererBinding<T : Any>(private val attachView: (T) -> Unit, private val detachView: (T) -> Unit) {
    private var view: WeakReference<T>? = null
    fun current(): T? = view?.get()
    fun attach(renderer: T) { view = WeakReference(renderer); attachView(renderer) }
    fun detach(renderer: T) {
        if (current() !== renderer) return
        detachView(renderer); view = null
    }
}

/** Opaque process-local media lifetime, never signaling authority or persisted state. */
class ReceiverSessionIdentity internal constructor() {
    /** Intent selector for this process-local lifetime; never persisted or reconstructed from it. */
    internal val anchorKey = java.util.UUID.randomUUID().toString()
    override fun toString() = "ReceiverSessionIdentity[local]"
}
data class ReceiverPresence(val session: ReceiverSessionIdentity? = null,
    val live: Boolean = false, val visible: Boolean = false, val absentUntilElapsed: Long? = null,
    val backgroundAccepted: Boolean = false)

internal fun receiverMediaAuthority(presence: ReceiverPresence, elapsed: Long): ReceiverMediaAuthority = when {
    !presence.live || presence.session == null -> ReceiverMediaAuthority.INACTIVE
    presence.visible -> ReceiverMediaAuthority.FOREGROUND
    presence.backgroundAccepted && presence.absentUntilElapsed?.let { it > elapsed } == true -> ReceiverMediaAuthority.BACKGROUND
    else -> ReceiverMediaAuthority.INACTIVE
}

/** Lifecycle registration only: tokens contain no Activity reference and convey no media ownership. */
internal class ReceiverViewerBindings {
    private var receiver: Any? = null
    private var session: ReceiverSessionIdentity? = null
    private var main: Any? = null
    fun attachReceiver(owner: Any, current: ReceiverSessionIdentity?) { receiver = owner; session = current }
    fun receiverCurrent(owner: Any, current: ReceiverSessionIdentity?) = receiver === owner && session === current
    fun receiverAttached(owner: Any) = receiver === owner
    fun receiverSession(current: ReceiverSessionIdentity?) { session = current }
    fun detachReceiver(owner: Any) { if (receiver === owner) { receiver = null; session = null } }
    fun attachMain(owner: Any) { main = owner }
    fun mainCurrent(owner: Any) = main === owner
    fun detachMain(owner: Any) { if (main === owner) main = null }
}

/** Main-thread serialized. Network grace remains exclusively ReceiverEngine's concern. */
internal class ReceiverVisibility(private val elapsed: () -> Long,
    private val later: (Long, () -> Unit) -> (() -> Unit),
    private val changed: (ReceiverPresence) -> Unit,
    private val stopSession: (ReceiverSessionIdentity) -> Unit,
    private val trace: (String) -> Unit = {}) {
    companion object { const val UI_GRACE_MS = 60_000L }
    var state = ReceiverPresence(); private set
    var lastStopReason: String? = null; private set
    private var viewer: Any? = null
    private var cancel: (() -> Unit)? = null
    private var epoch = 0L
    private fun publish(value: ReceiverPresence) { state = value; changed(value) }
    private fun invalidate() {
        epoch++
        if (cancel != null) trace("deadline-cancelled epoch=$epoch")
        cancel?.invoke(); cancel = null
    }
    fun connection(phase: ReceiverPhase) {
        if (phase in setOf(ReceiverPhase.ENDED, ReceiverPhase.ERROR)) {
            invalidate(); publish(ReceiverPresence(visible = viewer != null)); return
        }
        val id = state.session ?: ReceiverSessionIdentity()
        publish(state.copy(session = id, live = phase == ReceiverPhase.RECEIVING, visible = viewer != null))
        if (state.live && !state.visible && state.absentUntilElapsed == null) beginAbsence()
    }
    fun visible(owner: Any) {
        checkExpiry()
        viewer = owner; invalidate()
        publish(state.copy(visible = true, absentUntilElapsed = null, backgroundAccepted = false))
        trace("viewer-foreground token=${System.identityHashCode(owner)} live=${state.live}")
    }
    fun hidden(owner: Any, changingConfiguration: Boolean): Boolean {
        if (viewer !== owner || changingConfiguration) {
            trace("viewer-background-ignored token=${System.identityHashCode(owner)} config=$changingConfiguration")
            return false
        }
        viewer = null
        publish(state.copy(visible = false, backgroundAccepted = true))
        if (state.live && state.absentUntilElapsed == null) beginAbsence()
        trace("viewer-background token=${System.identityHashCode(owner)} live=${state.live}")
        return true
    }
    private fun beginAbsence() {
        val now = elapsed()
        val until = if (now > Long.MAX_VALUE - UI_GRACE_MS) Long.MAX_VALUE else now + UI_GRACE_MS
        publish(state.copy(absentUntilElapsed = until)); schedule()
    }
    private fun schedule() {
        invalidate()
        val id = state.session ?: return
        val until = state.absentUntilElapsed ?: return
        val ticket = epoch
        trace("deadline-scheduled epoch=$ticket until=$until")
        cancel = later((until - elapsed()).coerceAtLeast(0)) {
            if (ticket == epoch && state.session === id && !state.visible) {
                cancel = null
                if (!checkExpiry()) schedule() // Early wake-up never extends the absolute deadline.
            }
        }
    }
    fun checkExpiry(): Boolean {
        val id = state.session ?: return false
        val until = state.absentUntilElapsed ?: return false
        if (elapsed() < until) return false
        lastStopReason = "ui-background-timeout"
        trace("deadline-fired epoch=$epoch until=$until")
        invalidate(); publish(ReceiverPresence(visible = viewer != null)); stopSession(id)
        return true
    }
    fun matches(id: ReceiverSessionIdentity) = state.live && state.session === id && !checkExpiry()
    fun stop(id: ReceiverSessionIdentity): Boolean {
        if (!matches(id)) return false
        lastStopReason = "user-stop-active-share"
        invalidate(); publish(ReceiverPresence(visible = viewer != null)); stopSession(id)
        return true
    }
}

data class ReceiverFrameSize(val width: Int, val height: Int)
data class ReceiverFit(val width: Float, val height: Float)
/** Viewport and result use the same units; frame dimensions are rotated pixel dimensions. */
fun receiverAspectFit(width: Float, height: Float, frame: ReceiverFrameSize): ReceiverFit {
    if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 || frame.width <= 0 || frame.height <= 0)
        return ReceiverFit(0f, 0f)
    val scale = minOf(width.toDouble() / frame.width, height.toDouble() / frame.height)
    return ReceiverFit((frame.width * scale).toFloat(), (frame.height * scale).toFloat())
}
