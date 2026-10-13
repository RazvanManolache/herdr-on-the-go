# Third-party notices

Original Herdr on the Go code and documentation © 2026 Razvan Manolache,
GPL-3.0-only. See [LICENSE](LICENSE). Dependencies retain their own terms.

| Component | License and use |
| --- | --- |
| Kotlin standard library | [Apache 2.0](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt); Kotlin runtime. |
| AndroidX, Jetpack Compose, Material 3, Activity, Core, Security Crypto | [Apache 2.0](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/LICENSE.txt); app UI, lifecycle, and encrypted storage. |
| JSch 0.2.21, maintained by Matthias Wiedemann and contributors | BSD 3-Clause, originally by JCraft; SSH implementation. [Preserved license](docs/licenses/JSch-BSD-3-Clause.txt). |
| ZXing Android Embedded 4.3.0 and ZXing Core 3.4.1 | Apache 2.0; camera QR scanning for PC pairing. [License](docs/licenses/ZXing-Apache-2.0.txt) and [upstream notices](docs/licenses/ZXing-NOTICE.txt). |
| Gradle wrapper 8.11.1 | Apache 2.0; build bootstrap only. [Preserved license](docs/licenses/Gradle-Apache-2.0.txt). |
| AndroidJUnitRunner / AndroidX Test | Apache 2.0; test APK only. |
| JUnit 4.13.2 | [Eclipse Public License 1.0](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt); local/test dependency. |
| JSON-java 20240303 | [Public domain](https://github.com/stleary/JSON-java/blob/20240303/LICENSE); JVM unit tests only. Android supplies its own `org.json` at runtime. |
| Paramiko | [LGPL 2.1](https://github.com/paramiko/paramiko/blob/main/LICENSE); optional Python test fixture, not bundled in the app. |

Build dependencies are declared in the Gradle files and downloaded from their
publishers. This repository does not vendor their source histories or binaries,
apart from the Gradle wrapper JAR. Android dependency notices normally retained
by the build remain associated with their respective components.

[Herdr](https://herdr.dev/) and [herdr-bridge](https://github.com/RazvanManolache/herdr-bridge)
are separate installations, not bundled code or a grant of rights to their names.
This is an independent, unofficial Android companion; no affiliation or endorsement
is implied.
