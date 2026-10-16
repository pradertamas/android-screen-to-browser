# PoC 4A — Android screen → WebRTC P2P → browser

Historical implementation report. The user has since confirmed a physical
internet P2P PASS (124.15 s, VP8, 720x1560, srflx/srflx, no dropped frames on
the browser). The two-way relay was deployed separately at `96217dc`.
Pre-deployment/unverified statements below refer to that earlier stage.
Current AUTO/FORCE TURN development, credentials, metrics and test steps are
archived in [experiments/poc4b/POC4B.md](experiments/poc4b/POC4B.md).
The active P2P pairing release is documented in [PAIRING.md](PAIRING.md).

## Status and repository boundaries

Implementation and automated checks are complete; physical two-minute PASS has
NOT been established. No commit, push or deployment is part of this work.
All three projects remain in their original directories. No Git configuration
or repository structure was changed. `ui-capture-test` had no `.git` directory
at audit time; it was deliberately not initialized. The two other repositories
were inspected with per-command safe.directory overrides, not global config.

**Prerequisite:** the existing Render relay only forwards sender → receiver.
The local relay patch enables both directions. An operator must manually update
the deployed relay before this PoC works with the configured public WSS URL.
The existing one-way deployment cannot carry browser answers/ICE. Nothing here
has been deployed. Render cold starts can delay signaling; retry manually.

## Audit before implementation

- Android: AGP 9.4.1, Kotlin/Compose plugin 2.2.10, Compose BOM 2026.02.01,
  minSdk 35, compile/targetSdk 37, Java source/target 11, Gradle 9.6.0,
  configured daemon JDK 25. Maven Central already configured. No WebRTC or
  WebSocket client dependency existed. Versions and SDK settings are preserved.
- Manifest already had both foreground service permissions and a non-exported
  mediaProjection CaptureService. Added Internet/network-state permissions and
  a second, non-exported mediaProjection service.
- PoC 3B: MainActivity launches fresh capture consent; CaptureService starts
  foreground before getMediaProjection, registers a callback, owns its
  VirtualDisplay/ImageReader on a handler thread, and cleans them on stop.
  Its image sampling, metrics and PNG-save implementation are unchanged.
  The two test modes are mutually exclusive in the UI. Each has its own fresh
  consent result, service and capture resources. No token reuse.
- Web: dependency-free static pages; common.js handles the old WSS sample flow.
  Its session validator permits 1–64 characters, while the server requires 6–64.
  The new receiver independently enforces 6–64; old pages/common.js are unchanged.
  `node verify.cjs` is the existing deterministic/static verification entrypoint.
- Relay: registration is the WebSocket upgrade URL `/ws?session=...&role=...`;
  no registration JSON. Roles sender/receiver, one peer per role per session,
  409 duplicate role, 400 invalid identifier. Payload bytes/text-vs-binary are
  opaque. 64 KiB incoming limit, 1 MiB output buffer limit, 30 s heartbeat,
  missing opposite peer means drop, no history. Original receiver writes were
  ignored, so bidirectional forwarding was necessary. Limits are unchanged.

## Dependencies and native capture

`io.github.webrtc-sdk:android:150.7871.01` is a **community-maintained prebuilt
libwebrtc artifact**, not an official Google Android SDK distribution.
The requested version is published on Maven Central and provides org.webrtc,
ScreenCapturerAndroid and EGL encoder factories. The AAR was resolved and built
against the existing project configuration without changing toolchain versions.

Sources:
- https://github.com/webrtc-sdk/android
- https://central.sonatype.com/artifact/io.github.webrtc-sdk/android/150.7871.01
- https://github.com/webrtc-sdk/webrtc/blob/m150_release/sdk/android/api/org/webrtc/ScreenCapturerAndroid.java

`com.squareup.okhttp3:okhttp:4.12.0` supplies the WSS signaling client only.
Video path: MediaProjection → VirtualDisplay → SurfaceTextureHelper texture →
VideoSource → encoder → RTP/SRTP → browser video track. No Bitmap/ImageReader,
CPU frame copy or JPEG/PNG path is used by the WebRTC service.

Profile: portrait 720 × 1560, VideoSource adapts to 30 FPS; sender encoding caps
30 FPS and 2,000,000 bps. ScreenCapturerAndroid itself ignores its FPS argument.
No audio track is created. Codec is negotiated with default capabilities.
DefaultVideoEncoderFactory uses EGL and requests the hardware encoder path,
but actual selection is device/codec dependent. Encoder implementation and
powerEfficientEncoder are shown when libwebrtc exposes them; otherwise N/A.
Do not infer hardware encoding from merely having configured this factory.
Keep the phone portrait for this fixed-profile PoC; rotation/app-window content
may be scaled or letterboxed by Android. Secure content protection is unchanged.

## Signaling protocol

Endpoint: `wss://realtime-relay-k28l.onrender.com/ws`.
Android uses role `sender`, browser role `receiver`, with identical random
6–64-character ASCII letter/digit/underscore/hyphen session IDs.

1. Open browser receiver first; connect with the chosen ID.
2. Browser sends `{"type":"ready"}` every second until an offer arrives.
3. Android opens WSS after fresh consent/capture setup and also sends ready.
   Browser replies ready. Android produces only one offer per capture session.
4. Android sends `{"type":"offer","sdp":"..."}` after setLocalDescription.
5. Browser sets remote SDP, creates/sets answer and sends
   `{"type":"answer","sdp":"..."}`.
6. Both peers trickle `{"type":"ice","candidate":{"candidate":"...",
   "sdpMid":"0","sdpMLineIndex":0}}`. Candidates queue until remote SDP exists.
   Browser serializes async signaling operations to prevent SDP/ICE races.
7. Stop sends `{"type":"bye"}` best effort. Both endpoints close resources.
   Socket errors/closure stop the session. Recovery is manual and always requires
   a new Android consent; there is no token replay or automatic ICE restart.

Each message stays below 64 KiB. There are no WebSocket video payloads, media
chunks, data channels or server-side media processing. Relay stays opaque.

ICE config is isolated in `WebRtcConfig` and `RTC_CONFIG` in the receiver:
`stun:stun.l.google.com:19302`, no TURN, policy ALL/all. LAN host candidates
are expected but not guaranteed. Selected transport candidate pair stats report
local/remote type, protocol, state and RTT. A succeeded host/srflx/prflx pair,
no relay candidate and increasing decoded video counters demonstrate the direct
media path. Public STUN discovery is not media relay. Some LANs use AP isolation;
without TURN there is intentionally no fallback in that situation.

## Metrics and summaries

Android polls stats about once per second and updates StateFlow/Compose only
at that cadence. Reports peer/ICE/signaling states, negotiated codec, configured
capture and actual encoded resolution, captured/encoded/sent frames, packets,
bytes, delta bitrate, full-session average/peak bitrate, selected local/remote
candidate type, protocol, pair state and RTT. Encoder details are best effort.
Stop requests final stats with a 750 ms timeout before cleanup; on timeout the
last sample is retained. Summary retains duration, bytes, average/peak bitrate,
frames sent, average FPS, codec/resolution, pair types/protocol, mean/max RTT.

Browser polls getStats once per second without overlapping requests. Shows
connection/ICE/signaling states, selected pair and RTT; codec, dimensions,
received/decoded FPS deltas, browser framesPerSecond, received/decoded/dropped
frames, keyframes, packets received/lost; cumulative bytes, current/average/peak
bitrate and available incoming/outgoing bitrate. Missing optional stats are N/A.
Disconnect takes a final stats sample and keeps a selectable session summary,
including frames dropped, mean decoded FPS and estimated timing mean/max.

Rates and average FPS use full session duration, including signaling/setup and
quiet periods. Peak bitrate is the maximum sampled approximately-one-second
rate, not an instantaneous wire peak. Byte counters are WebRTC RTP statistics,
not a network-interface billable total; SRTP/IP/transport overhead is not fully
represented. RTT mean/max are sampled statistics, not a packet-level aggregate.
Final native counter sample can be up to the stats timeout/previous sample old.

Estimated WebRTC frame timing uses requestVideoFrameCallback metadata only:
receiveTime − captureTime, expectedDisplayTime − receiveTime,
expectedDisplayTime − captureTime. Each has current/mean/max; presentationTime
is also displayed if exposed. Missing, invalid or negative samples become N/A.
Remote captureTime depends on browser clock mapping and RTP/RTCP timestamp
availability. Expected display is predicted presentation, not a physical panel
measurement. No laboratory glass-to-glass accuracy is claimed. Hidden tabs,
scheduling, browser support and stream timestamps affect availability/accuracy.

Cleanup closes WSS/PeerConnection, stops and disposes capturer (which unregisters
MediaProjection.Callback, releases VirtualDisplay and stops projection), then
disposes track/source, SurfaceTextureHelper, factory, EGL and control thread.
System projection revocation also stops the entire session. START_NOT_STICKY
prevents service restart with stale consent.

## Automated verification

From ui-capture-test (JDK and a writable Gradle home may need local setup):

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$env:GRADLE_USER_HOME = 'C:/Sajat/Android_Screen_to_WebPage/.gradle-poc4a'
./gradlew.bat :app:assembleDebug testDebugUnitTest lintDebug --console=plain
```

From realtime-web-test:

```text
node --check webrtc-receiver.js
node verify.cjs
node verify-webrtc.cjs
```

From realtime-relay:

```text
node --check server.js
node verify-signaling.cjs
```

The WebRTC checks use fake browser APIs to verify signaling order, early ICE,
missing stats, bitrate, pair selection, timing, stop/reconnect and static links.
They do not prove real SDP interoperability, encoder performance or P2P video.
The relay test starts an isolated local process and checks both directions,
byte preservation, session isolation, role validation and payload limits.

## Physical test — Samsung SM-S931B + desktop

1. Manually arrange the relay update first (not performed here). Without the
   bidirectional patch, ready/answer/ICE from the browser cannot reach Android.
2. Connect the Samsung (Android 17 / One UI 9) and desktop to the same LAN.
   Avoid guest-network client isolation. Keep the phone unlocked and portrait.
3. Build/install `app/build/outputs/apk/debug/app-debug.apk` using Android Studio
   or `adb install -r app/build/outputs/apk/debug/app-debug.apk` from Android root.
4. Serve realtime-web-test as static files, e.g. from that directory:
   `npx --yes http-server . -p 8080 -c-1`.
   Open `http://localhost:8080/webrtc-receiver.html` in desktop Chrome/Edge/Firefox.
   The same file works on existing HTTPS GitHub Pages once manually published.
5. Choose a fresh unpredictable session ID (6–64 allowed characters), enter it
   on both endpoints. Browser: Connect, wait for signaling-open status.
6. Android: stop PoC 3B if running; use the separate POC 4A section, Start WebRTC
   share, grant NEW screen-capture consent. Select entire screen for the first
   test. Switch to harmless moving screen content; do not use secure content.
7. Confirm moving Android pixels in the standard video element, connected peer,
   completed/connected ICE and succeeded selected pair. If autoplay blocks,
   press Play video. Verify codec, dimensions, actual FPS/bitrate and increasing
   byte/frame counters. Hardware details may remain N/A.
8. Start an external stopwatch once live video and connection are confirmed.
   Keep streaming at least 120 seconds. Interact locally with the phone to
   produce visual changes; no remote input/control exists. Record freezes,
   dropped frames, bitrate and RTT. Static screens may naturally yield lower FPS.
9. Stop Android share. Browser should stop through bye (or disconnect manually).
   Keep/copy both session summaries. Record full-session metrics separately
   from the external 120-second connected-media observation. Confirm camera/
   capture indicator and foreground notification disappear.
10. Restart receiver and Android with fresh consent for another session. Test
    Android system capture-stop, browser Disconnect, denied consent and PoC 3B
    capture/sample save separately to check lifecycle and mode separation.

PASS only if moving Android screen video stays stable for >=120 connected-media
seconds through direct WebRTC P2P. Record resolution, actual FPS/bitrate/bytes,
codec, local+remote candidate types, protocol, RTT and dropped frames. Estimated
frame timing is optional and N/A is valid if the browser lacks timestamps.
No physical PASS is implied by the automated tests. PoC 4B starts only after
this test passes; TURN is deliberately absent here.

Recorded verification on 2026-10-16: Android assembleDebug, testDebugUnitTest
(4 tests, zero failures/errors), lintDebug succeeded (0 errors, 21 warnings;
existing resource/style/dependency advice and new dependency catalog/version
advice). Web existing 38 deterministic checks and the new WebRTC verification
passed; all JS syntax checks passed. Local relay integration verification passed.
Build log: workspace-root android-check.log. Lint report:
app/build/reports/lint-results-debug.html. Physical device/browser test not run.
