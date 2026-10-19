# Connection link share readiness audit

Audited baseline: ff29d9335a3ed5a4a83208feae4454d6c0ccb421.

Step 2 in MainActivity and ReceiverActivity uses rememberRequestShare ->
ConnectionRequestShareCoordinator -> ConnectionRequestController.create. It does
not call AppPairingViewModel.create. The persistent NAS pairing path has its own
worker/post completion and ConnectionShareDelivery one-shot delivery; its 30-minute
invite semantics and implementation are unchanged.

The full /connect URL is built from the controller's successfully created private
WAITING context by ConnectionRequestLink.build. Creation is explicit-action only.
The coordinator already waits asynchronously for CREATE completion and publishes
one delivery. Compose launches ACTION_SEND/EXTRA_TEXT only while RESUMED, consuming
the delivery before launching the native chooser. ActivityResult clears chooser
busy state on return, including cancel/back. Recreation/detach does not replay it.

The reported error maps specifically to UNAVAILABLE. CREATE/backend failures map
to CREATE_FAILED; chooser-launch exceptions map to CHOOSER_FAILED. A deterministic
test using the actual foreground monitor reproduced an erroneous UNAVAILABLE:
CREATE succeeds -> Share delivery ready -> monitor starts STATUS -> takeLink reads
shareableInitiatorData -> null solely because activeOperation is STATUS. The first
attempt loses delivery even though the link already exists. After STATUS finishes,
another tap reuses the same WAITING link and succeeds. Clicking reshare while a GET
is active had a related admission rejection. No failing backend request or premature
persistent invite creation is needed to reproduce either bug. The exact physical
interleaving cannot be proven retrospectively without device evidence.

Fix: read-only STATUS no longer blocks explicit access to an otherwise valid,
unexpired, role-matching WAITING initiator link. Mutating operations, wrong roles,
non-WAITING states, stale exact attempts and expiry still reject sharing. Existing
pre-delivery waiting for an active operation remains unchanged. No new polling,
retry loop, delay, backend request, authority, protocol or lifetime is introduced.
A response that has already changed the context to ACCEPTED/terminal still blocks
sharing; a link shared while a read-only GET is in flight is subject to the same
server single-use/expiry validation as any forwarded link.

Actual asynchronous CREATE waiting now labels the disabled Step 2 button
"Preparing link...". Preparation ends at ready delivery; chooser busy remains
single-flight until its ActivityResult. Creation failure clears busy/preparing and
shows "Couldn't prepare the connection link. Try again." Explicit retry is allowed.
The button stays first in Step 2. No automatic invite pre-generation was added.

Coverage includes the reproduced ready-delivery/STATUS race (failed before fix),
STATUS-before-observer ordering, reshare during STATUS, pending/double taps, full
canonical URL and one-shot consumption, failure/retry, chooser return/cancel and
mutating-operation exclusion. Android chooser/device behavior still requires a
physical retest; JVM tests verify production controller/coordinator/monitor behavior
and source wiring, not instrumented Activity execution.
