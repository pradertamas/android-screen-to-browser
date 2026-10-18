# CP5-B4D — request media adapters (explicit entry points only)

Baseline: `7ab2e90deabffdd9dde2b3b8ed14ca274e5977ee`.

## Media reuse and boundary

This checkpoint adds signaling adapters to the existing media owners. It does not
automatically start request media from MainActivity, ConnectionRequestActivity or
ReceiverActivity. B4E owns consent/navigation/automatic entry and final gate cleanup.
There is no new service, PeerConnection implementation, codec stack or renderer.
WebRtcReceiverSession is unchanged. Sender capture, encoder profile, STUN,
offer/answer/ICE algorithms, resize, statistics and foreground-service lifecycle
remain in WebRtcService. Receiver answer/ICE/rendering remain in the existing session.

## Sender

SenderSignalingChannel provides registration, peer presence, payload and ended
events, send/close and owner lifecycle hooks. LegacySenderSignalingChannel wraps
PairingHost attach/send/streaming/mediaStopped with the existing epoch. Its
synthetic peer presence preserves the already-established pre-consent legacy path.
PairingHost itself is unchanged.

WebRtcService has explicit LEGACY/REQUEST modes. Omitted mode retains LEGACY for
existing callers. REQUEST never infers authority from a session code. Its Intent
contains only explicit mode, the system MediaProjection consent result and a
random process-local handoff key. Legacy session/intent/epoch extras are rejected
in REQUEST mode. No bearer bootstrap, request ID, claim, secret, session code or
protected URL crosses that Intent boundary.

REQUEST startup is foreground service -> existing capture initialization -> track
created and projection not stopped -> handoff redemption -> fresh exact-flow
bootstrap -> protected socket open/auth. Capture failure prevents redemption/open;
service disposal abandons an outstanding reservation. Protected registered only
marks local signaling readiness. Only REAL protected peer-present invokes the
existing createPeerAndOffer path. No synthetic presence, PairingHost callback,
code regeneration or downgrade exists on this path. Request pairingCode is empty
and SDP/capture/signaling errors are generic. Socket closes before media disposal;
the media owner acknowledges disposal after native resources have been disposed.

## Process-only handoff and media acknowledgement

ConnectionRequestMediaHandoff stores random opaque keys and exact local flow
selectors, never precomputed bearer bootstraps. The key wrapper, owner and
redemption have redacted diagnostics. The raw random key is only an internal
process-local service handoff value, not a network authority or UI value.

Reserve checks exact flow, SEND/RECEIVE role and CONNECTING assignment. Consume
removes the entry exactly once, including concurrent callers. The controller then
revalidates the exact current flow and exports a fresh bootstrap. Same-role A->B
replacement does not adopt B. CONNECTED/stale/exit-requested flows fail closed.
Owner state is RESERVED -> STARTED -> DISPOSED under the controller lock. A live
STARTED media owner prevents initiator context replacement until local disposal.
Stale disposal can update its own acknowledgement but cannot pause/modify B.
Process death loses all handoffs and ownership; there is no persistence/recovery.

## Receiver

ProtectedReceiverWire maps protected registration to local registration, real
peer-present to Engine.present, offer/ICE to the existing media session and
peer-left/bye/transport failure to ended. Inbound answer fails closed. Events are
enqueued on the retained owner's main Handler before Engine consumes them, so
socket installation precedes callbacks. No legacy presence/token/code path is used.

ReceiverEngine explicitly enters REQUEST_BOUND policy. CREATE and JOIN are
signaling intents from the controller assignment; both remain answerers. Request
mode does not generate/normalize public codes, retry conflicts or schedule the
legacy five-minute CREATE rotation. ReceiverHome.code stays empty. Stop cancels
timers, closes socket/media and acknowledges disposal without starting anything.
The legacy CREATE/JOIN policy and ReceiverSignaling remain unchanged.

ReceiverViewModel.startRequest is an internal, uncalled B4E entry point. The
existing sole AppMediaRoles receiver registration and current-role admission stay
in force. RequestReceiverOwner, Engine and WebRtcReceiverSession are retained with
the ViewModel. Renderer detach/reattach never consumes a second handoff or opens a
second socket. stopRequest is exact-flow scoped. No Activity owns a protected socket.

## Exit and reconciliation foundations

cancelInitiator admits only the exact initiator attempt in WAITING/ACCEPTED/
CONNECTING with no active operation and no undisposed STARTED owner. Existing
exact recipient withdraw applies only to the winning recipient before CONNECTED.
requestMediaExit requires local teardown acknowledgement before HTTP exit and
marks the flow exit-requested. A busy admission or NETWORK failure retains that
intent, context and authority: explicit retry is possible, new media reservation
and bootstrap export are blocked. Readiness/setup role admission also refuses an
exit-requested flow.

HTTP conflict never invents local CANCELED/FAILED metadata. Explicit
reconcileMediaExit performs exact-flow authenticated status (including CONNECTED).
Generation/context guards discard old exit/status completions after replacement.
clearDisposedMedia accepts only exact disposed owners with server terminal or
CONNECTED state and no active operation. It never releases the gate. CONNECTED
means both signaling participants authenticated, not WebRTC media-connected.
The B4A HTTP monitor may stop at CONNECTED; media lifecycle belongs to the service
or retained receiver owner thereafter. There is no protected auto-reconnect or
legacy fallback. B4E must wire scoped exit/reconciliation, user-facing failure and
atomic gate cleanup after disposal; these are not automatic B4D operations.

## Gate, privacy and verification limits

The request-first gate stays ARM throughout request media start/stop. These new
adapters do not release it or restart legacy pairing. No new credential logging,
UI state, SavedState, Bundle or preference persistence is introduced. Activities,
manifest, setup/monitor, PairingHost, ReceiverSignaling, protected transport and
relay backend are unchanged.

JVM tests use the real protected transport with fake sockets, queued callbacks,
controlled controller workers and fake media/timers. They cover one-shot and
stale handoffs, capture-before-auth sequencing, real peer gating, request receiver
policies, retained owner reuse, scoped exits, lost-response retry and CONNECTED
reconciliation. Native capture, foreground service and renderer recreation still
require device testing; JVM retained-owner tests do not claim a device rotation test.

PHYSICAL /connect DOMAIN VERIFICATION: PENDING — second Android device unavailable.
Physical request media end-to-end is pending B4E wiring and device testing.
