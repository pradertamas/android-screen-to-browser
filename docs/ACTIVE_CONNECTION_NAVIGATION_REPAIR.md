# Active manual connection navigation after Share

Audit comparison: f69cb32f31b6bdf64373020ff9efc4a7a910002b versus
f59cd7640eaffac0b3c47d2fda45951cbc3cd630.

The reported TEST 3 was clarified as "Let another device connect here", not the
external /connect Activity. MainActivity, ReceiverActivity, ConnectionWorkflow,
ConnectionRequestActivity, their controllers and the shared dialog theme were
byte-identical across those two commits. No deleted persistent-pairing class was
on the active manual/request-first call graph. The cleanup did not remove a shared
functional panel; it exposed a previously unhandled navigation/state sequence.

## Reproduced cause

Share -> CREATE WAITING -> chooser -> Back retains the current request and ARM gate
by design. Share's synchronous idle legacy stop leaves sender STOPPED / receiver
ENDED. Connect/Show then selected a presentation path, but their gated legacy start
was rejected. Sender's editable() excludes STOPPED, and receiver controls were
inside a PAIRING-only branch. Show had no fallback content without a registered own
code. Result: title/Back (and sometimes a generic retry), with functional controls
hidden. Both manual paths have the same root cause. This sequence is also possible
with the f69cb32 sources; it cannot be attributed to a particular cleanup deletion.

## Repair and authority boundary

Explicit manual selection exits only the matching-role, idle WAITING initiator
request through existing exact-flow requestExit. It never exits a recipient,
accepted/connecting request or active media owner. Existing cleanup handles in-flight
STATUS, cancel, network error/retry, terminal reconciliation and atomic gate release.
Only after the controller is empty and the gate permits legacy starts, a selected,
RESUMED, matching-role screen starts the existing legacy registration. Back removes
the selection; background or another current request cannot start legacy media.
No new media stack, protocol, persistent-device feature or unconditional gate release
is introduced. Merely dismissing Share still preserves the WAITING link unchanged.

Connect always renders scanner, OR, code input and Connect; controls remain disabled
until the existing engine can accept input. Show renders preparation content until
its actual registration/code is ready. Error cleanup has the existing exact retry
UI rather than a silent fallback or an automatic retry loop.

## Verification

Controller/coordinator tests cover Share/chooser return -> both manual paths for both
roles, cancel-before-legacy ordering, in-flight STATUS, retained gate on network
failure, explicit retry, pending CREATE refusal and recipient/accepted/wrong-role
isolation. The pre-existing share, recipient, manifest and cleanup-negative tests
remain in the full suite.

Actual Activity/Compose tests exercise STOPPED/ENDED + ARM on emulator-5554. The
pre-fix APK failed the sender Connect/Show assertions because functional content was
absent. The repaired APK passes five tests: sender and receiver Connect/Show plus
the recipient Activity's invalid-link UI via its actual onNewIntent boundary.
Cold bearer scrubbing changes the Intent used by ActivityScenario lifecycle matching,
so the latter test uses an empty VIEW launch followed by real VIEW delivery, without
weakening production scrubbing. Valid recipient WAITING/Accept authority remains
covered by existing fake-transport/controller tests, not claimed as physical E2E.

AndroidX androidTest dependencies were updated to JUnit extension 1.3.0 and Espresso
3.7.0: the old Espresso 3.5.1 could not inject events on the current emulator because
it reflected InputManager.getInstance. The official 3.7.0 release fixes that path:
https://developer.android.com/jetpack/androidx/releases/test#espresso_3.7.0

The physical phone was not installed or modified. End-to-end QR/media and real
cross-device App Link validation still require the user's physical retest.
