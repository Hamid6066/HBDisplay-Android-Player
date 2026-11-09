# HBDisplay OTA v0.8 — elevator intermittent-network implementation gate

Status: DESIGN ONLY. No APK download, install, or deployment is enabled by this document.

## Verified existing behavior (source: MainActivity.java on feature/ota-v0-8-offline-safe)
- Player uses a persistent per-URL WebView resource cache under app-private files/offline_cache.
- Network availability triggers server probe; heartbeat and screenshot are opportunistic.
- OTA discovery is **read-only**, throttled to 900000 ms and called after successful display-server probe.
- OTA discovery currently derives its base URL from displayUrl and **returns immediately if displayUrl is HTTP**. The current Gradle default display URL is HTTP. Consequently default deployments will not even discover an OTA release.
- The executor is single-threaded. Screenshot uploads, health probes and OTA checks currently share it. Do **not** run a large APK download on that executor.
- Cache uses WebView resource interception. OTA binary must never be placed in or evict the signage offline_cache.
- Existing check parses JSON, checks version_code > current and rollout == approved, but does not verify package, signer, digest or perform downloads.
- AndroidManifest does not declare a privileged installation capability. Do not promise silent install.

## Non-negotiable product invariant
An elevator monitor can lose internet at any floor; signage must continue from its local content and must not depend on OTA. Tailscale offline is normal and not evidence of display failure.

## Implementation sequence / acceptance gates
1. **Independent OTA endpoint configuration**: introduce a separately configured HTTPS OTA base URL, independent of legacy HTTP displayUrl; do not log the device key. Keep OTA disabled by default until endpoint and trust chain are provisioned. Test invalid HTTP and TLS errors.
2. **Dedicated worker**: run OTA on a bounded dedicated executor / WorkManager, never on the existing single-thread executor or UI thread. Preserve heartbeat, screenshot and WebView responsiveness.
3. **Authenticated metadata**: enforce site_code, monitor_id, device key, package_name == ir.hbintellisys.player, version_code > installed, approved release, immutable release id, sha256, signing_cert_sha256. Reject malformed or oversized response; no redirects to HTTP.
4. **Resumable download**: use app-private ota/<release-id>.part and an atomic metadata journal. On reconnect, request HTTP Range bytes=<current-length>- with If-Range bound to ETag or Last-Modified. Require 206 + matching Content-Range start for append. On 200, restart safely from byte zero. On 416 or changed ETag, discard partial and restart. Enforce max size, free disk threshold, read timeouts, backoff and jitter. Sync data and atomically rename only after full length and SHA256 match. Never allow an incomplete .part to be installed.
5. **Independent signing check**: verify APK package, version and signing certificate against installed app via Android PackageManager/PackageInfo and approved pinned signer; SHA256 of bytes alone is insufficient. Use supported API levels (minSdk 26) and signature rotation handling deliberately. Reject mismatches.
6. **Installation gate**: determine actual ZC-H133 installation privileges in a nonproduction test. If user consent is required, report USER_ACTION_REQUIRED and do not interrupt elevator signage. Do not attempt unattended install through ADB or undocumented privileges.
7. **Rollout safety**: check server pause/revoke immediately before install; never infer permission from an earlier check. Test update interrupted by floor changes, process death, power cycle, 404/401/403, hash mismatch, wrong signer, insufficient storage and paused release.
8. **Observability**: report installed version separately from downloaded version; keep state machine and last error persisted. Only authenticated, fresh heartbeat can confirm installed version.

## Next code task
Implement and unit-test a standalone, non-installed, resumable download engine behind an explicit disabled feature flag. Do not modify MainActivity's existing offline cache, probe cadence, screenshot behavior or production CMS in the same commit.
