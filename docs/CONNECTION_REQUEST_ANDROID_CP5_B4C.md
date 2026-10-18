# CP5-B4C: protected request signaling foundation

Foundation only: there is NO production bootstrap acquisition or WebSocket open
call site in any Activity, monitor, setup coordinator or media engine. Nothing
automatically connects on CONNECTING/assignment. Explicit fake-socket tests drive
the new transport. Real two-device verification remains PENDING — second Android
device unavailable. No device is installed and no production relay smoke is run.

The relay's CONNECTED currently means **both protected signaling participants
authenticated**, not WebRTC media connected. Opening both sockets now would advance
the request before the media handoff exists. The next integration checkpoint must
coordinate that boundary with media readiness; B4C deliberately does not wire it.

## Bootstrap and authority

The controller exports a short-lived internal ProtectedSignalingBootstrap under
its authority lock, with no HTTP/socket call or public state mutation. Admission
requires exact opaque create-attempt/recipient-flow identity, CONNECTING, canonical
assignment code, and the correct assignment role/intent. Initiator is CREATE with
its original role and uses initiatorSecret. Winning recipient is JOIN with the
opposite role and uses its claimSecret. Capability is never socket authority.
ACCEPTED, CONNECTED, terminal, stale and non-winning flows return null; malformed
assignment is rejected rather than repaired. Existing HTTP parser guards remain.

The bootstrap contains an opaque flow, protected URL, role/intent and a private
consume-once auth frame. Its toString is redacted. It is not placed in UI StateFlow,
Bundle, SavedState, Intent, persistence, logs or diagnostics. Separate requestId or
secret properties are not exposed. A new bootstrap can be acquired for another
explicit attempt while the same CONNECTING context remains valid. A previously
exported A snapshot never gains B authority and is not a revocable lease; future
integration must acquire directly before open, check ownership and close stale
transports. No long-lived bootstrap cache is introduced.

## Wire contract

Production URL, built through java.net.URI:

`wss://realtime-relay-k28l.onrender.com/ws?session=<6-char>&role=sender|receiver&intent=create|join`

Exact /ws, canonical session code and exactly these three query parameters. No
requestId, secret, remote token or presence query. SEND maps to sender, RECEIVE
to receiver. The complete URL is sensitive because it contains the code and must
never be logged.

On socket open the FIRST outbound text is exactly the three-field object:
`{"type":"request-auth","requestId":"<22-char>","secret":"<43-char>"}`.
Canonical 16-byte requestId and 32-byte participant secret are required. The frame
is under the relay's 512-byte limit, consumed once, sent once, then discarded by
the transport. Missing/oversized auth or send=false fails closed. The relay's auth
timeout is 10 seconds; B4C does not add automatic client timeout/retry policy.

No application send is queued or permitted before registration=ok. Registration
must match the relay's protected schema: status=ok, matching role/intent and
remotePresence=false. A registration is accepted once. Premature peer-present or
application payload, non-ok/malformed registration, duplicate registration and
post-auth request-auth fail closed. Remote presence and legacy downgrade are not
supported.

After registration, peer-present emits at most one logical event. Offer, answer,
ice, peer-left and bye JSON texts can be forwarded verbatim to the event layer;
SDP/ICE contents are not interpreted. Standard JSON syntax, unique object keys,
bounded depth and strict lifecycle/control fields are validated. Malformed JSON,
binary input and oversized inbound fail closed. Inbound/outbound text limit is
64 KiB UTF-8, inclusive; oversized outbound is not sent and fails closed.

Local close uses 1000. Server 1008, unexpected close or socket failure produces a
generic failure event without reason, exception, URL or body output. No automatic
retry, new code generation or legacy fallback exists. The OkHttp adapter has no
logging interceptor, disables connection retry and redirects, and is instantiated
only by an explicit future caller. Tests use fake factories without internet.

## Concurrency and checkpoint limits

Transport state and sends are serialized by a lock. Auth reserves its state before
send, including synchronous fake callbacks. Consumer events are queued in order
and drain through a testable dispatch boundary outside the state lock. Pending
handoff/payload events are discarded on terminal failure/local close. No Activity
is retained. The controller never opens a socket under its lock.

There is no Activity wiring, WebRtcService/ReceiverEngine request-first start,
PeerConnection, offer/answer/ICE generation, MediaProjection consent or stream.
PairingHost, ReceiverSignaling, all media engines/services, manifest, NAS pairing
and relay backend remain unchanged.

Two B4B LOW coverage gaps are closed with deterministic tests: recipient STATUS
every 2 seconds and readiness every 5 seconds over 20 seconds without starvation;
and withdraw NETWORK failure followed by stale renewal and queued role callbacks,
with setup suspended and flow/claim/gate preserved for retry. Setup production
code is unchanged.
