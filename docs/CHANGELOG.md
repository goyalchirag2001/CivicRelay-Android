## 0.1.4-alpha — Connectivity, QR, SMS, UI repair

- Split Nearby radio discovery into explicit advertiser / discoverer roles for two-phone tests, with errors, discovery counts and connection diagnostics.
- Guarded contact QR rendering/scanning, enlarged high-density QR and validated pasted contact keys. Exact Contacts crash root cause still requires device Logcat.
- Explicit SMS location permission, GPS and recent-location fallback, maps link and non-deceptive unavailable state; manual send only.
- Redesigned Compose dark/light theme, vault entry screen, bottom navigation and More hub. Copyright preserved.
- Added standalone SMS-draft tests and detailed two-phone test matrix. Android SDK full build not run in preparation environment.

# Changelog

## Build compilation fix — Nearby metadata and vault import

- Pinned `com.google.android.gms:play-services-nearby` from 19.5.0 to 19.3.0: the newer library carries Kotlin metadata 2.4.0 incompatible with the app Kotlin compiler 2.2.21.
- Corrected `Vault.kt` import to `javax.crypto.spec.SecretKeySpec`. No encryption algorithms, vault bytes, or wire-protocol formats changed.
- The previous `checkDebugAarMetadata` step passed on the user's Windows machine. These latest changes require re-running Android compilation and device tests.


## Build compatibility fix — Compose API 36

- Corrected the four direct Jetpack Compose dependencies from 1.12.1 to 1.11.4 so AAR metadata matches Android Gradle Plugin 8.13.2 and `compileSdk 36`.
- JDK 17, Gradle 8.13, Kotlin, project source code, evidence handling and copyright remain unchanged.
- Verified source-package integrity and patch consistency only. Full Android build still pending on Windows.


## v0.1.3-alpha — Evidence viewer and recovery

- Added authenticated in-memory JPEG/MP4 preview after AES-GCM and SHA-256 verification.
- Added imported evidence provenance labels and reusable viewer for received copies.
- Added user-controlled decrypted export using Android Storage Access Framework, with warning and unlock-after-picker safeguard.
- Added basic media-format, pixel-bound, and MIME unit tests.
- Still no verified Android build or physical device validation. No safety/security certification.

# Changelog

Copyright © 2026 Chirag Goyal. All rights reserved.

## Current experimental release

- Added copyright notice inside locked and unlocked app screens, plus COPYRIGHT.md.
- Added per-file random AES-256-GCM content keys to newly captured media; wrapped under existing local media key. Legacy files remain readable without migration.
- Added recipient-encrypted portable `.crb` evidence export, per-export re-encryption without a plaintext media file, manual trusted-contact import with sender signature verification, per-recipient key binding, SHA-256 checks and GCM authentication. Bounded to 50 MiB media and user-driven transfer.
- Added encrypted `receipt` envelopes signed by trusted recipient identity. Outbound SOS transitions to `RECEIVED_BY_TRUSTED_APP` only after successfully receiving and verifying a matching return receipt. No human-read confirmation.
- Kept Android file-picker contracts registered at the top screen level so app lock onStop does not unregister the pending callbacks.
- Added Windows installation, build, APK, and three-phone tests, detailed protocol docs, and instrumentation tests for evidence archive access and receipts.
- No official APK or claimed Android build pass: compile/test on installed SDK and devices before distributing.

## Reliability and build-readiness update — 2026-10-14

- Added guarded CameraX callbacks and best-effort cache cleanup when recording or OCR finishes after the vault locks.
- Added lock synchronization on inbound network processing and guarded encrypted evidence saves.
- Added bounded per-peer inbound packet budgets, connected-peer cap and bounded cached-message replay on connect.
- Added contact fingerprint acknowledgement in the QR trust UI; added key-strength validation on contact import.
- Improved vault create-failure rollback and snapshot reads.
- Added optional CI and local Windows `build-debug.ps1` and `install-debug.ps1` scripts.
- Added JVM unit tests for ingress limiter and a build/device acceptance checklist.
- Bumped experimental version to 0.1.1-alpha.

**No claim of a full Android build, APK, emulator test, or physical radio test.** Those remain blockers.

## 0.1.2-alpha — Direct encrypted evidence transfer

- Recipient-approved Nearby FILE copying of recipient-encrypted 50 MiB evidence archives after signed direct trusted-key challenge.
- File-size/disk-space checks, hash-verification, authenticated import, sender-side signed storage receipt, progress display and encrypted transfer log.
- Pure-JVM transfer-policy tests, detailed testing and security documentation.
- Keeps manual `.crb` export/import as alternative.
- Still **not** Android-built, real-device tested or independently security-audited.
