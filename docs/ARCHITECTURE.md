# Technical architecture and offline protocol

## Overview

```text
User UI (Jetpack Compose) ───────────────────────────────────────┐
    ├─ Emergency -> GPS snapshot -> private JSON body           │
    ├─ Public updates -> ephemeral signed post                  │
    ├─ Contact verification -> offline QR of RSA public key     │
    ├─ Evidence CameraX -> private plaintext temp -> OCR -> AES │
    ├─ Incident log -> metadata + observed badge text          │
    └─ Network toggle -> Nearby P2P_CLUSTER                     │
                    │                                          │
         Envelope / signature / encryption                      │
                    │                                          │
         Bounded relay outbox ─── Bytes Payload ── Peers         │
                    │                                          │
    Receiver independently verifies packet signature            │
                    │                                          │
    [public bulletin -> unverified feed]                        │
    [SOS -> private decrypt -> verify known sender -> inbox]    │
                    │                                          │
         Local at-rest PBKDF2 + Android Keystore vault ◄────────┘
```

## Trust boundaries

1. Unknown nearby Android devices are **untrusted relays**. The connection itself is not proof of friendship. They may lie, record messages, be malicious, or disappear.
2. Public bulletins are meant to be public; a fresh RSA public key is created for each posted bulletin and used to sign it. The app displays all such reports as **UNVERIFIED**. Signatures detect modifications but do not authenticate the truth of reports.
3. Every device has its own persistent RSA-3072 identity pair, encrypted inside the personal vault. Its public key is transferred through offline contact QR, never through an account server.
4. Trusted contacts require **two-way pairing**, checked by physically comparing the printed 40-hex-character key fingerprint. Without reciprocal trust, recipient encryption might succeed but the receiving app will ignore a sender it does not recognize.
5. SOS packets have their own ephemeral outer signing identity. The true sending identity, signature proof and coordinates are inside AES-256-GCM encrypted content. AES session key is RSA-OAEP-SHA256 wrapped separately for each recipient. Recipient slot labels are anonymous indexes, not recipient fingerprints.
6. Signatures omit the hop count so relays may decrement it. This permits decremented forwarding but **does not authenticate hop history**. Invariants are validated independently.
7. Relay storage intentionally retains public bulletin plaintext (through base64) and opaque SOS packets. Timing, payload sizes and radio presence remain observable.

## Outgoing SOS

1. User selects trusted recipients and optionally location. App constructs an inner JSON body containing note/time/coordinates.
2. Sender signs body bytes with the persistent RSA private key; encrypts inner envelope (body, original sender public key, signature) with a freshly generated AES-256 key in GCM mode.
3. AES key is RSA-OAEP wrapped for each selected recipient; recipient indexes are generic `r0`..`r9`.
4. Fresh ephemeral RSA-3072 public/private pair signs the outer packet. Message has random UUID, creation/expiry (2 hours), hop limit 3 and fixed protocol version CR1.
5. App stores the outer encrypted packet in a capped outbox, and offers bytes payloads to connected peers.
6. A relay verifies the outer signature and expiry, deduplicates UUID, saves a copy with decremented hop count, and forwards while hops remain.
7. A trusted recipient tests key wraps for decryption, then validates inner persistent sender signature AND its presence in local verified contacts; otherwise the message is not displayed.
8. The receiving phone records `RECEIVED_ON_THIS_DEVICE` and creates a signed receipt encrypted for the original sender. If that return receipt reaches the sender and is verified as coming from one of the SOS recipients, the status changes to `RECEIVED_BY_TRUSTED_APP`. This is not a human read acknowledgment.

## Public updates

Each public update has a fresh ephemeral signature key. Maximum 280 characters. They expire in 60 minutes. All peers can read their cleartext, even when network links are encrypted. The app imposes a 20-second posting throttle locally, NOT a sophisticated network-level anti-spam system.

## Transport

The app uses Google's `play-services-nearby` with `P2P_CLUSTER`, advertising/discovery while user has explicitly enabled Mesh. It only sends Bytes payloads (<16KB) and requires Google Play services; P2P radio pathways vary by device and state. No automatic background service; when the app background-locks, advertising stops.

Delivery depends on density and movement of participating peers. This is a **best-effort store-and-forward mesh**, not universal connectivity or internet replacement. GPS itself does not need the internet in all conditions, but fix acquisition can fail or be delayed.

## Local storage / evidence

`vault.crv` = `"CRV2" | PBKDF2 salt (16 bytes) | Keystore AES-GCM encrypted [PBKDF2 AES-GCM encrypted JSON]`.

Evidence records use a fresh random AES file key for each new recording. Each file key is encrypted (wrapped) by the local media wrapping key stored inside the doubly encrypted vault. Existing legacy files still use their old key until re-encrypted. Media uses AES-GCM with fresh 12-byte IV and `CRV1` header. Files are in app-private internal storage; the vault records their SHA-256 digest and OCR suggestions. Capture APIs first write camera data into app-private temporary plaintext cache, then encrypt and delete it. This is not a secure-wipe guarantee, and recordings may be lost if interrupted before encryption. The manually initiated encrypted export/import flow makes a recipient-specific archive and verifies trusted sender, recipient, RSA signature, ciphertext hash, GCM authentication and plaintext SHA-256 on import. It is not a push service, automatic nearby file transfer, or guaranteed backup. See `EVIDENCE_AND_RECEIPTS.md`.

## Future protocol changes

- Replace ad-hoc RSA envelope construction with a standard independently audited HPKE implementation and protocol specification.
- Authenticated peer handshake UI, stronger explicit multi-hop delivery states, robust replay/TTL checks and flow control (signed *app receipt* envelopes are already prototyped).
- Chunked encrypted FILE-payload media replication (with quotas, user consent, receipt, integrity and opt-out).
- Rotate separate transport identities, add privacy-preserving anti-spam and abuse moderation.
- Accessible Hindi/English localization and auditable security design.
