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
and the existing sender/receiver media owners are unchanged. HTTP CONNECTED means
signaling participants authenticated, not WebRTC media connected. Temporary request
credentials remain process-memory only.

## Connect and show

Connect uses the existing scanner or six-character code entry/JOIN behavior.
Show uses the existing own CREATE registration, QR and code. Back changes the
presentation path according to the existing media/consent guards. Receiver rendering,
rotation retention, first-frame handling and sender MediaProjection are unchanged.
The independent sender #device remote-link capability remains unchanged.

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
The connection implementation and active media engines are preserved byte-for-byte.
Device installation, actual Android chooser/App Link delivery and media behavior are
not claimed by static/JVM validation of this cleanup.
