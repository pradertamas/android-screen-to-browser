# Android request control-plane foundation (CP5-B1)

The lazy process singleton `ConnectionRequests.controller` owns request contexts;
it is not another MediaRole owner and never calls AppMediaRoles. Activity recreation
and background transitions do not discard an existing context while the process
lives. **Process death recovery is not guaranteed**: all credentials are memory-only,
with no preferences, SavedStateHandle, backup, database or DataStore persistence.

ConnectionRequestApiClient is separate from the NAS PairingApiClient. Its HTTPS
base is derived from the existing WebRtcConfig.RELAY origin (wss -> https, ws ->
http; port retained, exact raw /ws path, no credentials/query/fragment). Invalid
configuration returns CONFIGURATION through the normal error model, never a raw
URL/exception. Signaling configuration is unchanged. OkHttp uses
no redirects, a 12-second call timeout, no-store and a 16 KiB response limit.
Local codec tests use test-only org.json; no runtime dependency was added.

HTTP mapping: create -> POST /connection-requests; status -> GET /:id/status;
accept/cancel/withdraw/participant-state/session -> corresponding POST suffixes.
Initiator uses initiatorSecret; preview uses capability; accepted recipient uses
claim. Claim is generated/stored before Accept and reused after uncertain responses
or conflict, never silently rotated. Cancel/withdraw retry retains credentials
until explicit clear, so the relay's idempotent terminal operation remains possible.

The low-level API/transport are synchronous and must not be called from UI code.
Controller operations run through a serial executor. Context mutation and StateFlow
publication share a lock. Context generation changes only on create/replacement,
preview or clear, never on ordinary operations. Every response checks its captured
generation/context; an old response cannot reset the new context's operation.
Exactly one operation is in flight per current context. An explicit mutation
attempt while any operation is in flight returns OPERATION_IN_PROGRESS locally,
without queuing/sending another HTTP call. Refresh during any in-flight operation
is a silent no-op: no generation/error/loading changes. This also coalesces duplicate
refreshes. Success and safe/network failures release the flight. A later refresh
runs normally. The claim is generated once before the first queued Accept and is
retained across lost responses/retries; a skipped refresh cannot lose winning rights.
Superseded successful creates are
best-effort canceled on the worker. Replacing an active initiator first attempts
Cancel, then creates the replacement even if Cancel failed; the safe cancellation
error is published with the replacement. An unreachable old request can therefore
remain server-side until expiry. CONNECTED replacement/Cancel is rejected locally.
clear() is explicitly local cleanup, not an implicit server Cancel/withdraw.

Public UI state contains metadata, operation and safe error categories only.
Secrets live in private contexts/redacted wrappers; no logging or raw exception
messages are published. Unknown state/invalid JSON/deadlines/assignment return
INVALID_RESPONSE; 404 does not fabricate a terminal state. 410 carries parsed
terminal metadata through the same scoped validation as status. Explicit auth scope
distinguishes initiator, preview capability and winning claim. Privileged accepted
status requires acceptedAt/connectBy and boolean readiness; connecting also requires
assignment, connected requires connectedAt, and failed requires safe failureReason.
Capability status accepts only reduced metadata; it does not require or expose
privileged fields. Malformed fields/types/timestamps and unknown states are rejected.
Expired/canceled records without accepted setup do not need setup timestamps.
Readiness/refresh are one-shot calls, with no lease/poll timers.

The next checkpoint must explicitly integrate UI/lifecycle and share delivery.
This checkpoint adds no Activity, manifest, App Link, Share Sheet, protected WS,
MediaProjection, native receiver, legacy or NAS pairing behavior.
