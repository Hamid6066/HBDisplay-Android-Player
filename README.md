# HBDisplay Android Player

Android signage player for the HBDisplay platform.

Initial target hardware: ZC-H133 (Allwinner), Android 10 / API 29, 1280x1024.

## v0.1 goals

- Start after boot
- Ask Tailscale to connect using its exported CONNECT_VPN receiver
- Keep the display awake and run immersive fullscreen
- Load the configured HBDisplay URL in an embedded WebView
- Recover automatically after network/server failures
- Show a local offline fallback instead of a blank/error page
- Provide a small heartbeat/status loop for future server integration

The first test configuration points to the current Setareh HBDisplay endpoint over Tailscale and is intentionally kept configurable for later multi-site provisioning.
