# CP5-B2A: legacy start gate

ConnectionRequestRuntimeGate owns one process-lifetime LegacyConnectionStartGate.
Fresh process defaults to legacy allowed. armRequestFirst/releaseRequestFirst are
idempotent policy changes, with no HTTP, storage, role mutation, Android context,
credential, session start or cleanup. Release does not restart a session.

runLegacyStart serializes the permission check and synchronous start dispatch with
ARM/RELEASE under one lock. After ARM returns, later guarded entrypoints cannot
dispatch a new legacy start. Do not put blocking I/O or suspend calls in its block.

Production boundaries: MainActivity onStart; resumed consent effect including show,
claim and launcher; sender Show/create, QR join, manual join and typed auto-join
entry; AppMediaRoles sender activation; ReceiverViewModel initialization,
ensureOwnCreate, retry and join. Receiver role binding remains unconditional.
An armed receiver join returns false. Other blocked start actions are safe no-ops.
The RECEIVE Activity redirect and global MediaRole owner are unchanged.

hide/close/stop are never gated. ARM deliberately does not stop existing sockets,
cancel engine-internal timers or revoke an already launched system consent dialog.
This gate controls orchestration entrypoints, not the legacy engines themselves.
The next checkpoint must perform this sequence without yielding between ARM and
legacy stop (on the existing main-thread orchestration boundary):

1. ARM
2. Explicitly stop the legacy waiting session (including its queued timers/callbacks)
3. HTTP connection-request create
4. Share Sheet

No production caller arms the gate in this checkpoint. Existing default legacy
behavior therefore stays enabled. No request creation, Share change, App Link,
polling, protected signaling or MediaProjection implementation change is included.
The consent effect does not observe gate changes as a restart trigger: RELEASE
alone does not invoke show/claim; a subsequent caller/lifecycle event is required.
