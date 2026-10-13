# Pair without a Windows login

Herdr on the Go 0.2.0 adds **paired TLS** alongside SSH. Approval stays inside a
dedicated **Pair devices** tab in Herdr. It never types a code into an agent's
prompt and never asks for a Windows account password.

1. Install the updated optional paired bridge on the PC, following
   `docs/PAIRING.md` included in the Windows bridge package. Its default
   network port is 7443; the PC must permit inbound access from the phone.
2. Inside Herdr, run:

   ```powershell
   & "$env:USERPROFILE\.herdr-bridge-emu\pair-devices.cmd" --open-tab --advertise YOUR_PC_IP
   ```

3. On Android, tap **Pair without Windows login**, scan the QR code from that
   PC tab, name the phone, and tap **Request pairing**. Pasting the complete
   pairing address also works if a camera is unavailable.
4. Herdr shows a device-request notification and a notice in the pairing tab.
   Approve the request there and enter the six digits displayed on the
   phone. The PC reads the digits without echoing them into terminal history.
5. Select a running Herdr session. Future connections use this phone's stored
   device credential; you can revoke it using `devices` and `revoke DEVICE_ID`
   in the same PC tab.

Only scan the QR/address displayed by your own PC. It carries the full server
certificate fingerprint; the app checks that certificate before transmitting
the random device secret. The short phone code approves the device after that
secure connection has been established.

Pairing is open for five minutes, requests expire after three minutes, and five
wrong code attempts lock a request. Unpaired devices cannot see sessions or
terminal content. Android stores approved-device credentials in encrypted
preferences excluded from backups. Forget removes the phone's local credential;
revoke it on the PC as well to block access there.

The same four-connection limit and one attached named session per connection
apply across SSH and paired connections. Existing SSH connections and saved
passwords remain supported. Bluetooth and internet relays are not involved;
the phone must be able to reach the chosen PC port over LAN or VPN.

## Verification

`tests/paired_bridge_check.py --serial emulator-5562` uses a fresh explicit
emulator and a disposable real Windows Herdr session. It verifies wrong-pin
rejection before pairing, the phone-code flow, state across rotation, encrypted
credential storage, actual command/readback, active revocation, and legacy SSH
connection migration. Approval is automated only for this isolated synthetic
test store. The production listener exposes no remote approval operation.

The bridge's `tests/pairing_console_check.py` independently verifies creation
of the real Herdr Pair devices tab, a device-request notice, hidden code input,
absence of the code/token from terminal history, and console revocation.

Physical-phone camera scanning and the work PC's actual inbound-port rules have
not been exercised. This is a local development build; the published GitHub
APK remains 0.1.1 until a new release is published.
