# Scanly

Ad-free document scanner for **Android 13+** and **iPhone (iOS 16+, so iPhone 14 and newer)**.

- Android: Kotlin, Jetpack Compose, Material 3, CameraX, OpenCV (`app/`)
- iPhone: native Swift, SwiftUI, AVFoundation, Vision, PDFKit (`ios/`)

Both apps have the same features and look. They share no code, so the Android app stays exactly as tested.

**Download:** grab the latest builds from the [Releases](../../releases) page (`Scanly.apk` for Android, `Scanly-unsigned.ipa` for iPhone).

## Features

- Home screen opens on a **PDFs** list: view in the built-in viewer, share, rename or delete any PDF you made; a **Scans** tab keeps your editable documents; Scan and Import buttons are always on screen
- Automatic edge detection + manual crop with perspective correction
- Per-page filters: Original, Cropped, Enhanced, Xerox (B&W)
- Select any pages (tap the circle on a page) to apply a filter to just those pages or delete them in one go
- Reorder pages by long-press + drag (or the move buttons in the page editor), rotate, delete
- Export to PDF (A4 or fit-to-image, three quality levels) with a name you choose
- Choose where PDFs are saved in Settings (any folder you pick; default is Downloads/Scanly on Android and On My iPhone › Scanly in the Files app on iPhone), then Open or Share
- No ads, no accounts, no internet permission

## Install on iPhone

The `.ipa` is **unsigned**: Apple only lets signed apps run on a real iPhone, and signing needs your own Apple ID. Pick one:

1. **Free Apple ID (no paid account):** install [Sideloadly](https://sideloadly.io) (Windows or Mac) or [AltStore](https://altstore.io), connect the iPhone by cable, drag `Scanly-unsigned.ipa` in and sign in with your Apple ID. The app works for 7 days and must be re-installed (re-signed) after that. On the phone, trust your developer profile in Settings › General › VPN & Device Management and enable Developer Mode (Settings › Privacy & Security) if asked.
2. **Paid Apple Developer account ($99/year):** build with your own signing team and distribute through TestFlight, where builds last 90 days.

## Build

### Android

Requires JDK 17 and the Android SDK (platform 35).

    gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk

Without a `keystore.properties` file the release build is signed with the debug key. To sign with your own key,
create `keystore.properties` in the project root (it is git-ignored):

    storeFile=my-release.jks
    storePassword=...
    keyAlias=...
    keyPassword=...

### iPhone

Needs a Mac with Xcode 16+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`):

    cd ios
    xcodegen generate
    open Scanly.xcodeproj

The GitHub Actions workflow `.github/workflows/ios.yml` does this on every push to `ios/`: it builds the app, runs the UI tests on an iPhone simulator, saves screenshots, and produces the unsigned IPA as a build artifact.

## Rename the app

- Android display name: `app/src/main/res/values/strings.xml` (`app_name`); package id: `applicationId` in `app/build.gradle.kts`
- iPhone display name: `CFBundleDisplayName` in `ios/Scanly/Info.plist`; bundle id: `PRODUCT_BUNDLE_IDENTIFIER` in `ios/project.yml`
- Icon: Android `app/src/main/res/drawable/ic_launcher_*.xml`; iPhone `ios/Scanly/Assets.xcassets/AppIcon.appiconset` (regenerate with `python design/make_ios_icon.py <out.png>`). A standalone copy of the artwork is in `design/icon.svg`
