# Android SDK 36 / Compose version compatibility fix

## Error observed on Windows

`app:checkDebugAarMetadata` failed with twenty issues because the app explicitly used Compose 1.12.1, which requires `compileSdk` 37 and Android Gradle Plugin 9.1.0 or newer. CivicRelay uses `compileSdk` 36 and Android Gradle Plugin 8.13.2.

## Fix

- Pinned these direct Compose dependencies to **1.11.4**: `ui`, `foundation`, `ui-tooling-preview`, and debug-only `ui-tooling`.
- Kept **Gradle 8.13**, **JDK 17**, **AGP 8.13.2**, **Kotlin 2.2.21**, `compileSdk = 36`, `targetSdk = 35`, and every other dependency as before.
- No application logic, sensitive data handling, permissions or key storage were modified.
- This is a source-level configuration fix; an actual Android SDK build and device test were not possible in the authoring environment.

## Install the fix on Windows

Use the changes-only ZIP and extract it **inside your existing** `D:\CivicRelay-Android` folder. Do **not** replace your `gradlew.bat`, `gradle/wrapper/` files or `local.properties`. Or manually change the four `1.12.1` declarations in `app/build.gradle.kts` to `1.11.4`.

Open PowerShell with JDK 17 configured and run:

```powershell
Set-Location D:\CivicRelay-Android
.\gradlew.bat --stop
.\gradlew.bat :app:checkDebugAarMetadata --refresh-dependencies
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

If a **different** dependency raises an SDK/AGP requirement, inspect the exact artifact and resolve its version deliberately instead of suppressing checks or immediately upgrading the full Android toolchain.

The `kotlinOptions.jvmTarget` warning is non-blocking. An Android SDK XML version warning can be addressed through Android Studio **SDK Manager → SDK Tools → Android SDK Command-line Tools (latest)**; this warning did not cause the reported AAR failure.

## Compatibility reference

Android Developers states Compose 1.12 and newer require API 37 and AGP 9.1+: https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler

Compose 1.11.4 release information: https://developer.android.com/jetpack/androidx/releases/compose-ui
