# Google Play Submission Checklist (CornerNAS)

## 1) Prepare release version
- Update app version in `app/build.gradle.kts`:
  - `versionCode` must increase for every upload (current: 2)
  - `versionName` is user-facing (current: 1.0.1)

## 2) Generate signed AAB
In Android Studio:
1. `Build` > `Generate Signed Bundle / APK`
2. Select **Android App Bundle**
3. Choose your **release keystore** (create it if needed)
4. Select `release` build type
5. Finish to generate the `.aab`

Tip: keep the keystore safe (backup). You will need it for updates.

## 3) Smoke test the release
- Install the release build on a device (or use internal sharing) and test:
  - Start/stop service
  - All shares discovery
  - Remote browsing + folder navigation
  - Image open + video streaming

## 4) Play Console setup
In Play Console, create or open the app:
- **App content**
  - Complete Data Safety form
  - Declare foreground service usage (dataSync)
  - If you use cleartext for LAN only, describe this in the policy answers
- **Store listing**
  - App name, short and full description
  - Screenshots (phone + tablet if available)
  - App icon (512x512)
  - Feature graphic (1024x500)
  - Privacy policy URL (required)

## 5) Upload AAB
- Go to `Release` > `Production` (or `Internal testing` for first try)
- Upload the `.aab`
- Add release notes
- Review and submit

## 6) After submit
- Wait for review
- If rejected, fix issues and bump `versionCode` again

## Common pitfalls
- Uploading APK instead of AAB
- Reusing the same `versionCode`
- Missing privacy policy or data safety answers
- Forgetting to sign with release keystore

