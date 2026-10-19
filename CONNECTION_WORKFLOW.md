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
retains authority/gate and uses the existing exact-target retry. Other recipient,
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

MainActivity can remain visible during live receiver grace. Step 2 replaces Share
with Show active share / Stop active share while keeping both manual cards disabled.
Show returns to the central session; Stop immediately disposes it. Deadline expiry
also disposes once and publishes idle, restoring normal Step 2. Domain admission,
role switching, request creation, the production legacy start gate and receiver
retry/create also reject conflicting live starts; disabled buttons are not authority.

The viewer centers an explicit aspect-fit rectangle calculated from rotated numeric
frame dimensions. Unused space is black. It has no permanent Receiving header or
diagnostics footer. Tapping video reveals a Stop receiving overlay for three seconds.
Diagnostic collection/logging and the diagnostic composable are retained.

The independent four-second ReceiverEngine network-disconnect grace is unchanged.
Real signaling/network failure can terminate the peer during UI grace. Request-owned
media stays STARTED during UI absence and is disposed only on actual stop/failure/
expiry. No background receiver service or persisted live session is added. Retention
is best-effort while the process survives; process death starts idle. Suspended timer
delivery may be late, so elapsed deadlines are rechecked when returning.

Physical validation remains required: full-frame edges across aspect ratios,
Home/return, Back/Show, explicit Stop, >60s absence, viewer/Main rotation, independent
network loss, and request-owned plus legacy/manual streams.
