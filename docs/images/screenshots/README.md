# Screenshot provenance

These PNGs are unmodified emulator captures of the production Compose screens,
rendered by `ScreenshotTest.capturePublicDocumentation`. The fixture uses an
isolated test application and entirely fictional state. It makes no SSH
connections and never reads saved user credentials or real terminal output.

Hosts use reserved `.example` names. The username `demo`, project path
`C:\Projects\demo-dashboard`, named sessions, statuses, and terminal messages
are invented. The apparent “24 tests passed” is sample terminal text, not test
evidence. Actual regression evidence is described in the repository review.

| File | View |
| --- | --- |
| `connections.png` | Three saved demo computers. |
| `sessions.png` | Reachable named sessions. |
| `terminal.png` | Terminal output, scoped draft, and 14 sp font. |
| `font-size.png` | The same view at 18 sp, using the font controls. |
| `computers.png` | Connected-computer switcher. |
| `jump.png` | Unified tab picker across three computers. |
| `search.png` | Search controls in the Sand theme. |

To reproduce, build and install the debug and Android-test APKs on an explicit
emulator, then run:

```powershell
adb -s emulator-5556 shell am instrument -w -r -e class io.github.razvanmanolache.herdronthego.ScreenshotTest io.github.razvanmanolache.herdronthego.test/io.github.razvanmanolache.herdronthego.IsolatedTestRunner
```

The captures are written to the app's `cache/public-screenshots` directory.
Copy their bytes with `adb exec-out run-as` or a binary-safe subprocess.
The test fixture ships in the test APK only; it is absent from the public release.

App UI and screenshots © 2026 Razvan Manolache; GPL-3.0-only.
