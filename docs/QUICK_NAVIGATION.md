# Quick navigation

The tab strip at the top includes tabs from **all spaces on all connected
computers**. Each chip shows its tab name and computer/space context. Swipe the
strip horizontally and tap any chip to switch directly.

Tap **All tabs** at the right of the strip to open the full picker. It groups
tabs by computer, account/session, and space, including spaces that are not
focused on the desktop. Search any combination of computer, space, or tab
names, such as `laptop docs` or `client API`, then tap the result.

The space header follows the tab selected on the phone. Navigation retains the
destination's computer, session, workspace, tab, and pane identities, so matching
tab names on different machines stay separate. Existing drafts remain with
their original destinations. A changed session or vanished pane is rejected
instead of redirecting the selection.

Only connected computers and their currently attached named Herdr sessions are
listed. The existing limit of four SSH identities and one selected named session
per host/port/account still applies.

## Verification for development version 0.1.2

Checked 2026-10-13:

- 29 unit tests passed, including inactive-space enumeration, duplicate tab
  identities on different computers, split-pane selection, combined search,
  and rejection of mismatched workspace/pane data.
- Two Compose UI tests exercised the production picker: searching and selecting
  a different computer, finding an inactive space, and clearing an empty result.
- Eight Android controller/Windows integration tests passed on a fresh API 36
  emulator. A disposable Herdr session contained two spaces; two synthetic SSH
  accounts represented separate computer identities with overlapping pane/tab
  IDs. Switching across spaces and identities, terminal readback, and rejection
  of a stale session were verified. The temporary session and transports closed.
- Debug, test, and signed release APKs assembled, and lint reported no errors.

No physical phones or previously installed apps were changed. This verifies
the real Windows bridge behind synthetic SSH authentication, rather than real
Windows SSH account authentication. The navigation improvements first tested in
the 0.1.2 development build are included in the public 0.2.1 release.
