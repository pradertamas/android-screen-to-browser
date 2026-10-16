# Symmetric pairing initiation, fixed media roles

Local implementation only. No commit, push, deployment or physical PASS claim.
The previously reported physical P2P release is the baseline. This revision needs
coordinated manual relay, Android and static receiver updates before the four
new physical flows can be evaluated. experiments/poc4b/ is untouched/excluded.

## Invariants

Android is always the screen sender and sole SDP offerer. Browser is always the
video receiver and SDP answerer. CREATE/JOIN describes who initiates pairing,
not media direction. Neither scanner's camera is attached to WebRTC. Camera
frames, QR payloads and session codes are not uploaded or application-logged.
The only network use of a parsed code is the signaling session registration.

Media profile and encoder path are unchanged: native ScreenCapturerAndroid /
SurfaceTexture / EGL encoder factory, 720x1560 portrait, target 30 FPS, max
2,000,000 bps, default codec negotiation (VP8 proven), video only. No TURN,
Cloudflare, audio, recording, remote input or server-side video processing.

## Registration protocol

The existing `/ws` query registration gains one explicit parameter:

```text
/ws?session=<CODE>&role=sender&intent=create
/ws?session=<CODE>&role=receiver&intent=join

/ws?session=<CODE>&role=receiver&intent=create
/ws?session=<CODE>&role=sender&intent=join
```

The relay validates the same ID alphabet/length (6-64 ASCII letters/digits/_/-)
and role; production pairing clients further restrict codes to six characters
from `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`. Exactly one intent parameter is allowed.
Malformed registrations fail HTTP 400. Syntactically valid explicit requests
upgrade, then receive a JSON result:

```json
{"type":"registration","status":"ok","intent":"create","role":"receiver"}
```

Other status values: `conflict`, `not-found`, `role-occupied`. Rejected explicit
clients receive that result and close with WebSocket code 1008. Using a JSON
result lets browsers distinguish a missing pairing from a conflict; native
browser WebSocket API does not expose failed upgrade HTTP status reliably.

- CREATE succeeds only when NO session exists under that code, including an
  opposite-role or legacy diagnostic session. No central allocation/database.
- JOIN succeeds only when an explicit creator is alive and the opposite role
  is free. Same-role/duplicate/third clients cannot take over a pairing.
- Successful second registration causes a relay-generated `peer-present` to
  BOTH peers, after the joiner's registration success response.
- Android starts its offer only on peer-present, never on a potentially lost
  early ready message. Browser answers; trickle ICE is still queued until remote
  SDP exists. All SDP/ICE payloads are forwarded unchanged.
- Closing either participant ends an explicit pairing, removes its map entry,
  notifies the survivor with `peer-left` and closes it. A later JOIN is not-found.
  Identity checks prevent old socket cleanup from deleting a newly created code.
- Existing diagnostic URLs without intent retain their old isolated legacy
  behavior for the existing sample pages/tests. They cannot join an explicit
  pairing session; explicit JOIN cannot enter a legacy diagnostic session.
  Explicit CREATE still conflicts with any legacy session. This preserves the
  older diagnostics without using legacy semantics for the new pairing UX.

The application logger continues to omit codes, URLs, QR data and payloads.
No session directory/enumeration API, account, persistent history or TURN route
exists. GET/POST `/turn/credentials` remain 404.

## Codes and collisions

Android uses SecureRandom.nextInt(32). Browser uses Web Crypto getRandomValues
on six bytes with an unbiased low-five-bit mapping to the 32-character alphabet.
Math.random is not used for codes. Both avoid recently generated codes in
memory; no saved code is intentionally reused by another Create action.

CREATE conflicts trigger fresh code/QR and at most four registrations total.
JOIN never silently generates a different code. Not-found gives
`Pairing code not found or expired.`; occupied/exhausted create gives a code-in-use
message. No collision retry recreates capture or reuses consent for a new capture.

Both entry fields trim whitespace, uppercase ASCII input and apply the same
alphabet rules. QR scanners accept either a raw valid code or the supported URL:

```text
https://pradertamas.github.io/realtime-web-test/webrtc-receiver.html?session=7K4M2Q
```

Host/path/scheme/query are validated. Other origins/paths, duplicate or additional
query entries, credentials, fragments and invalid codes are rejected. The normal
browser ?session= query still only prefills; it never auto-connects. Explicit
successful Scan, in contrast, joins automatically after validating the payload.
Android consumes the URL directly and does not open a browser.

## Scanner/generator dependencies and camera lifecycle

Android adds only `com.google.android.gms:play-services-code-scanner:16.1.0`,
configured QR-only. The already-present ZXing core 3.5.4 still generates QR images.
The Play services scanner supplies its own foreground UI and camera lifecycle;
the app does not request CAMERA permission or own a camera background service.
It starts only on Scan. Success/cancel closes that UI before handling the result;
the app does not retain frames. Scanner result listeners are not Activity-scoped
onStop listeners (opening the scanner stops MainActivity); destroyed activities
ignore late results. Camera permission errors are distinguished from unavailable
camera/module failures, with manual entry as fallback.

Google Play services and its scanner module must be available. Manifest metadata
requests the barcode_ui module; first use may need a Play services module download.
QR recognition occurs on-device. An unavailable module does not block manual
pairing. No Google API key, account login or Cloudflare credential is needed.
Reference: https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner

Browser primary decoder: native BarcodeDetector QR support where present.
Fallback: locally vendored jsQR 1.4.0. Generator: locally vendored
qrcode-generator 2.0.4. npm tarball integrity and local file SHA-256 are recorded
in vendor/PROVENANCE.json; original license notices/files are included. There is
no runtime CDN, backend QR API or new web build dependency.

Browser asks for video-only getUserMedia only after Scan; HTTPS or permitted
localhost is required. It prefers environment camera, with any-camera fallback
for unavailable facing modes. QR processing is local on a downscaled hidden
canvas, not reconstruction of the received screen video. The video display stays
a normal video element. Scan never calls addTrack on the receiver peer.

Success, invalid decoded payload, Cancel, pagehide/navigation and hidden page
stop every acquired track, clear srcObject and timers. A generation guard also
stops a stream returned AFTER permission was cancelled/hidden; late decoder
results cannot join. Denied/unavailable camera messages point to manual entry.

## Checks

Android:

```text
./gradlew.bat :app:assembleDebug testDebugUnitTest lintDebug
```

Web root:

```text
node verify.cjs
node verify-webrtc.cjs
node verify-pairing.cjs
node verify-qr-pairing.cjs
```

Relay root:

```text
node verify-signaling.cjs
node verify-pairing-privacy.cjs
node verify-symmetric-pairing.cjs
```

The symmetric test uses loopback sockets and verifies both creator roles,
conflicts against either role, missing JOIN, duplicate roles/third clients,
both-order peer-present and sender-offer/receiver-answer, creator cleanup, code
release and absence of code/QR/SDP secrets in logs. Browser tests cover create
retry/exhaustion, join not-found, generation, parsing, no query auto-connect,
local QR encode/decode, camera denial/fallback and cleanup including pending
permission cancellation. Android tests cover parsing and create versus join retry.
These are not a physical camera/WebRTC interoperability certification.

Recorded local results, 2026-10-16: Android assembleDebug/testDebugUnitTest/
lintDebug PASS, 8 unit tests (0 failures/errors), 0 lint errors/24 warnings.
Web existing 38 checks plus WebRTC/pairing/QR checks PASS. All three relay
verification scripts PASS. Build log: workspace-root android-check-symmetric.log.
PoC 3B CaptureService/CaptureMetrics hashes match the established baseline.

## Manual release preparation (not performed)

Review all three projects. Deploy the intent-aware relay, publish updated static
root receiver assets PLUS pairing.js, qr-tools.js, receiver-ui.css and vendor/
files/licenses, and install the rebuilt APK. Keep experiments/ out of release.
The new clients must not be tested against the old relay as if it supported
strict create/join; missing registration responses cannot establish correct
pairing semantics. No public remote test was automatically run.

## Physical four-flow matrix

Use Samsung SM-S931B / Android 17 / One UI 9, one desktop browser and the same
network/content/video profile as the established P2P test. Keep phone portrait.

| Test | Initiator | Joining action | Expected sequence |
|---|---|---|---|
| A | Android Create pairing | Browser enter code + Connect | Fresh phone consent, sender waiting, receiver JOIN, sender offer, video |
| B | Android Create pairing | Browser Scan QR | Browser camera permission only after Scan, camera stops on scan, receiver JOIN, video |
| C | Browser Create pairing | Android Enter pairing code + Continue | Receiver waiting; phone fresh consent; sender JOIN/offer; video |
| D | Browser Create pairing | Android Scan QR | Phone scanner consumes URL locally and closes; fresh consent; sender JOIN/offer; video |

For EACH row:

1. Stop prior pairing. Creator generates a new code and QR; check the waiting
   status before joining. In A/B grant fresh capture consent on phone creation;
   in C/D grant fresh consent AFTER joining code/QR validation.
2. Complete the specified joining action. Check both peer statuses and verify
   the same Android screen appears in the browser. Browser must never send screen
   video and Android must never become receiver.
3. Stream dynamic content for >=120 connected-media seconds. In diagnostics
   record codec/resolution/FPS/bitrate/bytes/RTT/dropped frames and direct candidate
   types. Capture-to-display timing remains best effort/N/A where unavailable.
4. Stop on phone and also test browser Disconnect in a repeat. Verify capture
   notification/indicator ends and creator session disappears. JOIN the old code:
   expect not-found/expired, not a new empty session.
5. Create again on the SAME creator side: check new code/QR. Repeat the other
   initiator side to check media roles stay fixed.

Scanner-specific negatives: deny permission, no camera, Cancel before acquisition,
Cancel with active preview, hide tab/navigate away, invalid URL QR, raw code QR,
permission dialog cancelled before a delayed stream arrives. Camera indicator
must stop; no scan should auto-join after cancellation. On Android also test
module unavailable/offline and use Enter pairing code fallback.

Additional negatives: invalid/short/long code, JOIN non-existent, duplicate role,
creator closes while other waits/streams, direct ICE failure. CREATE collisions
are covered deterministically by unit/local tests instead of waiting for a random
physical collision. Also smoke-test PoC 3B through Advanced diagnostics.

Only these physical runs can establish the four-flow PASS and retained streaming
stability. No commit/push/deployment was performed by this implementation.
