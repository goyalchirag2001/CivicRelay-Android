# Online sync, public posting and safety map — implementation contract

Status: **planning and policy layer only**. No cloud endpoints are deployed by this source batch. The Android app still has **no INTERNET permission**, cloud login, online sync client or real background location upload. Do not claim online check-ins are currently delivered to remote recipients.

## Features in this Android patch

- `More` protects the biometric hardware query against null/throwing OEM APIs and renders a simpler Privacy/Identity/Tools page. This is a likely crash fix only; confirm using a real Logcat stack trace if it recurs.
- `Evidence`: Library / Camera / Share; storage and crypto behavior unchanged. Incoming transfers and receipt history under Share; verified viewer under Library.
- `Community`: optional display name, role, broad area saved in encrypted vault. Each **public** post carries these self-asserted details and a separate persistent **posting public key** with an author signature. This does **not** establish legal identity. Public posting keys can link posts, so the user should be warned before enabling a profile. Contact/SOS RSA key is not reused as the posting identity.
- `Home`: explicit, one-time, encrypted location check-in for saved trusted contacts. This is **not** automatic tracking, and will only reach recipients through a working nearby relay. SOS remains separate.
- `More > Detention reports`: local encrypted notes only; a public map is **not** populated from private reports.
- `DetentionMapPolicy`: future backend gate; no public hotspot with fewer than five distinct signing keys, younger than 30 minutes, older than 6 hours, or without moderation. Keys are not proof of real independent humans; additional abuse protection is essential.

## Proposed Azure online subsystem (not deployed)

### Data flows and scopes

1. **Offline path**: keep verified nearby public updates and recipient-encrypted SOS/check-ins in bounded relay queue (TTL). Do not emit exact location to public content.
2. **Online upload**: with internet access and explicit opt-in, upload only encrypted envelopes (no plaintext locations, names, contacts or vault keys) to a minimal HTTPS gateway. Verify envelope signatures and enforce rate/size limits. Encrypt payload for each trusted recipient on device.
3. **Online pickup**: recipients poll or use push wake-up, download ciphertext addressed to their encrypted mailbox, verify/decrypt locally, issue receipt. An untrusted relay should never claim it can read or verify a recipient's decrypted payload.
4. **Auto reconnect**: schedule an immediate constrained job for network restoration, with periodic fallback via WorkManager. Periodic work is *inexact* and has a 15-minute minimum; it isn't guaranteed to run the instant the phone comes online. Notification or explicit foreground service where legally/platform permitted for higher timeliness. Never claim guaranteed real-time updates.
5. **Location sharing**: per-recipient consent, visible on/off switch, strong foreground indication, selectable duration (15 min/1 hr), optional update frequency, location precision, pause/stop and automatic expiry. NO remote or central public list of individuals and no silent enrollment. Lost/locked-device threat: a recipient can store decrypted location; E2EE is not a guarantee against a compromised endpoint.
6. **Community posts**: public signed metadata may be searchable online only if poster opts in. Real-person verification is *separate* from cryptographic key signatures. Independent volunteer review/organization attestations with revocable badges, clear badge issuer and date, appeals/reporting and anti-impersonation design; never call an unverified real-world name 'verified'.
7. **Aggregate incident map**: broad delayed cells only after independent corroboration, moderation and abuse controls; k=5 is an initial privacy threshold, not a proof. Suppress low counts, exact roads, people, identifying media and fine time series. Never publish private check-ins or incident details automatically. Explain uncertainty and allow removal/correction. Do not create a live public police/protester tracking map.

### Suggested Azure components

- **Azure Functions (Consumption / managed API)**: narrow HTTPS upload, pickup, receipts and moderation endpoints. Requests authenticated with signature challenges. No phone number needed to use private relay; avoid IP storage where possible and bound operational logging.
- **Azure Storage (Blob/Table) or Cosmos DB free-tier option**: short-lived encrypted envelopes, public opted-in posts and coarse approved aggregates. Use TTL cleanup; encrypt client-side. Cosmos free tier (when available and enabled) includes 1,000 RU/s and 25 GB, with overages chargeable; no cost guarantee.
- **Azure Maps**: optional coarse map rendering. Avoid giving precise user locations to third-party map APIs. A map can also be rendered with self-hosted tile data or approved sources, with costs and licensing reviewed.
- **Security**: separate resource group from CampusERP; Managed Identity; no storage connection strings in Android; private blob container, public access disabled, RBAC least privilege, HTTPS; Key Vault for infrastructure secrets (not user E2EE keys); firewall rules, cost alerts, rate and request caps. Azure budget alerts are not hard spending caps. Never use GitHub Actions logs to print tokens or private data.
- **Operating limit**: initial encrypted envelope size <= 8KiB; TTL 2 hours for SOS/check-in; public bulletin TTL 1–6 hours; media never automatically uploaded in the first online phase. Administrative actions must be audited without storing sensitive plaintext.

### Pending API schema sketch

- `POST /v1/envelopes` {packet_id, recipient_mailbox_ids, expires_at, opaque_ciphertext, sender_proof}; max 8KiB; rate-limited; no GPS plaintext.
- `GET /v1/inbox?cursor=...`: authenticated mailbox retrieval; returns opaque ciphertext and cursor.
- `POST /v1/receipts`: recipient-signed, packet-scoped receipt and timestamp.
- `POST /v1/community/posts`: explicit *public* opted-in post with signed profile key; moderation status separate from identity verification status.
- `GET /v1/map/areas`: aggregate-only, delayed, moderated, privacy threshold; no individual locations.

No address, keys, hostnames, cloud resources or charges should be created before separate threat-model, capacity and Azure-cost review.

## Practical sequence

1. Confirm this patch builds, More no longer crashes, Evidence tabs work, existing vault decrypts, and 2-phone community posts still verify.
2. Implement a private ciphertext gateway with consent and an independent privacy review; test reconnect and receipt behavior in a lab using fictional locations.
3. Add opt-in, bounded, time-expiring location sessions with explicit notifications and stop control. Validate foreground/background limits on Android 11–16.
4. Add identity-review workflows and public moderation. Launch a delayed, aggregated map only after abuse/safety review; do not launch live identifiable detention locations.

## Validation needed

- Native `:app:assembleDebug`, `:app:testDebugUnitTest`, and real phone Contacts/More/Camera/Share check; no build performed by this environment.
- Verify Android 13+ foreground service and notification behaviors and battery restrictions.
- Protocol compatibility: older app versions reject `checkin` packets; update both devices.
- Check memory and battery impact of the larger signed public bulletin (~2 KiB), SQLite/file quotas, and congestion. Validate public profile unlinkability expectations and disclosure risks.
