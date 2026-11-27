# Scanly

Ad-free document scanner for Android 13+ (Kotlin, Jetpack Compose, Material 3, CameraX, OpenCV).

**Download:** grab the latest APK from the [Releases](../../releases) page.

## Features

- Scan with the in-app camera (multi-page) or import photos from the gallery
- Automatic edge detection + manual crop with perspective correction
- Per-page or all-pages filters: Original, Cropped, Enhanced, Xerox (B&W)
- Reorder pages by long-press + drag (or the move buttons in the page editor), rotate, delete
- Export to PDF (A4 or fit-to-image, three quality levels) into Downloads/Scanly, then Open or Share
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
