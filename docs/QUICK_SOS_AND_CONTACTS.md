# CivicRelay experimental update — quick SOS, SMS contacts and relay priority

## Changes
- User-selected CivicRelay community logo is now the Android launcher image and appears on lock and top-bar screens. Supplied image preserved in `drawable-nodpi/civicrelay_full_logo.png`.
- A separate list of up to 10 SMS numbers/names is encrypted inside the existing vault. Does not require a CivicRelay account or QR key. The device SMS app is still responsible for transmission; **no automated SMS**.
- Connected peers replay **recent SOS, receipts and check-ins before public posts**, limited to 64 messages and one extra bounded replay. Failed sends have two short retries. This is neither a network guarantee nor end-to-end receipt; only verified signed receipts establish trusted-app storage.
- A Quick Settings tile and Nearby notification action can queue **one pre-armed signed, encrypted SOS packet** without the passphrase. From the unlocked SOS screen, choose **Arm emergency shortcut**, then add the CivicRelay SOS Quick Settings tile to Android. One tap consumes the pre-armed packet. It expires in **two hours** and has **no fresh GPS coordinates**. The vault remains locked; no decryption keys are made available to the tile. Requires at least one verified CivicRelay contact.
- Fingerprint check now displays a detailed Android status and links to phone biometric enrollment settings; manifest declares `USE_BIOMETRIC`. Works only where Android supports *strong* biometrics; weaker face or fingerprint devices cannot use the existing authenticated encryption key.

## Important platform limits
- Ordinary Android apps cannot reliably intercept repeated Power-button presses globally. Use the operating system's own emergency SOS settings for that gesture. The CivicRelay Quick Settings tile or persistent Nearby notification action are *additional*, explicitly armed convenience actions.
- A tile may require unlocking the screen or may be unavailable on a particular OEM/device. No guarantee it runs after reboot, process termination, radios disabled, battery restrictions, or deleted app data.
- SMS is carrier-readable, may reveal exact coordinates, and requires pressing **Send** in the system SMS app. A saved number is not automatically contacted when the encrypted SOS tile is used.
- Pre-armed packet is encrypted but its existence and metadata in internal app storage can be examined on a compromised device. Re-arm after use or expiry; changing trusted contacts clears the prepared alert.
- No Azure/cloud relay or distant live-location tracking is introduced by this patch. Queued traffic reaches other phones only when an actual Nearby route exists.

## Two-phone validation
1. Build: `.\gradlew.bat :app:assembleDebug` and `.\gradlew.bat :app:testDebugUnitTest` on JDK 17 / Gradle Wrapper 8.13.
2. Install to **both** Android phones using same original signing key: `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
3. Open More > Trusted people; save an example SMS contact and test selection on the SOS screen. Open the SMS draft, **don't send to a real number during testing**.
4. Verify Android shows the selected logo as launcher. Check More > Privacy for fingerprint status. If unsupported, test passphrase unlock instead.
5. Add verified CivicRelay contact via QR. On both devices enable Nearby using opposite roles and verify connected peers.
6. On Phone A create 40 synthetic public posts (subject to app rate limit), then queue a fictional protected SOS. Disconnect/reconnect. Newest SOS should be processed ahead of the bulletin backlog; do not treat a successful radio API call as proof of delivery.
7. Arm SOS on Phone A with vault unlocked, add Quick Settings tile. Lock CivicRelay (not necessarily device); tap tile. Unlock again and inspect that it reports queued, then check **signed receipt** if Phone B was present and unlocked. Second tap must not queue a duplicate packet. Test tile after expiry/disarming.
8. Test Android system Power-button emergency gesture **separately** in device Settings, without triggering a real emergency call.

## Security acceptance gates before real-world use
Independent code/security review; real-device radio, lock-screen and biometric compatibility testing; permission denial and OEM background shutdown tests; full replay/queue load test; artifact integrity and APK signing checks. Do not market as reliable emergency service.
