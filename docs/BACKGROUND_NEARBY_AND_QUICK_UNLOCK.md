# Background Nearby and fingerprint quick unlock

Version 0.1.5-alpha — experimental source code. **Not tested on physical Android devices.** No guarantees of delivery, privacy from a compromised phone, or indefinite background operation.

## Why this change exists

Previously, `MainActivity.onStop()` called `civic.lock()`, and `CivicService.lock()` stopped `mesh`. Opening Android's document picker, switching apps, or turning the screen off therefore stopped Nearby immediately. The app also demanded the full vault passphrase after every background transition.

## New behavior

- Opening another app **does not stop Nearby**. Explicitly enabling advertising or discovery starts a user-visible foreground service with the Android `connectedDevice` service type. It is not automatically started during installation or at boot.
- The Android notification includes **Stop**. The app's Nearby screen has a corresponding **Stop nearby connection** control. Both stop the radio and all Nearby sessions.
- The vault and nearby radio are independent. Vault data is locked after approximately two minutes outside the app, or immediately if the user taps Lock. An in-progress recording or decrypted evidence operation may be interrupted by locking.
- With the vault locked, the Nearby service **may relay signed public posts and encrypted private packets** using a bounded local relay cache. Private SOS decryption, receipts, trusted-evidence import, and authenticated evidence transfer require the user to unlock their vault. Queued private packets are checked after an unlock.
- The service is **not immortal**. Android may stop or kill it when permissions change, Bluetooth/Wi-Fi turns off, the user force-stops the app, battery restrictions apply, Play services fails, or the OS reclaims resources. It does not silently restart after reboot or process death. Reopen the app and start Nearby again when needed.
- Running Nearby while backgrounded can expose radio presence and uses battery. Make it a conscious opt-in; users should disable it when not needed.

## Optional biometric quick unlock

- Available on **Android 11+ with an enrolled strong biometric** supported by Android's framework. This source currently uses **fingerprint-compatible strong biometrics**, not a custom drawn pattern.
- Initially create/unlock the vault with the original strong passphrase. Then visit **More → Privacy and quick unlock → Enable fingerprint unlock**.
- An Android Keystore AES-GCM key with `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` wraps a copy of the vault's passphrase-derived 256-bit key after successful biometric authorization. The raw passphrase is **not** saved.
- Reopening a locked vault decrypts the wrapped key only after a successful system biometric prompt. This is a convenience/security tradeoff: someone who can unlock a registered biometric can unlock the app too. The passphrase remains the fallback.
- Adding/changing registered biometrics or reinstalling the app can invalidate the Keystore key; use the passphrase and enroll again. Never claim biometric access is seizure-proof or coercion-proof.
- Optional **Turn off fingerprint unlock** deletes the wrapped-key file and Keystore alias. This does not remove an unlocked vault's contents.
- Android 10 and devices without qualifying biometrics continue with the passphrase; do not replace strong vault encryption with a four-digit PIN or short pattern.

## Test on two real Android phones

1. Update both phones with the same debug signing key using `adb install -r` (do **not** uninstall first; existing test vaults would be deleted). Keep the same app ID `org.civicrelay.app`.
2. Unlock both vaults. In **Nearby**, select **Make my phone visible** on A and **Search for phones** on B. Grant Nearby/Location/Notification permissions as prompted. Observe the foreground notification and both devices' radio diagnostics.
3. Once connected, press Home on A for 30 seconds. Verify A's notification and B's peer count. Reopen A: the vault should still be unlocked (inside the short grace period) and the radio should remain active.
4. Press Home again and leave A for more than two minutes. Reopen A: the vault should be locked but the Nearby service should still be visible; unlock A using the passphrase. If configured, test fingerprint quick unlock instead. Verify radio remains on.
5. With A's vault locked and Nearby running, send **synthetic** signed updates/SOS through another peer. Verify forwarded packets are never represented as delivered to a private recipient until that recipient actually imports/opens them and returns a valid receipt. Verify no plaintext SOS is shown in notifications.
6. In A's notification, tap **Stop**. Confirm that Nearby shuts off and that its notification disappears. Re-enable from within the unlocked app.
7. Force-stop CivicRelay or switch off Bluetooth; confirm the UI does not promise indefinite connectivity. Restart Nearby manually after restoring radio service.
8. Under **More**, enable biometric quick unlock while unlocked. Lock the vault; verify that an authorized fingerprint unlocks it and an unsuccessful/cancelled prompt does not. Test **Turn off fingerprint unlock** then ensure passphrase unlock still works. Also verify biometric-enrollment changes fail safely with passphrase fallback.
9. Test document picker, switching apps, video recording, imported evidence, incomplete transfer while locking, process termination, and low-storage behavior.
10. Check Android Logcat for `SecurityException`, `ForegroundServiceStartNotAllowedException`, Keystore errors, and Google Nearby API status codes. Run unit and device tests; seek external security review before actual emergency use.

## Build on Windows

Run from your existing working `D:\CivicRelay-Android` folder with JDK 17 + Gradle wrapper 8.13:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

A successful `assembleDebug` creates `app\build\outputs\apk\debug\app-debug.apk`. You must independently verify and test on actual devices. Don't assume the patch builds until Gradle confirms it.

## Copyright

© 2026 Chirag Goyal. All rights reserved.
