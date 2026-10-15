> **Latest update (v0.1.8-alpha, 15 October 2026):** SMS contacts now have a separate screen under **More → Parents & SMS contacts** with reliable save/error feedback and a Draft SMS action. **SOS → Add SOS shortcut to Quick Settings** provides Android 13+ guided setup and older-phone instructions. Quick SOS retains its prepared alert if durable queuing fails and uses a one-time encrypted app-to-app packet (not background SMS). Read [`docs/QUICK_SOS_AND_SMS_TESTING.md`](docs/QUICK_SOS_AND_SMS_TESTING.md). Source is not a verified emergency tool; build and real-device tests still required for this patch.

> **Build note (2026-10-15):** Experimental source update v0.1.6-alpha adds a guarded More/Privacy screen, clearer Evidence layout, optional signed public posting profiles, one-time recipient-encrypted location check-ins, and a future privacy-map policy. **Online remote sync, public identity verification, and a live map are NOT implemented or deployed.** See `docs/ONLINE_SYNC_IDENTITY_AND_SAFETY_MAP.md`. Full Android build/device testing is pending for this version.

**Latest repair (0.1.5-alpha):** [Nearby connections, QR contacts, SMS location, modern UI and phone test guide](docs/PHONE_DISCOVERY_AND_UI_REPAIR.md).

# CivicRelay — Android experimental starter

**Status:** encrypted evidence transfer and recovery/viewer prototype source, **NOT** a verified/emergency-ready application. No APK is supplied. Designed for private testing only until device testing, accessibility testing, independent crypto/security audit and incident-response procedures have been completed.

## What you get

- Kotlin + Jetpack Compose Android app with no accounts, no app-managed backend and no mandatory cloud storage.
- Local passphrase vault: PBKDF2-HMAC-SHA256 (310k rounds) + Android Keystore bound second AES-GCM layer. Device keys may be backed by security hardware depending on the phone. The passphrase is never uploaded.
- A second, independent ephemeral-key-signed public bulletin identity per message (no long-lived bulletin pseudonym).
- Recipient-encrypted SOS for up to 10 previously verified trusted public keys, local GPS when available, 2-hour expiry; no device identifiers needed.
- Nearby Connections P2P_CLUSTER radio-based public bulletin and opaque encrypted SOS relay, three allowed hops, and capped local relay queue.
- In-app CameraX photo capture, 30-second SD video, optional microphone, post-capture AES-GCM stream encryption using a random media key inside the wrapped vault, SHA-256 evidence digest and OCR suggestions for visible Latin/Devanagari text.
- Structured incident form to document visible police name/badge/unit details, location and witness notes (manual, not facial recognition).
- Offline QR pairing (display and scan) for adding trusted public keys. **Both parties must compare fingerprints out-of-band** before trusting.
- Optional SMS app handoff: only after an explicit button press. Cellular signal required; SMS is NOT end-to-end encrypted.
- App screen capture prevention via FLAG_SECURE, Android cloud backup disabled, private app storage, lock on background.
- **Previous:** per-file AES-256-GCM content keys for recordings and recipient-encrypted 50 MiB manual export/import remain available.
- **New:** end-to-end encrypted SOS receipt packets signed by the actual trusted contact, resulting in `RECEIVED_BY_TRUSTED_APP` only if the original sender verifies the return receipt.
- In-app **© 2026 Chirag Goyal. All rights reserved.** notice.
- **Reliability update:** camera callbacks are cancelled on screen shutdown/backgrounding and attempted plaintext temp-file cleanup occurs, including when the vault has locked (this is **not secure deletion**).
- **Reliability update:** a background packet cannot be processed after vault lock; encrypted-vault readers return immutable-by-convention JSON copies, and failed creation no longer leaves an unlocked partial state.
- **Reliability update:** inbound Nearby packet sizes/rates are bounded per connection, connected peers and replay bursts are capped; app explains manual radio enabling. These are **not** anti-surveillance or full denial-of-service protections.
- **Reliability update:** adding a trusted contact requires checking the displayed fingerprint and explicitly confirming comparison over another channel.
- **Previous:** on-demand 1-hop Nearby recipient-encrypted evidence transfer only to directly authenticated trusted contacts; receiver must explicitly accept an offer. Shows progress and locally stores cryptographically verified recipient-app storage receipts. 50 MiB evidence size ceiling; no automatic or background transfer.
- **New:** in-vault verified media viewer: AES-GCM authentication + SHA-256 validation before JPEG preview or video playback. Play back MP4 with Android MediaPlayer's in-memory MediaDataSource; no new internal plaintext video file.
- **New:** imported evidence provenance labels, in-app viewing of trusted-contact received copies, and explicitly confirmed user-directed plaintext JPEG/MP4 export through Android's Storage Access Framework. The OS document picker locks the vault and a second unlocked confirmation is needed to complete the export.
- **New:** bounded preview size, recognition of expected JPEG/MP4 signatures and unit tests for basic media policies; no claim of full media-parser security.
- **Build support:** Windows build/install PowerShell scripts, optional GitHub Actions Android build workflow, and pure-JVM inbound traffic limiter and transfer-policy unit tests.

## What this code does NOT provide

- No automatic person identification or police face matching. No government facial database scraper.
- No guarantees against police access, compelled disclosure, compromised Android OS, physical destruction, uninstall or factory reset.
- No reliably persistent recording while the app is locked or in the background.
- Opt-in direct Nearby evidence transfers and manual export/import are included, but no multi-hop large-file relay, automatic background synchronization, guaranteed file retention or independently verified human review. Playback and user-directed recovery/export exist, but are NOT certified secure.
- No SMS transmission by the app itself, no automatic telephone calls, no cloud push. Cryptographically signed app-level SOS receipts exist; there are no human read receipts or guaranteed delivery routes.
- No continuous location tracking. One location fix per SOS, when permitted and available; it reaches parents only when a route exists to their app or the user sends an SMS through their own SMS app.
- No spam moderation, abuse-resistant Sybil defense, full replay defense or anonymous wireless transport. Radio observers can still detect nearby participation.
- No device-level Android integration test or APK build was possible in the authoring environment. The new Nearby FILE protocol has **not** been device-validated. Must build, test and review before distribution.

For direct sharing security, consent and device tests read **`docs/NEARBY_EVIDENCE_TRANSFER.md`**. For build checks and real-device tests, start with **`docs/BUILD_AND_RELIABILITY_CHECKLIST.md`** and the updated **`docs/WINDOWS_SETUP_AND_TESTING.md`**. For archive and receipt design, open **`docs/EVIDENCE_AND_RECEIPTS.md`**.

For in-app viewer, import/recovery and plaintext export restrictions, see **`docs/EVIDENCE_VIEWING_AND_RECOVERY.md`**. A decrypted export is intentionally outside CivicRelay's vault security boundary.

## Prerequisites

- Android Studio (current stable); Java 17 for Gradle; Android SDK API 36; 2–3 physical Android phones with Google Play services installed.
- Jetpack Compose 1.11.4 (pinned for `compileSdk 36` / AGP 8.13.2 compatibility; see `docs/COMPOSE_GRADLE_COMPATIBILITY.md`).
- Google Nearby Connections 19.3.0 (pinned to avoid Kotlin 2.4 metadata in 19.5.0 with Kotlin compiler 2.2.21; see `docs/KOTLIN_COMPILATION_FIX.md`).
- Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.21. A Gradle Wrapper is not included: generate one from a trusted locally installed Gradle 8.13 installation. The new `tools/build-debug.ps1` works directly with Gradle in PATH.
- Bluetooth + Wi-Fi enabled on participating devices; GPS optional for SOS location.

## Build on Windows, step by step

1. Download and install **Android Studio** from `https://developer.android.com/studio`. Open the **CivicRelay-Android** folder (the folder containing `settings.gradle.kts`).
2. Open **Settings → Languages & Frameworks → Android SDK → SDK Platforms**. Install **Android 16 (API 36)**, then ensure SDK Build-Tools and Android Platform-Tools are installed.
3. Set Android Studio's **Gradle JDK** to JDK 17 (Studio's bundled JBR, if it is 17, or a separate JDK 17).
4. The supplied project intentionally contains no precompiled Gradle Wrapper binary. Install a trusted Gradle 8.13 locally (`https://gradle.org/install/`) and from the project root run `gradle wrapper --gradle-version 8.13` to generate official `gradlew.bat` and wrapper files. Confirm the generated wrapper JAR against Gradle's published checksums.
5. In Android Studio choose **Sync Project with Gradle Files**. Run `powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-debug.ps1 -RunLint`, or run in terminal (Windows):

   ```powershell
   .\gradlew.bat :app:assembleDebug
   .\gradlew.bat :app:connectedDebugAndroidTest
   ```

6. If the build succeeds, find the debug APK at `app/build/outputs/apk/debug/app-debug.apk`. This location is **an expected Gradle output**; no APK is included here.
7. Install only to your own test phones. Enable Developer options / USB debugging, connect the device and use Android Studio's Run button. Use a strong phone screen lock.
8. Create a different passphrase on each device. Under **Contacts**, display the QR on Device A, scan it on Device B, and vice versa; **compare full key fingerprints on both screens** before entering trust.
9. On each phone open **Mesh → Start nearby sharing**, grant radio permissions and confirm Bluetooth and Wi-Fi radios are on. Exchange a clearly labeled test update.
10. Send a **test** SOS containing no real personal data. Verify the intended contact receives it and a third, non-recipient phone cannot read it. Never infer delivery from the sender's QUEUED status.

For the fastest development loop, use a physical Android 13–16 phone; emulators are not a reliable substitute for radio mesh testing.

## Emergency-use caution

This is a **learning/research prototype**. Never advise protesters, witnesses or their families to depend on this app for emergency communication or evidence protection until a qualified independent team has reviewed and tested it. In an urgent emergency, use available local emergency services and established safety plans.

## Repository contents

- `app/src/main/java/org/civicrelay/app/` — activity, UI and orchestration
- `crypto/` — asymmetric signed envelopes, recipient encryption, device key
- `storage/` — passphrase+hardware-bound vault, encrypted evidence, bounded relay cache
- `mesh/` — Nearby Connections transport
- `media/` — CameraX evidence capture, bundled OCR, QR scanner/generator
- `app/src/androidTest/` — Android crypto/envelope and relay-cache instrumentation tests
- `app/src/test/` — JVM tests for inbound network traffic limits
- `tools/` — build and local USB installation helper scripts
- `.github/workflows/android-build.yml` — optional CI build verification (requires GitHub Actions)
- `docs/ARCHITECTURE.md` — protocol/flow and threat model
- `docs/SECURITY.md` — limitations and security hardening priorities
- `docs/48_HOUR_PLAN.md` — 48-hour development/test plan
- `docs/BUILD_AND_RELIABILITY_CHECKLIST.md` — release-blocking build and on-device checks
- `docs/TEST_MATRIX.md` — physical device acceptance checks
- `docs/WINDOWS_SETUP_AND_TESTING.md` — Windows install/build/test commands and evidence copy workflow
- `docs/EVIDENCE_AND_RECEIPTS.md` — encrypted archive and SOS receipt protocol
- `docs/NEARBY_EVIDENCE_TRANSFER.md` — consented direct 1-hop encrypted evidence transfer protocol and threats
- `docs/EVIDENCE_VIEWING_AND_RECOVERY.md` — authenticated in-memory viewer and recovery/export testing

## Copyright and licensing

**Copyright © 2026 Chirag Goyal. All rights reserved.** See `COPYRIGHT.md`. No license is assigned to this starter. Before public open-source publication, choose a license (e.g., Apache-2.0 or GPLv3), include the full license text, and audit third-party dependency licenses.

## Background Nearby and quick unlock (0.1.5-alpha)

Nearby now runs only after the user explicitly enables it and uses an Android connected-device foreground service, independent of private-vault locking. The vault locks two minutes after backgrounding, while optional strong Android fingerprint quick unlock can be enabled from More. For setup, privacy caveats and two-phone test cases read [`docs/BACKGROUND_NEARBY_AND_QUICK_UNLOCK.md`](docs/BACKGROUND_NEARBY_AND_QUICK_UNLOCK.md). This feature requires physical Android device validation.

## New experimental urgent-alert controls

See [Quick SOS, SMS contacts and relay priority](docs/QUICK_SOS_AND_CONTACTS.md). This source remains an experimental prototype and is not a guaranteed emergency service.
