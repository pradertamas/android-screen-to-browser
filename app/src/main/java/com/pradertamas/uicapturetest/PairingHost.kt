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
    val controller = AutoPairing(object : PairingTransport {
        override fun open(code: String, intent: PairingIntent, message: (String, String, String) -> Unit, ended: () -> Unit): PairingLink {
            var registrationSeen = false
            var remoteSupported = false
            val ws = http.newWebSocket(Request.Builder()
                .url("${WebRtcConfig.RELAY}?session=$code&role=sender&intent=${intent.wire}").build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { main.post {
                    try {
                        require(text.toByteArray(Charsets.UTF_8).size < 64 * 1024)
                        val m = JSONObject(text)
                        if(m.optString("type") == "registration" && !registrationSeen){
                            registrationSeen = true
                            remoteSupported = m.optString("status") == "ok" && m.optBoolean("remotePresence", false)
                        }
                        message(m.optString("type"), m.optString("status"), text)
                    } catch (_: Exception) { ended() }
                } }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); main.post { ended() } }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.post { ended() } }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null); main.post { ended() }
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
    }, { mutable.value = it }, remoteToken = { tokens?.get() })
    fun post(action: (AutoPairing) -> Unit) { main.post { action(controller) } }
}
