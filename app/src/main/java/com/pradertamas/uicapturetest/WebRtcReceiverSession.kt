package com.pradertamas.uicapturetest

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.webrtc.*
import java.util.Locale

/** Main-thread owner. No MediaProjection, capture service, local track, audio or createOffer API. */
class WebRtcReceiverSession(context: Context, private val candidate: (ReceiverIce) -> Unit,
    private val connection: (String) -> Unit, private val failed: () -> Unit,
    private val firstFrame: () -> Unit, private val dimensions: (Int, Int) -> Unit,
    private val diagnostics: (String) -> Unit) : ReceiverMedia {
    private val main = Handler(Looper.getMainLooper())
    private val egl = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var track: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private var rendererEpoch = 0L
    private var closed = false
    private var statsPending = false
    private var previousTime = SystemClock.elapsedRealtime()
    private var previousBytes: Double? = null
    private var previousFrames: Double? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            val pc = peer
            if (pc != null && !statsPending) {
                statsPending = true
                pc.getStats { report -> post { statsPending = false; publish(report) } }
            }
            main.postDelayed(this, 1000)
        }
    }
    private fun post(action: () -> Unit) { main.post { if (!closed) try { action() } catch (_: Exception) { failed() } } }
    init {
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
            factory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
            val config = PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder(WebRtcConfig.STUN).createIceServer())).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = PeerConnection.IceTransportsType.ALL
            }
            peer = checkNotNull(factory!!.createPeerConnection(config, object : PeerConnection.Observer {
                override fun onSignalingChange(s: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { post { connection(s.name) } }
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { post { connection(s.name) } }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
                override fun onIceCandidate(c: IceCandidate) { post { candidate(ReceiverIce(c.sdpMid, c.sdpMLineIndex, c.sdp)) } }
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
                override fun onAddStream(s: MediaStream) {}
                override fun onRemoveStream(s: MediaStream) {}
                override fun onDataChannel(d: DataChannel) { d.close() }
                override fun onRenegotiationNeeded() {} // Deliberately answerer only.
                override fun onTrack(transceiver: RtpTransceiver) {
                    val video = transceiver.receiver.track() as? VideoTrack ?: return
                    post {
                        if (track !== video) {
                            renderer?.let { track?.removeSink(it) }
                            track = video
                            renderer?.let(video::addSink)
                        }
                    }
                }
                override fun onRemoveTrack(receiver: RtpReceiver) { post { failed() } }
            }))
            main.post(ticker)
        } catch (e: Exception) { close(); throw e }
    }
    fun attach(view: SurfaceViewRenderer) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || renderer === view) return
        detach()
        val epoch = ++rendererEpoch
        renderer = view
        view.init(egl.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() { post { if (rendererEpoch == epoch && renderer === view) firstFrame() } }
            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) { post {
                if (rendererEpoch == epoch && renderer === view) {
                    if (rotation % 180 == 0) dimensions(width, height) else dimensions(height, width)
                }
            } }
        })
        view.setMirror(false)
        view.setEnableHardwareScaler(true)
        view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        track?.addSink(view)
    }
    fun detach(expected: SurfaceViewRenderer? = renderer) {
        if (renderer !== expected) return
        rendererEpoch++
        val view = renderer; renderer = null
        if (view != null) {
            track?.removeSink(view)
            view.clearImage(); view.release()
        }
    }
    private fun observer(failure: () -> Unit, created: (SessionDescription) -> Unit = {}, set: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { post { created(sdp) } }
        override fun onSetSuccess() { post(set) }
        override fun onCreateFailure(error: String) { post(failure) }
        override fun onSetFailure(error: String) { post(failure) }
    }
    override fun offer(sdp: String, remoteReady: () -> Unit, answer: (String) -> Unit, failure: () -> Unit) {
        if (closed) return
        check(!MediaRole.RECEIVE.offerer && !MediaRole.RECEIVE.captureAllowed)
        if (sdp.lineSequence().any { it.startsWith("m=audio ") }) { failure(); return }
        peer!!.setRemoteDescription(observer(failure, set = {
            remoteReady()
            if (!closed) peer!!.createAnswer(observer(failure, created = { description ->
                peer!!.setLocalDescription(observer(failure, set = { answer(description.description) }), description)
            }), MediaConstraints())
        }), SessionDescription(SessionDescription.Type.OFFER, sdp))
    }
    override fun ice(candidate: ReceiverIce) {
        if (!closed) peer?.addIceCandidate(IceCandidate(candidate.mid, candidate.line, candidate.candidate))
    }
    private fun publish(report: RTCStatsReport) {
        val all = report.statsMap.values
        val inbound = all.firstOrNull { it.type == "inbound-rtp" && (it.members["kind"] == "video" || it.members["mediaType"] == "video") }?.members.orEmpty()
        fun number(key: String) = (inbound[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }
        val bytes = number("bytesReceived"); val frames = number("framesDecoded")
        val now = SystemClock.elapsedRealtime(); val seconds = (now - previousTime) / 1000.0
        fun delta(value: Double?, previous: Double?, multiplier: Double = 1.0) =
            if (value != null && previous != null && seconds > 0) fmt((value - previous).coerceAtLeast(0.0) * multiplier / seconds) else "N/A"
        val transport = all.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
        val nominated = all.filter { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
        val pair = report.statsMap[transport?.members?.get("selectedCandidatePairId") as? String]
            ?: all.firstOrNull { it.type == "candidate-pair" && it.members["selected"] == true } ?: nominated.singleOrNull()
        val local = report.statsMap[pair?.members?.get("localCandidateId") as? String]?.members.orEmpty()
        val remote = report.statsMap[pair?.members?.get("remoteCandidateId") as? String]?.members.orEmpty()
        fun type(value: Any?) = value?.toString()?.takeIf { it in setOf("host", "srflx", "prflx", "relay") } ?: "N/A"
        val protocol = local["protocol"]?.toString()?.lowercase(Locale.ROOT)?.takeIf { it in setOf("udp", "tcp") } ?: "N/A"
        val codec = report.statsMap[inbound["codecId"] as? String]?.members?.get("mimeType")?.toString()
            ?.takeIf { it in setOf("video/VP8", "video/VP9", "video/H264", "video/AV1", "video/H265") } ?: "N/A"
        val rtt = (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()?.times(1000)
        diagnostics("Peer: ${peer?.connectionState()} / ICE: ${peer?.iceConnectionState()} / Signaling: ${peer?.signalingState()}\n" +
            "Frames received: ${number("framesReceived")?.let(::fmt) ?: "N/A"} / decoded: ${frames?.let(::fmt) ?: "N/A"}\n" +
            "Frames dropped: ${number("framesDropped")?.let(::fmt) ?: "N/A"}\nApprox decoded FPS: ${delta(frames, previousFrames)}\n" +
            "Bytes received: ${bytes?.let(::fmt) ?: "N/A"}\nInbound bitrate: ${delta(bytes, previousBytes, 8.0)} bps\n" +
            "Codec: $codec\nCandidate local / remote: ${type(local["candidateType"])} / ${type(remote["candidateType"])}\n" +
            "Protocol: $protocol / RTT: ${rtt?.let(::fmt) ?: "N/A"} ms")
        previousTime = now; previousBytes = bytes; previousFrames = frames
    }
    private fun fmt(value: Double) = String.format(Locale.US, "%.2f", value)
    override fun close() {
        if (closed) return
        closed = true; main.removeCallbacksAndMessages(null)
        runCatching { detach() }; track = null // Remote track belongs to the PeerConnection.
        val pc = peer; peer = null
        runCatching { pc?.close() }; runCatching { pc?.dispose() }
        runCatching { factory?.dispose() }; factory = null
        runCatching { egl.release() } // Renderer and decoder/factory are already gone. Never await final getStats.
    }
}
