# Android connection workflow

The current product has two media roles and three explicit entry actions.

1. Choose role: SEND or RECEIVE, owned by AppMediaRoles.
2. Choose how to start:
   - Share connection link (primary, first action).
   - Connect to another device.
   - Let another device connect here.

## Share connection link

WorkflowStart -> rememberRequestShare -> ConnectionRequestShareCoordinator ->
ConnectionRequestController.create(role) -> POST /connection-requests on the relay.
ConnectionRequestLink builds the full HTTPS /connect fragment URL. The native
Android ACTION_SEND chooser receives that complete URL. ConnectionRequestActivity
handles the recipient link and requires explicit Accept.

Creation begins only on a user tap. While CREATE is pending the button reads
"Preparing link..." and is disabled. Successful preparation offers one chooser;
return/cancel/back restores the normal label and enabled state. Multiple taps do
not create parallel requests or duplicate choosers. Creation failure shows an
inline error and permits explicit retry. No artificial delay or retry loop exists.
Read-only foreground STATUS does not invalidate an already-created WAITING link.
Expiry and server authority remain governed by the existing request API metadata.

Accepted setup, exact-flow handoff, consent-before-sender-auth, protected signaling
and the existing media engines are reused. HTTP CONNECTED means
signaling participants authenticated, not WebRTC media connected. Temporary request
credentials remain process-memory only.

## Connect and show

Connect uses the existing scanner or six-character code entry/JOIN behavior.
Show uses the existing own CREATE registration, QR and code. Back changes the
presentation path according to the existing media/consent guards before live receiving.
Sender MediaProjection and first-frame establishment remain unchanged.
The independent sender #device remote-link capability remains unchanged.

Choosing Connect or Show after returning from Share explicitly leaves the idle
WAITING initiator link: its exact request is canceled through the existing media
cleanup coordinator before the legacy registration starts. This invalidates that
shared link only when the user chooses the alternative connection method, not when
the chooser is dismissed. The request gate is never released blindly. Cleanup error
before media has started retains authority/gate and uses the existing exact-target retry. Other recipient,
accepted or active media contexts cannot be exited by this navigation action.
During the transition Connect keeps scanner/code/Connect controls visible but
disabled; Show explains code preparation until a real accepted code is available.

## Android App Links and configuration

The sole external VIEW connection entry is ConnectionRequestActivity with HTTPS,
exact /connect, DEFAULT/BROWSABLE, autoVerify and singleTop. Its existing
pairingAppLinkHost placeholder and PAIRING_APP_LINK_HOST build/local setting remain
unchanged; deployment uses pairing.pradert.synology.me. The dialog theme resource
keeps its historical Theme.PairingInvite name because the connection Activity uses it.
Android source cleanup does not modify server endpoints, assetlinks or database data.

Old device_identity.xml backup exclusions remain as a safeguard for data left by
older installations; there is no current reader/writer for that retired preference.
No stored credential migration or deletion is performed by this source cleanup.

## Regression boundary

Unit tests cover strict /connect parsing, manifest routing, absence of retired client
state/API consumers, request share readiness and one-shot delivery, recipient explicit
Accept, ownership, cleanup, consent, manual connection and receiver media policy.
Device installation, actual Android chooser/App Link delivery and media behavior are
not claimed by static/JVM validation.

## Native receiver lifetime and viewer

ReceiverSessions owns one process-scoped ReceiverSessionCoordinator, wrapping the
existing ReceiverEngine, signaling adapters and WebRtcReceiverSession. ReceiverViewModel
is only an Activity facade. Clearing a viewer does not clear the live peer. The
AppMediaRoles receiver registry binds the central owner, not individual facades.

Connection phase and viewer visibility are separate. Only first-frame RECEIVING
may enter UI grace. Home/app switch or Back from live video detaches the renderer
and starts one absolute elapsedRealtime deadline, 60 seconds after loss of visibility.
Returning before the deadline cancels expiry and reattaches to the same track/peer;
no new code, CREATE/JOIN, handoff or bootstrap is used. Configuration replacement
does not start or extend grace. Stale visibility, renderer-release and timer callbacks
cannot detach/stop a replacement. Connecting still stops when genuinely left.

MainActivity is also a foreground viewer of the retained receiver: it cancels the
absence deadline, and a new absence window starts only when Screen Share leaves
the foreground again. Step 2 replaces Share
with Show active share / Stop active share while keeping both manual cards disabled.
Show returns to the central session; Stop immediately disposes it. Deadline expiry
also disposes once and publishes idle, restoring normal Step 2. Domain admission,
role switching, request creation, the production legacy start gate and receiver
retry/create also reject conflicting live starts; disabled buttons are not authority.

The viewer centers an explicit aspect-fit rectangle calculated from rotated numeric
frame dimensions. Unused space is black. It has no permanent Receiving header or
diagnostics footer. Tapping video reveals a Stop receiving overlay for three seconds.
Diagnostic collection/logging and the diagnostic composable are retained.

Receiver network grace remains four seconds. A foreground-started disconnect keeps
that grace even if the UI later hides. For a RECEIVING attempt previously established
by actual PeerConnection CONNECTED, a disconnect beginning under accepted background
authority is instead retained within the existing absolute 60-second absence bound.
Returning foreground while still disconnected transfers that episode once to a fresh
normal four-second recovery grace. Actual PeerConnection CONNECTED cancels it; ICE
CONNECTED alone does not establish eligibility or cancel recovery grace. Re-hiding an
unresolved foreground recovery episode cannot shelter it or renew its grace. Recovery
while hidden does not extend the original absence deadline. This is not guaranteed
60-second media survival: FAILED/CLOSED, explicit Stop/bye, protocol/auth rejection,
process death and genuine terminal conditions may still end it sooner.
During setup, signaling loss still ends the attempt. Once the peer has connected
and media is RECEIVING, socket/I/O loss marks signaling unavailable and releases
only the dead signaling link. The same peer, media owner, visible active-share state
and UI deadline remain. A late ICE send cannot terminate that retained media session.
Actual peer FAILED/CLOSED, peer-disconnect grace expiry, explicit bye,
protocol rejection, user stop or UI deadline expiry still terminate the session.
No signaling reconnect is attempted: the current request authority exports a fresh
bootstrap only in CONNECTING, not for re-registration of an established request.
Signaling and media are separate liveness domains. After actual WebRTC establishment,
signaling disappearance or peer-left degrades signaling on both endpoints rather than
ending viable media. During setup these events remain terminal. Explicit bye and
protocol/authentication rejection remain terminal in either phase. ICE/PeerConnection
state and the receiver's foreground/background authority govern media liveness;
signaling degradation does not restart or extend deadlines. Intentional receiver Stop attempts a best-effort bye over
available authenticated signaling, then immediately disposes locally regardless of send
success. No retry or reconnection is added.
Request-owned
media stays STARTED during UI absence and is disposed only on actual stop/failure/
expiry. Retention is best-effort while the process survives; process death starts idle. Suspended timer
delivery may be late, so elapsed deadlines are rechecked when returning.

RECEIVE has a foreground execution anchor, ReceiverExecutionAnchorService. It does
not own or reconstruct media: ReceiverSessions/coordinator/engine/native session
remain the owners, and renderer attachment remains Activity-specific. The coordinator
requests one anchor after first-frame RECEIVING with committed media and accepted
foreground UI. WAITING, rejected/stale handoffs and wrong-role flows cannot start it.
The service validates the exact process-local receiver identity again before promoting.

The anchor uses specialUse with a declared subtype for bounded live screen-share
receiver continuity while rendering is detached. RECEIVE does not capture a display;
it does not claim mediaProjection, connectedDevice prerequisites or ordinary background
media playback. This platform-supported declaration still requires justification for
distribution policy; it is not a guarantee of OEM/network behavior.

An ongoing Receiving screen share notification offers Stop for that exact lifetime;
the action delegates to existing receiver Stop authority. Notification permission is
requested from visible receiver UI, without blocking media if denied. Android still
requires the FGS notification, but denial can hide it from the notification drawer.
Selectors are opaque local UUIDs, never credentials, and cannot reconstruct a peer.
START_NOT_STICKY means no automatic session restoration or intent redelivery. An absent
process-local lifetime or stale command cannot attach to or stop a replacement.

The anchor stays active through signaling degradation, background DISCONNECTED retention
and natural recovery. It stops when the exact receiver becomes terminal. It adds no
timer: the existing absolute 60-second absence deadline remains authoritative, and native
FAILED/CLOSED and explicit Stop/bye remain terminal. No signaling reconnect, ICE restart,
renegotiation or replacement peer is added. This is an execution-continuity experiment,
not a claim that a foreground service prevents the physical transport failure. Logs include
anchor-foreground-established and anchorForeground on receiver lifecycle snapshots.

Physical validation remains required: full-frame edges across aspect ratios,
Home/return, Back/Show, explicit Stop, >60s absence, viewer/Main rotation, independent
network loss, and request-owned plus legacy/manual streams.

## Ended request ownership

The controller remains the single request authority. When an exact STARTED media
owner acknowledges disposal, that local media lifetime is over even if the last
HTTP snapshot still says CONNECTING. Cleanup retires that context and releases its
gate once, without waiting for withdraw/status HTTP to succeed. One best-effort
exit may run with the retired request's credentials; its result is diagnostic only
and cannot publish into a replacement context. No retry loop or server-state fiction
is introduced. Existing generation/context checks reject late HTTP completions.

Known terminal/unavailable previews with no reserved/running media are also eligible
for retirement. Recipient admission rechecks these conditions before reporting a
conflict. FINISH_NO_CONTEXT uses the same guarded check; it never clears a live
replacement request. A retained dialog may accept a fresh explicit App Link only
after its old flow has lost authority and the controller is idle. Its local transfer
flags are reset for that newly admitted flow.

Live media (including signaling-degraded media), pending reservations, active setup
and unresolved pre-media Accept/exit results remain protected. Activity destruction,
role SEND, or renderer absence alone are never proof that request ownership ended.
