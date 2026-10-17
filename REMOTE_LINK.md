# Persistent remote pairing link (local implementation)

The remote link identifies this installation using a random bearer secret, not
an Android/hardware identifier or the six-character session code. Anyone with
the link can request a connection only while the visible sharing app owns a
waiting sender CREATE. Sharing always requires physical MediaProjection approval.
No remote wakeup, push, background app launch, account, recording, TURN or audio.

## Token and storage

RemoteDevice.generate uses 16 SecureRandom bytes (128 bits), Base64URL without
padding: exactly 22 case-sensitive characters. The final character is one of
A/Q/g/w because of canonical trailing bits. No timestamp or expiry. The first
remote-capable waiting registration or Share/Copy use creates the token.

Private SharedPreferences remote_device.xml, token key, MODE_PRIVATE. Writes use
commit before returning a new token. RemoteTokenStore reads storage each time;
rotation/new pairing/restart does not generate a replacement unless missing or
invalid. Clear data/uninstall can generate a new token. No account/server DB.
backup_rules.xml excludes this dedicated sharedpref file; data_extraction_rules
excludes it separately from cloud-backup and device-transfer. Existing manifest
already references both rules. Tests parse the real XML exclusions. Storage
tests use injected read/write adapters and new store instances, not an actual
process restart or physical backup/restore test.

URL: https://pradertamas.github.io/realtime-web-test/webrtc-receiver.html#device=<TOKEN>
The base receiver URL is shared with PairingCode, so future domain migration has
one Android base URL. This URL is a secret; sharing/clipboard/history inherently
give it to the chosen recipient/tool. Bookmarks also retain it. The fragment is
not included in the hosting HTTP request; remote resolve sends it only in the
POST body, never a request URL. It is not printed in the app or diagnostics.

Share link opens ACTION_SEND, text/plain, EXTRA_SUBJECT and EXTRA_TEXT through
Intent.createChooser. No target app or automatic send. Copy link uses the system
ClipboardManager and marks the clip sensitive, then shows Link copied.
Regenerate has a confirmation dialog. A new persisted token is sent on the
current waiting registration; the relay atomically removes its old hash before
installing the new one. The UI says New remote link saved, not a claim that a
network revocation has finished before the relay receives it. A remote control
ack confirms server processing; the relay test waits for it when checking old
link revocation. Offline network latency cannot be eliminated by a client UI.

## Presence and lifecycle

AutoPairing refreshes presence only on a successful own CREATE WAITING state
while the home is visible. The transport must advertise remotePresence:true in
its FIRST successful registration response; this capability is latched so a
later peer message cannot enable token transmission through an old relay.
No support -> no token transmission; ordinary local pairing remains usable.

JSON body: {type:"remote-presence-register",token:...}. Never part of a WSS URL.
The relay consumes this control message rather than forwarding it to a peer.
One socket owns at most one token mapping. Regeneration reads the replacement
from storage; later rotation callbacks cannot re-register an old captured token.
There is no token in PairingHome, WebRtcUi, metrics or exception messages/logs.

Peer JOIN removes mapping on the relay before peer-present/consent. Leaving home,
switching to JOIN, rotation, Cancel and socket cleanup retire the old creator.
After Cancel a new waiting CREATE registers the same stored token. After Stop
registration resumes only once native disposal is complete and home is visible.
Single-app sharing in another app may leave home hidden: reopen sharing home to
become available again. The active stream itself never publishes remote presence.

The same existing chooser, capture service, video track and portrait/landscape
resize path is used after remote JOIN. No consent result or persistent token is
used as a WebRTC session identifier.

## Checks and physical retest

Run :app:assembleDebug testDebugUnitTest lintDebug. RemoteDeviceTest covers secure
format, storage adapter reread/regeneration, URL/share payload, backup exclusions,
old-relay safety, waiting-only registration, rotation, Cancel and Stop recovery.
Actual Share Sheet/clipboard behavior and process/reboot persistence require
physical checks. No real generated token is printed by tests.

A. Copy link, verify URL structure locally without publishing the secret.
B. Share link -> system chooser; choose a messaging app manually if desired.
C. With phone home waiting, open link on updated remote browser -> no code/QR ->
   chooser on phone -> Allow -> stream. Test both capture choices.
D. Background/close phone home (not waiting) -> same link shows unavailable,
   no capture notification or app wakeup. Reopen phone home -> Try again works.
E. Stop and later reopen/reuse the same link -> new short session, fresh consent.
F. Wait five minutes: short code changes, copied remote URL stays identical.
G. Save old link privately, confirm Regenerate, wait for presence update, then
   old resolve is unavailable and new link works. Test app restart and phone
   reboot: newly saved link remains the same. Do not clear app data for this test.
H. Remote consent Cancel -> new waiting state -> browser Try again -> Allow.
I. Remote stream Stop -> ended/Try again -> new waiting connection, no reload.
J. Rotate portrait/landscape/portrait in a remote stream; record real dimensions.
Also repeat all existing local Android-first/browser-first QR and typed flows.

Deployment is manual after review: the relay needs remote-presence.cjs, server.js
and its existing join-limit.cjs; web needs the versioned remote-link.js and all
updated receiver assets. Updated APK still targets the public WSS host. An old
public deployment does not implement remote resolve. No secrets/env token setup
is needed. Optional relay REMOTE_ALLOWED_ORIGINS adds comma-separated exact web
origins. Existing defaults cover GitHub Pages and localhost/127.0.0.1:8080.

No commit, push or deployment was performed. Experiments/archive untouched.
