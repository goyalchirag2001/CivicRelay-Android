# 48-hour experimental MVP work plan

This repository is the **initial code**. The schedule below estimates work for two development days; it is not a guarantee of safe or bug-free delivery.

## Hours 0–4: Build and device installation

- Import project, generate Gradle wrapper, install Android SDK 36, fix any SDK/dependency compile errors that could not be tested in authoring environment.
- Run `:app:assembleDebug`, `:app:connectedDebugAndroidTest` on 2 devices, inspect all Android runtime permission flows.
- Check screen lock/Keystore initialization on recent Samsung, Pixel and other Android handsets.

## Hours 4–12: Trusted contact, SOS and offline mesh

- Verify QR scanner and displayed fingerprint workflow on separate test phones.
- Verify public post appears across Nearby after data is disabled; verify private SOS decrypts only for intended trusted contacts.
- Add paced outbound payload flow control and stricter per-endpoint packet rate limits; test as peer count grows.
- Diagnose discovery failure modes, disabled radios, revoked permissions, OS battery limits and close/open behavior.

## Hours 12–20: Camera, evidence and incident logs

- Test image capture and 30s video with/without mic permission on hardware; verify output encrypted and temporary cache removed.
- Test OCR on mock nameplates in English/Devanagari; display explicit OCR-as-suggestion language.
- Add robust handling of app backgrounding mid-recording and file cleanup on crash; tune device-specific CameraX triple binding.
- Add ability to view encrypted local photos WITHOUT exporting them to shared storage; consider video playback only after careful plaintext handling.

## Hours 20–32: Data integrity and safety testing

- Add end-to-end delivery acknowledgment; handle queued/sent/received separately.
- Add UI messaging for completely failed radio/permission states.
- Verify TTL/hops/dedup; cap packet replay, spam and queue abuse.
- Consider encrypted, consent-based peer replication of small evidence files only if transport tests are reliable; video replication deferred.

## Hours 32–44: Accessibility and real-world negative tests

- Hindi+English strings, high contrast, font scaling, touch targets, older phones, low battery.
- Simulate disconnected peers, unexpected kill, device reboot, loss of location, unverified reports and malicious malformed packets.
- Audit logs, external intents, network permissions, Android backups and cloud usage.

## Hours 44–48: Private alpha release only

- Review all critical findings, fix blockers, verify full builds/tests, generate test-only signed APK and publish checksums.
- Distribute to a **small consenting test group**, collect problems, clearly label it experimental.
- Do not claim safety/security assurance. Do not invite people to rely on the app in real detention or danger.

## Larger follow-up scope

- Encrypted media replication with independently controlled copies (most important anti-deletion feature).
- Hardened messages/receipts/relay retry, push-to-talk audio, optional cellular SOS and accessible location controls.
- Revocation, contact fingerprint re-verification, scoped group keys, firmware/OS security checks, vulnerability disclosure, third-party audit.
- Optional cloud encrypted backup only with capped free-tier usage and user-controlled keys.
