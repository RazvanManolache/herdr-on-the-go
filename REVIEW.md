# Android app review

Reviewed and fixed 2026-10-13. Scope: Herdr on the Go, including its interaction
with the current Windows `herdr-bridge`. **All 15 findings below are addressed in
version 0.1.1.** The original findings and original verification are preserved
below as historical evidence; their line numbers refer to the pre-fix source.

## Resolution and current verification

| Finding | Resolution |
| --- | --- |
| R01 | Strict host-key repository verifies during SSH key exchange, before authentication. |
| R02 | Encrypted storage only; explicit session-only option; legacy plaintext erased. Storage/cleanup failure disables saving. |
| R03 | Drafts and sends carry a captured computer/session/pane target. Successful sends clear only the unchanged original draft. |
| R04 | Durable, synchronized mutation reservations preserve the original key after lost/partial replies and restarts. Changed/expired retries are blocked pending inspection. |
| R05 | Standard SHA-256 fingerprint hashes raw SSH key bytes. New `hk2:` pins require old installations to verify keys again. |
| R06 | Coalesced background `raw.read` polling updates the visible terminal every two seconds, with 15-second snapshots. |
| R07 | Waiters are registered before writes; intermediate acknowledgments cannot complete mutations; disconnect fails pending waiters. |
| R08 | Text, history, subscription, frame, cache, and attachment limits are bounded; history stops at 2,000 lines. |
| R09 | ViewModel retains the controller across recreation. Final cleanup closes all transports before cancelling workers. |
| R10 | Snapshots replace current state; only bounded, one-shot handshake futures remain. No periodic snapshot queue. |
| R11 | Attachment reads and strict UTF-8 decoding run off the main thread and stop after the limit plus one byte. Picker destination survives rotation. |
| R12 | Both credential stores are excluded from legacy backup, cloud backup, and device transfer. Mutation journal uses `noBackupFilesDir`. |
| R13 | Forget synchronously erases the encrypted password before removing connection metadata and drafts; deletion failures are reported. |
| R14 | Failed discovery/connect/attach paths disconnect sockets and remove their entries. Repeated authentication failures consume no slots. |
| R15 | Selected named session is persisted; notification identity includes session; notification reconnect attaches automatically to the original session and pane. |

Current checks:

- Debug, Android test, and signed release APKs assemble successfully.
- **24 unit tests pass**: request routing, mutation journal restart/scope/expiry,
  bounded attachment decoding, message limits, and host-key decisions.
- **Four compiled-transport SSH scenarios pass** against ephemeral loopback
  servers. Changed and rejected-unknown keys produce **zero password attempts**;
  accepted/known keys authenticate once. Fingerprints match standard SSH SHA-256.
- **Eight Android instrumentation tests pass** on emulator-5556 (Android API 35),
  using isolated preferences and files. Coverage includes encrypted credentials,
  secure-store and legacy-cleanup failures, forgetting, scoped drafts, durable
  pending actions, actual MainActivity recreation and retained host-key decision,
  and Windows bridge integration.
- The integration test uses synthetic SSH authentication in front of the
  **installed Windows bridge and a real disposable named Herdr session**. It
  verifies send/readback, delayed output without manual refresh, the 2,000-line
  limit, a deliberately lost completed-command reply, retry after controller
  restart with the **same key and one execution**, automatic named-session
  notification reconnect, five failed connection attempts without leaked slots,
  and shutdown of multiple SSH transports. Its temporary Herdr server is stopped.
- Lint: **0 errors, 8 warnings** (target SDK/dependency update advisories). The
  existing AGP tested-SDK warning remains; toolchain upgrades are outside these
  findings.
- Rendered SSH form inspected from the emulator's instrumentation screenshot.
- The renamed release APK's signature, application ID, display name, bundled
  notices, and absence of test fixtures were checked. Its first launch succeeded
  on the emulator. Seven public screenshots were captured from isolated fictional
  state and visually inspected. See [release verification](docs/releases/v0.1.1.md).

Evidence and rerunnable tests:

- `app/src/test/java/io/github/razvanmanolache/herdronthego/SafetyTest.kt`
- `app/src/androidTest/java/io/github/razvanmanolache/herdronthego/ControllerTest.kt`
- `tests/ssh_security_probe.py` → `app/build/security-probe/results.json`
- `tests/windows_bridge_check.py` → `app/build/windows-bridge-check/results.json`
  and `instrumentation.txt`
- `app/build/reports/lint-results-debug.html`
- `app/build/review/before-fixes-20261013-021115.zip` (pre-fix source backup)

Validation boundaries: tests installed onto the explicit emulator only. No
physical phone was changed. The Windows SSH service/account authentication path,
real-device background behavior, Play Store distribution, and OS process-death
execution recovery were not exercised. Journal recovery was tested across
controller restart with the same private files; unit tests reopen the durable
file independently. Background delivery remains best-effort without a foreground
service, as documented in the README.

## Original review

P1 means a security or command-safety issue that should be fixed before relying
on the app. P2 means a correctness, reliability, privacy, or compatibility issue.
Findings marked **source trace** follow the actual code path but were not
reproduced through the Android UI.

### Original verification

- Debug APK assembly completed successfully using the existing local toolchain.
- Android lint completed: **0 errors, 6 warnings**, plus informational findings.
  Its report is `app/build/reports/lint-results-debug.html`.
- `testDebugUnitTest` returned **NO-SOURCE**. There are no application unit tests.
- A loopback-only SSH test executed the app's compiled `SshBridge` class with
  JSch 0.2.21 against a temporary Paramiko server. It used an ephemeral host key
  and a synthetic password, and did not access saved app credentials.
- Both changed-key and rejected-unknown-key scenarios delivered the synthetic
  password to the test server before the app rejected the key.
- The same test confirmed the app's displayed fingerprint differs from the
  standard SHA-256 SSH fingerprint for the identical public key.
- Test harness and results: `app/build/review/probe_host_key.py`,
  `HostKeyProbe.java`, `Base64.java`, and `host-key-results.json`. The JVM-only
  Base64 adapter delegates to Java's standard encoder; SSH transport and key
  checking use the app's actual compiled class.

The initial offline lint attempt lacked a cached lint dependency; retrying with
dependency download enabled succeeded. Conflicting SDK environment variables
were aligned only in the build process. No project configuration was changed.
AGP 8.5.2 also warns that its tested compile SDK maximum is 34, while the app uses
35. This warning did not prevent assembly or lint.

## Findings, in priority order

### R01 — P1: Password authentication happens before host-key acceptance

**Reproduced with the compiled app transport.**

`SshBridge.kt:57-74` sets `StrictHostKeyChecking=no`, calls `Session.connect`,
and only then compares/prompts for the host key. A counterfeit server that
accepts password authentication receives the password before the unknown-key
prompt or changed-key rejection. Pressing REJECT cannot undo that disclosure.

JSch performs authentication inside `connect`; its host-key checking hook runs
before authentication, which is where this decision must happen. See the
[exact JSch 0.2.21 implementation](https://raw.githubusercontent.com/mwiede/jsch/jsch-0.2.21/src/main/java/com/jcraft/jsch/Session.java).

**Fix:** use a proper `HostKeyRepository` and strict checking, with explicit
first-use acceptance during key exchange. Reject changed or missing keys before
any authentication. Test that a rejecting verifier results in zero password
authentication calls on the server.

### R02 — P1: Credential encryption silently falls back to plaintext

**Source trace:** `App.kt:98-108`, `258-271`.

Any exception creating the encrypted preference store selects
`herdr_on_the_go_secret_plain`. Subsequent successful connections save SSH passwords
there without warning. A keystore/storage failure therefore removes the very
protection the UI and README imply. `MODE_PRIVATE` restricts normal app access;
it does not encrypt the credential file.

**Fix:** fail closed for persistent credential storage. Offer an explicit
session-only connection if secure storage is unavailable; do not save a
plaintext substitute. Handle old plaintext data with a deliberate migration or
deletion flow.

### R03 — P1: Drafts carry across computers and panes

**Source trace:** `Screens.kt:377-386`, `478-482`, `867-874`, `695-700`;
`App.kt:618-627`.

The message draft is a single unkeyed `remember` value in `AgentScreen`.
Switching between two live computers keeps the same screen/composition and
changes the active connection, while retaining the old draft. Sending then
targets the new computer and pane. A command or private prompt prepared for A
can be sent to B. Switching tabs also carries the same draft into another pane.

**Fix:** key draft state by connection, session, and pane. Capture that complete
destination with the send action; do not resolve a mutable active destination
later. Preserve each pane's draft independently.

### R04 — P1: An uncertain command is reported as failed and its retry key is lost

**Source trace:** `App.kt:598-639`; `Screens.kt:695-700`.

`sendAndAwaitVerdict` collapses timeout and a definite negative verdict to
`false`. The UI reports "Send failed by server", clears the draft immediately,
and discards the generated idempotency key. If the server executed the command
but its reply was lost, manually resending creates a new key and can execute it
again. The hardened bridge cannot deduplicate two different keys.

**Fix:** retain the destination, text, key, and pending outcome until resolved.
Distinguish success, definite rejection, partial completion, and unknown outcome;
surface the server's reason. Preserve the draft on definite failure and reuse
the original key for a retry of the same operation. Never advertise an uncertain
operation as safe to repeat.

### R05 — P2: Displayed host fingerprints use the wrong input bytes

**Reproduced with the compiled app transport.**

`SshBridge.kt:25-29` hashes the UTF-8 bytes of `HostKey.getKey()`. JSch returns
the public key as Base64 text, so the app hashes its textual encoding instead
of the decoded key blob. The displayed `SHA256:` value cannot be matched against
`ssh-keygen -lf`, including the fingerprint check documented for the bridge.
See [JSch's HostKey implementation](https://raw.githubusercontent.com/mwiede/jsch/jsch-0.2.21/src/main/java/com/jcraft/jsch/HostKey.java).

**Fix:** hash decoded key bytes or use JSch's standard SHA-256 fingerprint
support. Migrate existing trust records carefully: changing the calculation
must not automatically trust a key or bypass the verification in R01.

### R06 — P2: "Live" terminal output stops updating after the initial subscription

**Source trace across both projects:** `App.kt:618-639`, `701-719`, `835-851`;
`herdr-bridge/bridge/stdio-responder.py:258-286`.

The current Windows bridge produces a finite set of terminal events for each
`timeline.subscribe`; it does not run an ongoing output publisher. The app's
periodic watcher requests only `snapshot.get`, whose metadata response does not
update terminal text. Sending a message also requests no new output. Terminal
content therefore stays at its earlier read until a tab switch, manual read,
or another subscription, despite the "Live" label.

**Fix:** agree on a continuous subscription contract, or explicitly poll bounded
reads for relevant panes. Refresh after sends and track revisions; avoid loading
every pane on every tick. Test output arriving while the user stays on one pane.

### R07 — P2: Reply waiters are registered after requests are sent

**Source trace:** `SshBridge.kt:187-191`, `220-238`; `App.kt:551-552`, `575-576`.

`send` writes and flushes before `awaitResult` installs its queue. A quick reply
can arrive in between and go to the general frame handler instead. That handler
does not consume `ack` or `raw.read.result`, so the expected response is lost.
Subscriptions can wait 15 seconds unnecessarily and reads can falsely time out
after 30 seconds. The mutation path already demonstrates the correct ordering
by installing its waiter before `sendRaw`.

**Fix:** register before writing, and route replies by expected frame type as
well as request ID. Complete outstanding waiters on disconnect. Add a test where
the reply arrives immediately during the send operation.

### R08 — P2: Client requests exceed the hardened bridge's limits

**Source trace:** `App.kt:538-553`, `567-592`, `624-627`, `671-683`;
`Screens.kt:737-744`.

"Load earlier" grows `currentLines + 1000` without a ceiling, eventually
exceeding the bridge's 2,000-line maximum. Its failure reply is treated as an
empty text result, hiding the reason. Subscriptions include every pane without
respecting the 64-pane maximum. Attachments allow 100 KB even though text sends
are limited to 12,000 characters, and typed messages have no matching limit.
Rejected sends also lose their draft, as described in R04.

**Fix:** align client limits with the bridge, reserve room for the appended
newline, page/chunk pane subscriptions when appropriate, and show protocol
errors instead of silently ignoring them. Make attachment limits consistent
with the actual send capacity.

### R09 — P2: Destroying the activity does not reliably close all connections

**Source trace:** `MainActivity.kt:37-40`; `App.kt:450-477`, `854-856`.

`onDestroy` queues `disconnect()` on the worker and immediately calls
`shutdownNow()`. The queued cleanup may be discarded before it runs. Even if it
runs, it closes only the active server; other live servers remain open. Reader
callbacks retain the old controller. Rotation/activity recreation can leave
obsolete SSH connections alive while the new activity opens more connections.

**Fix:** provide deterministic close-all cleanup and cancel/join readers before
disposing the controller. Use an appropriate retained owner if connections are
intended to survive activity recreation. Do not enqueue cleanup onto an executor
that is immediately stopped.

### R10 — P2: Periodic snapshots accumulate in an unbounded queue

**Source trace:** `App.kt:81`, `416-418`, `701-707`, `835-851`.

Every periodic snapshot is offered to `snapshotQueue`, but the queue is consumed
only once during attachment. Normal connected use therefore retains all later
snapshots: approximately 240 per hour per attached computer. Pane text is bounded,
but this queue is not, so long sessions can exhaust memory.

**Fix:** use a one-shot attach waiter or conflated latest-value delivery and stop
queueing snapshots after attachment. Remove closed-pane cached data as snapshots
change, and verify stable memory during sustained use.

### R11 — P2: The attachment size limit is checked after an unbounded main-thread read

**Source trace:** `Screens.kt:557-563`; `App.kt:671-683`.

The picker callback calls `readAttachText` synchronously. `readBytes()` allocates
the whole document before checking the 100 KB limit. Selecting a large file or
a slow content provider can freeze or exhaust the app before it shows "too
large". The picker accepts all file types despite the text-only description.

**Fix:** read on an I/O worker with a strict byte limit, stopping at limit + 1,
and validate the supported content type/encoding. Apply the smaller send limit
from R08 rather than allocating an arbitrary file first.

### R12 — P2: Password preference files are eligible for Android backup

**Source trace:** `AndroidManifest.xml:8-12`; `App.kt:98-108`.

Backup is explicitly enabled and there are no backup-exclusion resources. The
encrypted preferences can be restored without their device keystore key and
become unreadable; the plaintext fallback from R02 is also eligible for backup.
Android documents both the default inclusion of shared preferences and the need
to exclude encrypted preference files. See
[Auto Backup](https://developer.android.com/identity/data/autobackup) and
[EncryptedSharedPreferences](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences).

**Fix:** explicitly exclude credential files from both older backup rules and
Android 12+ data-extraction rules, including device transfer. Remove the
plaintext fallback and handle existing restored/corrupt stores without silently
changing their security properties.

### R13 — P2: Forgetting a connection leaves its saved password behind

**Source trace:** `App.kt:258-271`, `275-302`.

`forgetConnection` removes the connection rows and live server but never deletes
the matching `pw:user@host:port` entry. Re-adding the same endpoint can recover
the supposedly forgotten password, and it remains in whichever credential
store was used.

**Fix:** remove the credential with the connection and clean obsolete plaintext
entries if they exist. Verify that forget/re-add requires fresh credentials.

### R14 — P2: Failed connections consume live-server slots without cleanup

**Source trace:** `App.kt:323-347`, `204`.

The app inserts a `ServerConn` before authentication and discovery. Its exception
handler only sets an error; it does not close/remove the new entry. Four failed
connections to distinct endpoints can exhaust `MAX_LIVE`, even with no live
connection. Unsaved failed endpoints may have no saved card to forget, and a
post-authentication discovery failure can leave an SSH session open.

**Fix:** roll back failed additions and close partial connections. Count genuinely
live or intentionally connecting entries, and restore the prior active selection
when a new connection fails.

### R15 — P2: Selected named sessions are not persisted for later reconnection

**Source trace:** `App.kt:342-343`, `374-400`, `508-523`;
`Screens.kt:339`, `365`.

The connection is saved before the session picker, usually with `default`.
`attach` changes only `currentSession` and never updates the saved connection.
Notification intents also omit the session name. After process death, the saved
connection and notification reconnection do not preserve the actual selected
named session; the deep-link path returns to session selection instead of
completing the promised jump.

**Fix:** persist the successfully attached session, include it in notification
destinations, and validate pane/tab membership before jumping. Test a non-default
session across process recreation and notification launch.

## Suggested fix order and acceptance checks

1. **SSH trust:** fix R01/R05 first. A rejected or changed host key must receive
   no authentication secret, and fingerprints must match `ssh-keygen`.
2. **Credential lifecycle:** fix R02/R12/R13. Secure-store failure must not create
   plaintext credentials; backup rules must exclude them; forgetting must erase
   them.
3. **Command safety:** fix R03/R04/R07. Independent drafts stay with their exact
   targets; lost replies retain the original key and an unknown outcome; immediate
   replies must never be dropped.
4. **Bridge compatibility:** fix R06/R08. Output updates on an unchanged active
   pane; history/message/subscription requests respect actual limits and display
   server errors.
5. **Lifecycle and resources:** fix R09/R10/R11/R14/R15. Exercise rotation with
   four hosts, repeated failed connections, sustained snapshots, a large document,
   and a named-session notification after process restart.

The existing build/lint results do not cover these behavioral checks. The local
SSH reproduction establishes the transport flaws, not a full Android UI or
phone-to-Windows acceptance test. No source fixes or deployment were performed.
