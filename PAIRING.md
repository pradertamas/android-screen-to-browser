# Screen Share pairing UX — direct P2P only

Local implementation, no commit/push/deployment. The active app/receiver need no
Cloudflare, TURN credentials, accounts or credential endpoint. The proven PoC 4A
MediaProjection → libwebrtc → browser video path is retained: portrait 720×1560,
30 FPS adaptation/sender cap, max ~2 Mbps, video only, default codec negotiation.
PoC 3B CaptureService/CaptureMetrics are unchanged and available in Advanced
diagnostics. The QR Bitmap is generated once per code, not per video frame.

## Normal flow

Current automatic CREATE/Scan/auto-JOIN UX supersedes the manual flow below:
see [AUTO_PAIRING.md](AUTO_PAIRING.md). The older flow is retained as history.

This document records the earlier Android-created pairing UX. The current
two-initiator Create/Scan/Enter release, exact protocol, dependencies and four
physical flows are documented in [SYMMETRIC_PAIRING.md](SYMMETRIC_PAIRING.md).

1. Android Start share generates a fresh code immediately and opens the required
   Android screen-capture consent. Allow capture. No stored consent is replayed.
2. App displays the code in large monospace text, a QR and Connecting / Waiting
   for browser. Screen capture and foreground service follow the existing path.
3. On the receiving device open:
   `https://pradertamas.github.io/realtime-web-test/webrtc-receiver.html`
4. Enter the code and Connect. Or scan the phone QR, confirm the prefilled code
   on the receiver page, then Connect. There is no automatic QR-link connection.
5. Existing ready → offer → answer → trickle ICE pairs the same session. App
   shows Browser connected / Starting stream / Streaming. Browser shows its
   connection/playback state. Video travels only over WebRTC, never WSS.
6. Stop clears the displayed code/QR and stops capture/resources. Diagnostics
   retain the final metrics. Every later Start generates a new code.

Both peers use `stun:stun.l.google.com:19302`, ALL/all policy, no TURN servers.
Failure to connect shows `Direct connection could not be established.` after
ICE failure or a 45-second negotiation timeout. A transient disconnect gets a
15-second recovery window. Waiting for a receiver expires after five minutes;
start a new share to get a new code. No automatic reconnection with stale consent.

## Code and collision handling

Alphabet: `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`, exactly six characters, 32 symbols
per position (30 bits of random choice). Android uses java.security.SecureRandom
with bounded nextInt(32), not timestamps or pseudorandom UI state. A small
in-memory set avoids repeating the last 256 generated codes in this app process;
there is no persisted code allocation database or intentional old-code reuse.

Receiver trims surrounding whitespace, accepts ASCII lowercase, uppercases,
then enforces length/alphabet. Internal spaces, 0/O/1/I, punctuation and Unicode
lookalikes are invalid. The six-character code itself is the relay session ID;
existing relay validation `[A-Za-z0-9_-]{6,64}` already accepts it.

Only a sender WebSocket upgrade rejected with HTTP 409 causes an automatic
new code/re-registration: at most four attempts total (initial + three retries).
Each attempted code is distinct. QR and displayed code update with StateFlow.
Network/other HTTP failures do not silently change the code. Collision retry
does not recreate capture, reuse a projection token for another capture, or
create another VirtualDisplay. Stale socket callbacks cannot affect the new one.
If retries are exhausted, ask the user to Start again with fresh consent.

The existing relay protects duplicate roles, not a central session reservation:
a receiver-only session does not reject a new sender. An occupant knowing the
code can take the receiver role. Keep codes private and share them only with
the intended receiver. No directory/list/discovery endpoint was added.

## QR and URL handling

Dependency: `com.google.zxing:core:3.5.4` only; no camera permission, scanner UI,
Android scanner wrapper or external QR generation API. QRCodeWriter generates
a black/white 600×600 image with a four-module quiet zone, displayed at 220 dp.

Exact payload for example code 7K4M2Q:

```text
https://pradertamas.github.io/realtime-web-test/webrtc-receiver.html?session=7K4M2Q
```

`pairing.js` parses exactly one session parameter with URLSearchParams, normalizes
and validates it, then prefills the receiver field only. Invalid/duplicate query
values produce a helpful message and cannot start a socket. The page never
auto-connects, stores the code in localStorage or creates an active-session list.
Referrer policy is no-referrer. QR URLs still contain the secret in the address
bar/browser history; avoid sharing screenshots/URLs. No third-party assets load.

## Preserved PoC 4B experiment

Before changing the active files, exact source snapshots were copied into
`ui-capture-test/experiments/poc4b/` (MainActivity/WebRtcService) and
`realtime-web-test/experiments/poc4b/` (receiver HTML/JS/verifier). These are
inactive source references, not additional projects or runnable entry points.
No experimental file was deleted. webrtc-network.js, verify-network.cjs,
NetworkMetrics/tests, POC4B.md and the relay TURN endpoint sources/tests/env
template/docs now reside only under each project's experiments/poc4b/ area.
The active receiver loads pairing.js, not webrtc-network.js. The active Android
service has no credential HTTP call or TURN mode. Do not ship the historical
snapshot HTML as an alternative normal receiver.

## Relay privacy change

The user explicitly authorized only removing session identifiers from logs.
The application logger now retains event/role/reason without pairing codes.
Validation, session isolation, duplicate-role protection, WSS forwarding and
limits did not change. The former local PoC 4B HTTP endpoint hookup has now been
removed from active server.js; GET and POST /turn/credentials return 404.
The earlier TURN server snapshot/modules remain only in experiments/poc4b/.
The release contains the two-way signaling server and privacy change only.
Exclude experiments/ from release assets. No deployment was performed.

Existing Render application logs remain unchanged until a separately authorized
privacy-only release. Infrastructure access-log configuration must also avoid
retaining WebSocket query strings. No remote service configuration was changed.

## Checks

Android: `./gradlew.bat :app:assembleDebug testDebugUnitTest lintDebug`.
New tests: code rules/random output format, bounded collision retries/status
selection and a full QR encode/decode URL round trip. Existing metrics tests stay.

Web directory:

```text
node verify.cjs
node verify-webrtc.cjs
node verify-pairing.cjs
node --check pairing.js
node --check webrtc-receiver.js
```

Relay directory (local tests only):

```text
node verify-signaling.cjs
node verify-pairing-privacy.cjs
```

Browser verification covers direct STUN-only negotiation, early ICE, stats,
timing, final summaries/cleanup, normalized socket session URL, valid/invalid
query prefill without auto-connect, TURN-config rejection and direct failure UI.
The privacy check verifies real loopback sender/receiver registration and checks
that application logs contain neither the code nor a session field.

Recorded verification on 2026-10-16: assembleDebug, testDebugUnitTest (9 tests,
0 failures/errors), lintDebug (0 errors, 23 warnings) succeeded. Existing 38 web
checks, updated WebRTC checks, pairing rules/query checks, syntax checks and
local relay signaling/privacy checks passed. PoC 3B capture file hashes match
the established baseline. No public WSS test or physical device run was performed.
Android build output is recorded in workspace-root android-check-pairing.log.

Release cleanup verification on 2026-10-16: assembleDebug/testDebugUnitTest/
lintDebug PASS, with 7 active tests and 0 errors/23 lint warnings. The two TURN
economics tests now live outside the app test source set under experiments/.
All three web verification commands and both relay verification commands PASS.
GET/POST /turn/credentials are explicitly checked to return 404. Current build
log: workspace-root android-check-release.log. No commit/push/deploy performed.

## Physical test — Samsung SM-S931B and desktop

1. Build/install `app/build/outputs/apk/debug/app-debug.apk`. Keep the Samsung
   portrait and use the same video profile/network as the proven 4A run.
2. Serve the updated web root locally, e.g.
   `npx --yes http-server . -p 8080 -c-1`, then open
   `http://localhost:8080/webrtc-receiver.html` on desktop. No TURN server needed.
   To test the public URL/QR destination, first manually publish the updated
   receiver HTML/JS, pairing.js and receiver-ui.css in a separate release.
3. Android Start share → fresh screen-capture consent → Allow. Check six large
   characters, visible QR and Waiting for browser. No session/token input needed.
4. Enter the code on desktop, using lowercase and surrounding spaces in one
   run, then Connect. Check normalization to uppercase and moving screen video.
5. Verify the Advanced diagnostics: connected peer/ICE, direct host/srflx/prflx
   candidate types, codec, 720×1560, FPS, bytes/bitrate, RTT, decoded/dropped frames
   and optional best-effort frame timing. No relay candidates or TURN config.
6. Stream dynamic content for at least 120 seconds; compare stability to 4A.
   Stop on phone; code/QR should disappear, capture indicator/notification should
   stop, browser should disconnect and retain its summary.
7. Start again: verify a fresh code. Scan its QR with the receiving device;
   browser opens the exact public ?session= URL, fills code, but does not connect
   until Connect is pressed. A QR scanned on the sender phone itself would open
   a receiver there; use the actual intended receiving device for this test.
8. Negative tests: denied consent, invalid/short/long code, expired/old code,
   browser Disconnect, system capture-stop and failure to establish direct ICE.
   Verify useful status and a clean fresh Start. Collision policy is unit tested;
   random real collisions are not expected during manual testing.
9. Open Advanced diagnostics and run PoC 3B independently, including sample save,
   stop and restart; the WebRTC share must remain mutually exclusive with it.

Automated checks do not establish physical PASS. Public Pages and relay were
not deployed; QR prefill on the public site requires the later web release.
