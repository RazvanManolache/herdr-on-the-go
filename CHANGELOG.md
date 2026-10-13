# Changelog

## 0.2.1 — 2026-10-13

Includes the pairing and navigation improvements below; 0.2.0 and 0.1.2 were
development builds without separate public APK releases.

- Paired connections send a read-only keep-alive every 20 seconds, independently
  of terminal polling, and detect missing replies.
- Dropped paired connections reconnect automatically with bounded backoff,
  preserving the selected computer, session, tab, pane, and unsent drafts.
- Revoked devices, changed certificates, and unavailable sessions stop automatic
  retries and require attention. Reconnecting never replays a terminal command.
- The Windows paired bridge no longer expires an authenticated idle stream after
  60 seconds; partial frames and stalled writes remain bounded.

## 0.2.0 — Included in 0.2.1

- Optional paired TLS transport alongside SSH, with PC certificate pinning from
  a physical QR/address and six-digit approval inside a dedicated Herdr tab.
- Separate encrypted device credentials; no Windows username or password needed
  for paired connections. PC-side revocation closes active connections.
- QR scanning, pending-pair cancellation, retained pairing state across rotation,
  and compatibility with existing saved SSH connections.

## 0.1.2 — Included in 0.2.1

- A global tab strip includes all spaces on all connected computers, with
  computer/space labels to distinguish matching tab names.
- An explicit **All tabs** button opens a searchable picker grouped by computer,
  session, and space; search combines computer, account, space, and tab names.
- The space header follows the phone's selected tab instead of the desktop's focus.
- Navigation validates the captured computer, session, workspace, tab, and pane
  before switching; a stale selection cannot silently change sessions.

## 0.1.1 — First public release

Released as **Herdr on the Go**, an independent Android companion for Windows
Herdr sessions. The new application ID is
`io.github.razvanmanolache.herdronthego`.

- Multiple connected computers, named-session selection, and a unified tab picker.
- Live terminal polling, scoped drafts, message sending, and bounded text attachments.
- Themes, search, and adjustable terminal fonts from 10 to 24 sp.
- SSH verification before authentication and encrypted password storage without fallback.
- Durable command retry identities and explicit handling of unknown or partial outcomes.
- Retained state across rotation, named-session notification reconnects, and transport cleanup.
- Public documentation, fictional screenshot fixtures, GPLv3, Gradle wrapper,
  regression tests, and a signed release APK.

Earlier builds were local development versions and were not public Android releases.
