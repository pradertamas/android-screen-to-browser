# CP5-B4B: accepted request readiness and session setup

The relay grants readiness only in ACCEPTED, with a 10 second lease and a
120 second establishment window. The process-owned setup coordinator renews
ready=true after successful completion at a 5000 ms target. Background cancels
renewal and recipient status timers; it sends no ready=false. The server lease
expires naturally. In-flight HTTP may finish after pause.

MainActivity and ReceiverActivity register separate opaque RESUMED setup owners
for SEND and RECEIVE. Initiator readiness requires matching owner/current role,
the armed request-first gate and the existing idle media policy. Initiator role
is never automatically changed. ConnectionRequestActivity registers its own
retained-flow selector with a per-instance lifecycle owner.

Winning recipient role mapping is SEND initiator -> RECEIVE recipient, RECEIVE
initiator -> SEND recipient. Main-dispatched preparation switches only if needed,
with exact current recipient flow validation at execution. Switch failure does
not force active media, send readiness, discard claim/context, or release the gate.
Claim-scoped recipient status observation continues while foreground even if role
preparation fails; only readiness is withheld.
There is no Activity/Context reference in the coordinator. Role switching uses
AppMediaRoles, the existing single media-role owner; gate protection blocks legacy
auto-start and consent. No media/projection system is changed.

Controller setup APIs reserve operations under the same lock as exact flow/side,
winning authority, state and single-flight checks. Initiator uses its private
initiatorSecret; recipient uses its private winning claim with CLAIM scope.
The internal setup projection has only opaque identity, role/state, readiness,
assignment presence and completion metadata. Completion revisions are private
per-context values, not request identifiers or public UI authority.
The latest STATUS/READINESS/SESSION completions are retained as three bounded,
credential-free records with completion elapsed time, so conflated Flow observation
cannot lose a mutation backoff or extend its origin to observation time. A B4A
successful STATUS does not clear an outstanding readiness/session backoff.

Recipient ACCEPTED foreground status refresh uses CLAIM auth and a 2000 ms target.
It stops on background, stale ownership, CONNECTING assignment, CONNECTED or
terminal states. Initiator status still comes exclusively from B4A; setup adds
no second initiator status loop. All HTTP stays on the controller worker.

Only a successful fresh initiator STATUS completion with both readiness booleans
true can trigger one session operation. Exact completion/flow validation is
repeated under the controller lock; readiness responses and duplicate state emits
cannot reuse it. The relay remains authority for lease validity. A 409 readiness
expiry race preserves ACCEPTED and allows renewal plus a later fresh status to
retry, without a tight loop. A lost session response preserves context; subsequent
B4A status can recover the existing CONNECTING assignment. The scoped session API
also admits fresh CONNECTING status recovery using the relay's idempotent binding
contract. No client session code is generated.

NETWORK/SERVER setup failures retry readiness after 2000 ms. Positive Retry-After
creates an elapsedRealtime monotonic deadline bound to the exact opaque flow.
It survives background/resume, including completion in background. Null/zero
clears it; seconds conversion and deadline addition saturate at Long.MAX_VALUE.
Replacement never inherits backoff. Timers are cancelable with epoch guards;
setup uses coroutine scheduling on Main rather than adding another executor.

Successful session metadata is CONNECTING with CREATE and the initiator role.
Recipient status yields JOIN and the opposite role. Existing parser and controller
assignment validation remains intact. Invalid responses stop setup attempts until
a new valid controller completion; there is no legacy fallback. Assignment code
stays in controller metadata, never new UI, clipboard, diagnostics or logging.

Winning recipient Back/Cancel still withdraws in ACCEPTED and CONNECTING, without
ready=false. Flow-scoped setup suspension precedes withdraw; a failed withdraw
retains claim/context/gate and permits explicit withdraw retry. Stale owners and
callbacks cannot control replacement flows. Terminal and CONNECTED stop timers,
without automatic clear, gate release or restart. The gate stays armed throughout
accepted/connecting setup.

There is no WebSocket/request-auth, protected signaling, legacy CREATE/JOIN,
PeerConnection, offer/answer/ICE, MediaProjection consent or WebRtcService start.
No new UI or Cancel flow is added. Share, explicit Accept, uncertain-Accept claim
retry, NAS /pair and manual legacy workflows retain their existing behavior.
All state is process-memory only; process-death recovery is not guaranteed by V1.
Protected signaling/media setup is a later checkpoint.

PHYSICAL /connect DOMAIN VERIFICATION:
PENDING — second Android device unavailable. No device installation is performed.
