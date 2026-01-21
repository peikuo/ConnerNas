# Play Store TODO

## Security
- Replace global cleartext with a network security config (allow only loopback + LAN subnet) or add HTTPS.
- Remove or gate debug logs with `BuildConfig.DEBUG`.

## UX
- Hide the "Test server" button in release builds or move it to a diagnostics screen.
- Add a clear "Copy/Share URL" hint near the IP (optional).
- Consider a fixed-port option or fallback when LAN access fails.

## Compliance
- Fill Google Play Data Safety form (local file access + LAN sharing).
- Ensure foreground service notification explains purpose and how to stop it.

## Release
- Set release signing keys / Play App Signing.
- Update `versionCode` / `versionName`.
- Provide store listing text, screenshots, and feature graphic (EN/ZH).
