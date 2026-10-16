# Sender consent audit and device verification

All three sender entry points in MainActivity (Create pairing, validated manual code,
and validated scanner result) call `launchMediaProjectionConsent`. PairingIntent
affects signaling, not capture configuration. The same rtcConsent result launcher
starts WebRtcService only after RESULT_OK with non-null result data.

Before this change, these same entry points already shared beginShare and the
parameterless createScreenCaptureIntent call. No JOIN-only, app-only, or
browser-originated projection configuration was found. Consequently the reported
Samsung picker difference cannot be attributed to a different capture configuration
in this source. Its device-side cause remains unconfirmed.

The common sender path now explicitly uses
createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice()).
Android documents this as equivalent to the parameterless API, so this change
makes the intended policy explicit but does not establish that the reported OEM
behavior is fixed. No default-display/app-only policy is requested.

minSdk 35, targetSdk 37, compileSdk 37: MediaProjectionConfig (API 34) needs no
version branch. The separate PoC 3B capture launcher is unchanged.

Each attempt creates a new consent request. No result Intent or projection token
is retained for another session. Cancel resets WebRtcUi (busy false, no pairing
code), starts no foreground service and performs no sender registration. A
browser-created session remains owned by the waiting browser, allowing a retry;
the phone has not joined it yet. Consent still precedes sender registration,
preserving the existing order.

## Physical verification (required; automated checks cannot prove system UI)

Use the Samsung SM-S931B and desktop receiver, testing fresh sessions each time:

1. Android Create pairing: verify the single-app/full-screen system choice;
   approve single-app, connect the browser with the code, verify only the chosen
   app is visible, then Stop. Repeat with full-screen and verify app switching.
2. Browser Create pairing -> Android Enter code -> Continue: verify the same
   choice. Test single-app and full-screen in separate sessions, including live
   video, app visibility/app switching and Stop.
3. Browser Create pairing -> Android Scan QR: verify the same choice after the
   scanner exits. Test both sharing options in separate sessions with the same
   video and Stop checks.
4. For every entry point, start another fresh session and Cancel in the system
   dialog. Verify no capture notification/video or stuck Starting status; retry
   must request consent again. Close a waiting browser-created session when done.

If any path still skips the choice, record Android/One UI build, entry point,
previous sharing selection and the exact system screen shown. Do not report a
device PASS from compilation or unit tests alone.

Reference: https://developer.android.com/reference/android/media/projection/MediaProjectionConfig
