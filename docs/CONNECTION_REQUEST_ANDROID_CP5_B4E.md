# CP5-B4E — Android request media UX wiring

Baseline: `188b238991d83a6007cdf3cb0e521f6649cdb862`.

## Android paths

* Initiator SEND: MainActivity Share -> existing request setup/foreground status ->
  CONNECTING -> retained system MediaProjection prompt -> WebRtcService REQUEST,
  using the controller's CREATE assignment.
* Recipient RECEIVE: /connect -> explicit Accept -> existing role/readiness setup ->
  CONNECTING -> one-shot RECEIVE reservation -> ReceiverActivity -> retained
  ReceiverViewModel/request JOIN. The source Activity finishes after navigation
  succeeds, without decline, withdraw, clear or gate release.
* Initiator RECEIVE: existing ReceiverActivity Share -> CONNECTING -> one-shot
  RECEIVE reservation -> its existing ViewModel/request CREATE. No extra navigation.
* Recipient SEND: /connect -> explicit Accept -> preparing -> CONNECTING -> same
  retained system prompt -> REQUEST service/JOIN. Successful service handoff finishes
  the source Activity without withdraw/clear/release.

SEND remains offerer and RECEIVE remains answerer. CREATE/JOIN are signaling
intents. No browser request-first path, new media engine or relay protocol is added.

## Authority and consent

The internal media snapshot contains local flow identity, side/local role, request
state, owner phase, operation, exit intent and safe completion records. It contains
no request ID, secret, claim, assignment code, protected URL or bootstrap. UI state
does not acquire bearer authority. Reservation remains the controller's atomic
exact-flow admission; only CONNECTING, matching role, armed gate and resumed owner
can initiate the UI orchestration. CONNECTED cannot start a new reservation/socket.

RequestSenderLaunchViewModel retains a pure launch owner, exact flow and opaque
handoff while configuration changes. A prompt is marked in flight before launch;
repeat emissions/resume/recreation cannot request another prompt or reservation.
ActivityResult uses that same handoff once. No consent Intent, Activity reference,
flow/handoff authority or prompt state is persisted in SavedState/Bundle/preferences.
The platform ActivityResult registration handles result delivery after recreation.

The request consent launcher is separate from MainActivity's unchanged legacy
rtcConsent/PairingHost epoch path and uses createConfigForUserChoice. A successful
result starts the existing service with only MODE=REQUEST, CONSENT and HANDOFF.
After startForegroundService returns, the service owns handoff cleanup; source
ViewModel destruction never abandons a transferred reservation.

Deny/cancel, prompt launch failure and service-start exceptions abandon the local
reservation and request exact-flow exit through the lifecycle coordinator.
Initiators cancel; winning recipients withdraw. Stale results cannot adopt or exit
a replacement flow. Destruction of a never-launched sender owner cannot cancel a
recipient RECEIVE navigation. The B4D capture-before-auth/service media path is
unchanged.

## Receiver handoff and retention

Navigation uses REORDER_TO_FRONT/SINGLE_TOP and only REQUEST_MEDIA_HANDOFF.
ReceiverActivity scrubs this extra from incoming/current Intents before handing it
to the existing ViewModel, both in onCreate and onNewIntent. Duplicate, unknown,
stale or refused handoffs fail closed and cannot open a second socket. Refused
unconsumed reservations are abandoned and exited with their exact flow authority.
Navigation failure follows the same cleanup policy.

The ViewModel retains Engine, native session, request owner and protected socket.
Renderer detach/attach is unchanged. A warm request start reasserts the existing
sole AppMediaRoles receiver binding; another ViewModel owner is refused. Request
Stop/Back calls exact request stop before role switch/finish. Stale retained flow
callbacks do not switch role/stop a replacement request. Legacy Back and scanner,
manual code, QR, conflict retry and rotation remain on their existing paths.

## Disposal, exit and gate

ConnectionRequestMediaLifecycle is process-local and serialized on the main
dispatcher. It observes controller publication/media revision events, without
polling media phase. Service/receiver disposal acknowledgements wake it via the
controller revision. Reserved service-start failure also sends a narrow queued
abandon notification to this orchestration layer; core reserved-release itself
still performs no HTTP. Callbacks and native/service operations never run under
the controller authority lock.

* Disposed while CONNECTING: exact cancel/withdraw, then finalization after a
  server terminal result.
* Disposed while CONNECTED: no cancel/withdraw, only local exact finalization.
* Disposed while CANCELED/FAILED/EXPIRED: local exact finalization.
* No media owner after consent cancel: exitRequested permits terminal/CONNECTED
  finalization after the scoped exit/reconciliation.
* Active STARTED or RESERVED media cannot be finalized/released.

finishMediaFlow checks exact identity, no operation, terminal/CONNECTED and no
active media under the controller lock; it clears and invokes only the short gate
release policy in that same boundary. Stale A cannot clear/release B. Gate remains
ARM during WAITING/ACCEPTED/CONNECTING and while CONNECTED media is active.

Conflict/state mismatch after exit gets one controlled status reconciliation.
Fresh CONNECTED is accepted as server state, never fabricated as local terminal.
Nonterminal/failed reconciliation requires explicit retry. NETWORK cleanup error
retains context/authority and the armed gate, prevents media restart and disables
automatic initiator monitoring for an exiting flow. No automatic exit retry loop
is introduced. MainActivity and ReceiverActivity show "Could not close the
connection" / "Try again"; recipient UI also has a flow-scoped retry. Retry admits
one exact exit or reconciliation operation.

HTTP CONNECTED still means signaling participants authenticated, not media-connected.
Sharing/Receiving remains driven by WebRtcService/ReceiverEngine/first frame.
The sender's existing Stop sends WebRtcService.STOP. Legacy service disposal never
enters request lifecycle cleanup. No automatic protected reconnect or legacy
downgrade is introduced.

## Boundaries and verification

WebRtcService, SenderSignalingChannel, ReceiverEngine, ProtectedReceiverWire,
RequestReceiverOwner, WebRtcReceiverSession, ProtectedRequestSignaling, PairingHost,
AutoPairing, ReceiverSignaling, setup/status coordinators, CaptureService, manifest,
NAS/AppPairing and relay are unchanged. Persistent legacy #device behavior is
unchanged. Request assignment codes remain absent from MainActivity code UI,
ReceiverHome/QR and diagnostics. No bearer/consent/flow persistence or new logging.

Tests exercise the actual controller, handoff, pure retained consent owner and
lifecycle coordinator with controlled workers, fake transport/media and queued
callbacks. Android Intent fields, separate consent wiring, extra scrubbing and
renderer reuse receive source audits plus compilation. These do not claim an
instrumented Activity/FGS/MediaProjection or physical rotation test.

Process death may lose flow, handoff, consent and backoff under V1. A new process
does not reconstruct/adopt authority from restored Activity type or missing tokens.

PHYSICAL Activity/media/rotation tests: PENDING.
PHYSICAL /connect DOMAIN VERIFICATION: PENDING — second Android device unavailable.
No device installation or browser end-to-end claim is made in this checkpoint.
