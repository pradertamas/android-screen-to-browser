# Dynamic captured-content dimensions

## Audit: source evidence, not a new physical measurement

Before this change WebRtcService started ScreenCapturerAndroid at 720 x 1560,
adapted VideoSource to that profile, and passed a MediaProjection.Callback that
only implemented onStop. There was no changeCaptureFormat, captured-content
resize handler, display/configuration/orientation listener or dynamic output
size. Therefore the app never requested a VirtualDisplay/texture resize when
the captured content changed. The old adaptation aspect request could also crop
other content shapes even after only the capturer was fixed.

The actual cached io.github.webrtc-sdk:android:150.7871.01 AAR was inspected via
javap, not inferred from an arbitrary upstream revision. startCapture registers
the supplied callback on SurfaceTextureHelper.getHandler(). changeCaptureFormat
updates stored width/height and synchronously marshals updateVirtualDisplay to
that handler. updateVirtualDisplay sets the texture size and, on API >= 31 with
an existing display, uses VirtualDisplay.resize and setSurface instead of
creating another display/projection. minSdk 35, target/compile SDK 37 make this
the relevant branch. No dependency or SDK change is needed.

VideoSource's three-int adaptOutputFormat overload builds orientation-aware
aspect requests; it does not resize the capture display itself. It is updated
with each new output shape so its old 19.5:9 crop request is not retained.

The user's prior physical PoC established portrait 720 x 1560. No current device
portrait/landscape encoder dimensions, browser videoWidth/videoHeight or inbound
stats were measured during this local task. In particular, landscape-before-fix
must remain N/A until measured; a static 720 x 1560 display is source evidence,
not an invented physical stats sample.

## Implementation

MediaProjection.Callback.onCapturedContentResize (API 34, below minSdk 35) gives
the actual captured region, including single-app resizing, not just device
orientation or physical display size. Events post from libwebrtc's texture
handler to the existing WebRtcControl handler. They never synchronously wait for
that control thread from inside the callback. The same capturer/projection,
track, peer and signaling registration remain alive.

CaptureDimensions.fit chooses 720 x 1560 for width <= height, otherwise
1560 x 720. scale = min(1, boxWidth/inputWidth, boxHeight/inputHeight).
Both scaled dimensions round down to the nearest even integer. This preserves
aspect to sub-two-pixel quantization, fits the box and original input, and stays
within 1,123,200 pixels. Invalid/too-small dimensions (including extreme aspect
ratios that cannot yield two even pixels on both axes) are ignored.

Examples: 1080 x 2340 -> 720 x 1560; 2340 x 1080 -> 1560 x 720;
1920 x 1080 -> 1280 x 720; 1080 x 1920 -> 720 x 1280;
1200 x 1200 -> 720 x 720; 640 x 360 remains 640 x 360.

CaptureResizeController coalesces to the latest valid size over 100 ms. Equal
current output does nothing, including cancelling an obsolete queued resize.
The control thread updates VideoSource adaptation and calls the existing
ScreenCapturerAndroid.changeCaptureFormat(w,h,30). Same track; no new offer,
renegotiation, consent, PeerConnection or display session is requested.
2 Mbps encoding cap, 30 FPS target, codec negotiation and video-only stay intact.

Stop serializes with resize, cancels delayed work and clears current content/
output. Controller generation checks reject old queued tasks even after restart.
Each service instance owns its controller and handler. An atomic projection-stop
flag also rejects callbacks before the queued onStop cleanup runs. Last content/
output are snapshotted for the retained summary separately from active state.

Android diagnostics: Captured content, Capture output, Encoded resolution, bounds.
Browser diagnostics: Current received video from the actual HTML video element,
plus existing inbound Received width/height. Updated by the existing ~1s stats
loop. Dimensions alone never change the receiver view. Existing contain/full-size
CSS handles both orientations and is unchanged. Cache mechanism is unchanged;
the web release version is bumped for the changed diagnostics asset.

## Validation

CaptureDimensionsTest covers profiles, 16:9, square, small/odd/invalid input,
even dimensions, bounds/aspect tolerance, duplicate output, rapid coalescing,
return-to-current, Stop, stale queued tasks/restart and Stop during apply.
The web verify-orientation.cjs covers changing DOM/RTP resolution on the same
peer/socket while staying STREAMING, missing-stats fallback and responsive CSS.
Automated checks do not prove actual Samsung encoder or browser dimensions.

Physical retest (record real values, do not prefill them with target values):

1. Full-screen portrait: record Android content/output/encoded size and browser
   Current received video plus inbound width/height, codec, FPS, dropped frames,
   bitrate and RTT. Check large uncropped portrait picture.
2. Rotate to landscape while streaming: record the same fields; expect landscape
   actual frame dimensions, no new consent/pairing/reconnect and correct large
   landscape display. A brief transition frame drop is acceptable.
3. Rotate back: record real portrait dimensions; confirm same peer connection.
4. Repeat portrait -> landscape -> portrait -> landscape -> portrait, watching
   latency, connection state/FPS and old-resolution leaks over at least 2 minutes.
5. Fresh single-app consent with a rotation-capable app: repeat and verify that
   captured-app bounds, including other shapes/split-window if supported, drive
   the result. Do not assume single-app follows the full physical display.
6. Stop in landscape: no delayed resize/capture notification; new pairing works.
7. Cancel fresh consent then pair/Allow again without page reload.
8. Save portrait / landscape / portrait-again videoWidth x videoHeight and
   Android encoded sizes in the physical report; mark any unavailable field N/A.

Official references:
https://developer.android.com/reference/android/media/projection/MediaProjection.Callback#onCapturedContentResize(int,%20int)
https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/ScreenCapturerAndroid.java

No commit/push/deploy. Relay and experiments/archive are untouched.
