# Kotlin compilation compatibility fix

## Errors seen on the first actual CivicRelay build

The user's Windows build successfully passed `:app:checkDebugAarMetadata`, but `:app:compileDebugKotlin` failed:

1. Google `play-services-nearby:19.5.0` includes Kotlin metadata **2.4.0**; project Kotlin **2.2.21** reports expected metadata **2.2.0**.
2. `Vault.kt` incorrectly imported `javax.crypto.SecretKeySpec`; the type lives in `javax.crypto.spec.SecretKeySpec`.

## Changes

- Pin `com.google.android.gms:play-services-nearby:19.3.0`, an older published Nearby Connections library. This version predates the 2026 newer Kotlin-generated metadata, without requiring a project-wide Kotlin/AGP upgrade. Actual dependency metadata and full Android API compatibility must still be verified by compiling on an SDK-equipped machine.
- Change only the `Vault.kt` import; **no** vault key generation, encryption, password, record format or evidence behavior was intentionally changed.
- Do not use `-Xskip-metadata-version-check`: suppressing this error would not establish compatibility.

## Apply changes to existing Windows project

Extract `CivicRelay-Kotlin-Fix-Changes.zip` directly into `D:\CivicRelay-Android`, overwriting the matching source files but **not** deleting your generated Gradle Wrapper, `local.properties`, or other local files. Do not replace the whole folder if you already have local changes.

```powershell
Set-Location D:\CivicRelay-Android
.\gradlew.bat --stop
.\gradlew.bat :app:compileDebugKotlin --refresh-dependencies
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Expected APK **if the complete build succeeds**: `app\build\outputs\apk\debug\app-debug.apk`.

If the compiler still reports a metadata incompatibility, note which resolved artifact it names and inspect the dependency graph with:

```powershell
.\gradlew.bat :app:dependencyInsight --dependency play-services-nearby --configuration debugCompileClasspath
```

If a different Kotlin error appears, provide its first `e: file:///...` lines. Subsequent compiler errors may become visible after these blockers are removed.

## Validation boundary

This patch is based on the source packaged previously and the actual Gradle output shared by the user. Java import correctness, project configuration and archive contents can be checked offline; only Windows Android SDK builds can verify the complete compile. Not yet a field-safe app.

Copyright © 2026 Chirag Goyal. All rights reserved.
