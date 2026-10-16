# Inactive PoC 4B source snapshot

MainActivity.kt and WebRtcService.kt are exact copies of the local TURN experiment
before the pairing UX task changed the active app to credential-free P2P.
They are outside app/src and are not compiled. Do not add this directory as a
source set. NetworkMetrics.kt, NetworkMetricsTest.kt and POC4B.md are now also
archived in this directory, outside the app's production/test source sets.
This snapshot is for review/recovery, not a second Android project or deployable
application. It has no Gradle project or Git repository of its own.

The active UI has no TURN mode, credential input or Cloudflare HTTP call.
The existing relay TURN experiment was not removed or deployed by this task.
