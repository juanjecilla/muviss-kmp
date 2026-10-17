# iOS app

The SwiftUI host for the shared Kotlin framework. Gradle builds `:app:shared`
into a static framework (the "Compile Kotlin Framework" phase); Xcode links it
into two products:

- `iosApp`: the app (`Muviss.app`).
- `MuvissWidget`: the WidgetKit extension, embedded in the app.
- `iosAppUITests`: the XCUITest smoke target, run through the `iosApp` scheme.

## Build and test

```bash
xcodebuild -project app/iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO build

xcodebuild test -project app/iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO
```

Signing, the Team ID and the App Group are covered in `docs/RELEASING.md`
(section 11). Without them the app falls back to the Documents database, as
ADR 0016 describes.

`scripts/ios/add-ui-test-target.rb` created `iosAppUITests` and its scheme
entry. It is idempotent, but the result is committed, so there is no need to
run it again.

## Architectures: arm64 only (#35)

Every configuration sets `ARCHS = arm64`. Every iPhone and iPad that can run
this app is arm64, and Apple Silicon Macs run arm64 simulators. The x86_64
simulator is not built, so an Intel Mac cannot build this target. Add a
universal build only if an Intel simulator has to be supported, because it
adds an x86_64 Kotlin/Native link for a slice no device uses.

## Version

Both targets run `scripts/ios/set-version.sh` in a "Set version from git"
phase, so the app and the widget always carry the same numbers. App Store
Connect rejects a mismatch (ITMS-90473).

- `CFBundleShortVersionString` is the numeric `X.Y.Z` of the latest tag, with
  any `-rcN` dropped, as Android's `versionName` is. An untagged build reads
  `0.1.0`.
- `CFBundleVersion` is the Android `versionCode` (ADR 0030): the
  `scripts/release/version-code.sh` value on an exact `vX.Y.Z[-rcN]` tag, else
  the commit count.

Test it with `scripts/ios/test/set-version-test.sh` (macOS, needs PlistBuddy).

## Privacy and export

- `PrivacyInfo.xcprivacy` is in both targets. It declares no tracking, the
  crash-data type, and the required-reason APIs the code reaches.
- `ITSAppUsesNonExemptEncryption` is `NO` in both `Info.plist` files.
- The iPad orientations are all four (`INFOPLIST_KEY_UISupportedInterfaceOrientations_iPad`),
  so the app does not need `UIRequiresFullScreen` (ITMS-90474).
