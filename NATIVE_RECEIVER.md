# Native Android receiver — technical step 1

Baseline: Android main f8e9ebc. Browser and relay runtime unchanged. No new dependency.
This is a local implementation, NOT a two-physical-device PASS until the matrix below
has been executed. No attached ADB device was available during development.

## Audit and isolation

Existing MainActivity / PairingHost / AutoPairing own sender registration and consent.
AutoPairing is intentionally sender-specific: peer-present -> CONSENT -> WebRtcService
attach -> sender offer. It is not reused as a receiver controller. WebRtcService,
MediaProjection, ScreenCapturerAndroid, resize, video profile, STUN, remote browser
link and sender offer/answer code are untouched. The only sender screen change is
an isolated Receive screen entry available when capture is idle and pairing editable.

MainActivity Receive screen -> hides/closes sender waiting registration -> separate
ReceiverActivity. Its ViewModel owns ReceiverEngine, ReceiverSignaling and one
WebRtcReceiverSession per accepted offer. No sender waiting socket runs in parallel.
Back/Stop receiving finishes ReceiverActivity; MainActivity.onStart resumes normal
sender auto-CREATE with a new code. Background receiving is not supported: onStop
ends the receiver except during a configuration recreation. On return from background,
Retry receiving explicitly starts a fresh receiver attempt.

## Existing wire protocol, both orders

/ws?session=<CODE>&role=receiver&intent=create|join

Receiver creates automatically, shows the existing secure six-character code and
locally generated QR. Sender scans/enters it using its unchanged UI. Receiver can also
enter a sender-created code and press Join. Receiver code entry/scanner automation
is not part of the final SEND/RECEIVE UX; no new receiver camera/scanner is added.
The QR remains the existing HTTPS ?session=<CODE> URL; the sharing app extracts it.
Persistent #device browser links are unaffected and are not app-to-app links.

Relay already validates CREATE conflicts, JOIN existence/opposite role, emits
registration then peer-present to both clients, and sends peer-left/closes the
other socket on paired disconnect. Thus no new relay event/protocol is needed.
Signaling creator is independent of media role. Receiver peer-present only enters
CONNECTING; it never creates an offer. Sender always asks fresh system consent and
is the offerer. Receiver:

incoming offer -> setRemoteDescription(OFFER) success -> drain early ICE ->
createAnswer -> setLocalDescription(ANSWER) success -> send {type:answer,sdp}.

Local trickle ICE is {type:ice,candidate:{candidate,sdpMid,sdpMLineIndex}}.
Remote ICE is queued (maximum 256) until setRemoteDescription succeeds, including
ICE arriving before the offer. Duplicate offers are ignored; no renegotiation or
ICE restart is introduced. Signaling JSON is bounded below 64 KiB. No SDP, raw
ICE, code, URL, QR payload or persistent token is logged or put into diagnostics.
Receiver never sends a persistent token or advertises remote presence.

## Native SDK and renderer

Existing community prebuilt io.github.webrtc-sdk:android:150.7871.01 is retained.
The actual cached AAR API was inspected with javap: PeerConnection.Observer supports
onTrack(RtpTransceiver), transceiver.receiver.track() provides VideoTrack, and
SurfaceViewRenderer supports RendererEvents.onFirstFrameRendered and
onFrameResolutionChanged(width,height,rotation). No guessed SDK API is used.
Upstream reference: https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/SurfaceViewRenderer.java

WebRtcReceiverSession uses Unified Plan, iceTransportsType ALL, the existing
stun:stun.l.google.com:19302, DefaultVideoDecoderFactory and no local tracks.
Audio offers are rejected; there is no microphone/camera permission, capture,
MediaProjection, service, WebView, bitmap reconstruction or transcoding.
Codec negotiation is unchanged; no new codec is forced. Hardware decoder factory
is enabled without claiming any particular session actually uses hardware decoding.

The session owns EglBase + factory + peer. SurfaceViewRenderer is initialized from
that EGL context, mirror false, hardware scaling enabled, SCALE_ASPECT_FIT. Track
addSink/removeSink attach/detach the view. A real first-rendered-frame callback
changes CONNECTING -> RECEIVING, not a connected ICE/PC event. The surface stays
available behind opaque pairing/connecting UI so rendering is not deadlocked by
hiding a SurfaceView before its first frame. Pairing controls are removed from
composition while receiving. KEEP_SCREEN_ON is set only while receiving and cleared
on state changes/background/exit.

Sender frame size changes use the same track/peer; the renderer adjusts its own
layout. The resolution diagnostic uses onFrameResolutionChanged with rotation
applied, for example 720 x 1560 -> 1560 x 720 -> 720 x 1560. No portrait fixed ratio,
crop, rotation hack, new consent or renegotiation is needed.

Receiver device rotation uses the standard retained ViewModel, not configChanges.
The old Activity detaches/releases its renderer in onDestroy; the replacement view
initializes from the SAME session EGL and reattaches the SAME remote VideoTrack.
The ViewModel has only a weak view reference; native sink ownership is explicitly
removed on detach. PC/WebSocket stay alive on configuration recreation. Other
onStop cases end the session. Multi-window/system lifecycle details still need
physical testing; foreground-only means a stopped receiver is intentionally ended.

## End, retry and race guards

PAIRING -> peer-present -> CONNECTING -> first rendered frame -> RECEIVING.
peer-left/bye/socket end -> ENDED. SDP/network failure -> ERROR.
ENDED/ERROR -> Retry receiving -> fresh CREATE, code, socket and subsequent peer.
Stop/Back -> cleanup -> sender home. Sender consent Cancel is detected through
existing relay peer-left/disconnect and ends the receiver, including before offer.

Registration timeout 15 seconds; post-peer first-frame timeout 60 seconds.
Waiting CREATE rotation 5 minutes, only before peer-present. CREATE collision
uses the existing PairingRegistration maximum four attempts.
Disconnected has one 4-second grace timer; connected/completed cancels it,
failed/closed ends immediately. Temporary disconnection keeps receiving UI/frame.

All app control and native/network callbacks are serialized on the main Handler.
ReceiverEngine generations guard every retired wire/media callback. Native session
closed and renderer-generation guards protect queued track/frame/SDP/stats callbacks.
Cleanup is idempotent and invalidates state before closing socket/media; timers,
ICE queue, pending SDP flags and registration are cleared. Renderer sink is removed,
image cleared, renderer released, peer closed/disposed, factory disposed, then EGL
released. A remote track is owned by the peer and is not separately double-disposed.
Final getStats is never awaited. The last periodic diagnostic snapshot may remain
on the ended screen; a new attempt clears it. No active counters/timers survive.

## Diagnostics and verification

About once per second, with one outstanding getStats request maximum:
connection/ICE/signaling states, current rendered dimensions, frames received,
frames decoded/dropped, approximate decoded FPS (counter delta), RTP bytes received,
inbound bitrate (byte delta), negotiated codec, selected local/remote candidate
types, protocol and current RTT. Missing stats are N/A. First delta needs two samples.
Candidate pair selection uses transport selectedCandidatePairId first, then explicit
selected, then one unambiguous nominated succeeded pair. Addresses/SDP/URLs are not
shown. Received RTP bytes exclude transport/provider overhead.

Run from ui-capture-test with the configured Android JDK/SDK:
./gradlew.bat :app:assembleDebug testDebugUnitTest lintDebug
ReceiverEngineTest covers fixed receiver role, CREATE/JOIN, never offer on peer-present,
answer flow, queued ICE, duplicate offer, generation guards for late native/socket
callbacks, fresh peer after stop, idempotent cleanup, Cancel/end/retry, sender restart,
collision/rotation, disconnect grace and no capture/logging code path. Existing
AutoPairing, CaptureDimensions and RemoteDevice tests must also pass.
These JVM tests do not simulate real Android system UI, renderer EGL operations,
ViewModel recreation or actual decoded frames. Those require the matrix below.
Run all existing web verifies and all five local relay verifies; do not run the
public remote signaling test or deploy anything automatically.

## Two-Android physical validation (pending)

Use two Android 35+ devices with internet/STUN-reachable networks; install the same
app/build/outputs/apk/debug/app-debug.apk on both. Call them A (sender), B (receiver).
Begin on the same Wi-Fi, then optionally repeat on separate internet networks.

1. B normal home -> Receive screen. B shows receiver code/QR; old sender code expires.
   A scans B QR or enters B code. Only A shows MediaProjection chooser. Choose Full
   screen. B must show A's live screen in the native renderer, no browser/WebView.
   Record selected candidate types/protocol/codec/FPS/bitrate/bytes/RTT on B.
2. In A portrait, record B Current received video exact width x height. Rotate A
   landscape then portrait repeatedly; record all three dimensions, visually check
   contain/no crop/no stretch. Verify no new consent, socket, pairing or peer.
3. While receiving, rotate B itself both ways. Video continues; no new code/connection.
   Check first-frame/renderer recovery, diagnostics, no EGL crash and aspect fit.
4. A Stop sharing: B must hide video and show ended state. B Retry receiving -> fresh
   code. A joins again. Repeat once with Single app, rotating a rotatable captured app.
5. During a new stream, B Stop receiving or system Back: B returns to sender home,
   new sender QR/code appears; A handles peer loss. Confirm old code is expired.
6. B receiver waiting, A joins, A cancels consent. B must leave Connecting for ended.
   B Retry and A join/Allow must work without restarting either app. Repeat 3 Cancels.
7. Repeat start/receive/stop/new receiver at least 5 times, including brief background
   and return. Background ends receiver; Retry creates a fresh attempt. Watch crashes,
   stale surfaces, lingering sockets, memory/latency/FPS degradation.
8. Optional symmetric order: A own sender CREATE, B Receive screen -> enter A code ->
   Join. Confirm A still offerer/consent owner, B answerer, stream starts.
9. Brief network interruption below 4 s: if recoverable, receiving UI does not reset.
   Sustained interruption/failed state: ended/error and fresh Retry must work.
10. Android A -> existing browser receiver: recheck QR/code, portrait/landscape,
    Cancel/retry and Stop/new pairing. Open existing private #device link too;
    resolve, consent, receiving, Stop/Try again must be unchanged.
11. Keep an actual stream for at least 2 minutes; record dimensions/FPS/dropped
    frames/bytes/bitrate/candidate pair/RTT and visual smoothness. No physical PASS
    may be claimed from compilation or emulator smoke tests alone.

No commit, push, deploy, archive branch or experiments/poc4b change is authorized.

## Local verification result — 2026-10-17

- assembleDebug: PASS; debug APK: app/build/outputs/apk/debug/app-debug.apk.
- testDebugUnitTest: 31 tests, 0 failures (8 new ReceiverEngine tests).
- lintDebug: PASS, 0 errors / 25 existing warnings; no new receiver warning.
- Web: verify, verify-webrtc, verify-pairing, verify-qr-pairing,
  verify-cancel-retry, verify-auto-pairing, verify-receiver-state,
  verify-cache-busting, verify-orientation, verify-remote-link all PASS.
- Relay: verify-signaling, verify-pairing-privacy, verify-symmetric-pairing,
  verify-join-limit, verify-remote-presence all PASS against local test servers.
- ADB device list empty; physical decoding, EGL recreation and two-Android P2P
  validation remain pending. No emulator smoke test was claimed/performed.
- Actual SDK bytecode audit also confirmed SurfaceEglRenderer reports the first
  frame on frame delivery before delegating to EglRenderer. The callback proves
  a renderable remote frame arrived, not the physical display presentation time.
  Physical visual validation is therefore required even with this first-frame gate.
- Modified: AndroidManifest.xml, MainActivity.kt.
- Added: ReceiverActivity.kt, ReceiverViewModel.kt, ReceiverEngine.kt,
  ReceiverRegistration.kt, ReceiverSignaling.kt, WebRtcReceiverSession.kt,
  ReceiverEngineTest.kt, this document.
- Git: all repos on main; Android has 2 modified + 8 untracked files listed above.
  Web and relay working trees clean. Experiments/archive untouched. No commit,
  push, deployment or public remote-signaling test was performed.
