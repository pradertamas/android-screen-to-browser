# Persistent app-to-app pairing — backend port to configurable REST

Current server implementation: ../pairing-api (plain PHP 8.2+/PDO/MariaDB 10.11).
Setup/architecture/parity: pairing-api/README.md, NAS_DEPLOYMENT.md, PARITY.md.
The NAS hostname has NOT been selected. Do not use the Render host for this feature.

One configuration source: PairingBackendConfig. Gradle reads environment first,
then ignored root local.properties keys PAIRING_API_BASE_URL, PAIRING_APP_LINK_HOST,
PAIRING_APP_LINK_PATH. These feed BuildConfig runtime values and manifest placeholders.
Safe defaults: https://pairing.invalid, pairing.invalid, /pair. Default build succeeds,
UI explicitly reports unconfigured, and controller/client prevent network requests.
Tests inject https://pairing.example.test and custom paths; no real hostname committed.
Use HTTPS origin-only API URL (443), an exact lowercase App Link host and exact path.

DeviceIdentityStore remains lazy/private/synchronized, 128-bit public random ID plus
256-bit bearer credential in excluded device_identity.xml. No change to remote_device.xml,
backup exclusions, credential/token formats, role/capture code or browser token storage.
Android knows only the existing /v1 REST contract, never PHP, NAS or database details.
After registration it sends Authorization: Bearer, never URL credentials; no redirects.

Server returns inviteUrl from its public_base_url/path. Android validates the exact
configured scheme/host/path and canonical fragment, then shares that SAME server URL.
It never substitutes the Render host. Query/encoded/duplicate fragment links rejected.
Share pairing link -> identity -> create -> standard Share Sheet; incoming App Link
-> inspect -> explicit Pair/Cancel. No accept changes MediaRole/starts media/capture.
An unreachable configured backend produces Pairing temporarily unavailable, without
breaking ephemeral QR/code pairing, SEND/RECEIVE or browser remote links on Render.

PairingInviteActivity remains the floating singleTop App Link entry with autoVerify.
Configuration recreation keeps ViewModel/in-flight guards; onNewIntent handles explicit
new links. Actual Verified App Links behavior and floating Activity/media lifecycle
still require physical validation after NAS/host/certificate setup.

See the NAS document for signingReport, assetlinks generation/response, manual
verification and deployment. Existing browser #device token remains a DIFFERENT
persistent capability; /pair#invite is temporary, 30-minute, one-use trust setup.
No FCM/account/paired-device Connect UI/media request added.

Run :app:assembleDebug testDebugUnitTest lintDebug. Existing tests preserved, updated
pairing tests use injected config, new tests cover disabled/no-request default,
custom host/path and absence of a Render pairing dependency. No deployment/commit/push.
PHYSICAL APP-TO-APP AND LIVE NAS/MARIADB VALIDATION PENDING.