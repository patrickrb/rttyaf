# RTTYAF shared-core integration

RTTYAF vendors **ham-radio-core 0.1.0** unchanged at repository-root
`ham-radio-core/`. Canonical source: `patrickrb/FT8AF/ham-radio-core`, extracted
from FT8AF baseline `c2f63e8`. The module includes its MIT license, public-contract
tests, README contract, and `SHA256SUMS`; updates must replace the whole verified
payload, not patch this consumer's copy. See [the contract](../ham-radio-core/README.md).

Imported archive SHA-256:
`02e632964a4e619438bdc300abac7b45bb924cd2a7e1e3bc00695878c2c6dcba`.

`rttyaf/settings.gradle` maps `:ham-radio-core` to `../ham-radio-core`, and the
app uses a project dependency (no published artifact). `UsbAudioDevice` imports
`radio.core.audio.{FirDecimator,RationalResampler}` and
`radio.core.usb.UacAudioFormats`; `MainViewModel` imports
`radio.core.cat.CatLiveness` directly, without compatibility wrappers.

Before replacement, all four local implementations matched the core's executable
code and APIs, ignoring packages/imports/comments/whitespace. Their four test
files matched exactly after the package declaration. Those duplicate classes and
44 duplicate tests were removed from the app; no unique tests needed moving.
All app-specific USB, modem, PTT, and UI tests remain. JNI/native filters, USB
ownership/permissions, PTT orchestration, mode behavior, and UI are unchanged.

## Checks and results — 2026-10-30

Run from repository root with JDK 17; Android commands additionally require an
SDK. This port was validated on Linux with OpenJDK 17.0.21, platform 36,
build-tools 34.0.0, NDK 27.0.12077973, and CMake 3.22.1, without a repository
mirror or NDK override.

| Exact command | Observed result |
| --- | --- |
| `sha256sum /tmp/ham-radio-core-0.1.0.tar.gz` | Matched the archive digest above before extraction. |
| `(cd ham-radio-core && sha256sum -c SHA256SUMS)` | All 13 payload entries OK, including after integration/build. |
| `bash rttyaf/gradlew -p ham-radio-core clean test jar` | BUILD SUCCESSFUL; 44 tests, 0 failures/errors/skips; `ham-radio-core-0.1.0.jar` produced. No Android SDK needed. |
| `bash rttyaf/gradlew -p ham-radio-core javadoc` | BUILD SUCCESSFUL; 56 documentation warnings in the unchanged upstream sources. |
| `bash rttyaf/gradlew -p rttyaf --no-daemon --max-workers=2 :ham-radio-core:test testDebugUnitTest assembleDebug` | BUILD SUCCESSFUL; 44 core + 1,027 app tests, 0 failures/errors/skips; debug APK built. The invocation was run as `./gradlew --no-daemon --max-workers=2 :ham-radio-core:test testDebugUnitTest assembleDebug` from `rttyaf/`. |
| `git diff --check` | Passed. |

JUnit XML confirms the retained app suites include 20 `RttyModemTest` tests,
14 `RttyTransmitterTest` tests, 9 `UsbAudioRateResolutionTest` tests, and
13 `UsbAudioDetachRoutingTest` tests. The total remains 1,071 tests, now split
between core and app rather than duplicated.

APK inspection confirmed all four core class descriptors, no old app-local class
descriptors, and `librttyaf.so` for arm64-v8a, armeabi-v7a, x86, and x86_64.
The current CMake build contains libusb + USB audio JNI only; no native host test
suite remains in this baseline. Inherited desktop/iOS FT8 suites reference removed
sources and were not run. No Android device was attached (`adb devices` returned
an empty list), so installation, connected tests, and physical USB/CAT/PTT behavior
were not verified. Existing resource-format/deprecation warnings did not fail the
build; no UI appearance changes were made.

Android CI now includes `ham-radio-core/**` in its path filter and runs
`:ham-radio-core:test` explicitly alongside `testDebugUnitTest`: the Android app
test task alone does **not** execute the Java library tests. The existing failure
artifact upload includes the core HTML/JUnit report directories and runs on test
failure as well as Codecov failure.

All Android workflow path/job/release gates were inspected: there is no secondary
`git diff`/`grep` release-scope filter. Every path-scoped downstream job consumes
`detect`; main pushes and Android tags always build/release independently of
changed paths. Local YAML assertions and execution of the actual detection and
release-tag Bash scripts passed for all six cases: core-only PR runs but does not
release; docs-only PR skips; both core-only and docs-only main/tag pushes run and
select releases. Report-path assertions confirmed the core HTML index and all four
JUnit XML files exist. No hosted CI or release action was triggered. Readiness
files are owned separately and were not changed here.

## Combined readiness integration — 2026-10-30

The readiness and core exports were integrated against current upstream main
`541e2f33b46432c61b0c00a976692fe6a0142e60`, not against a stale app snapshot.
All four executable implementations/APIs and test bodies were independently
compared with that upstream before removing the app-local copies. The canonical
module manifest remains unchanged and all 13 entries pass verification.

Fresh-orb setup passed cold (1m40s) and warm (13s); a minimal-environment login
shell found JDK 17 and the SDK tools. Combined validation from repository root:

```sh
bash -lc 'cd rttyaf && ./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" :ham-radio-core:test testDebugUnitTest assembleDebug'
```

Result: **BUILD SUCCESSFUL in 5m49s**, 61 tasks executed; 44 core tests across
4 suites and 1,027 app tests across 115 suites, zero failures/errors/skips.
The APK contains the four relocated classes, none of their legacy descriptors,
and all four native ABI libraries. Package `radio.ks3ckc.rttyaf`, versionCode 11,
versionName `1.2.1-dev`; verified Android debug signing, not production signing.

Bounded `lintDebug detekt ktlintCheck --continue` **failed**: lint reported
623 errors and 1,264 warnings; detekt reported 9 findings; ktlint main/test
source checks failed. Lint errors point to unchanged source or unchanged lines
in import-only edits; Kotlin source/tests are unchanged. This is not a claim
that a full baseline lint run was performed. Do not suppress these findings or
describe this integration as lint-clean. Core coverage is not included in the
app JaCoCo upload, although its tests execute explicitly.

Actual workflow Bash selectors were exercised without publishing: Android
core-only PRs run tests without release, Android main selects internal, Android
tags select production, and desktop main selects production while PRs remain
build-only. Readiness rejects missing app/core reports, all-skipped core tests,
failures in either module and a missing APK. Core-only PRs trigger readiness and
its downloadable debug APK artifact. Release automation was not changed.

**Internal testing only:** do not merge to main (desktop production side effect)
or push release tags. All five apps must be tested by the user before explicit
production approval. No device was attached; installation, instrumentation and
physical USB/CAT/PTT/RF tests were not performed. See
[development guidance](development.md#internal-testing-boundary-for-this-rollout)
for signing/install warnings and the access boundary.
