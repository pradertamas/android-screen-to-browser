# PoC 4B — AUTO versus forced Cloudflare TURN

## Baseline and scope

Historical/local experiment: the current primary app/receiver is now the
credential-free P2P pairing UX documented in [PAIRING.md](PAIRING.md).
Pre-pairing active source snapshots are retained in each project's
experiments/poc4b directory; the TURN helpers/backend files are not deleted.
The flow below is not the default Start share path anymore.

The user reports PoC 4A physically PASS on Samsung SM-S931B, Android 17 / One UI 9,
including an internet P2P 124.15 s run: 25,972,571 sent/received bytes, 3255 frames,
VP8, 720 × 1560, srflx/srflx UDP, Android mean RTT 40.72 ms, browser 0 dropped
frames and receive → expected display mean 89.82 ms. This is the established
baseline, not a TURN result. The older POC4A.md is a historical implementation
report; its pre-deployment/pre-physical-test status is superseded by this result.

PoC 4B is implemented locally; real Cloudflare issuance and physical TURN
streaming have NOT been tested by the agent. No commit, push or deployment.
The three projects remain separate, in this workspace. No older copies touched.

The libwebrtc artifact, codec negotiation, EGL factory, MediaProjection capture,
720 × 1560 portrait profile, 30 FPS adaptation/encoding cap, 2,000,000 bps sender
cap and video-only behavior are unchanged. No audio, encoder tuning, own media
timestamps or remote control was added. PoC 3B capture files are unchanged.
Bitrate peaks can exceed the target on a sampled window, as in the baseline.

## Audit and design

The existing WebRtcService created a PeerConnection immediately with a fixed
STUN/ALL config and collected outbound stats about once per second; browser
created a matching STUN peer at Connect and collected inbound/pair/frame timing
stats. Both retained a final summary. The two-way relay already forwards opaque
JSON correctly. It needs no WSS forwarding change for PoC 4B negotiation.

One bounded native-client credential HTTP route is added to that Node service,
keeping the deployment surface small. No separate credential server is required.
See sibling `realtime-relay/TURN.md` for exact environment variables and manual
deploy steps. Both modes now require that credential endpoint: AUTO still uses
ALL and prefers ICE-selected direct paths, with TURN available as fallback.
There is deliberately no silent STUN-only fallback if credential acquisition fails.

## Credential and signaling sequence

1. Browser connects as receiver; it creates no PeerConnection yet and sends
   `ready` with configuration protocol `version:1` every second.
2. Android operator chooses mode, enters the separate endpoint access token,
   then grants a fresh MediaProjection consent. Foreground service starts.
   The access token is passed only to the non-exported service and removed
   from its Intent; UI clears it after Start. It is never persisted or logged.
3. Android calls the fixed HTTPS `/turn/credentials` with the operator gate
   token and session ID. Server uses environment-only Cloudflare TURN key/API
   token to obtain one temporary credential set, TTL 600 s. No long-lived
   Cloudflare credential enters APK, browser, signaling, docs or repository.
4. Android initializes the unchanged native capture path and opens WSS as
   sender. Upon browser ready, it sends `session-config` containing version,
   UUID configId, mode, temporary TURN iceServers, expiry and price assumption.
   WSS is encrypted; the relay does not inspect/log payloads. This is a small
   ICE/control JSON message, never a video payload.
5. Browser validates config, expiry and Cloudflare URL allowlist, then creates
   its PeerConnection: AUTO = existing Google STUN + TURN / policy all;
   FORCE_TURN = TURN only / policy relay. It mirrors the mode in the UI and
   sends `config-ack` with version/configId/mode.
6. Android checks the acknowledgment before creating its own matching peer
   and creating the offer. Offer/answer/trickle ICE carry configId + mode;
   wrong or absent identifiers abort the session. Browser serializes messages
   and queues early ICE until remote SDP. Repeated identical config is safe;
   attempts to change it mid-session abort. Old 4A clients fail closed rather
   than accidentally negotiate ALL versus RELAY. Update both endpoints together.
7. Stop/bye/socket failure/system projection revocation clean up as before.
   Credentials are released from app-owned config references. Both clients
   stop 30 s before expiry; there is no refresh/restart with stale consent.

No permanent secret goes into client source or BuildConfig. The endpoint gate
is a manually supplied, limited operator credential, distinct from Cloudflare's
API token; it is not a user account system. Re-enter it for each new share.
Temporary passwords are not printed or included in summaries. Treat browser
developer-tool/network captures as sensitive until credential expiry.

## TURN proof and comparable metrics

Both peers show local/remote candidate types, selected pair state, protocol,
relayProtocol where exposed, address family only (raw addresses are not shown),
and current/mean/max RTT. Missing metadata is N/A. Pair selection uses the
transport's selectedCandidatePairId, or explicit selected flag, or exactly one
nominated succeeded pair; ambiguous candidates do not qualify as proof.

This implementation uses a conservative test: `relay / relay` AND pair state
`succeeded` means TURN relay confirmed YES. Both peers force RELAY, so this is
stricter than accepting just one relay endpoint. FORCE_TURN without proof shows
NO / FAIL / NOT RELAY (including initial connection setup), never a TURN PASS.

Any observed growing RTP counter on a non-relay or unknown selected path makes
the whole session ineligible for TURN costing. Even after a later relay switch,
the complete session cost remains N/A. Current pair confirmation and full-session
cost eligibility are separate. One-second stats are sampled evidence, not
packet-level proof against all possible sub-second route changes.

Android preserves outbound frame/byte/packet/codec/resolution/bitrate stats and
final summary; browser preserves decoded/received FPS, dropped/key/total frames,
packet loss, bandwidth availability, and requestVideoFrameCallback timing.
Current/average/peak bitrate, FPS, bytes, RTT, mode and network proof are in the
summaries. Browser retains current and one previous completed summary in memory,
without localStorage or a history backend; reload clears them.

## Economics definition and limitations

```text
Measured RTP GB/hour = bytes / sessionDurationSeconds × 3600 / 1,000,000,000
Estimated relay USD/hour = Measured RTP GB/hour × configured USD/GB
```

Sender uses outbound-rtp.bytesSent; receiver uses inbound-rtp.bytesReceived.
They measure the same one-way video at two observation points. Do NOT add them.
Decimal GB is used, not GiB. Zero duration, absent/invalid counters and overflow
give N/A; zero bytes with positive duration gives 0. Cost additionally requires
eligible relay proof and valid nonnegative configured price. Non-relay AUTO
sessions show measured RTP rate, but relay cost N/A, not a claimed zero bill.

The single operational price setting is server env `TURN_USD_PER_GB`; code
default 0.05 is an explicit configured assumption. Both peers receive the same
price in the one session-config. Displayed USD/hour is an estimate and excludes
protocol/provider billing differences. It is NOT a provider usage-meter reading.

Excluded/unresolved overhead includes RTP headers as defined by the particular
stats implementation, SRTP protection, RTCP, ICE/STUN connectivity checks,
TURN allocation/refresh/channel framing, UDP/IP, optional TCP/TLS overhead and
retransmission/accounting differences. RTX/FEC may have separate stats streams;
the current measurement follows the main outbound/inbound video RTP report.
Provider aggregation, pricing tiers/free allowances and account-wide usage are
not measured. Cloudflare documents charging edge-to-TURN-client egress including
TURN overhead after authentication, rather than merely application RTP payload:
https://developers.cloudflare.com/realtime/turn/faq/
Use Cloudflare's dashboard/analytics separately for actual billable usage; this
PoC does not call its usage/GraphQL APIs or claim exact billed relay bytes.

Full session durations are preserved from 4A: Android starts at capture setup;
browser starts at Connect. Setup/waiting time can differ, so Android/browser
hourly rates and average FPS need not match even when cumulative bytes/frames
match. For A/B, use the same start sequence and similar setup delays; report
both durations. A two-minute stopwatch observation starts only once video is
connected and moving; it is distinct from either full-session summary duration.

The supplied 4A sender baseline corresponds to 0.753131 RTP GB/hour. At a 0.05
USD/GB assumption that would be about 0.0377 USD/hour IF a TURN run had the same
RTP rate. This is a hypothetical payload-cost reference, not a measured TURN
result or provider bill. The actual 4B values must come from Session B.

Frame timing is unchanged: capture→receive and capture→expectedDisplay if the
browser supplies timestamps, plus receive→expectedDisplay. Missing timestamps
remain N/A. Remote clock mapping and predicted display time are best effort,
not laboratory glass-to-glass latency. No own timestamp media protocol was added.

## Build and tests

Android (JDK/writable Gradle home as in the existing workspace):

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$env:GRADLE_USER_HOME = 'C:/Sajat/Android_Screen_to_WebPage/.gradle-poc4a'
./gradlew.bat :app:assembleDebug testDebugUnitTest lintDebug --console=plain
```

Browser directory:

```text
node verify.cjs
node --check webrtc-receiver.js
node --check webrtc-network.js
node verify-webrtc.cjs
node verify-network.cjs
```

Relay directory:

```text
node --check server.js
node --check turn-credentials.cjs
node verify-signaling.cjs
node verify-turn-credentials.cjs
```

Tests include zero duration, zero bytes, normal/very high rates, missing/invalid
stats, policy mapping, mismatched config rejection, early ICE, relay proof,
summary retention, credential auth/validation/limits and sanitized upstream errors.
They use fake TURN values and no Cloudflare/public signaling requests.
Do not automatically run the remote test before the manual deployment.

Recorded local checks on 2026-10-16: assembleDebug, testDebugUnitTest (6 tests,
0 failures/errors) and lintDebug succeeded; lint reported 0 errors/21 warnings.
Existing browser checks: 38/38 PASS; expanded WebRTC, network/cost and syntax
checks PASS. Local relay signaling and mocked credential HTTP checks PASS.
`android-check-4b.log` in the workspace root records the Android build run.
The PoC 3B CaptureService/CaptureMetrics file hashes remain unchanged.

## Exact manual A/B procedure

1. Follow `realtime-relay/TURN.md`: privately configure Cloudflare TURN key,
   separate endpoint gate and price assumption on Render, then manually deploy
   the reviewed revision containing the new HTTP module. Existing WSS URL stays.
2. Manually publish the updated receiver HTML, JS and `webrtc-network.js` on
   GitHub Pages, or serve the web folder locally with
   `npx --yes http-server . -p 8080 -c-1`. Desktop URL locally is
   `http://localhost:8080/webrtc-receiver.html`. Reload without stale caches.
3. Install `app/build/outputs/apk/debug/app-debug.apk` on the Samsung, e.g.
   `adb install -r app/build/outputs/apk/debug/app-debug.apk` from Android root.
   Use the same Samsung, browser version, desktop, network path and dynamic
   content sequence for A and B. Keep portrait/unlocked and the browser visible.
4. Session A: use a new unpredictable ID on both peers. Browser Connect first.
   Android select AUTO / P2P preferred; enter ONLY the separate endpoint access
   token, Start share, grant NEW entire-screen consent, show dynamic content.
   Check both mode displays, policy all, selected pair/protocol/codec/resolution.
   If AUTO selects relay, it is not a direct P2P control: record that fact and
   repeat under conditions yielding host/srflx/prflx for the control comparison.
5. Once moving video is connected, observe for >=120 seconds. Record actual FPS,
   average/peak bitrate, bytes, dropped frames, RTT and available timing. Stop
   Android share, wait for browser summary (Disconnect manually if necessary).
   Keep Android summary and browser selectable summary. Expect the screen-sharing
   indicator and foreground notification to disappear.
6. Session B: change to a new ID, Browser Connect, Android FORCE TURN relay,
   re-enter endpoint access token, Start and grant a fresh consent. Verify both
   mode displays and `relay / relay`, succeeded pair, TURN confirmed YES on BOTH
   endpoints before considering this a TURN test. If NOT RELAY or cost N/A, do
   not accept it as a completed TURN cost measurement; capture the diagnostic.
7. Run the same moving content for >=120 connected-media seconds, with the same
   unchanged 720×1560 / 30 FPS / ~2 Mbps profile. Stop and record both summaries:
   bytes/FPS/bitrate/drops/RTT/timing, measured RTP GB/hour, configured USD/GB and
   estimated USD/hour. Browser previous summary now contains Session A, current
   summary contains B. Copy both before reloading. Confirm negotiated codec is
   the same; if not, mark the comparison and repeat rather than forcing a codec.
8. Compare B−A RTT and timing, decoded FPS, dropped frames and bitrate. Use one
   endpoint's consistent duration definition for hourly comparisons; keep the
   other endpoint as a cross-check. Do not add sender+receiver video counters.
9. Also test denied consent, Stop during credential fetch, browser Disconnect,
   invalid endpoint key, system capture-stop, fresh-session restart and PoC 3B
   separately. No credential strings should appear in UI logs or summaries.

PASS requires AUTO still functioning, a confirmed forced-relay path, >=120 s
stable moving video, successful decode and recorded metrics. Timing may be N/A
if unsupported. No automatic PASS is based merely on policy=relay or tests.
