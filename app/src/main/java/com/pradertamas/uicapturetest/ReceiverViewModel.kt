package com.pradertamas.uicapturetest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.webrtc.SurfaceViewRenderer

/** Activity façade only. Clearing a viewer never disposes the process-owned live peer. */
class ReceiverViewModel(application: Application) : AndroidViewModel(application) {
    internal val owner = ReceiverSessions.get(application)
    val state = owner.state
    init { owner.prepare() }
    internal fun ensureBoundToTask(taskId: Int) = owner.ensureBoundToTask(taskId)
    internal val requestIdentity get() = owner.requestIdentity
    internal val requestFlow get() = owner.requestFlow
    internal fun startRequest(handoff: String) = owner.startRequest(handoff)
    internal fun stopRequest(flow: Any) = owner.stopRequest(flow)
    fun ensureOwnCreate() = owner.ensureOwnCreate()
    fun retry() = owner.retry()
    fun join(value: String) = owner.join(value)
    fun attach(renderer: SurfaceViewRenderer) = owner.attach(renderer)
    fun detach(renderer: SurfaceViewRenderer) = owner.detach(renderer)
    fun stop() = owner.stop()
}
