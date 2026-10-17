# Android connection workflow UI

Scope: Compose/Material3 presentation; native SEND/RECEIVE roles, WebRTC engines,
MediaProjection user-choice, protocol, identity formats and backup rules unchanged.

## Audit and supported behavior

- MainActivity hosts SEND. ReceiverActivity hosts RECEIVE with a retained
  ReceiverViewModel and existing SurfaceViewRenderer attachment lifecycle.
- AppMediaRoles is the sole role source, SEND on fresh process, existing switch
  guards. No UI-owned role flag or persisted role is introduced.
- AutoPairing owns sender CREATE/JOIN, 250 ms input debounce, five-minute waiting
  rotation and peer-present/fresh-consent orchestration. ReceiverEngine retains
  its CREATE/JOIN/answer-only flow and four-second disconnect grace.
- Google Code Scanner was already used in SEND (play-services-code-scanner 16.1.0).
  RECEIVE reuses that same explicit scanner API/dependency. It opens the Google
  scanner UI, not an embedded preview; camera ownership/Back/success cleanup are
  handled there. The waiting receiver is retained during that explicit scanner
  and the code-link share chooser, like the existing sender external-UI flow.
  Ordinary background navigation still stops the receiver. Streaming is unchanged.
- Persistent invite creation/inspection/explicit acceptance remains isolated from
  roles/capture/media. Home exposes the same persistent invite share action first in Step 2; the floating
  App Link Activity retains its expanded confirmation surface.
- There was a GET /v1/pairings client method but no home list, device-name field,
  revoke/remove client action, or persistent-peer-to-media-session bridge.
  No removal endpoint, direct Connect, device names or backend is invented here.

## Presentation

1. Choose role: shared segmented buttons with equal touch geometry, real role state.
2. Choose how to start: persistent Share connection link first, then real saved-device rows above
   exactly two connection-path action cards. Empty list has no fake
   rows. Cards share typography, padding and minimum title/helper line geometry.
3. Selected path only: Connect offers the existing native scanner action, OR,
   alphanumeric connection-code input, Connect and inline status/error. Sender
   auto-JOIN remains. Show offers existing CREATE QR/code, full short-lived
   connection URL Copy/Share and inline status. Sender also retains its separate
   persistent remote-link controls, including regeneration.

The QR and ephemeral Copy/Share URL is PairingCode.receiverUrl(code), unchanged
`https://pradertamas.github.io/realtime-web-test/webrtc-receiver.html?session=<CODE>`.
Persistent sender remote controls still use their existing full #device URL/share
text; persistent app invite shares the server-supplied /pair#invite URL unchanged.
Clipboard links are marked sensitive. Own code-link share uses an Activity-result
launcher so opening the chooser does not retire the waiting CREATE registration.

Back dismisses only the presentation selection and cancels pending typed JOIN;
it does not replace the role, persistent identity or waiting CREATE. Active
consent/connection guards disable navigation as before. Receiving hides the home
workflow and leaves video plus Stop primary. Diagnostics/PoC3B remain secondary.

## Saved-device data and limits

List refresh uses only an already stored valid identity (no ensure/register).
It calls the existing authenticated listPairings method and accepts only canonical
128-bit `otherDeviceId` public identifiers, never arbitrary JSON/credentials.
Rows show `Device <last eight public-ID characters>`, explicitly labelled as saved
connections requiring QR/code to start sharing. Rows are deliberately not Connect
buttons. Missing/invalid response fields produce an unavailable message rather
than fake/partially guessed devices. Friendly names and Remove are not supported
by the current Android API/model. Backend response compatibility and a live saved
connection list must be validated with the deployed NAS; JVM tests do not make
network calls or claim live-backend validation.

## Physical retest (pending)

1. Fresh process defaults SEND; select both real roles, rotate the waiting UI,
   confirm selected role remains controlled by AppMediaRoles.
2. No saved identity/list: no device placeholder and no registration merely to
   display the list. Create/accept a real persistent invite, return/reopen home;
   validate list above the two cards, safe ID representation, no direct session.
   Accept alone must not open MediaProjection or WebRTC.
3. Each role: Connect -> scan valid/raw QR, deny/cancel scanner, type/paste code,
   Connect. Native Google scanner camera must close on success/cancel. No fake
   embedded camera. Back returns to choices without rotating the own code.
4. Each role: Show -> QR and code -> other device joins. Copy/Share contain the
   entire existing URL. Returning from chooser retains waiting registration.
5. SEND: role/path selection alone must not open consent; real peer-present still
   requests fresh Android single-app/full-screen choice. Test both, Cancel then
   retry without browser reload, active Stop and a fresh session.
6. RECEIVE: first real frame dominates; Stop/system Back, rotate during playback,
   short disconnect recovery and >4 s disconnect/error/retry. No capture consent.
7. Verify persistent remote-link share/copy/regenerate, App Link accept/Cancel,
   advanced metrics and PoC3B. Inspect small/large-font portrait/landscape layouts.

No push, deployment, dependency upgrade or changes outside the Android repo.

## Local automated verification

Final :app:assembleDebug testDebugUnitTest lintDebug: BUILD SUCCESSFUL.
50 JVM unit tests, 0 failures/errors, including five new workflow/public-ID/read-only
identity tests plus existing role, receiver, disconnect grace, cancellation,
rotation, persistent invite and backup-exclusion tests. Lint: 0 errors, 26 warnings.
No connected-device/instrumentation or live NAS request was run. This is not a
physical streaming/scanner/paired-list PASS; execute the physical matrix above.

## Persistent invite Share Sheet delivery fix

The Step 2 button already called AppPairingViewModel.create -> controller.create
-> existing backend create -> full invite URL -> share event. There was no hidden
second share action. The audited UI delivery had two defects: lifecycle detection
required LocalContext to be a ComponentActivity, and chooser launch failure was
silently discarded after consuming the link. Code alone does not establish which
branch caused the reported physical failure; no device trace was collected.

AppPairingSection now uses LocalLifecycleOwner and an Activity Result launcher for
ACTION_SEND/text/plain with the existing full-link share text and native chooser.
A ViewModel-owned memory-only event is claimed once before launch. Native launch
failure is visible and preserves the same invite for explicit Retry; it does not
create another invite or replay automatically on resume/recomposition. New incoming
invites, Cancel and ViewModel disposal clear pending/failed share state. A fresh
process does not restore bearer links from saved state. Backend expiry/single-use
remain authoritative and unchanged; a retained failed-launch link may expire under
those existing rules. No connection/session/media action is added.

Physical retest: Step 2 Share -> creating indication -> native chooser -> choose a
share app and verify the entire /pair#invite URL. Cancel chooser, return, rotate:
no second chooser without a new user action. Test unavailable API (inline Retry,
no chooser), then recovery. Test the same in SEND and RECEIVE waiting states and
from the floating App Link entry. Verify accepting an invite does not start media.
