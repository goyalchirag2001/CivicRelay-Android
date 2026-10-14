# Threat model and security limitations

**Do not market this as police-proof, seizure-proof, deletion-proof or safe for live use.**

## Protections present in source (NOT yet device-verified)

- Passphrase-derived AES vault, Android Keystore AES wrapping, randomized GCM IVs, private app storage, backups disabled.
- Media encrypted after CameraX finalization using fresh per-file AES keys and a plaintext SHA-256 integrity digest; temporary plaintext period remains.
- `FLAG_SECURE` inhibits common Android screenshots and recent-app preview capture; it does NOT stop photography of the screen or malicious OS access.
- Per-SOS recipient encryption and persistent-sender signature proof hidden inside ciphertext; fresh ephemeral outer signing identity and anonymous recipient slots.
- Signed ephemeral-pseudonym bulletin posts, TTL, signed content validation, maximum small payload size, bounded relay queue.
- Explicit discovery toggle, lock when app is backgrounded, no server endpoint, no INTERNET permission.
- Camera OCR is on-device and makes no identity claim.
- Opt-in, recipient-encrypted portable evidence copies; trusted-contact signature check and two hash checks on import. This is manual copying, not deletion resistance.
- Signed, recipient-encrypted app-level SOS acknowledgment messages; no guaranteed reverse path or proof the recipient has read the alert.

## Critical unaddressed risks

- **Phone seized while unlocked**: an adversary with the screen unlocked can read material visible in the app; root/forensic/OS exploits and coercion defeat app-only controls.
- **Temp media**: files exist unencrypted in a private cache until CameraX finalization, OCR, and encryption. Deleting a file is not secure erasure on flash storage. Android may store media or audio buffers in other memory or system components.
- **Background interruption**: auto-lock stops discovery, and interrupting video can lose evidence, leave a short-lived temp file or prevent successful encryption. Cleanup attempts do not guarantee secure erasure.
- **Capture data survival**: app data can be erased through uninstall, factory reset, storage corruption or destruction. Real evidence resilience requires at least two independently controlled encrypted copies.
- **Loss of device or forgotten passphrase**: no recovery because hardware key is device-bound; backups are disabled.
- **Metadata / traffic analysis**: nearby participants, packet sizes/times, and radio activity remain observable. Public posts intentionally spread openly; relay packets persist as metadata-bearing files.
- **Offline falsehoods / harassment**: signatures only show that the same ephemeral key signed a report; truth cannot be verified. No malicious peer throttling, moderation or Sybil defense exists yet.
- **Availability**: Nearby may fail due to disabled radios, permissions, Google Play services, OS policies, congestion or distance. Three-hop forwarding and TTL do not guarantee delivery. Signed app-level SOS receipts can establish delivery to a trusted app **only if the return packet reaches the sender**; they do not prove a human read it.
- **Phone lock policy**: the app passphrase is not a substitute for a strong device screen lock and up-to-date Android.
- **Cryptography**: no external review, protocol version negotiation, key revocation/rotation or secure memory erasure. RSA identity pair currently does both signature and key wrapping; split into separate, audited keys before production.
- **SMS**: carrier and SMS app can observe plaintext. Optional SMS coordinate handoff exposes location if selected.
- **Physical/legal safety**: verify recording laws, consent, local circumstances and support contacts; publishing identifying details can endanger people or expose them to harassment.

## Privacy choices for officer documentation

Capture **visible, user-observed** nameplate, badge, unit, time, location and witness description. Bundled Latin/Devanagari OCR assists with text transcription, which may be inaccurate. There is **no police face recognition, biometric identification, mass photo scraping, or automatic matching of a face to a person's identity**. An official public directory lookup, if implemented in a later release, would be triggered manually and based only on a verified public badge/name lookup, not a facial match.

## Required before ANY public-risk use

1. Obtain an independent Android and cryptographic security review and a threat assessment with journalists/protest legal support and rights organizations.
2. Independently verify the newly added manual encrypted evidence copies and app receipt implementation on physical phones; add automatic approved copy transfer, reliable recovery tests and usable privacy defaults.
3. Test seizure while locked/unlocked, low-battery, abrupt process kill, storage-full, app uninstall, backup/restore, loss of Keystore key, permission changes and root/ADB forensic exposure.
4. Test on a diverse fleet of physical Android devices with no mobile internet, Bluetooth-only, Wi-Fi-only, crowded radios and three-hop participation.
5. Perform penetration testing (packet flooding, replay, malformed payloads, malicious contact keys, UI deception, unverified news, GPS spoofing).
6. Create external security disclosure policy, reproducible builds, signing key custody and emergency release rollback procedure.
