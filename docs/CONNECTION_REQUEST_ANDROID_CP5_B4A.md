# CP5-B4A: initiator foreground status monitor

The process-owned ConnectionRequestInitiatorMonitoring singleton observes controller
and app-role events. Observation itself makes no HTTP call until the monitor decides
an eligible refresh is due. There is one cancelable scheduler future, not parallel
polling loops. The production ScheduledThreadPoolExecutor uses one daemon thread
and removes canceled tasks. Tests use a controlled scheduler and worker executor.

## Foreground and role policy

MainActivity registers its own opaque owner as SEND in onResume and removes it in
onPause. ReceiverActivity does the same with RECEIVE. Foreground means RESUMED, not
STARTED. The process monitor stores opaque identities and MediaRole values, never
an Activity, Context, Intent, URI, requestId or credential. Multiple matching owners
share the same loop; removing one does not stop the others. Only an owner matching
the request's initiator role is eligible, and the current AppMediaRoles role must
also match. A role-state change re-evaluates eligibility without switching roles.

An eligible active initiator context triggers immediate refresh on foreground entry,
resume or request replacement, unless the same request still has server backoff.
Active states are WAITING, ACCEPTED and CONNECTING.
CONNECTED, CANCELED, EXPIRED and FAILED stop timing. Recipient contexts never poll.
The monitor keeps only the existing opaque create-attempt identity to distinguish
request replacement; it never retains full request metadata or wire identifiers.

## Scheduling and controller boundary

Normal 2000 millisecond cadence is foreground-only. Server Retry-After is separate:
after STATUS completion, a positive value establishes a private monotonic deadline
using injected elapsed milliseconds (production: SystemClock.elapsedRealtime()).
The next foreground poll waits max(2000 milliseconds, remaining server backoff).
Both seconds conversion and deadline addition saturate safely at Long.MAX_VALUE
rather than wrapping to an early poll. Null/zero Retry-After clears server backoff.
Duplicate state emits do not create another GET or timer. A synchronous completion
also schedules the next attempt instead of recursively starting more GETs.

No eligible RESUMED owner means cancel the future. A canceled callback is epoch
guarded even if already queued. An operation admitted while foreground may complete
after pause; completion while background records server backoff without scheduling
another request. Background cancels normal cadence, but preserves server backoff
for the exact opaque target. Early resume waits the remaining backoff; resume after
expiry (or without backoff) refreshes immediately. Request replacement, including
same-role replacement, never inherits the old target's backoff. Terminal/CONNECTED,
recipient or absent contexts discard the old backoff. Process death may lose it
under V1 policy; no persistence is introduced. If a
GET is already active, it satisfies that refresh and its completion starts cadence.

refreshInitiatorStatus(expectedRole, expectedFlow) checks side, role, opaque context
origin, active state and operation admission under one controller lock. The controller
alone reads initiatorSecret and sends initiator-scoped status. It returns false without
changing state/error when admission is ineligible or busy. Any existing operation
blocks a new GET. Mutation completion is re-evaluated; an active GET is never duplicated.
The existing generation guard ignores old A responses after B replaces the context.

## Checkpoint limits and verification

There is no new request UI or Cancel UI. Terminal handling only stops monitoring:
no automatic clear or gate release. ACCEPTED/CONNECTING are observed server states;
this monitor does not call readiness, participant-state, session creation, request-auth,
WebSocket, WebRTC, MediaProjection or legacy CREATE/JOIN. Share, recipient /connect,
explicit Accept/withdraw, NAS /pair and manual legacy behavior remain separate.
No production logging or persistence is added. Process death loses controller/monitor
state, consistent with V1. Accepted setup and cleanup UX belong to later checkpoints.

PHYSICAL /connect DOMAIN VERIFICATION:
PENDING — second Android device unavailable.

No device installation, physical App Link test or production HTTP/WSS smoke is run
in this checkpoint. Unit coverage exercises immediate/cadenced refresh, Retry-After,
background and stale timers, multiple owners, role changes, context replacement,
initiator-only authorization and no setup/cleanup side effects.
