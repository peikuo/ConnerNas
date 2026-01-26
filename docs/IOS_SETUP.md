# iOS Setup (Xcode) Step by Step

## 1) Create a new Xcode project
1. Open Xcode
2. `File` > `New` > `Project...`
3. Choose **iOS > App**
4. Product Name: `CornerNAS`
5. Interface: **SwiftUI**
6. Language: **Swift**
7. Bundle Identifier: your reverse domain (e.g. `com.yourname.cornernas`)
8. Save the project to: `ios/CornerNAS` (inside this repo)

## 2) Add existing source files
In Xcode, in the Project Navigator:
1. Right‑click the app group > `Add Files to "CornerNAS"...`
2. Add:
   - `ios/CornerNAS/Sources/CornerNASApp.swift`
   - `ios/CornerNAS/Sources/Views/*`
   - `ios/CornerNAS/Sources/Models/*`
   - `ios/CornerNAS/Sources/Services/*`
3. Make sure **"Copy items if needed" is unchecked** (keep files in repo).

## 3) Add resources
1. Right‑click the app group > `Add Files to "CornerNAS"...`
2. Add:
   - `ios/CornerNAS/Resources/Info.plist`
   - `ios/CornerNAS/Resources/en.lproj/Localizable.strings`
   - `ios/CornerNAS/Resources/zh-Hans.lproj/Localizable.strings`
   - `ios/CornerNAS/Resources/Base.lproj/InfoPlist.strings`
   - `ios/CornerNAS/Resources/zh-Hans.lproj/InfoPlist.strings`
3. Ensure they show under the app target.

## 4) Set Info.plist
In the project settings:
1. Select the app target
2. Go to **Info** tab
3. Confirm `NSLocalNetworkUsageDescription` and `NSBonjourServices` exist
   - If not, add them manually

## 5) Update the generated App file
Xcode will create its own `CornerNASApp.swift`. Replace its contents with the repo version.

## 6) Build & run
1. Select a simulator or your iPad device
2. Press **Run**

## 7) Next implementation steps
- Bonjour browse + advertise for `_cornernas._tcp`
- HTTP server endpoints (`/api/v1/list`, `/api/v1/file`, etc.)
- Files picker for shared folders (security‑scoped bookmarks)
- Remote browser UI + media preview (AVPlayer/QuickLook)

If you want, I can guide each implementation step in order.
