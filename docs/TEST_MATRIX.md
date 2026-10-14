# Device test checklist — must all be checked before sharing test APK

Use fictional names, empty photos and dummy location only.

| Test | Steps | Expected |
|---|---|---|
| New vault | Create with strong passphrase, lock/reopen | Wrong pass cannot open; correct pass opens |
| Device-bound layer | Copy vault to separate Android install | Not decryptable without original Android Keystore key |
| Backups | Inspect ADB backup/Android backup configuration | Private data excluded |
| QR trust | Display QR A, scan B; manually check fingerprint; repeat B -> A | Reciprocal verified contact keys saved |
| Unknown contact | Do NOT add A on B; send encrypted SOS to B | B app does not show SOS as trusted |
| No internet | Turn off mobile data, keep radios enabled, connect 2 devices | Public update can appear if Nearby connects |
| Bulletin integrity | Inject tampered payload into receiver test | Invalid signature ignored |
| Private SOS | A selects B, C acts as relay without B private key | B decrypts; C cannot read content |
| SOS label | Press SOS while no peers reachable | Status remains QUEUED, not delivered |
| Signed receipt | B receives SOS from A; receipt returns to A | Only then does A display RECEIVED_BY_TRUSTED_APP; C cannot forge B's receipt |
| Three-hop | Position A↔B↔C with no A↔C direct connection | Packet forwarded if all peers active; no promise of delivery |
| Expiry | Inject expired/future packet in test | Rejected, not forwarded |
| Contact privacy | Inspect SOS packet | No stable sender public key and no recipient public-key fingerprints visible |
| Precise location OFF | SOS with location unchecked | No coordinates in encrypted payload |
| GPS denied | Deny precise location, send SOS | SOS still queued with explicit unavailable location |
| SMS fallback | Open SMS composer with mobile internet disabled | User confirms manually; carrier can see plaintext |
| Evidence photo | Capture photo; inspect app directory | Only finalized .crv media (and transient cache); keyWrap is per-file |
| Encrypted bundle | A exports a 1 KB dummy photo for trusted B, B imports | Ciphertext signed, GCM validated and plaintext hashes equal, no plaintext file written |
| Unauthorized import | C attempts to import A→B .crb | Rejected because wrong recipient / untrusted sender |
| Picker lock | A opens Save dialog; B opens Select dialog | Vault locks on app background; callbacks survive, import requires unlock |
| OCR | Capture simulated nameplate in English/Hindi | Suggested text saved, user must verify |
| Video | Record 5s with mic granted/denied | SD video saved encrypted; mic permission affects audio |
| Evidence interrupted | Background while capturing | No claim of complete file; no persistent plaintext after cleanup |
| Public news | Publish false dummy report | User sees prominently UNVERIFIED label |
| Radio refusal | Bluetooth/Wi-Fi off, permissions denied | Failure visible; no success claim |
| App lost | Uninstall app and reinstall | Evidence is lost; do not claim anti-deletion |
| No GMS device | Run on Android without Google Play services | App's mesh cannot operate; clear failure needed |
| Build | assembleDebug + connectedDebugAndroidTest | Both pass, inspect lint and dependency issues |

**Current authoring environment checks:** XML parse and static source checks were run; Android Studio/SDK, device, emulator and Gradle 8.13 were not present. The table above is **unexecuted**, not a declaration of passed tests.

## Verified media preview and local recovery (added)

- [ ] After camera capture, tap Verify & view for a JPEG and MP4; confirm GCM and SHA-256 validated and playback works without mobile data.
- [ ] Tamper encrypted local file and confirm viewer refuses; do not display partly decrypted content.
- [ ] Import a signed `.crb` copy from a verified trusted contact; confirm recovered-from fingerprint and ability to view/re-share it.
- [ ] Select decrypted export; system document picker backgrounds and locks app, requires unlock and explicit finalize; test cancel and interrupted write cleanup.
- [ ] Watch memory usage and process lifecycle (background lock, rotation, low-memory, export during lock).
- [ ] Confirm output JPEG/MP4 outside vault is plaintext and carries explicit warning.
- [ ] Run `:app:connectedDebugAndroidTest` including EvidenceViewerInstrumentedTest on Android device.
