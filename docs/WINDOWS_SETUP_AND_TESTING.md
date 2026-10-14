# Windows setup, development, APK builds, and first-device tests

**Copyright © 2026 Chirag Goyal. All rights reserved.**

This application is an **experimental alpha**, not a vetted emergency or evidence-storage system. Run first with invented sample data on personally owned test phones. Release publicly only after reliable Android builds, real-device test results, an independent security audit, and a safe incident-response plan.

## 1. Install the tools on Windows 10/11

1. Download Android Studio from https://developer.android.com/studio and install the Windows edition. Allow it to install the Android SDK and accept the Android SDK licenses. Allow the installer to add standard Android dependencies.
2. Open Android Studio → Settings → Languages & Frameworks → Android SDK. In **SDK Platforms**, install Android SDK Platform **API 36**. In **SDK Tools**, install **Android SDK Build-Tools**, **Android SDK Platform-Tools**, and the latest SDK Command-line Tools. You can also install an Android Emulator system image for UI-only tests.
3. Install **JDK 17** (e.g. Eclipse Temurin 17 from https://adoptium.net/). In Android Studio → Settings → Build, Execution, Deployment → Build Tools → Gradle set **Gradle JDK** to JDK 17. Confirm `java -version` inside your terminal if using terminal-driven builds. The Gradle JDK can differ from your shell JDK; both should be checked.
4. Install **Gradle 8.13** from https://gradle.org/install/ **only to bootstrap the missing Wrapper**. Extract the distribution to a local directory such as `C:\tools\gradle-8.13` and add its `bin` folder to your user PATH. Restart the terminal, then run `gradle --version` and confirm 8.13. Use only official releases and verify published checksums where possible.
5. Recommended: install Git for Windows from https://git-scm.com/downloads/win and check `git --version`. VS Code is optional; Android Studio is sufficient for Kotlin/Compose editing.
6. For physical-device testing, have at least **two Android 10+ phones with Google Play services**, ideally three. Keep them on current security updates. The test phones must enable Bluetooth and Wi-Fi.

## 2. Open the project and compile

1. Extract the ZIP. In Android Studio, choose **Open** and select the folder containing `settings.gradle.kts`, `build.gradle.kts`, and `app/`.
2. In the Android Studio terminal, create the project's Wrapper (once):

```powershell
gradle wrapper --gradle-version 8.13 --distribution-type bin
```

3. When the project syncs successfully, run:

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

4. The **expected** debug APK path on successful build is `app\build\outputs\apk\debug\app-debug.apk`. It is not included in this source ZIP. If you see a Kotlin/Gradle error, copy the **first concrete build error** and fix it; don't assume this unaudited alpha compiled on the author's machine.
5. Use `Build → Build Bundle(s) / APK(s) → Build APK(s)` in Android Studio to build via the interface.

## 3. Install APK on a test phone

1. Settings → About phone → tap **Build number** seven times (may be located under Software information depending on vendor). Enable **Developer options → USB debugging**.
2. Connect phone by USB. Accept the device's RSA debugging fingerprint on the phone. In Windows terminal:

```powershell
adb devices
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

3. Alternatively, select your physical device in the Android Studio device dropdown and press **Run**. USB debugging is for trusted test computers; turn it off later if no longer needed.
4. A **debug APK is development-signed**; do not publish it as a vetted release. Later create a signed release through Android Studio's **Build → Generate Signed App Bundle / APK** with a securely stored signing key and documented release process.

## 4. Test with three phones

- **Phone A (sender)**: Create a vault with an invented test passphrase. In Contacts, use QR display to share a public key.
- **Phone B (trusted recipient)**: Create an independent vault and mutually add A/B QR public keys; verify both public-key fingerprints by direct comparison. Fingerprints alone are insufficient if the exchange itself is substituted; compare on trusted screens.
- **Phone C (unknown relay)**: Create a third vault. Do **not** save B or A as a contact. It must not decrypt the private A→B SOS.
- On all three devices open Mesh → Start nearby sharing, grant the relevant local radio permissions, and verify that peers appear. Test the public updates. These updates must be considered unverified and potentially readable by all nearby peers.
- From A send a synthetic SOS and verify B receives it. A should remain `QUEUED_NOT_DELIVERED` until B's separately signed, recipient-encrypted receipt makes it back to A; only then show `RECEIVED_BY_TRUSTED_APP`. If the radios or connection fail, statuses must never change to received merely because a message left A's process. A receipt means **received by the app**, not seen by the person.
- Record a **non-sensitive** sample photo on A. Select B under Evidence and tap **Make encrypted copy** for the photo; save the `.crb` archive to a user-chosen location. Transfer the file to B by USB or a local sharing method the user controls. On B, select it under Evidence; because the document picker backgrounds the app, unlock the vault again, then choose **Import pending copy**. The imported hash should match A's original plaintext hash.
- Try to import A→B evidence on C. It must fail. Try a modified bundle and a broken zip. Both must fail safely. Ensure files larger than the 50 MiB limit are refused. The app should not have to decrypt the media into a plaintext file for verification.
- Revoke permissions, lock screens, interrupt a transfer, switch between portrait and landscape, reboot, and repeat after a cold launch. Test multiple Android vendors and Android versions; Nearby relies on Google Play services.

## 5. Important limitations

- `.apk` installs on Android. `.aab` is a Play Store publishing format. `.exe` is a Windows desktop executable, **not** an Android format. This repo does not build a Windows app.
- Evidence preservation is **manual recipient-encrypted copy/export and import**; no automatic trusted peer file transfer or cloud sync yet. Deleting the app can destroy all **uncopied** local data.
- Receipt is **cryptographically verified app receipt** when a valid signed packet reaches the original sender via the mesh. It is not acknowledgment by a human, and it has no cross-city delivery guarantee.
- Do not rely on this prototype for urgent assistance, medical help, legal advice or safeguarding irreplaceable recordings. Use established communication and emergency resources where possible.
- Plaintext camera media can temporarily exist in private app cache while being recorded and before encryption. Android Keystore support, device unlocking, forced disclosure, and OS compromises impose additional limitations. Do not describe this app as seizure-proof.

## 6. Source editing quick reference

- `app/src/main/java/org/civicrelay/app/MainActivity.kt`: Compose screens and dialogs.
- `.../CivicService.kt`: behavior and actions.
- `.../storage/Vault.kt`: encrypted private vault, metadata, media keys, SOS statuses.
- `.../storage/EvidenceBundle.kt`: manually transferred, recipient-encrypted evidence files.
- `.../crypto/Crypto.kt`: signed and encrypted packets.
- `.../mesh/NearbyMesh.kt`: local radio transport.
- `app/src/main/res/values/strings.xml`: app strings and copyright notice.
- `app/build.gradle.kts`: Android configuration and dependencies.

After each edit, commit to Git and rebuild. Never place API secrets, QR private keys, real phone numbers, sensitive recordings, or real protester information in a repository.


## Windows shortcut added in the reliability update

After installing Android Studio + SDK, JDK 17, and Gradle 8.13, the simplest commands in PowerShell at the project root are:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-debug.ps1 -RunLint
adb devices
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\install-debug.ps1
```

The build script prints a SHA-256 checksum for the resulting APK and **fails** on compilation/unit-test failures. It does not perform instrumentation tests or a security audit. See `docs/BUILD_AND_RELIABILITY_CHECKLIST.md` for a practical acceptance checklist.
