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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.*
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.io.IOException

object WebRtcConfig {
    const val RELAY = "wss://realtime-relay-k28l.onrender.com/ws"
    const val CREDENTIAL_ENDPOINT = "https://realtime-relay-k28l.onrender.com/turn/credentials"
    const val STUN = "stun:stun.l.google.com:19302"
}
data class WebRtcUi(val busy: Boolean = false, val status: String = "Idle", val metrics: String = "N/A")
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
        const val MODE = "mode"
        const val ACCESS_TOKEN = "endpointAccessToken"
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
    private var credentialCall: Call? = null
    private var networkConfig: JSONObject? = null
    private var iceServers = emptyList<PeerConnection.IceServer>()
    private var mode = ConnectionMode.AUTO
    private var configId = ""
    private var usdPerGb = 0.0
    private var expiresAt = 0L
    private var bytesAvailable = false
    private var relayConfirmed = false
    private var relayEverConfirmed = false
    private var nonRelayMediaObserved = false
    private var relayProtocol = "N/A"
    private var addressFamily = "N/A"
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
        val accessToken = intent.getStringExtra(ACCESS_TOKEN).orEmpty()
        intent.removeExtra(ACCESS_TOKEN)
        try {
            mode = ConnectionMode.valueOf(intent.getStringExtra(MODE) ?: "AUTO")
            check(accessToken.length in 32..256 && accessToken.matches(Regex("[A-Za-z0-9_-]+"))) { "Enter the endpoint access token (not a Cloudflare token)" }
            check(consent != null && session.matches(Regex("[A-Za-z0-9_-]{6,64}"))) { "Invalid consent/session" }
            check(!CaptureState.state.value.busy) { "Stop PoC 3B first" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("webrtc", "WebRTC screen share", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 5, Intent(this, WebRtcService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "webrtc").setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("PoC 4B WebRTC share").setContentText("Video only • open app for metrics")
                .setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build()
            startForeground(4, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            handler.post { try { fetchCredentials(consent, session, accessToken) } catch (_: Exception) { finish("Credential request setup failed") } }
        } catch (e: Exception) { handler.post { finish("Start error: ${e.message}") } }
        return START_NOT_STICKY // Never replay MediaProjection consent.
    }
    private fun fetchCredentials(consent: Intent, session: String, accessToken: String) {
        if (closing) return
        WebRtcState.set(WebRtcUi(true, "Fetching temporary TURN credentials"))
        val request = Request.Builder().url(WebRtcConfig.CREDENTIAL_ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .post(JSONObject().put("session", session).toString().toRequestBody("application/json".toMediaType())).build()
        credentialCall = http.newCall(request).apply {
            timeout().timeout(20, TimeUnit.SECONDS)
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { handler.post { finish("Credential HTTPS request failed; check endpoint deployment/network") } }
                override fun onResponse(call: Call, response: Response) {
                    val raw = try { response.use {
                        if (!it.isSuccessful) { handler.post { finish("Credential endpoint HTTP ${it.code}") }; return }
                        val source = checkNotNull(it.body).source()
                        source.request(32769)
                        check(source.buffer.size <= 32768)
                        source.readUtf8()
                    } } catch (_: Exception) { handler.post { finish("Credential response could not be read") }; return }
                    handler.post {
                        if (closing) return@post
                        try {
                            check(raw.length <= 32768)
                            val data = JSONObject(raw)
                            expiresAt = data.getLong("expiresAt")
                            check(expiresAt - System.currentTimeMillis() in 120_000..660_000)
                            usdPerGb = data.getDouble("usdPerGb")
                            check(usdPerGb.isFinite() && usdPerGb >= 0)
                            val turn = data.getJSONArray("iceServers")
                            check(turn.length() in 1..8)
                            val entries = mutableListOf<PeerConnection.IceServer>()
                            for (i in 0 until turn.length()) {
                                val entry = turn.getJSONObject(i)
                                val urls = entry.getJSONArray("urls")
                                check(urls.length() in 1..8)
                                val values = (0 until urls.length()).map { urls.getString(it) }
                                check(values.all { it.matches(Regex("turns?:turn\\.cloudflare\\.com:(3478|443|80|5349)\\?transport=(udp|tcp)")) })
                                entries.add(PeerConnection.IceServer.builder(values).setUsername(entry.getString("username"))
                                    .setPassword(entry.getString("credential")).createIceServer())
                            }
                            iceServers = if (mode == ConnectionMode.AUTO) listOf(PeerConnection.IceServer.builder(WebRtcConfig.STUN).createIceServer()) + entries else entries
                            configId = UUID.randomUUID().toString()
                            networkConfig = JSONObject().put("type", "session-config").put("version", 1)
                                .put("configId", configId).put("mode", mode.name).put("usdPerGb", usdPerGb)
                                .put("expiresAt", expiresAt).put("iceServers", turn)
                            handler.postDelayed({ finish("Temporary TURN credentials nearing expiry; start a fresh session") },
                                (expiresAt - System.currentTimeMillis() - 30_000).coerceAtLeast(1))
                            start(consent, session)
                        } catch (_: Exception) { finish("Invalid temporary TURN configuration") }
                    }
                }
            })
        }
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
        socket = http.newWebSocket(Request.Builder().url("${WebRtcConfig.RELAY}?session=$session&role=sender").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { handler.post {
                if (!closing) { WebRtcState.set(WebRtcUi(true, "Signaling open; waiting for browser mode acknowledgment")); send(JSONObject().put("type", "ready").put("version", 1)) }
            } }
            override fun onMessage(webSocket: WebSocket, text: String) { handler.post {
                if (!closing) try { receive(JSONObject(text)) } catch (_: Exception) { finish("Signaling/configuration mismatch or invalid payload") }
            } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { handler.post { finish("WSS connection failed") } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { handler.post { finish("Signaling closed ($code)") } }
        })
        handler.postDelayed({ if (!offered) finish("Browser configuration acknowledgment timed out; update both peers") }, 60_000)
        handler.post(ticker)
    }
    private fun createPeerAndOffer() {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (mode == ConnectionMode.FORCE_TURN) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        }
        peer = checkNotNull(factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                if (s == PeerConnection.PeerConnectionState.FAILED) handler.post { finish("Peer connection failed; start a fresh session") }
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
        if (message.optString("type") in listOf("offer", "answer", "ice", "bye")) message.put("configId", configId).put("mode", mode.name)
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
            "ready" -> if (!offered) {
                check(m.optInt("version") == 1) { "Update browser to PoC 4B" }
                send(checkNotNull(networkConfig))
            }
            "config-ack" -> if (!offered) {
                check(m.getString("configId") == configId && m.getString("mode") == mode.name && m.getInt("version") == 1)
                offered = true
                createPeerAndOffer()
            }
            "answer" -> if (offered && !remoteSet) {
                requireSession(m)
                peer!!.setRemoteDescription(observer {
                    remoteSet = true
                    pendingIce.forEach { peer!!.addIceCandidate(it) }; pendingIce.clear()
                }, SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")))
            }
            "ice" -> {
                requireSession(m)
                check(peer != null)
                val c = m.getJSONObject("candidate")
                val ice = IceCandidate(c.optString("sdpMid", "0"), c.getInt("sdpMLineIndex"), c.getString("candidate"))
                if (remoteSet) peer!!.addIceCandidate(ice) else { check(pendingIce.size < 256); pendingIce.add(ice) }
            }
            "bye" -> finish("Browser disconnected")
        }
    }
    private fun requireSession(m: JSONObject) {
        check(m.getString("configId") == configId && m.getString("mode") == mode.name)
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
        relayProtocol = "${local["relayProtocol"] ?: "N/A"} / ${remote["relayProtocol"] ?: "N/A"}"
        addressFamily = "${NetworkMetrics.addressFamily(local["address"] ?: local["ip"])} / ${NetworkMetrics.addressFamily(remote["address"] ?: remote["ip"])}"
        relayConfirmed = NetworkMetrics.relayPair(local["candidateType"] as? String, remote["candidateType"] as? String,
            pair?.members?.get("state") as? String)
        if (relayConfirmed) relayEverConfirmed = true
        if (bytes > previousBytes && !relayConfirmed) nonRelayMediaObserved = true
        previousBytes = bytes; previousAt = now
        val rtt = (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()?.times(1000)
        if (rtt != null) { rttTotal += rtt; rttCount++; rttMax = maxOf(rttMax, rtt) }
        val detail = summary(now) + "\nCurrent bitrate: ${fmt(current)} bps\n" +
            "Peer: ${peer?.connectionState()}\nICE: ${peer?.iceConnectionState()}\nSignaling: ${peer?.signalingState()}\n" +
            "Frames captured: ${capturer?.numCapturedFrames ?: "N/A"}\nFrames encoded: ${m["framesEncoded"] ?: "N/A"}\n" +
            "Packets sent: ${m["packetsSent"] ?: "N/A"}\nPair state: ${pair?.members?.get("state") ?: "N/A"}\nRTT: ${rtt?.let { fmt(it) } ?: "N/A"} ms\n" +
            "Encoder implementation: ${m["encoderImplementation"] ?: "N/A"}\nPower efficient encoder: ${m["powerEfficientEncoder"] ?: "N/A"}"
        val proof = if (mode == ConnectionMode.FORCE_TURN && !relayConfirmed) " / FAIL / NOT RELAY (or not yet selected)" else ""
        WebRtcState.set(WebRtcUi(true, "Sharing / ${peer?.connectionState()}$proof", detail))
    }
    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun summary(now: Long): String {
        val seconds = ((now - startedAt) / 1000.0).coerceAtLeast(0.001)
        val gb = NetworkMetrics.gbPerHour(if (bytesAvailable) bytes else null, seconds)
        val cost = NetworkMetrics.costPerHour(gb, usdPerGb, relayConfirmed && relayEverConfirmed && !nonRelayMediaObserved)
        return "Mode: ${mode.label}\nTURN relay confirmed: ${if (relayConfirmed) "YES" else "NO"}\n" +
            "TURN cost test eligible: ${if (cost != null) "YES (sampled pair evidence)" else "NO / NOT RELAY, mixed path or missing stats"}\n" +
            "Measured RTP GB/hour: ${gb?.let { String.format(Locale.US, "%.6f", it) } ?: "N/A"} (decimal: 1 GB = 1,000,000,000 bytes)\n" +
            "Configured cost assumption: USD $usdPerGb / GB\nEstimated relay USD/hour: ${cost?.let { String.format(Locale.US, "%.4f", it) } ?: "N/A"}\n" +
            "Estimated - excludes protocol/provider billing differences. Sender RTP only; do not add receiver bytes.\n" +
            "Relay protocol local / remote: $relayProtocol\nAddress family local / remote: $addressFamily\n" +
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
        credentialCall?.cancel(); credentialCall = null
        networkConfig = null; iceServers = emptyList()
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
        WebRtcState.set(WebRtcUi(true, "$reason — session summary (final or latest stats sample)", snapshot))
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

