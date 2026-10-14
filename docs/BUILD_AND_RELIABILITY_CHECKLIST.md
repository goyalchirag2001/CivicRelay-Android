# Windows build and reliability checklist

Copyright © 2026 Chirag Goyal. All rights reserved.

## The output is an APK, not a Windows EXE

This is an Android application. To edit: Android Studio. To install: signed (for debug, automatically debug-signed) APK. To upload to Play: release-signed AAB, only after the app has been audited, tested and meets publishing requirements.

## Required on Windows

1. Android Studio and **Android 16 SDK / API 36**, Build Tools **35.0.0**, Platform Tools (ADB).
2. **JDK 17** configured as the Gradle JDK. Run `java -version` and ensure the selected JDK is 17.
3. **Gradle 8.13** available via PATH for first-time bootstrapping. If not present, install it from gradle.org. The repository does not bundle a precompiled wrapper JAR. Optional: once installed, run `gradle wrapper --gradle-version 8.13 --distribution-type bin` and commit wrapper script/JAR verified against Gradle's published checksum.
4. A physical Android device with Developer options, **USB debugging**, and Google Play services. Ideally two or three phones for nearby mesh testing.
5. No backend, cloud account, paid service, Docker, or Azure required.

## First build

Open the project root in Android Studio and Sync Project. Use PowerShell at the root:

```powershell
java -version
gradle --version
.\tools\build-debug.ps1 -RunLint
```

For a first compilation pass, if Android Lint produces separate warnings, run without `-RunLint` to isolate compilation errors. A successful build creates `app\build\outputs\apk\debug\app-debug.apk`; the build script prints the SHA-256 digest. **Do not infer a working app merely from an APK compiling.**

### If PowerShell refuses to execute scripts

Run `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-debug.ps1 -RunLint` in a trusted development PowerShell session. Do not change system-wide execution policy.

## USB install

```powershell
adb devices
.\tools\install-debug.ps1
```

For Windows driver problems, use your manufacturer's USB driver documentation; choose **File Transfer** mode and approve your computer's USB debugging fingerprint on the phone.

## Real-phone checks — use fictional data

1. **Vault:** Create and unlock with a long passphrase, force-stop and reopen. Wrong passphrase must not unlock. Do not uninstall/reset without expecting data loss.
2. **Activity lifecycle:** Open Evidence, start a video, press Home or switch applications. The app must lock and must not expose a finished recording in galleries; inspect app private cache and confirm no leftover capture files. This is NOT a secure erase proof.
3. **QR trust:** Exchange public codes via QR on separate phones. Confirm full key fingerprints through another channel before selecting confirmation.
4. **Public updates:** With cellular data and Wi-Fi internet off, turn on Bluetooth and Wi-Fi radios; start the Nearby mesh on both phones. A public fictional update should propagate and be clearly unverified.
5. **SOS:** Exchange keys first. Connect sender and intended recipient plus an untrusted third device. Send synthetic SOS. Only intended recipient may decrypt; sender must remain **QUEUED** until a cryptographically valid recipient receipt arrives. A relay forwarding a message is not final delivery.
6. **Stale/oversized input:** Confirm receivers ignore malformed packets and that peer rate limiting prevents repeated accepted floods; no application crash.
7. **Camera:** Take a photo and a video, ensure post-capture encryption is completed and record hashes are available. Media remains potentially plaintext for a short window before encryption.
8. **Encrypted export/import:** On recipient phone import the archive from a trusted sender; importing with an unrelated account must fail. Evidence remains encrypted and is not auto-forwarded.
9. **Permissions:** Test granting and denying Camera, Audio, Nearby Devices, Wi-Fi, and Location on Android 10, 12 and 13+ if devices are available. The user must be able to turn radios on manually.
10. **Restart/disconnect:** Stop sharing, disconnect devices, reconnect, and check statuses reflect the actual state. A successful `sendPayload` transport is *not* proof of recipient delivery.

## Security limitations

- Nearby participants and Bluetooth/Wi-Fi transmissions may be observable. Encryption protects content but not all metadata.
- The app has **no guarantee against confiscation, unlocked-device access, forced disclosure, deletion, OS compromise or loss of all participating relay devices**.
- The current prototype is not safe to advertise as operational emergency infrastructure. It has not received Android SDK compilation or a professional independent audit in the authoring environment.
- The CI workflow is optional. It uses online dependency downloads and normal GitHub Actions consumption, which are distinct from an application-hosting requirement.
