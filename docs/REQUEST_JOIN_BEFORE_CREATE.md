# Request JOIN-before-CREATE diagnostics and Android regression

The protocol fix lives in realtime-relay: an exactly authenticated request JOIN
receives registration ok before CREATE exists, then waits for real peer-present.
This cancels ReceiverEngine's existing 15-second registration deadline. There is
no new Android reconnect, sleep or timer; the server's original connectBy remains
the bound on waiting. No UI, link, role policy, consent policy or media codec/
capture behavior changes.

The deterministic Android regression uses the actual controller, one-shot handoff,
RequestReceiverOwner, protected transport and ReceiverEngine with fake socket,
media and scheduler. It proves registration cancels the deadline, retains one
owner/socket without media creation, then late peer-present/offer yields one
answer and first-frame state. It does not claim actual video rendering. A separate
entry test proves navigation is rejected before effective RECEIVE role and only
admitted after that role is supplied.

## Activity ordering audit

ConnectionRequestSetup prepares the recipient's opposite role while ACCEPTED;
mediaEligible requires the same effective role plus CONNECTING before reservation
or navigation. ReceiverActivity validates RECEIVE and associates its retained
owner/task before consuming the handoff. No additional proven role-ordering bug
was found or changed. Existing fallback branches retain their behavior.

Recipient RECEIVE finishes its source only after successful navigation dispatch.
Recipient SEND finishes after startForegroundService returns successfully. This
is dispatch acknowledgement, not proof that capture/socket/media is already
healthy; subsequent service failure can still occur. No new owner-ack protocol is
introduced. Finish and fallback logs identify those existing paths on a device.

## Logcat

Capture `adb -s <serial> logcat -v threadtime RequestFlow:I '*:S'` separately for
both phones (do not clear unrelated logs). Events include:

- HTTP_COMPLETE with operation, HTTP code, typed error/state, effective role,
  readiness and assignment. ACCEPT + 200 + ACCEPTED indicates Accept success.
- CONSENT_REQUESTED, CONSENT_GRANTED/CANCELLED, launch failure, SERVICE_DISPATCHED.
- RECEIVER_DISPATCH/DISPATCHED/DISPATCH_FAILED and HANDOFF_ACCEPTED/REJECTED.
- SOCKET_OPEN, AUTH_SENT, REGISTER_OK or a whitelisted registration rejection,
  PEER_PRESENT, local/remote close with numeric code, socket/terminal failure.
- Receiver fallback role/ViewModel/attachment reasons and Activity finish reasons.

REGISTER_OK is server acknowledgement of request auth plus registration; AUTH_SENT
alone is not success. Relay diagnostics distinguish auth rejection, duplicate,
registration failure and auth timeout. A close code alone does not prove which
server check failed. No exception text, payload, raw ID, session code, URL, token,
handoff ID or credential is logged. `request` is a 12-hex SHA-256 correlation tag;
`flow` is an opaque process-local identity. Protocol logging effects execute
outside the transport state lock. HTTP completion logging is outside controller
locks and describes the received result, not a guarantee of current-flow adoption.

## Physical follow-up (pending)

After the new relay is deployed separately and the diagnostic Android APK is
installed separately: Phone A SEND -> Share link; Phone B open -> Accept.
Keep A foreground for setup/consent. Wait 10-15 seconds before Allow on A.
B must remain waiting, then show A's screen after Allow. Repeat with reversed
initiator/media roles, and with receiver Back before Allow. Collect both logcats
and correlate the request digest with relay logs. The original physical symptom
has not been proven to be this race; no install/deploy or physical test is claimed.
