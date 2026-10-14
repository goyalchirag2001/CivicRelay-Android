# Direct encrypted evidence copying — experimental protocol

Copyright © 2026 Chirag Goyal. All rights reserved.

## Purpose and limits

**Designed for private prototype tests only.** No cloud account or internet is required. Both phones must be powered on, within Nearby Connections range, running the app in the foreground, vaults unlocked, Mesh switched on, and keys previously exchanged and **independently verified via QR fingerprint comparison**. This is a **direct 1-hop** file transfer only, *not* multi-hop video streaming, automatic continuous backup, guaranteed retention or a security-audited system. Limit: **one transfer at a time and 50 MiB evidence plus archive overhead**.

Both devices need sufficient battery and free disk space. If they disconnect, lock, background, or crash, the transfer stops; **there is no automatic restart/resume**. The sender may retry from the original encrypted evidence. No evidence is copied to arbitrary public bulletin peers.

## Protocol states

1. Nearby P2P_CLUSTER connection opens; each phone sends an unlinked random challenge. Phones respond by signing the challenge using their vault's long-term RSA identity. Only previously trusted public keys are accepted as directly authenticated peers. **This process reveals the app's stable contact public key to other nearby devices that send challenges**, even if those devices are not trusted. It is **not anonymous**, unlinkable or resistant to relay attacks against proximity.
2. Sender selects an evidence record and previously trusted contact, taps **Send encrypted copy over nearby**. Existing `EvidenceBundle.export` authenticates and re-encrypts the media to a recipient-specific `.crb` archive without creating plaintext exports. The sender computes encrypted archive SHA-256, size and a random offer ID.
3. Sender sends a signed `offer` to a *directly authenticated trusted endpoint*. The receiver displays the trusted contact alias and file size. The receiver must tap **Accept encrypted copy**. The receiver checks storage availability for roughly two copies plus 10 MiB headroom. It can also **Decline**.
4. Signed `accept`/`start`/`ready` messages bind the offer ID, ciphertext hash, length, intended recipient and Nearby payload ID. Only after `ready` does the sender transmit a **Nearby FILE payload** containing the recipient-encrypted `.crb`, not decrypted footage.
5. Receiver rejects any unsolicited or wrong-sized FILE payload; transfer callback progress is shown on both phones. Nearby temporarily stores incoming encrypted file bytes in a system-managed file/Downloads URI, **which is not the app's private encrypted vault**. The app copies and bounds this *encrypted* data, compares size and SHA-256 and runs `EvidenceBundle.import`; this verifies trusted sender and recipient, signature, ciphertext hash, AES-GCM authentication tag and plaintext digest, then rewraps the evidence key under the recipient's private vault.
6. Only after verified import does receiver sign and send `stored` back over the authenticated direct connection. Sender validates sender key, signature, offer ID, size and SHA-256 before marking `VERIFIED_STORED_BY_TRUSTED_APP` in the encrypted local transfer log. It does **not** prove a human read the file or that their phone will retain it indefinitely. If no receipt arrives, the status remains unconfirmed.
7. Aborted payloads, staging archives and temporary files are cleaned up on a best-effort basis. Delete is **not secure erase** on flash/modern Android; the Nearby provider may retain temporary files until cleanup. A third-party app with privileged access, compromised OS, or a phone seized while unlocked can still expose material.

## Threat-model caveats

- Nearby itself connects to arbitrary radio peers for public bulletins; *direct evidence* only proceeds after a signed key proof and recipient acceptance. Peer proof only establishes key control, not human identity or verifiable physical proximity.
- The Nearby service, radio observers and OS may learn metadata including transfer participation, time, size and the signed long-lived pairing key during authentication. A malicious nearby device may attempt radio denial-of-service or spoof proximity by relaying messages.
- No forward secrecy is claimed. Identity and archive key compromise may compromise prior stored evidence archives obtained by an attacker.
- Disk-space checks and file quotas do not completely prevent a compromised OS or a maliciously cooperating trusted peer from exhausting device resources.
- File receiver auto-imports *after explicit offer acceptance*. An import after the user locks cannot be acknowledged. The receiver is not an immutable archive; it can later delete its own copy.
- There is no guaranteed message or video delivery. The OS may kill the app, revoke permissions or suspend Nearby sessions without warning.

## Two-phone test plan

- Enable airplane mode on both phones, then enable Bluetooth and Wi-Fi manually and keep them close. Confirm neither needs internet. Both must have Google Play services and previously verified each other's QR fingerprint.
- Device A creates and encrypts a clearly fictional 1–5 MiB photo/video; Device B has sufficient storage. Both unlock and tap Mesh → Start nearby sharing, then verify **Trusted nearby contacts: 1** on Evidence.
- On A select B, tap **Send encrypted copy over nearby**. Confirm B sees an offer, the size and **Accept/Decline**. Test **Decline** first and verify A never shows a stored receipt.
- Retry and accept. Observe progress; B should show the record in its Evidence list only after import succeeds; A must show the signed `stored` receipt and see it in encrypted transfer history.
- Repeat with no free space, unplugging/removing radio mid-transfer, locking receiver during import, and disconnecting each peer. Verify none is falsely labeled `stored`.
- On a third phone C without trust keys, observe that C cannot trigger approved recipient import or receive decrypted evidence. This does not guarantee C cannot observe radio activity.
- Verify there is no decrypted media file in Photos/Gallery/Downloads, only transient encrypted ciphertext managed by Nearby. OS cleanup is best effort, not provable secure erasure.
- Test legacy vaults created in prior builds: `transfers` list should be initialized lazily when a new transfer is logged.

## Known deployment blocker

A complete Android Gradle build and physical device validation has **not** been performed in the packaging environment. Build in Android Studio, run unit and instrumentation tests, and obtain independent security review before considering any field deployment.
