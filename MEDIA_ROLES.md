# Unified media roles

Status: IMPLEMENTED / AUTOMATED VERIFIED; PHYSICAL ANDROID-TO-ANDROID VALIDATION PENDING.
Baseline Android main f8e9ebc + the existing local native receiver work. No new
library, UI migration, persistent pairing, backend or protocol is introduced.

## Domain and ownership

MediaRole.SEND: signaling sender, offerer, local capture permitted.
MediaRole.RECEIVE: signaling receiver, answerer, remote VideoTrack/native renderer,
no MediaProjection/capture foreground service. The enum also builds registration
URLs using its explicit signalingRole. PairingIntent remains the separate CREATE /
JOIN dimension. SEND+CREATE, SEND+JOIN, RECEIVE+CREATE and RECEIVE+JOIN are valid.
No role is inferred from Activity, session code, QR payload, creator/joiner, requester,
identity or persistent token. A later direction-independent paired relationship can
choose a MediaRole per request; no paired-device feature is implemented now.

AppMediaRoles is the single process-local role owner and exposes one StateFlow.
Its pure MediaRoleController owns switchRole, generation, leave guard and cleanup /
activation ordering. Fresh process defaults SEND; nothing is written to preferences,
savedInstanceState, token store or remote device identity. Configuration recreation
retains role. A restored ReceiverActivity in a NEW process redirects to SEND/Main
rather than inferring RECEIVE from its Activity type.

Both Activities collect that same StateFlow. Existing Compose/Material3 is used:
one shared SingleChoiceSegmentedButtonRow with SEND / RECEIVE, selected-state
semantics, built-in check indicator (not color alone) and descriptive accessibility
labels. No ON/OFF Switch or new UI dependency. Session phase remains independent.
Diagnostics adds Media role: SEND / RECEIVE without secrets.

## Transitions

SEND -> RECEIVE while idle/creating/waiting/error:
1. Check the actual current session/capture state again at click, not just UI state.
2. Invalidate role generation; hide/close PairingHost. Its epoch invalidates late
   messages before socket close; rotation/recovery/debounce/timeout are cancelled.
3. Existing relay socket cleanup removes sender remote presence. No API change.
4. Publish RECEIVE; navigate to existing ReceiverActivity. Its retained ViewModel
   binds the receiver owner and auto-CREATEs role=receiver. Existing code/QR/Join,
   answerer session and native renderer are reused, not reimplemented.

RECEIVE -> SEND while pairing/ended/error:
1. Same central switchRole invalidates generation and releases receiver ownership.
2. ReceiverEngine.stop closes wire/media, clears ICE and timers. Native cleanup
   detaches/releases renderer, closes/disposes peer/factory then EGL.
3. Publish SEND, start normal PairingHost.show (fresh sender CREATE), finish receiver.
4. MainActivity.onStart.show is idempotent and does not duplicate that registration.

Same role -> same role is a no-op: no generation increment, cleanup or restart.
Receiver Back and Stop receiving use returnToSend(stopActive=true), the SAME central
transition with explicit user-requested session termination. Segmented SEND uses
returnToSend() without that override. No duplicate Back-specific cleanup exists.
Cleanup failures are caught best-effort after invalidation; a generic error is shown
without exception/secret details. Failed receiver Activity launch/initialization
returns to usable SEND and displays the error. No two active role owners are kept.

## Active guard and media invariants

Selector disabled during SEND JOINING/CONSENT/CONNECTING/STREAMING, pending/busy
capture or capture-test session; RECEIVE CONNECTING/RECEIVING is also disabled.
WAITING/idle and inactive ERROR/ENDED permit switch. Scanning disables the sender
selector too. The central leave check reads live phases, preventing a stale enabled
button from switching after peer-present has started a handshake. Stop/Back remain
explicit actions that can terminate receiving.

SEND + either pairing intent still runs the existing fresh user-choice consent,
then WebRtcService sender offer. RECEIVE + either intent still waits on peer-present,
then setRemoteDescription(offer) -> createAnswer -> setLocalDescription -> answer.
Receiver never calls createOffer. Registration role comes from MediaRole, not intent.
Additional SEND guards protect MediaProjection result/service entry; changing to SEND
alone never asks for consent. Existing PoC 3B capture remains available in SEND.
The video profile, SDK/dependencies, codec negotiation, STUN, dynamic resize and media
pipeline are unchanged. No TURN/audio or persistent app-to-app pairing is activated.

## Lifecycle and privacy

Role generation is invalidated before cleanup. Existing sender epoch/registration
attempt guards and receiver engine/native/renderer generation/closed guards remain
responsible for rejecting late WS, SDP, ICE, frame, stats and timer callbacks. Role
changes do not weaken or replace those proven per-session guards.
Receiver ViewModel binding survives configuration recreation, keeping the existing
peer/socket and EGL; the old Activity detaches its renderer and the replacement
reattaches the same track. No configChanges hack or duplicate transition is added.
Background keeps the selected role but ends receiving using the existing onStop
rule; foreground Retry is explicit. MainActivity does not start a sender registration
while RECEIVE is selected; returning via launcher brings the receiver to the front.

Switching does NOT regenerate DEVICE_TOKEN. SEND waiting resumes existing presence
registration with that same stored token; RECEIVE never advertises it. Secret remains
a #device browser link, not app-to-app pairing. QR payload/alphabet and role-independent
session IDs stay unchanged. Remote code/token/QR is never added to diagnostics/logs.

## Automated coverage

MediaRoleTest: cold SEND/no persistence, same-role no-op, both transitions, all four
role/intent wire and offerer/permission mappings, all phase guards, explicit Back
termination, invalidate-before-cleanup/reentrancy/failure recovery, actual sender and
receiver pairing cleanup with forced late messages/timers, and unchanged token with
renewed sender presence. Shared UI/lifecycle source contracts supplement the pure
controller tests; they are not simulated Android instrumented UI tests.
Existing AutoPairing, CaptureDimensions, RemoteDevice, receiver ICE/answer/cleanup
and consent cancellation tests remain required. ViewModel/renderer recreation and
physical rendering still require two-device validation.

## Later physical matrix

1. Fresh app/process on A -> SEND selected; on B choose RECEIVE -> receiver code.
   A enters/scans B code (SEND JOIN, RECEIVE CREATE), consent only A, then video on B.
2. A sender CREATE; B enters A code (RECEIVE JOIN). Same sender-offerer/receiver-answerer.
3. In waiting state repeatedly switch SEND <-> RECEIVE. Record fresh codes; confirm
   old codes expire, no stale socket/timer/renderer, no consent solely due to switch.
4. During sender consent/connecting/streaming and receiver connecting/receiving,
   selector must be disabled. It must not stop an active stream when tapped.
5. Stop/end first, then switch role. Back on B returns SEND with fresh QR/code.
6. Rotate both waiting UIs; selected role survives. Rotate B while receiving: same
   peer/track, renderer reattaches. Rotate A content portrait/landscape/portrait.
7. Background B ends reception but retains RECEIVE; return and Retry works. Kill
   process/relaunch -> SEND, including restored receiver Activity stack.
8. Reuse the existing private remote browser link after SEND -> RECEIVE -> SEND.
   RECEIVE should be unavailable; new SEND waiting should resolve the same token.
9. Desktop browser receiver: code/QR, consent Allow/Cancel/retry, Stop, rotation.
10. Mobile browser ?mode=mobile (session/remote/normal): confirmation gate, fullscreen
    fallback and existing Stop/Try again behavior. Web files were not changed here.

No second physical Android is available. Do not claim Android-to-Android PASS.
No commit, push, deploy, archive/restore or experiment change is authorized.

## Local verification — 2026-10-17

- Android assembleDebug, testDebugUnitTest, lintDebug: PASS.
- 37 JVM unit tests, 0 failures (6 new MediaRoleTest methods plus all 31 prior tests).
- Lint: 0 errors / 25 existing warnings; no new role-selector warnings.
- All 11 web verifies PASS; no updater/generator was invoked. verify-cache-busting
  calls the updater's pure versionHtml export only, not its file-writing CLI.
- All 5 local relay verifies PASS (signaling, privacy, symmetric pairing, JOIN limit,
  remote presence/resolve). No public remote-signaling test was invoked.
- Web+relay: 53 tracked/untracked file SHA-256 hashes identical to the task-start
  snapshot in workspace web-relay-before-media-roles.json. No new web/relay diff.
- Android main: 4 modified tracked files (manifest and MainActivity already modified
  by native receiver; PairingHost and WebRtcService gain explicit role guards/wiring),
  13 untracked files across prior native receiver and this role feature. New for this
  step: MediaRole.kt, AppMediaRoles.kt, MediaRoleSelector.kt, MediaRoleTest.kt,
  MEDIA_ROLES.md. Existing local receiver Activity/ViewModel/Engine/Registration/
  WebRtcReceiverSession receive only role integration; media implementation retained.
- Web main retains exactly its previous 8 modified + 3 untracked mobile receiver
  files. Relay main remains clean. Experiments/archive and persistent tokens untouched.
- No commit, push, deploy or physical Android-to-Android PASS.

IMPLEMENTED + AUTOMATED VERIFIED.
PHYSICAL ANDROID-TO-ANDROID VALIDATION PENDING.
