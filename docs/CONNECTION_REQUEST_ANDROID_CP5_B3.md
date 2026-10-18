# CP5-B3: recipient /connect control plane

`ConnectionRequestActivity` is a separate exported, singleTop, floating Activity.
Its HTTPS autoVerify intent filter uses the existing pairingAppLinkHost placeholder
and the exact `/connect` path. The NAS `/pair` Activity/filter/backend are unchanged.
Assetlinks hosting/configuration is unchanged. This checkpoint configures Android
routing; actual device domain verification and cold/warm link opening require a
later device smoke (no installation is performed here).

## Link and Intent privacy

The parser accepts only canonical
`https://pairing.pradert.synology.me/connect#v=1&r=<22>&c=<22>&i=send|receive`.
It rejects query, userinfo, explicit ports, alternate origins/paths, duplicate or
extra fields, missing values, alternate order/version, percent encoding, padding
and noncanonical Base64URL. Parsed models have redacted string representations.
The Activity captures raw input only on its stack and clears incoming/current
Intent data BEFORE parsing, including onNewIntent. Only a non-secret ownership
boolean is saved; no URL, identifier, capability or claim is saved/logged/rendered.

## Entry, BUSY and ownership

Invalid input has no gate, stop, controller or network effects. Valid input first
checks existing controller context/operation conflict, then AppMediaRoles.pairingAllowed.
This policy permits idle sender CREATING/WAITING and receiver PAIRING, but rejects
consent/connecting/streaming/capture/receiving. A floating window avoids deliberately
stopping the underlying Activity/media simply by covering it.

On the main thread, with no yield: ARM -> stopIdleLegacyForRequestFirst -> preview
-> one initial capability status GET. The helper rechecks the idle policy, stops
SEND via hide + close or calls the registered RECEIVE owner stop callback. It never
switches media role or starts anything. Existing lifecycle/manual start gates prevent
legacy restart. Invalid/BUSY/conflict entry cannot overwrite another request context.

The process coordinator retains only an opaque recipient ownership token, safe UI
state and last operation kind. Controller credentials stay private. Token-scoped
query and atomic clear/release prevent this screen from clearing another context.
onNewIntent conflicts are shown safely; an existing owned flow remains intact.

## Preview, explicit Accept and exit

Opening a link only previews and checks status; it never generates a claim or Accepts.
WAITING exposes Accept / Not now. SEND initiator means this recipient will later RECEIVE;
RECEIVE initiator means this recipient will later SEND. No role switch happens here.
Status errors offer explicit status retry; terminal/unavailable and already-handled
requests do not offer Accept. No automatic GET occurs on resume or observation.

Accept rechecks live media-idle and operation state. The existing controller generates
and stores the claim before HTTP; duplicate taps are blocked. Network/lost-response
retry uses the same claim. Accept in flight blocks Back/Close. Outside-touch dismissal
is disabled. After an uncertain Accept failure, Close remains blocked until the user
retries Accept with the retained claim and resolves the outcome; clearing would risk
abandoning a successful but unobserved server acceptance. Conflict/unavailable or a
terminal response permits local exit.

Accept success ACCEPTED/CONNECTING retains the claim and armed gate, showing Request
accepted / Preparing connection. Back/Cancel now invokes withdraw, keeping the Activity
open. Withdrawal success recipient_withdrew clears the owned context, releases the gate
and finishes. Withdrawal failure retains the claim/gate and offers explicit retry.
An observed unrelated terminal state can be closed locally. CONNECTED is not withdrawn
by this coordinator; actual connected media setup/Stop belongs to a later checkpoint.

Before acceptance, Not now/Back (including during status loading) is local-only:
clear owned context -> generation invalidation -> release -> finish. No server decline,
cancel or withdraw is sent, so another recipient can still accept the forwarded link.
Release is a policy update and does not explicitly restart legacy pairing.

## Lifetime and boundary

The process coordinator/controller survive configuration recreation; a saved non-secret
owner flag reconnects the screen without parsing the scrubbed Intent or issuing another
GET/Accept. UI state is reconstructed from the coordinator/controller event observation.
Process-death recovery is not promised. With no process context and no bearer URI, the
screen safely reports invalid input; a fresh delivered link may start a new preview.
OS process termination is not interpreted as explicit user withdrawal.

There is no polling, readiness, session creation, request-auth/WS, WebRTC setup,
MediaProjection or browser fallback. The next checkpoint owns accepted role assignment,
foreground status handling and protected setup. JVM tests cover strict links, fake HTTP
transport/controlled executor lifecycle, Accept/withdraw retries and stale status exit;
Activity/manifest/source wiring checks supplement behavioral tests. No production HTTP
or WSS is used by these tests.
