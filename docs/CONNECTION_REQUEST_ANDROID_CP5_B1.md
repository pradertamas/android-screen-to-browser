# Android request control-plane foundation (CP5-B1)

The lazy process singleton `ConnectionRequests.controller` owns request contexts;
it is not another MediaRole owner and never calls AppMediaRoles. Activity recreation
and background transitions do not discard an existing context while the process
lives. **Process death recovery is not guaranteed**: all credentials are memory-only,
with no preferences, SavedStateHandle, backup, database or DataStore persistence.

ConnectionRequestApiClient is separate from the NAS PairingApiClient. Its HTTPS
base is derived from the existing WebRtcConfig.RELAY WSS origin (validated /ws,
no credentials/query/fragment). Signaling configuration is unchanged. OkHttp uses
no redirects, a 12-second call timeout, no-store and a 16 KiB response limit.
Local codec tests use test-only org.json; no runtime dependency was added.

HTTP mapping: create -> POST /connection-requests; status -> GET /:id/status;
accept/cancel/withdraw/participant-state/session -> corresponding POST suffixes.
Initiator uses initiatorSecret; preview uses capability; accepted recipient uses
claim. Claim is generated/stored before Accept and reused after uncertain responses
or conflict, never silently rotated. Cancel/withdraw retry retains credentials
until explicit clear, so the relay's idempotent terminal operation remains possible.

Controller operations run through a serial executor. Context mutation and StateFlow
publication share a lock. Every operation has a generation guard; superseded
responses cannot overwrite the current state. Superseded successful creates are
best-effort canceled on the worker. Replacing an active initiator first attempts
Cancel, then creates the replacement even if Cancel failed; the safe cancellation
error is published with the replacement. An unreachable old request can therefore
remain server-side until expiry. CONNECTED replacement/Cancel is rejected locally.
clear() is explicitly local cleanup, not an implicit server Cancel/withdraw.

Public UI state contains metadata, operation and safe error categories only.
Secrets live in private contexts/redacted wrappers; no logging or raw exception
messages are published. Unknown state/invalid JSON/deadlines/assignment return
INVALID_RESPONSE; 404 does not fabricate a terminal state. 410 carries parsed
terminal metadata. Readiness/refresh are one-shot calls, with no lease/poll timers.

The next checkpoint must explicitly integrate UI/lifecycle and share delivery.
This checkpoint adds no Activity, manifest, App Link, Share Sheet, protected WS,
MediaProjection, native receiver, legacy or NAS pairing behavior.
