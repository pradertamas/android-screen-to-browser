package com.pradertamas.uicapturetest

/** Once-only dispatch from committed, visible RECEIVE. Contains no media or Android ownership. */
internal class ReceiverAnchorAdmission(private val start: (ReceiverSessionIdentity) -> Unit) {
    private var requested: ReceiverSessionIdentity? = null
    fun changed(ui: ReceiverUi, role: MediaRole, mediaCommitted: Boolean) {
        val id = ui.presence.session
        if (requested !== id) requested = null
        if (id == null || requested === id || role != MediaRole.RECEIVE || !mediaCommitted ||
            ui.home.phase != ReceiverPhase.RECEIVING || !ui.presence.live || !ui.presence.visible) return
        requested = id // Admit before dispatch; reentrant/lifecycle callbacks cannot duplicate it.
        start(id)
    }
}

/** Service command policy only. Selectors are checked against the existing process-local owner. */
internal class ReceiverAnchorLifecycle(
    private val active: (String) -> Boolean,
    private val foreground: (String) -> Unit,
    private val stopReceiver: (String) -> Boolean,
    private val stopService: (Int) -> Unit,
    private val trace: (String, String?) -> Unit = { _, _ -> },
    private val refreshNotification: (String) -> Unit = {}
) {
    var key: String? = null; private set
    private var startId = 0
    private var stopRequestedFor: Int? = null
    fun command(action: String?, selector: String?, id: Int) {
        startId = id // Even an ignored command advances Android's stopSelfResult boundary.
        when (action) {
            "START" -> {
                if (selector == null || !active(selector)) {
                    trace("anchor-stale-start-ignored", selector); stopIfIdle(); return
                }
                if (key == selector) return
                key = selector; stopRequestedFor = null
                try {
                    foreground(selector)
                    trace("anchor-exact-session-attached", selector)
                } catch (error: Exception) {
                    key = null
                    trace("anchor-foreground-failed exception=${error.javaClass.simpleName}", selector)
                    stopIfIdle()
                }
            }
            "STOP" -> {
                val admitted = selector != null && active(selector) && (key == null || key == selector) && stopReceiver(selector)
                trace(if (admitted) "anchor-notification-stop-admitted" else "anchor-notification-stop-rejected", selector)
                stopIfIdle()
            }
            "REFRESH" -> {
                if (selector != null && key == selector && active(selector)) refreshNotification(selector)
                stopIfIdle()
            }
            else -> stopIfIdle() // Null restart/malformed command must never reconstruct anything.
        }
    }
    fun changed() { if (key != null) stopIfIdle() }
    private fun stopIfIdle() {
        val held = key
        if (held != null && active(held)) return
        key = null // Clear before external callbacks, including synchronous state publication.
        if (stopRequestedFor == startId) return
        stopRequestedFor = startId
        trace("anchor-stop-requested reason=session-unavailable", held)
        stopService(startId)
    }
}
