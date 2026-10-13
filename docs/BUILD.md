# Build, setup, and safety guide

Native Android client for [herdr-bridge](https://github.com/RazvanManolache/herdr-bridge)
on Windows, over SSH or paired TLS. Kotlin, Jetpack Compose, and Material 3.
No vendor account is required. [Pairing setup](PAIRING.md).

Package: `io.github.razvanmanolache.herdronthego` · development version **0.2.0** (code 4) · Android 8.0+
(minSdk 26; compile/target SDK 35).

## Features

- Saved SSH connections and up to four connected computers, with separate panes,
  terminal history, and message drafts for each computer and Herdr session.
- Session selection, a global tab strip and searchable **All tabs** picker across
  computers and spaces, terminal search, selectable text,
  font size (10–24 sp), themes, and commands extracted from terminal output.
- Automatic refresh: visible terminal output about every two seconds; snapshots
  and agent status about every 15 seconds while the process is running.
- Text-file insertion into the current draft, with bounded UTF-8 decoding on a
  background worker. Attachments are message text; there is no binary upload.
- Agent completion notifications, including the computer, named session, and
  exact pane to reopen. Notifications require Android permission.
- SSH host-key verification before authentication, encrypted password storage,
  and durable tracking of commands whose replies are lost.

## Requirements

On the Windows computer:

1. Herdr and the Windows SSH bridge installed and working. Follow the
   [bridge installation instructions](https://github.com/RazvanManolache/herdr-bridge#readme),
   including its SSH shell configuration. Discovery invokes POSIX-style commands
   through that shell.
2. A running Herdr session and at least one pane to control.
3. An SSH account with password authentication and permission to run the bridge.
   The app currently supports passwords, rather than imported SSH private keys.
4. Network access from the Android device to the computer's SSH port. A physical
   phone uses the computer's reachable LAN/VPN address; an Android emulator can
   use `10.0.2.2` to reach the host loopback interface.

To build:

- JDK **17**.
- Android SDK platform **35**, build tools **34.0.0**, and platform-tools (`adb`).
- The checked-in Gradle wrapper pins **8.11.1** and verifies its distribution checksum.
- Internet access for the first dependency download from Google Maven and
  Maven Central. AGP 8.5.2 and Kotlin 2.0.21 are pinned in `build.gradle`.

## Build and install

From this directory in PowerShell, replacing the SDK/JDK paths as necessary:

```powershell
$env:JAVA_HOME = 'C:\SDK\jdk-17.0.17.10-hotspot'
$env:ANDROID_HOME = 'C:\SDK\Android'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat assembleDebug
adb devices
adb -s YOUR_DEVICE_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. It is signed with the
local development key. Use the same signing key for subsequent updates; a
signature mismatch requires signing with the original key or uninstalling the
old app, which removes its local data. The public APK uses a dedicated release
key; debug builds and release builds have different signatures.

## Private release signing

The signing key and passwords must stay outside the checkout. The publisher's
private signing identity is not part of the source archive or APK. To create
your own development release identity on Windows:

```powershell
python tools/prepare_release_key.py
$env:HERDR_SIGNING_PROPERTIES = Join-Path $env:USERPROFILE '.android\signing\herdr-on-the-go\release.properties'
.\gradlew.bat assembleRelease
```

Restrict access to that private directory and back it up securely. Losing the
key prevents updates signed as the same publisher. The script reuses an existing
identity and never overwrites it. For your own setup, `HERDR_SIGNING_PROPERTIES`
can point to any private properties file with `storeFile`, `storePassword`,
`keyAlias`, and `keyPassword`. Use forward slashes in the keystore path.

With signing configured, the output is `app/build/outputs/apk/release/app-release.apk`.
Without private signing configuration, the release task produces an unsigned
APK suitable for build checks, not installation. This intentionally keeps CI
independent of release secrets.

The publisher's v0.1.1 signing certificate SHA-256 is:

```text
6fba9e3dcfcacc28bf2631a62225ca8ab709f5079654f0d0dc6d0ef140d51536
```

Verify an APK using the Android build tools' `apksigner verify --print-certs`.
Your own generated key will have a different fingerprint.

AGP 8.5.2 warns that SDK 35 exceeds its tested SDK 34 maximum. The documented
build and lint checks succeed with this toolchain; dependency/toolchain upgrades
are separate work.

## Connect

1. Add a connection with the host, SSH port, username, and password.
2. Choose whether to save the password. Saving uses the Android keystore and
   EncryptedSharedPreferences. If secure storage cannot open, the app permits
   connecting without saving and displays the storage problem.
3. Compare the displayed `SHA256:` host fingerprint with the intended SSH
   server before accepting it. A changed key is rejected. Version 0.1.1 uses new
   raw-key pins; old nonstandard fingerprint records require verification again.
4. Select a reachable Herdr session and connect. The selected session is saved.
5. Choose a pane and send a message. Sending also presses Enter in that terminal.
   Text files are inserted into a draft first, so they can be reviewed before sending.

Forgetting a connection erases its saved password, drafts, summary, and connection
row. If secure deletion fails, the app reports the problem and keeps the row.
Previously stored plaintext fallback passwords are erased on startup; they are
never reused. A cleanup failure disables password saving until storage is repaired.
Passwords are excluded from Android backup and device transfer.

## Interrupted commands

A missing reply does not prove that a command failed. Before sending a mutation,
the app synchronizes a reservation containing its destination, content hash, and
idempotency key to its private, non-backup journal. It does not store command text
or passwords in that journal.

If a reply is lost, the draft remains and the action is shown as **outcome unknown**
or **partially completed**. Reconnecting and sending identical text to the same
computer/session/pane reuses the original key. Edited text is blocked while that
action is unresolved. Reservations survive app restarts; drafts and session-only
passwords remain in memory and survive rotation, but are lost with the process.

Use **Review unresolved actions** to inspect outstanding actions. Check the target
terminal before marking an action inspected: doing so permits a new action to
execute. Automatic retry is limited to 23 hours, before the bridge's 24-hour
completed-result retention boundary. Older reservations require inspection.
Unresolved actions are retained even when a connection is forgotten.

Limits: 12,000 UTF-16 code units per message including Enter (a conservative limit
for non-BMP characters), 2,000 history lines, 200,000 cached characters per pane,
48,000 attachment bytes before decoding, 256 in-memory drafts, and 128 unresolved
actions. Timeline subscriptions request only the visible pane. Polls are coalesced;
snapshot responses do not accumulate in a waiting queue.

Rotation retains the controller and its connections. Finishing the activity closes
all transports. Background polling is best-effort while the process remains alive;
the app has no foreground service and cannot guarantee notifications under Android
background restrictions or after process termination.

## Tests

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python -m pip install paramiko
python tests\ssh_security_probe.py
```

The SSH probe runs the production compiled transport against ephemeral loopback
servers using synthetic credentials. It verifies zero password attempts for
changed/rejected keys, standard fingerprints, accepted-key pinning, and known-key reuse.

Android tests use a custom runner and isolated preference/file names, including
the MainActivity rotation test, so tests never open saved user credentials.
To run the local Android tests on one explicit emulator (the live test is skipped
without fixture arguments):

```powershell
adb -s emulator-5556 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w io.github.razvanmanolache.herdronthego.test/io.github.razvanmanolache.herdronthego.IsolatedTestRunner
```

For the complete Windows integration check, keep `herdr-bridge` as a sibling
directory, install its Windows bridge first, and start an emulator:

```powershell
python tests\windows_bridge_check.py --serial emulator-5556
```

This installs only onto the specified emulator, creates and stops a disposable
Herdr session, and provides synthetic SSH authentication in front of the installed
Windows bridge. It tests delayed output, message send/readback, a dropped reply
followed by the same-key retry after controller restart, named-session notification
reconnect, history limits, failed connection cleanup, and closing every transport.
It does not certify a real Windows SSH account or a physical phone.

Reports: `app/build/test-results/testDebugUnitTest`, `app/build/reports/lint-results-debug.html`,
`app/build/security-probe/results.json`, and `app/build/windows-bridge-check`.
See `REVIEW.md` for the original findings and their resolution.
