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
