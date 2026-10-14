# Secure evidence viewing and recovery (experimental)

This prototype can now view both original local recordings and **trusted-contact imported** records in Evidence. It does **not** prevent device seizure, memory forensics, deletion, coercion, or OS compromise. Run only on test phones using synthetic media.

## View/verify flow

1. Unlock the vault and open **Evidence**. Locate original or imported evidence by timestamp and SHA-256.
2. Tap **Verify & view**. The app requires an existing private `.crv` file with `CRV1` header, decrypts with the record's key, authenticates the AES-GCM tag, computes SHA-256 over plaintext and compares it with the stored hash. Only after both validations can it show the content.
3. JPEG data is decoded with bounds-based sampling and a pixel limit. MP4 playback uses an Android `MediaDataSource` backed by verified in-memory bytes instead of writing a decrypted file in the app cache. Errors should show a non-fatal message.
4. Imported records identify the trusted public-key fingerprint from which the evidence originated. This is a signed-at-import application assertion, not a proof of a person's identity or the veracity of the captured scene.
5. Close the viewer or lock/switch apps. The app tries to release playback and zero the clear-data buffer. Android decoding, media codecs, garbage collection, swap and a compromised OS prevent guaranteed memory erasure.

## Recovery: encrypted versus plaintext

- **Recover inside the private vault:** The existing encrypted `.crb` import or direct Nearby offer accepts records only when addressed to the local vault and signed by a saved trusted contact. Once imported, an independent encrypted copy can be viewed, re-exported as encrypted `.crb` for another contact, or explicitly exported in plaintext. There is no universal recovery account/password. Copies on a wiped device cannot be reconstructed without another authorized copy.
- **Export decrypted media:** Tap **Export decrypted copy**, read the warning, choose a user-controlled filename through Android's Storage Access Framework, unlock the vault again after the system picker returns, then **Finalize decrypted export**. The app verifies evidence before writing it and never intentionally writes plaintext into its own cache for this export. The chosen folder, gallery apps, device backups or cloud synchronization MAY expose the exported JPEG/MP4. An interrupted write attempts to delete the incomplete document, with no secure deletion guarantee.
- **Export encrypted archive:** Keep using **Export encrypted .crb** when moving evidence between trusted devices. This is safer than plaintext sharing but its current crypto/protocol implementation remains unaudited.

## Test matrix (two trusted Android devices)

1. Capture a synthetic JPEG and MP4 on Phone A; verify/view both offline with radios off.
2. Unlock Phone B; QR-pair and verify the correct fingerprints independently. From Phone A transfer or export a recipient-encrypted test bundle; receive/import it on B. Confirm the item is marked 'Recovered from trusted contact'.
3. Verify/play the received copy. Confirm hash matches the import record and that a corrupted `.crv` fails verification without displaying anything.
4. Open picker for decrypted export. Confirm that opening picker locks the vault; return to app, unlock again, verify a warning and manually finalize. Confirm JPEG/MP4 opens in normal media viewers and is **not encrypted**. Confirm cancel attempts to remove empty target.
5. Lock the app during verification or export: no viewer should remain available and incomplete exports should be removed on a best-effort basis.
6. Corrupt encrypted bytes, truncate file, change vault digest, test bad image dimensions, test unsupported media and large recordings. A failure must not crash or leave a plaintext file in app storage.
7. Capture process death, rotation and low-memory conditions during playback and SAF callbacks. Do not report 'passed' without actual device execution.

## Important limitations

- File-format signatures and JPEG pixel bounds are basic constraints, **not** a media parser sandbox.
- Android media codecs may handle sensitive frames internally and the preview stores up to 50 MiB clear media in RAM; memory pressure can still cause failures on low-end phones. This implementation is not secure erasure.
- No remote permanent backup, automatic multihop media relay, gallery cleanup, or independent security audit.
- Generated copyright remains: **© 2026 Chirag Goyal. All rights reserved.**
