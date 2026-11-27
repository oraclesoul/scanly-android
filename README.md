# Scanly

Ad-free document scanner for Android 13+ (Kotlin, Jetpack Compose, Material 3, CameraX, OpenCV).

**Download:** grab the latest APK from the [Releases](../../releases) page.

## Features

- Home screen opens on a **PDFs** list: view in the built-in viewer, share, rename or delete any PDF you made; a **Scans** tab keeps your editable documents; Scan and Import buttons are always on screen
- Automatic edge detection + manual crop with perspective correction
- Per-page filters: Original, Cropped, Enhanced, Xerox (B&W)
- Select any pages (tap the circle on a page) to apply a filter to just those pages or delete them in one go
- Reorder pages by long-press + drag (or the move buttons in the page editor), rotate, delete
- Export to PDF (A4 or fit-to-image, three quality levels) with a name you choose
- Choose where PDFs are saved in Settings (any folder you pick, default Downloads/Scanly), then Open or Share
- No ads, no accounts, no internet permission

## Build

Requires JDK 17 and the Android SDK (platform 35).

    gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk

Without a `keystore.properties` file the release build is signed with the debug key. To sign with your own key,
create `keystore.properties` in the project root (it is git-ignored):

    storeFile=my-release.jks
    storePassword=...
    keyAlias=...
    keyPassword=...

## Rename the app

- Display name: `app/src/main/res/values/strings.xml` (`app_name`)
- Package id: `applicationId` in `app/build.gradle.kts` (and optionally `namespace`)
- Icon: edit `app/src/main/res/drawable/ic_launcher_*.xml` (a standalone copy of the artwork is in `design/icon.svg`)
