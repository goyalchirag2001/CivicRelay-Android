# SMS contacts and Quick Settings SOS — private beta test

CivicRelay 0.1.8-alpha, © 2026 Chirag Goyal. All rights reserved.

## Important difference

**SMS contacts** are phone numbers saved in the encrypted vault. Drafting an SMS opens the system messaging app; **you must press Send yourself**. SMS needs carrier service, is not E2EE, and there is no automatic background SMS from this release.

**CivicRelay trusted contacts** are cryptographic keys exchanged with the QR workflow. **Quick SOS** queues a pre-encrypted **app-to-app** message addressed to these keys, **not** to SMS phone numbers. Quick SOS does not include current GPS and is one-time, expiring after two hours. Queued does not mean delivered.

## Test A: SMS contacts

1. Unlock → More → Parents & SMS contacts.
2. Enter a test name and mobile number with country code (+91 for India), confirm consent, tap Save phone number. The contact should appear in the saved list. If invalid/duplicate, error must be visible and fields retained.
3. Close/reopen and unlock. Check persistence; remove a test number and check persistence again.
4. Tap Draft SMS; SOS should open with the number filled in.
5. Tap Open SMS with/without location. Confirm the system SMS draft, then **do not press Send** during the synthetic test. For location: permission may be needed and GPS unavailable indoors.

## Test B: Quick SOS

1. Install on **two** phones; each must have a vault. Add the receiving phone as a verified CivicRelay QR contact on the sending phone. For receiving receipts, both phones must have paired contact keys.
2. Unlock sender → SOS → Arm emergency shortcut. Confirm READY and expiration time.
3. Tap Add SOS shortcut to Quick Settings (Android 13+). On older systems swipe down twice → Edit/Pencil → drag CivicRelay SOS tile. A phone may disallow tile actions on its lock screen.
4. Start Nearby, keep the two phones connected, arm the alert, then use the Quick Settings tile from the sender. The tile should show SOS queued and become inactive because the prepared alert is one-time.
5. Unlock again; inspect SOS activity, and check the second phone after unlocking. Receipt is not guaranteed. Repeat with radio disabled to verify queued != delivered.
6. Re-arm before every subsequent test and within two hours.

## Known limitations

- Android does not expose an ordinary-app global repeated Power-button shortcut. Use the device's native Emergency SOS for immediate system assistance.
- The Quick Settings tile may be unavailable from the locked screen depending on Android/OEM policy; do not depend on it without testing that exact device.
- Nearby must have peers/a route. There is no automatic internet gateway yet.
- No background SMS dispatch, no live Quick SOS GPS, no emergency dispatch service, no delivery guarantees.
- The app has not undergone an independent security audit. Use fictional details only.
