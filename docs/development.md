# Build and test RTTYAF

## Linux x64 / fresh Amp orb (supported host checks)

From the repository root, run `.agents/setup` if Amp has not already run it.
Setup is noninteractive and repeatable, and installs:

| Tool | Version / source of truth |
| --- | --- |
| JDK | Debian OpenJDK 17 |
| Gradle | 8.9, via `rttyaf/gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle plugin | 8.7.3, `rttyaf/build.gradle` |
| Android SDK | platform 36, build-tools 34.0.0 (AGP default) |
| NDK | 27.0.12077973 (AGP 8.7 default) |
| CMake | 3.22.1, `rttyaf/app/build.gradle` |
| Android CLI tools | Google Linux archive 11076708 |

The SDK is shared by worktrees at `~/.local/share/android-sdk`; Gradle uses
`~/.gradle`. Allow several GB of disk and a few minutes for cold downloads.
The setup log lists each phase. A warm run reuses installed SDK packages and
Gradle caches. It doesn't download an emulator, install desktop/iOS toolchains,
start services, change tracked files, or need release secrets.

Environment exports are persisted in `~/.config/rttyaf/env.sh`, loaded once per
new login shell by `~/.bash_profile`. Check from a **new** shell, not by sourcing
profile edits into the setup process:

```sh
bash -lc 'java -version; command -v sdkmanager; command -v adb'
```

Run host verification from the repository root:

```sh
bash -lc 'cd rttyaf && ./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" :ham-radio-core:test testDebugUnitTest assembleDebug'
```

Use a 1536 MiB Gradle heap, one worker and in-process Kotlin in bounded orbs.
Always run `:ham-radio-core:test` explicitly: `testDebugUnitTest` alone does not
execute the portable Java library tests. Its unchanged source/test payload and
update rules are documented in [shared-radio-core.md](shared-radio-core.md).
Do not use `clean` on every iteration: it discards useful incremental work.
In a shell that already has the toolchain environment, focused checks are:

```sh
cd rttyaf
./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' testDebugUnitTest --tests 'radio.ks3ckc.rttyaf.rtty.RttyModemTest'
./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' jacocoTestReport
```

The modem suite exercises real text → AFSK → text round trips, baud rates,
letters/figures shifts, streaming boundaries and noise without a radio. The full
suite also includes Robolectric Android/resource tests and mock HTTP servers;
it needs no real QRZ/POTA account. First execution downloads test dependencies,
including Robolectric's Android runtime, from Maven repositories.

Outputs (relative to `rttyaf/`):

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Unit report: `app/build/reports/tests/testDebugUnitTest/index.html`
- JUnit XML: `app/build/test-results/testDebugUnitTest/TEST-*.xml`
- Core report/XML: `../ham-radio-core/build/{reports/tests/test/index.html,test-results/test/TEST-*.xml}`
- Coverage: `app/build/reports/jacoco/jacocoTestReport/{html/index.html,jacocoTestReport.xml}`

Optional static checks are `./gradlew lintDebug detekt ktlintCheck`. Existing
style findings may fail these checks; do not mass-format unrelated code. A
setup-only success is **not** a passing build or test suite. Report failing
test names and distinguish baseline failures from regressions.

`.github/workflows/agent-readiness.yml` exercises this same setup twice, checks
a clean login environment, and runs host tests plus the debug build whenever
the onboarding files change. Existing Android CI covers Android source changes.
Both workflows detect core changes and run/upload both modules' tests.
Readiness also uploads `rttyaf-internal-debug-apk` for authenticated CI download,
including on core-only PRs. App JaCoCo/Codecov reporting does not include the
vendored core's coverage; this is an observability limitation, not a skipped
core test suite.

## Internal testing boundary for this rollout

Do not merge this integration to `main`: although Android's main lane publishes
to Play internal, the desktop workflow also creates a **production** desktop
release on every main push. Keep the feature PR build-only and distribute the
debug APK for internal testing. Do not alter release automation to bypass this.
No production release, tag or promotion is authorized until the user tests all
five apps (FT8AF, RTTYAF, SSTVAF, ROTA, JTTYAF) and explicitly approves.

Debug APKs use a development signing key, not the Play/release key, and cannot
replace a differently signed installed app. Back up/export logs and settings
before uninstalling anything; uninstalling deletes app data. A Play-track build
requires access to the configured signing key and Play service account; never
paste these secrets into a thread. See [release-pipeline.md](release-pipeline.md)
for the existing procedures, but audit every platform workflow before delivery.

## Existing macOS / Windows checkouts

Use JDK 17 and an Android SDK with the packages above; Android Studio's SDK
manager can install them. Set `JAVA_HOME` and `ANDROID_HOME` (or put `sdk.dir`
in ignored `rttyaf/local.properties`). Do not run the Debian orb setup script
on macOS or Windows.

On macOS, run the same `./gradlew` commands inside `rttyaf/` with JDK 17 selected.
On Windows use `gradlew.bat testDebugUnitTest` and `gradlew.bat assembleDebug`
from that directory. From WSL use `cmd.exe /c "gradlew.bat testDebugUnitTest"`
with a Windows JDK/SDK configured; do not assume WSL has a Linux JDK installed.

## Android device checks (not available in an ordinary orb)

`adb devices` lists attached devices. Select one explicitly if several exist:

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
ANDROID_SERIAL=SERIAL ./gradlew connectedDebugAndroidTest
adb -s SERIAL shell am start -n radio.ks3ckc.rttyaf/radio.ks3ckc.rttyaf.ComposeMainActivity
adb -s SERIAL exec-out screencap -p > /tmp/rttyaf-screen.png
adb -s SERIAL pull /sdcard/Android/data/radio.ks3ckc.rttyaf/files/debug.log /tmp/rttyaf-debug.log
adb -s SERIAL logcat --pid="$(adb -s SERIAL shell pidof radio.ks3ckc.rttyaf)"
```

Run these from `rttyaf/`, replacing `SERIAL`. The screenshot/log may contain
callsigns or account data: inspect/redact before sharing. Instrumentation
exercises the actual app but cannot replace radio/USB/audio testing. Orbs have
no KVM; use an Android runner/device or the existing emulator CI job instead.

## Legacy ports and boundaries

The desktop React frontend can be checked independently:

```sh
cd desktop
npm ci
npm run build
```

This only checks TypeScript and bundles the frontend; it does **not** validate
the native app. `desktop/src-tauri/build.rs` and iOS C shims still reference the
removed `ft8af/app/src/main/cpp` FT8 DSP sources. Their native build/test commands
are not currently usable from a fresh clone. Restoring/porting those products
is separate work, not a prerequisite for Android development. The old FT8
claims in their READMEs are historical, not the active RTTY build contract.
iOS app validation also needs macOS/Xcode, unavailable in Linux orbs.
