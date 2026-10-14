# CivicRelay: Nearby, Contacts, SMS and interface repair

Release: 0.1.4-alpha. Still **not ready for real emergency reliance**.

## What changed

1. Nearby Connections now has explicit roles rather than trying to advertise and discover at the same time. Phone A advertises; Phone B discovers. Traffic is bidirectional after connection. Google Play services API failures and how many endpoints were found are visible in the app, rather than silently stopping both radios. No guaranteed multihop routes.
2. Contacts has an on-screen, protected QR renderer for the local RSA public-contact key (420px bitmap) and retains Copy Public Code as a fallback. Scanning remains manual and requires camera permission. A rejected malformed code cannot be saved. The initial Contacts crash was reported without a stack trace; this is a defensive fix, not a proven root-cause resolution.
3. SMS location requests precise location permission when selected, attempts an on-demand GPS fix, then uses the last known location only when its elapsed time age is at most ten minutes. The draft identifies current versus potentially stale coordinates, adds a maps link, or explicitly states that location is unavailable. SMS remains **unencrypted and requires the user to press Send**.
4. A dark/light Compose Material 3 theme, a new unlock design, a four-tab bottom navigation bar, an emergency hero card, and dedicated More cards replace the wide scrolling tab strip. © 2026 Chirag Goyal. All rights reserved. remains in the app.

## Apply the patch to an existing Windows installation

1. **Keep your existing installed app and vault. Do not uninstall it**; uninstalling destroys local app data.
2. Download the changes-only ZIP and extract directly into `D:\CivicRelay-Android` with overwrite enabled.
3. Preserve locally generated `gradlew.bat`, `gradle/wrapper`, and `local.properties`.
4. Keep JDK 17 and project Gradle 8.13; global Gradle 9.6 is unrelated.
5. Run:

```powershell
cd D:\CivicRelay-Android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

If Android Studio uses Java 25 by default, set its Gradle JDK to Temurin 17 before building.

## Two-phone test matrix (synthetic data only)

| Test | Expected observable result |
|---|---|
| Both phones have Google Play services and Bluetooth/Wi-Fi/Location on | Both can request/hold permissions while app is open |
| Phone A More → Nearby → Make discoverable | Status `Discoverable to CivicRelay phones nearby` |
| Phone B More → Nearby → Find nearby phones | At least one endpoint Found, connection requested, peer count eventually 1 |
| Only one phone scanning | No connection: this is expected |
| Phone B sees Found but peers stay 0 | Inspect radio diagnostics for request/connection errors |
| Both phones connected, public test update | Update appears on the other phone while both unlocked |
| Phone A More → Contacts | QR and Copy public contact code available without a crash |
| Phone B QR scans A; then A scans B | Save each contact only after independently checking full fingerprint |
| Send private SOS to saved contact | Message queued; device receipt only on real reception |
| Send encrypted synthetic evidence after trusted pairing | Recipient approves file; signed storage receipt only after import succeeds |
| SMS fallback with precise location permission | Opens SMS draft with coords and map link, or unavailability explanation |
| Turn location off | Draft must not falsely claim a fresh fix |
| Stop Nearby / background app | Mesh stops; on reopening app, vault requires unlock |

## Troubleshooting

* Both phones should have the **same APK build**, remain within a few meters, and have Google's Play services enabled. Both must grant requested Nearby Devices and precise Location permissions for this experimental radio-scanning build. Nearby scanning does not itself share SOS GPS coordinates. Turn on location *services* in phone settings before starting, even though Internet/mobile data can be disabled. Nearby Connect does not work across arbitrary cities without relays.
* First run Phone A advertising; then start Phone B discovery. Some OEM devices restrict scanning or Google Play services radios, so check the displayed error code rather than assuming the app is connected.
* If QR crashes again, obtain Android Logcat. One useful command (run just after reproducing the crash):

```powershell
adb logcat -d -v time > D:\CivicRelay-Android\civic-debug-log.txt
```

Review/redact the log before sharing: it may contain sensitive device identifiers and content. Search for `FATAL EXCEPTION`, `AndroidRuntime`, and `org.civicrelay.app`.
* If SMS lacks coordinates, grant precise location, turn on Location services, and test outdoors. Nearby BLE permissions are separate from high-accuracy location permission.
* If `:app:compileDebugKotlin` errors arise, send the **first** failing Kotlin error lines. The project cannot be fully compiled in the current preparation environment.

## Security limitations

The relay doesn't authenticate anonymous public updates as trustworthy facts. Signing detects packet modification but does not verify real-world claims. Direct file copies remain recipient-encrypted, but no recording is undeletable or immune to phone compromise. Bluetooth/wireless activity can reveal the presence of devices. SMS is visible to the telecom carrier. Do not use real names or sensitive footage in the pilot.
