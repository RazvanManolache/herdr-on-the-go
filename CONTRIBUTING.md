# Contributing

Thanks for helping improve Herdr on the Go. Small, focused changes are welcome.

1. Explain the problem and how to reproduce it, with fictional or redacted data.
2. Keep command destinations, pre-authentication host verification, encrypted
   storage, and retry identities intact. A timeout is not proof that nothing ran.
3. Use JDK 17 and the checked-in Gradle wrapper. Run:

   ```powershell
   .\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
   ```

4. Add regression coverage for behavior changes. Emulator tests use isolated
   preferences; do not make tests depend on personal SSH credentials or live panes.
5. Update documentation and screenshot fixtures when visible behavior changes.
   Keep screenshots synthetic and credit their source.

See [the build guide](docs/BUILD.md) for the optional Windows integration test.
Keep APKs, keystores, signing properties, local SDK paths, and generated reports
out of commits. Release binaries belong in GitHub Releases.

Contributions to original project code are provided under GPL-3.0-only.
