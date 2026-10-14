# Encrypted evidence copies and SOS receipts (experimental protocol)

**Copyright © 2026 Chirag Goyal. All rights reserved.**

## Evidence bundle

`EvidenceBundle.export` creates a ZIP container with two fixed entries: `header.json` and `evidence.crv`. These files are encrypted for an **explicitly selected, saved trusted contact**. The header contains a public schema and an RSA-OAEP-SHA256-wrapped random transport key. The transport key protects an AES-GCM encrypted manifest. Only the recipient can decrypt that manifest. The manifest includes the signed sender identity, intended recipient identity, the evidence ID/type/time, original plaintext SHA-256, ciphertext SHA-256, record-specific AES content key and optional OCR suggestion. A signature using the sender's long-term key covers identity, the intended recipient, hashes, record metadata and OCR checksum.

The exporter authenticates the original encrypted evidence and streams it directly into a *new*, recipient-specific encrypted artifact in app cache. No plaintext exported media file is created. New local recordings use one random AES content key per file; older `CRV1` recordings lacking `keyWrap` are re-encrypted from the local vault key without sharing the vault-wide key. Exports are hard-limited to 50 MiB of evidence.

The recipient stages an encrypted archive from Android's document picker, unlocks their own vault, and explicitly imports it. The importer verifies that (1) the recipient is this vault, (2) the sender's identity key was manually saved as trusted, (3) the RSA signature is valid, (4) the ciphertext hash matches, (5) the evidence decrypts with a valid AES-GCM authentication tag, and (6) the original plaintext hash matches. Only then is the file moved into encrypted private storage, with the per-file key rewrapped under the recipient's local media wrapping key. The app does not automatically forward/import sensitive media from unknown peers.

**This is not deletion-resistant storage**: it only survives loss of the original device if a recipient manually transfers, accepts, and retains their encrypted copy. File transfer outside the app may expose metadata (file size, device proximity, creation time, selected app), so choose methods carefully. The current alpha has no media viewer, player, or verified retrieval workflow.

## SOS receipts

Incoming recipient-encrypted SOS packets include an inner sender signature. If it verifies and the sender is a saved trusted contact, the recipient app creates a **new**, signed `receipt` envelope containing the original packet ID and receipt time. The receipt is encrypted only to the sender's trusted public key, queued, and forwarded over the same hop-bounded mesh. A received receipt is accepted only if it is valid, decrypted by the original sender, signed by a saved trusted contact, and that contact was one of the intended recipients of the original SOS packet. Acknowledgment changes status from `QUEUED_NOT_DELIVERED` to `RECEIVED_BY_TRUSTED_APP`. It cannot prove the user saw or acted on the message.

The relay cache remains bounded (128 short packets). Public posts have shorter expiration than SOS/receipt. There is no cloud, persistent background networking, large-file mesh relay, forward-secrecy proof, privacy audit, or anti-censorship guarantee.

## Next engineering work before field testing

- Automated and abuse-resistant *direct trusted-device* encrypted file transfer, with explicit receiving approval, storage quotas and receiver delivery acknowledgments.
- Security review of the scheme and Android device/lifecycle behavior, crash resilience and cryptographic key handling.
- Access-controlled evidence viewer and export recovery workflow with explicit user consent and no plaintext persistence.
- Queue encryption for sensitive metadata, stronger anti-spam protections, radio privacy analysis, independent accessibility and multilingual UX testing.
