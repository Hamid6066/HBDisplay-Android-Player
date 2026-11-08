# HBDisplay Android Player — offline-safe OTA contract (design, not enabled)

## Goal
A player that was offline during release publication must discover the latest **approved** compatible version when it reconnects. A successful GitHub build is NOT an approved deployment.

## Central release manifest (authenticated, site-aware)
GET /api/player-updates/check?site_code=<site>&monitor_id=<id>&version_code=<int>
Header: X-HBDisplay-Device-Key: <device-specific secret>

200 JSON (example only):
{
  "schema": 1,
  "status": "update_available",
  "release_id": "player-8-approved",
  "version_name": "0.8.0",
  "version_code": 8,
  "package_name": "ir.hbintellisys.player",
  "apk_url": "https://<approved-host>/releases/player-8.apk",
  "sha256": "<64-hex-lowercase>",
  "signing_cert_sha256": "<64-hex-lowercase>",
  "min_supported_version_code": 7,
  "rollout": "approved"
}
No update: {"schema":1,"status":"up_to_date"}.
Never place device secrets in URLs or logs. Authenticate every request, including APK download, with short-lived scoped download tokens if necessary. Use HTTPS with validated TLS; no downgrade to HTTP.

## Player state machine
OFFLINE -> ONLINE -> AUTHENTICATED -> CHECK -> {UP_TO_DATE | DOWNLOAD}
DOWNLOAD -> VERIFY_SHA256 -> VERIFY_PACKAGE_AND_SIGNER -> READY_TO_INSTALL
READY_TO_INSTALL -> INSTALL_REQUESTED -> {INSTALLED | USER_ACTION_REQUIRED | FAILED}
INSTALLED -> APP_RESTART -> HEARTBEAT_VERSION_CONFIRMED -> SUCCESS
Failures -> exponential backoff with jitter; resume/check after reconnect. Avoid concurrent installs. Never overwrite the currently running installation with unverified bytes.

## Android installation limitation
Standard third-party Android applications cannot assume unattended installation. Determine whether each ZC-H133 panel supports Device Owner / managed enterprise installation, OEM privileged installer, or user-confirmed PackageInstaller. Use only documented authorized mechanisms. Until confirmed, installation must remain disabled and the UI/status must report USER_ACTION_REQUIRED rather than claim success. Preserve the maintenance overlay and offline content during check/download.

## Release gating
Build -> tests -> signed artifact -> signer certificate verification -> explicit approval -> immutable release record -> staged rollout -> monitoring -> wider rollout.
Only approved release records are served. No automatic deployment on GitHub push.
Do not reuse an APK signed with a different certificate. Require version_code strictly greater than installed; avoid accidental downgrade.

## Telemetry
Heartbeat reports installed version_name/version_code, update_state, target_release_id, last_update_error, last_check_at and last_install_at. Central system_versions is populated from authenticated, recent player reports only; unknown remains unknown.

## Required tests
1. Offline for days, reconnect -> check approved release.
2. No update -> no download.
3. Bad SHA-256 or signer -> reject.
4. HTTP interruption -> retry without corruption.
5. No install privilege -> USER_ACTION_REQUIRED, not SUCCESS.
6. Rollout pause/revoke -> no new installs.
7. Maintenance overlay stays visible during check/download.
8. Reboot after successful install -> actual installed version reported.
9. Wrong site/device key -> reject.
10. Failed installation -> preserve previous working player.
