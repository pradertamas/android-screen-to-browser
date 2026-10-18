package com.pradertamas.uicapturetest

import android.os.Handler
import android.os.Looper
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Process-local signaling owner. No capture and no foreground service while waiting. */
object PairingHost {
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(PairingHome())
    val state = mutable.asStateFlow()
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var tokens: RemoteTokenStore? = null
    fun initialize(context: Context) {
        if(tokens == null){
            val prefs = context.applicationContext.getSharedPreferences(RemoteDevice.PREFS_FILE, Context.MODE_PRIVATE)
            tokens = RemoteTokenStore({ prefs.getString("token", null) }, { prefs.edit().putString("token", it).commit() })
        }
    }
    fun remoteLink() = RemoteDevice.url(checkNotNull(tokens).get())
    fun remoteShareText() = RemoteDevice.shareText(checkNotNull(tokens).get())
    fun regenerateRemoteLink() { checkNotNull(tokens).regenerate(); controller.refreshRemotePresence() }
    val mediaRole = MediaRole.SEND
    val controller = AutoPairing(object : PairingTransport {
        override fun open(code: String, intent: PairingIntent, message: (String, String, String) -> Unit, ended: () -> Unit): PairingLink {
            val callbackEpoch = state.value.epoch
            var registrationSeen = false
            var remoteSupported = false
            val ws = http.newWebSocket(Request.Builder()
                .url(mediaRole.registrationUrl(code, intent)).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { main.post {
                    try {
                        require(text.toByteArray(Charsets.UTF_8).size < 64 * 1024)
                        val m = JSONObject(text)
                        if(m.optString("type") == "registration" && !registrationSeen){
                            registrationSeen = true
                            remoteSupported = m.optString("status") == "ok" && m.optBoolean("remotePresence", false)
                        }
                        if (m.optString("type") in setOf("peer-present", "peer-left", "bye"))
                            lifecycleSignal(m.optString("type"), callbackEpoch)
                        message(m.optString("type"), m.optString("status"), text)
                    } catch (_: Exception) { lifecycleSignal("parse-failure", callbackEpoch); ended() }
                } }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); main.post { lifecycleSignal("failure", callbackEpoch); ended() } }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.post { lifecycleSignal("closed", callbackEpoch); ended() } }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null); main.post { lifecycleSignal("closing", callbackEpoch); ended() }
                }
            })
            return object : PairingLink {
                override val remotePresenceSupported: Boolean get() = remoteSupported
                override fun send(text: String) = ws.send(text)
                override fun close() { ws.close(1000, "Pairing ended"); ws.cancel() }
            }
        }
    }, { delay, action ->
        val task = Runnable { action() }; main.postDelayed(task, delay)
        val cancel: () -> Unit = { main.removeCallbacks(task) }
        cancel
    }, { mutable.value = it }, remoteToken = { tokens?.get() }, diagnostic = ScreenShareLifecycleDiag::write)
    private fun lifecycleSignal(event: String, callbackEpoch: Long) {
        val home = state.value
        ScreenShareLifecycleDiag.write("event=socket-$event phase=${home.phase} epoch=${home.epoch} callbackEpoch=$callbackEpoch current=${callbackEpoch == home.epoch}")
    }
    fun post(action: (AutoPairing) -> Unit) { main.post { action(controller) } }
}
