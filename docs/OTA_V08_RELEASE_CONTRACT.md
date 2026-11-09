# HBDisplay v0.8 — OTA release contract (development only)

This document specifies a **proposed** contract for the isolated OTA lab. It does not claim that production CMS currently implements this endpoint.

## Discovery

`GET https://<configured-authorized-host>/api/player-updates/check?site_code=...&monitor_id=...&version_code=8`

Request header: `X-HBDisplay-Device-Key: <per-device-secret>`. Do not put the key in URLs, telemetry, or logs. The server must authenticate the key and bind it to the requested site and monitor. The client must reject redirects and use a trusted TLS certificate.

No update:

```json
{"status":"up_to_date"}
```

Candidate:

```json
{
  "status":"update_available",
  "rollout":"approved",
  "release_id":"lab_release_9",
  "version_code":9,
  "apk_url":"https://ota.example.invalid/releases/lab_release_9.apk",
  "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "size_bytes":27472
}
```

The SHA256 and size are examples, **not** a production release. No approved candidate is available by default.

## Fail-closed acceptance

The client accepts a candidate only after an authenticated HTTPS 200 response, bounded JSON parsing, `status=update_available`, `rollout=approved`, a strictly increasing integer `version_code`, a safe release ID, HTTPS APK URL without embedded credentials or fragment, 64-character SHA256, and a positive APK size <= 150 MiB. The current pure-Java validator is `OtaReleaseContract`; the check-only Android discovery currently logs only status/version and **does not yet pass metadata to the downloader**.

Server authorization and rollout approval must be checked per device. TLS alone is not authorization. The APK must also be verified with Android signing APIs and the expected signing certificate before any installation proposal. SHA256 integrity is necessary but not sufficient.

## Elevator/offline requirements

A release may be staged in private app storage only. A dedicated low-priority worker may resume using validated strong ETag + Range/If-Range across short connectivity windows. No installation may occur during normal playback. Preserve the current offline cache, WebView, heartbeat, screenshot, and recovery behavior. Do not assume continuous network or Tailscale availability.

## Deployment gate

No production endpoint changes, no monitor updates, no installer, no Device Owner assumptions, and no automatic OTA activation in this branch. First prove the new JVM tests in CI, then test the lab endpoint with fake monitor credentials, verify the signing certificate and actual Android package version, and only then design an explicit, authorized installation flow.
