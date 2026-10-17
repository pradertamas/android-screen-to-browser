# Automatic Android pairing

Current UX: opening the visible home generates a fresh six-character SecureRandom
CREATE sender registration with code and ZXing QR. No capture/foreground service
or consent prompt starts just from app opening. PairingHost owns the waiting
socket; AutoPairing is the Android-free, unit-tested state controller.

States: STOPPED -> CREATING -> WAITING -> CONSENT -> CONNECTING -> STREAMING.
Own waiting CREATE -> valid input/scan -> close old link -> JOINING -> WAITING ->
peer-present -> CONSENT. No concurrent JOIN. CREATE collision retries at most
four registrations with new codes. The service attaches to the existing socket
only after fresh user-choice consent, then creates the sender peer and offer.
Epoch checks discard stale callbacks and old consent results. No token reuse.

Manual input uses AutoPairing.input: ASCII, trim, uppercase, max six, exact existing
alphabet. AutoPairing.DEBOUNCE_MS = 250; each edit cancels the previous task, and
only six valid characters join. Paste follows the same path. Scan cancels pending
typing and, after local parsing, joins immediately. Existing Google QR scanner,
QR payload, permissions and QR generator dependencies are unchanged.

AutoPairing.ROTATE_MS = 300000: only unpaired registered own CREATE rotates.
JOIN, CONSENT, CONNECTING and STREAMING never rotate. Close is idempotent and
cancels timers/queued input and retires the old link before opening its replacement.

Cancel closes signaling and resets pending capture UI; no foreground service or
capture starts. Error/Cancel regenerates a fresh own pairing after one second,
keeping the notice visible. Stop/media failure waits for native service disposal
before regenerating, avoiding overlap between projection sessions. In background,
regeneration waits for home to reopen. Waiting registrations close on navigation
away; camera and consent overlays do not count as app navigation. Actual media
remains owned by the existing foreground service.

PoC 3B capture remains separate under Advanced diagnostics. When that mode is
active the waiting pairing closes; it resumes with a new code after capture ends.
User-choice single-app/full-screen API, STUN/ALL and 720 x 1560/30 FPS/max 2 Mbps,
video-only capture/codec negotiation are unchanged. No TURN/Cloudflare dependency.

Run :app:assembleDebug testDebugUnitTest lintDebug. AutoPairingTest uses a fake
clock/transport for startup, collisions, typing/paste, QR replacement, repeated
Cancel, fresh epochs, Stop/disposal boundary, rotation/no streaming rotation and
late callbacks. Existing real QR round-trip tests remain active. Physical Android
picker/camera/video validation is still required.

Physical matrix: browser-first Scan and code; Android-first Scan and code; paste
on each side; verify own old code expires on JOIN. Repeat consent Cancel three
times then Allow without reloading; Stop from either side and pair again with
new codes. Test both single-app and full-screen, camera errors/cancel, five-minute
waiting rotation, no rotation during streaming, and background/reopen. Opening
home alone must never show consent. Verify two-minute video stability and no
capture notification after Cancel. The complete A-I matrix is also in the web
project's AUTO_PAIRING.md in this workspace.

All changes are local. No commit/push/deploy; experiments/poc4b is untouched.

Persistent remote links are an additional optional path; see REMOTE_LINK.md.
They resolve only waiting own CREATE presence to this same short session/consent
flow, without changing capture or the local pairing UI.
