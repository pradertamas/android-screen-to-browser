package com.pradertamas.uicapturetest

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.*
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.Locale
import java.util.concurrent.TimeUnit

object WebRtcConfig {
    const val RELAY = "wss://realtime-relay-k28l.onrender.com/ws"
    const val STUN = "stun:stun.l.google.com:19302"
}
data class WebRtcUi(val busy: Boolean = false, val status: String = "Idle", val metrics: String = "N/A", val pairingCode: String = "", val intent: PairingIntent = PairingIntent.CREATE)
object WebRtcState {
    private val mutable = MutableStateFlow(WebRtcUi())
    val state = mutable.asStateFlow()
    fun set(value: WebRtcUi) { mutable.value = value }
}

/** Owns exactly one fresh consent token, texture capturer and sender peer. All control is serialized. */
class WebRtcService : Service() {
    companion object {
        const val START = "webrtc.START"
        const val STOP = "webrtc.STOP"
        const val CONSENT = "consent"
        const val SESSION = "session"
        const val INTENT = "pairingIntent"
    }
    private val thread = HandlerThread("WebRtcControl")
    private lateinit var handler: Handler
    private var closing = false
    private var disposed = false
    private var destroyed = false
    private var started = false
    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var helper: SurfaceTextureHelper? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var peer: PeerConnection? = null
    private var socket: WebSocket? = null
    private var registration: PairingRegistration? = null
    private var pairingIntent = PairingIntent.CREATE
    private var registered = false
    private var userStatus = "Connecting"
    private var addressFamily = "N/A"
    private var bytesAvailable = false
    private val directTimeout = Runnable { finish("Direct connection could not be established.") }
    private val waitingTimeout = Runnable { finish("Pairing code expired. Start a new share.") }
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val pendingIce = mutableListOf<IceCandidate>()
    private var offered = false
    private var remoteSet = false
    private var startedAt = 0L
    private var previousAt = 0L
    private var previousBytes = 0.0
    private var bytes = 0.0
    private var frames = 0.0
    private var peak = 0.0
    private var currentBitrate = 0.0
    private var codec = "N/A"
    private var resolution = "N/A"
    private var candidate = "N/A"
    private var protocol = "N/A"
    private var rttTotal = 0.0
    private var rttMax = 0.0
    private var rttCount = 0
    private var statsPending = false
    private val ticker = object : Runnable {
        override fun run() {
            if (closing) return
            collectStats()
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() { super.onCreate(); thread.start(); handler = Handler(thread.looper) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { handler.post { finish("Stopped") }; return START_NOT_STICKY }
        if (intent?.action != START || started) { if (!started) stopSelf(); return START_NOT_STICKY }
        started = true
        val consent = intent.getParcelableExtra(CONSENT, Intent::class.java)
        val session = intent.getStringExtra(SESSION).orEmpty()
        try {
            pairingIntent = PairingIntent.valueOf(intent.getStringExtra(INTENT) ?: "CREATE")
            check(consent != null && PairingCode.normalize(session) == session) { "Invalid consent/session" }
            check(!CaptureState.state.value.busy) { "Stop PoC 3B first" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("webrtc", "WebRTC screen share", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 5, Intent(this, WebRtcService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "webrtc").setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Screen Share").setContentText("Video only • open app for metrics")
                .setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build()
            startForeground(4, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            handler.post { try { start(consent, session) } catch (_: Exception) { finish("Screen sharing could not start.") } }
        } catch (e: Exception) { handler.post { finish("Start error: ${e.message}") } }
        return START_NOT_STICKY // Never replay MediaProjection consent.
    }
    private fun start(consent: Intent, session: String) {
        if (closing) return
        startedAt = SystemClock.elapsedRealtime(); previousAt = startedAt
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
        egl = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext)).createPeerConnectionFactory()
        source = factory!!.createVideoSource(true)
        // ScreenCapturerAndroid ignores FPS; adaptOutputFormat and sender encoding enforce the requested cap.
        source!!.adaptOutputFormat(720, 1560, 30)
        helper = SurfaceTextureHelper.create("WebRtcScreen", egl!!.eglBaseContext)
        capturer = ScreenCapturerAndroid(consent, object : MediaProjection.Callback() {
            override fun onStop() { handler.post { finish("Projection stopped by system") } }
        })
        capturer!!.initialize(helper, applicationContext, source!!.capturerObserver)
        capturer!!.startCapture(720, 1560, 30)
        track = factory!!.createVideoTrack("screen", source)
        registration = PairingRegistration(session, pairingIntent)
        connectSender()
        handler.postDelayed(waitingTimeout, 5 * 60_000L)
        handler.post(ticker)
    }
    private fun setStatus(status: String) {
        userStatus = status
        WebRtcState.set(WebRtcState.state.value.copy(busy = true, status = status,
            pairingCode = registration?.code.orEmpty()))
    }
    private fun connectSender() {
        if (closing) return
        registered = false
        val pairing = checkNotNull(registration)
        setStatus("Connecting")
        socket = http.newWebSocket(Request.Builder().url("${WebRtcConfig.RELAY}?session=${pairing.code}&role=sender&intent=${pairing.intent.wire}").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { handler.post {
                if (!closing && webSocket === socket) {
                    setStatus("Connecting")
                }
            } }
            override fun onMessage(webSocket: WebSocket, text: String) { handler.post {
                if (!closing && webSocket === socket) try { receive(JSONObject(text)) }
                catch (_: Exception) { finish("Connection could not be established. Start a new share.") }
            } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                response?.close()
                handler.post {
                    if (closing || webSocket !== socket) return@post
                    if (pairing.retry(code) != null) connectSender()
                    else finish(if (code == 409) "Could not reserve a pairing code. Please start again." else "Disconnected. Check your internet connection and start again.")
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { handler.post {
                if (webSocket === socket) finish("Disconnected")
            } }
        })
    }
    private fun createPeerAndOffer() {
        val config = PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder(WebRtcConfig.STUN).createIceServer())).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
        peer = checkNotNull(factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                handler.post {
                    if (closing) return@post
                    when (s) {
                        PeerConnection.PeerConnectionState.CONNECTED -> { handler.removeCallbacks(directTimeout); setStatus("Streaming") }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> { setStatus("Disconnected"); handler.postDelayed(directTimeout, 15_000) }
                        PeerConnection.PeerConnectionState.FAILED -> finish("Direct connection could not be established.")
                        else -> Unit
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(c: IceCandidate) { handler.post {
                send(JSONObject().put("type", "ice").put("candidate", JSONObject().put("candidate", c.sdp)
                    .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)))
            } }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }))
        val sender = peer!!.addTrack(track, listOf("screen-stream"))
        val parameters = sender.parameters
        parameters.encodings.forEach { it.maxBitrateBps = 2_000_000; it.maxFramerate = 30 }
        check(sender.setParameters(parameters)) { "Cannot apply sender video profile" }
        peer!!.createOffer(observer { s -> peer!!.setLocalDescription(observer {
            send(JSONObject().put("type", "offer").put("sdp", s!!.description))
        }, s) }, MediaConstraints())
    }
    private fun send(message: JSONObject) {
        if (closing) return
        val text = message.toString()
        if (text.toByteArray(Charsets.UTF_8).size >= 64 * 1024) {
            handler.post { finish("Signaling payload too large") }; return
        }
        if (socket?.send(text) != true) handler.post { finish("Signaling unavailable") }
    }
    private fun observer(done: (SessionDescription?) -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(s: SessionDescription) { handler.post { if (!closing) try { done(s) } catch (e: Exception) { finish("SDP error: ${e.message}") } } }
        override fun onSetSuccess() { handler.post { if (!closing) try { done(null) } catch (e: Exception) { finish("SDP error: ${e.message}") } } }
        override fun onCreateFailure(error: String) { handler.post { finish("SDP create: $error") } }
        override fun onSetFailure(error: String) { handler.post { finish("SDP set: $error") } }
    }
    private fun receive(m: JSONObject) {
        when (m.getString("type")) {
            "registration" -> when (m.getString("status")) {
                "ok" -> { registered = true; setStatus(if (pairingIntent == PairingIntent.CREATE) "Waiting for receiving screen..." else "Browser connected") }
                "conflict" -> {
                    val old = socket
                    if (registration?.retry(409) != null) { socket = null; old?.close(1000, "Retry"); connectSender() }
                    else finish("Pairing code already in use. Please create another pairing.")
                }
                "not-found" -> finish("Pairing code not found or expired.")
                else -> finish("Pairing code already in use.")
            }
            "peer-present" -> if (!offered) {
                check(registered)
                offered = true
                handler.removeCallbacks(waitingTimeout)
                setStatus("Browser connected")
                handler.postDelayed(directTimeout, 45_000)
                createPeerAndOffer()
                setStatus("Starting stream")
            }
            "answer" -> if (offered && !remoteSet) {
                peer!!.setRemoteDescription(observer {
                    remoteSet = true
                    pendingIce.forEach { peer!!.addIceCandidate(it) }; pendingIce.clear()
                }, SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")))
            }
            "ice" -> {
                check(peer != null)
                val c = m.getJSONObject("candidate")
                val ice = IceCandidate(c.optString("sdpMid", "0"), c.getInt("sdpMLineIndex"), c.getString("candidate"))
                if (remoteSet) peer!!.addIceCandidate(ice) else { check(pendingIce.size < 256); pendingIce.add(ice) }
            }
            "bye", "peer-left" -> finish("Disconnected")
        }
    }
    private fun collectStats() {
        val pc = peer ?: return
        if (statsPending) return
        statsPending = true
        pc.getStats { report -> handler.post {
            statsPending = false
            if (!closing) publish(report)
        } }
    }
    private fun publish(report: RTCStatsReport) {
        val all = report.statsMap.values
        val out = all.firstOrNull { it.type == "outbound-rtp" && (it.members["kind"] == "video" || it.members["mediaType"] == "video") }
        val m = out?.members.orEmpty()
        fun n(key: String) = (m[key] as? Number)?.toDouble()
        n("bytesSent")?.let { bytes = it; bytesAvailable = true }; frames = n("framesSent") ?: frames
        val now = SystemClock.elapsedRealtime()
        val current = if (now > previousAt) (bytes - previousBytes).coerceAtLeast(0.0) * 8000 / (now - previousAt) else 0.0
        currentBitrate = current
        peak = maxOf(peak, current)
        val codecStats = report.statsMap[m["codecId"] as? String]
        codec = codecStats?.members?.get("mimeType")?.toString() ?: codec
        resolution = if (m["frameWidth"] != null) "${m["frameWidth"]} × ${m["frameHeight"]}" else resolution
        val transport = all.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
        val nominated = all.filter { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
        val pair = report.statsMap[transport?.members?.get("selectedCandidatePairId") as? String]
            ?: all.firstOrNull { it.type == "candidate-pair" && it.members["selected"] == true }
            ?: nominated.singleOrNull() // Do not guess between multiple nominated pairs.
        val local = report.statsMap[pair?.members?.get("localCandidateId") as? String]?.members.orEmpty()
        val remote = report.statsMap[pair?.members?.get("remoteCandidateId") as? String]?.members.orEmpty()
        candidate = "${local["candidateType"] ?: "N/A"} / ${remote["candidateType"] ?: "N/A"}"
        protocol = local["protocol"]?.toString() ?: "N/A"
        addressFamily = "${addressFamily(local["address"] ?: local["ip"])} / ${addressFamily(remote["address"] ?: remote["ip"])}"
        previousBytes = bytes; previousAt = now
        val rtt = (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()?.times(1000)
        if (rtt != null) { rttTotal += rtt; rttCount++; rttMax = maxOf(rttMax, rtt) }
        val detail = summary(now) + "\nCurrent bitrate: ${fmt(current)} bps\n" +
            "Peer: ${peer?.connectionState()}\nICE: ${peer?.iceConnectionState()}\nSignaling: ${peer?.signalingState()}\n" +
            "Frames captured: ${capturer?.numCapturedFrames ?: "N/A"}\nFrames encoded: ${m["framesEncoded"] ?: "N/A"}\n" +
            "Packets sent: ${m["packetsSent"] ?: "N/A"}\nPair state: ${pair?.members?.get("state") ?: "N/A"}\nRTT: ${rtt?.let { fmt(it) } ?: "N/A"} ms\n" +
            "Encoder implementation: ${m["encoderImplementation"] ?: "N/A"}\nPower efficient encoder: ${m["powerEfficientEncoder"] ?: "N/A"}"
        WebRtcState.set(WebRtcUi(true, userStatus, detail, registration?.code.orEmpty(), pairingIntent))
    }
    private fun addressFamily(address: Any?): String = when {
        address !is String || address.isEmpty() -> "N/A"
        address.contains(":") -> "IPv6"
        address.matches(Regex("[0-9]+(\\.[0-9]+){3}")) -> "IPv4"
        else -> "N/A / obfuscated"
    }
    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun summary(now: Long): String {
        val seconds = ((now - startedAt) / 1000.0).coerceAtLeast(0.001)
        return "Mode: Direct P2P / STUN only\nAddress family local / remote: $addressFamily\n" +
            "Duration: ${fmt(seconds)} s\nBytes sent: ${if (bytesAvailable) fmt(bytes) else "N/A"}\nAverage bitrate: ${fmt(bytes * 8 / seconds)} bps\n" +
            "Current bitrate: ${fmt(currentBitrate)} bps\n" +
            "Peak bitrate (~1 s): ${fmt(peak)} bps\nFrames sent: ${fmt(frames)}\nAverage FPS: ${fmt(frames / seconds)}\n" +
            "Codec: $codec\nEncoded resolution: $resolution\nCapture profile: 720 × 1560 / 30 FPS / max 2 Mbps\n" +
            "Candidate local / remote: $candidate\nProtocol: $protocol\nAverage RTT: ${if (rttCount > 0) fmt(rttTotal / rttCount) else "N/A"} ms\nMaximum RTT: ${if (rttCount > 0) fmt(rttMax) else "N/A"} ms"
    }
    private fun finish(reason: String) {
        if (closing) return
        runCatching { send(JSONObject().put("type", "bye")) }
        closing = true; handler.removeCallbacks(ticker)
        handler.removeCallbacks(directTimeout); handler.removeCallbacks(waitingTimeout)
        // Take a final counter snapshot before disposing. Bound the wait if native stats do not return.
        val pc = peer
        if (pc == null) { completeFinish(reason); return }
        handler.postDelayed({ completeFinish(reason) }, 750)
        runCatching { pc.getStats { report -> handler.post {
            if (!disposed) {
                runCatching { publish(report) }
                completeFinish(reason)
            }
        } } }.onFailure { completeFinish(reason) }
    }
    private fun completeFinish(reason: String) {
        if (disposed) return
        disposed = true
        val snapshot = if (startedAt > 0) summary(SystemClock.elapsedRealtime()) else WebRtcState.state.value.metrics
        socket?.close(1000, "Session stopped"); socket = null
        peer?.close(); peer?.dispose(); peer = null
        runCatching { capturer?.stopCapture() }.onFailure { Log.w("WebRtcPoC", "Capture stop", it) }
        capturer?.dispose(); capturer = null
        track?.dispose(); track = null
        source?.dispose(); source = null
        helper?.dispose(); helper = null
        factory?.dispose(); factory = null
        egl?.release(); egl = null
        pendingIce.clear()
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        WebRtcState.set(WebRtcUi(true, reason, snapshot))
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (destroyed) releaseControlThread() else stopSelf()
    }
    private fun releaseControlThread() {
        WebRtcState.set(WebRtcState.state.value.copy(busy = false))
        thread.quitSafely()
    }
    override fun onDestroy() {
        handler.post {
            destroyed = true
            finish("Stopped")
            if (disposed) releaseControlThread()
        }
        super.onDestroy()
    }
}

