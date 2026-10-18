# CP5-B2B: initiator Share orchestration

Normal SEND and RECEIVE **Share connection link** now use the process-owned
`ConnectionRequests.controller`. Manual Connect/Let/QR/code and diagnostic
persistent-device Copy/Regenerate remain legacy features.

## Start and quiet state

The Activity callback checks the main thread, then synchronously executes:

1. ARM `ConnectionRequestRuntimeGate` (new create or defensive reshare).
2. SEND: `PairingHost.controller.hide()` then `close()`; RECEIVE: `model.stop()`.
3. Reuse a valid same-role WAITING initiator request, or enqueue HTTP create.
4. Publish one owner-scoped chooser delivery; launch native ACTION_SEND/text/plain.

There is no post, yield or asynchronous boundary between ARM and stop. Sender
hide clears visibility; close cancels rotation/debounce/timeout/recovery, removes
the socket and advances its epoch before socket closure. PairingHost closes and
cancels the transport. Receiver stop clears running, advances generation, cancels
timers, closes signaling/media and clears the native session. Late callbacks and
already queued timer actions cannot restart these stopped owners. Existing gate
checks block lifecycle, role, ViewModel and manual starts while armed. Stop/close
remain unrestricted. Share is unavailable during active legacy media setup/media.

## Link and credentials

Canonical format (stable fragment order):

`https://pairing.pradert.synology.me/connect#v=1&r=<id>&c=<capability>&i=<send|receive>`

The URI builder accepts only canonical 128-bit, 22-character Base64URL identifiers
and capabilities. No query, initiator secret, claim or session code is included.
The targeted controller API returns only link data and has a redacted string
representation. Neither general request UI state nor share UI state contains
credentials or the link. The full URL is delivered only to ACTION_SEND EXTRA_TEXT
following an explicit user action; it is never diagnostic output.

## Reshare, ownership and failures

An unexpired same-role WAITING request reuses the identical link. If the gate is
already armed, this reshare fast path does not ARM or stop the quiet legacy owner
again. If the gate is unexpectedly released, ARM and explicit stop precede reshare.
Every new create still requires ARM followed synchronously by explicit stop.

Android validity uses `SystemClock.elapsedRealtime()`, including deep sleep.
The private process-memory deadline is anchored at create invocation, before any
replacement cancel or HTTP latency. The audited relay create response guarantees
`serverNow` equals creation time and `expiresAt` equals creation time plus TTL;
it does not include `createdAt`. Thus the deadline is create-start elapsed milliseconds
plus (`expiresAt` - `serverNow`). No epoch timestamp is compared directly with elapsed
time, no client TTL constant is introduced, and status updates do not extend this
deadline. At or after the deadline, WAITING is not reusable and an explicit Share
may create a replacement. The production clock is injectable for deterministic tests.

A different-role WAITING request follows existing best-effort
cancel/replacement policy. ACCEPTED/CONNECTING/CONNECTED refuse Share locally.

Create returns an opaque local attempt handle immediately when the local start
is accepted, not when HTTP succeeds; refusal returns null. The private initiator
context retains its origin attempt. The coordinator uses unscoped lookup only for
an explicit existing-request reshare; new-create completion and delivery consumption
require that exact origin attempt. Current WAITING/role alone never suffice.
If an external create supersedes an invocation, the old invocation finishes safely
without delivery when controller work settles. It cannot release the newer attempt's
gate, even if that newer attempt fails. A subsequent explicit Share can reshare the
new valid WAITING request. The existing generation guard and abandoned-result
best-effort cancel remain intact. Attempt handles are redacted and absent from UI
state, URLs, storage and logging.

One process coordinator owns single-flight through create and chooser completion.
A second tap cannot repeat ARM/stop/create or open another chooser. The coordinator
retains no Activity, Context, stop callback, credentials or URL. It observes controller
StateFlow events; this observer makes no HTTP calls and is not polling.

Create failure produces no chooser. The gate releases only when the controller
has no locally owned request/context/operation, and release does not restart legacy
pairing. The existing controller replacement policy retires the old local context
even if best-effort cancel fails; if replacement also fails, a remote orphan may
remain until server expiry. This checkpoint does not change that policy.

Chooser failure leaves the request WAITING and gate armed. Retry shares the same
request. Returning from the chooser neither creates nor cancels a request.

An opaque composition owner scopes chooser delivery. Disposal/recreation or media
role change abandons automatic delivery, never the process-owned HTTP request.
In-flight create finishes once; its completion without a current owner does not
launch a chooser. The user can tap Share again to reuse the completed WAITING
request. Old chooser callbacks cannot consume another owner's delivery.

## Checkpoint boundary

No status polling, readiness renewal, participant-state/session invocation,
request-auth/WebSocket setup, MediaProjection, recipient App Link or Accept UI is
added. WAITING has no legacy or request-bound socket/media session. Subsequent
checkpoints must implement foreground status handling, explicit cancel/return to
legacy UX, recipient preview/Accept and protected setup separately. The request
and gate remain process-lifetime only; no process-death recovery is introduced.

Tests use fake transport and a controlled executor for create/reshare/replacement,
failures, owner lifecycle and single-flight. Additional real AutoPairing and
ReceiverEngine fake-wire tests fire stale timers/callbacks after stop; source
wiring checks supplement these behavioral tests. No production request is made.
