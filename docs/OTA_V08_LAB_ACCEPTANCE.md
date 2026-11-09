# HBDisplay v0.8 — isolated laboratory acceptance gates

**Status: NOT APPROVED FOR PRODUCTION.** The feature branch and draft PR are for development only. No CMS, AFTAB/Setareh production server, or elevator monitor may be used as a test target.

## Current implementation boundary

- MainActivity performs HTTPS metadata discovery only if its display URL already uses HTTPS. The default display URL is HTTP; there is **no independent configured OTA HTTPS endpoint** yet.
- The download worker is **not automatically invoked** from MainActivity. It must be called explicitly in a future lab-only path.
- Staging requires HTTPS, strong ETag for resume, exact approved size, and SHA256.
- The worker checks APK package name, approved version code, and permanent release signing certificate before reporting staged success.
- No installer, Device Owner privilege, install permission, rollback, or automated OTA scheduling is implemented.
- A green GitHub JVM build does **not** demonstrate Android PackageManager behavior or real elevator connectivity.

## Lab prerequisites (all mandatory)

1. Dedicated Android test device with known Android API level, package state, and USB debugging. **Not** an elevator monitor.
2. Isolated HTTPS test endpoint with a trusted certificate, explicit test device identity, and fake release metadata. Never reuse a production device key.
3. An APK signed with the expected HBDisplay release certificate, same package ID, and strictly higher version code; keep its SHA256 and byte count in the test manifest.
4. Test WebView content stored locally and an independent camera/video record of screen continuity.
5. Baseline recovery path: known-good APK and documented Android package management procedure. Do not claim rollback is automated.

## Acceptance matrix

| Gate | Procedure | Required evidence |
| --- | --- | --- |
| A: static | GitHub Actions JVM tests, signed release build, apksigner certificate digest | Link to successful workflow run and artifact |
| B: identity | Instrumented Android test of PackageManager archive parsing; valid signed APK; wrong package, wrong version, wrong certificate | Device logs and pass/fail per case |
| C: continuity | Show cached WebView content; disable Wi-Fi/network for 30 minutes; reconnect intermittently | Continuous screen recording; no blank display or forced reload |
| D: resume | Interrupt HTTPS transfer at controlled offsets; restore network; inspect Range, If-Range, ETag and final SHA256 | Server request log and hash evidence |
| E: server faults | Exercise HTTP 200 on Range, 206 malformed Content-Range, 416, short body, oversized body, wrong SHA256, missing TLS trust | Explicit rejection; existing display unaffected |
| F: security | Unauthorized device, missing auth, expired credentials, redirects, wrong signing cert | Rejection without credential leakage or install |
| G: resource | Repeat updates with low free disk, process kill, app restart, long disconnection | No cache eviction, resource exhaustion, or degraded display |
| H: installation | Separately design user-confirmed installer or authorized Device Owner/OEM flow; verify Android upgrade and recovery | Signed-off device-specific deployment procedure |

## Laboratory commands (example only)

```bash
adb devices -l
adb shell getprop ro.build.version.sdk
adb shell pm list packages | grep ir.hbintellisys.player
adb shell dumpsys package ir.hbintellisys.player
sha256sum app-release.apk
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs app-release.apk
```

Never push the development APK to a live elevator. Avoid `adb install -r` on production. A test APK cannot be considered installable merely because it is correctly signed.

## Go/no-go

**NO-GO** until A–G have reproducible evidence, H has an approved device-specific installation plan, and the owner explicitly approves a non-production pilot. Only after a successful isolated pilot should production rollout even be proposed. Keep PR in Draft; do not merge as a substitute for field validation.
