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
    override fun toString() = "ReceiverSessionIdentity[local]"
}
data class ReceiverPresence(val session: ReceiverSessionIdentity? = null,
    val live: Boolean = false, val visible: Boolean = false, val absentUntilElapsed: Long? = null)

/** Main-thread serialized. Network grace remains exclusively ReceiverEngine's concern. */
internal class ReceiverVisibility(private val elapsed: () -> Long,
    private val later: (Long, () -> Unit) -> (() -> Unit),
    private val changed: (ReceiverPresence) -> Unit,
    private val stopSession: (ReceiverSessionIdentity) -> Unit) {
    companion object { const val UI_GRACE_MS = 60_000L }
    var state = ReceiverPresence(); private set
    private var viewer: Any? = null
    private var cancel: (() -> Unit)? = null
    private var epoch = 0L
    private fun publish(value: ReceiverPresence) { state = value; changed(value) }
    private fun invalidate() { epoch++; cancel?.invoke(); cancel = null }
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
        publish(state.copy(visible = true, absentUntilElapsed = null))
    }
    fun hidden(owner: Any, changingConfiguration: Boolean): Boolean {
        if (viewer !== owner || changingConfiguration) return false
        viewer = null
        publish(state.copy(visible = false))
        if (state.live && state.absentUntilElapsed == null) beginAbsence()
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
        invalidate(); publish(ReceiverPresence(visible = viewer != null)); stopSession(id)
        return true
    }
    fun matches(id: ReceiverSessionIdentity) = state.live && state.session === id && !checkExpiry()
    fun stop(id: ReceiverSessionIdentity): Boolean {
        if (!matches(id)) return false
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
