# RTTYAF contributor and agent guide

RTTYAF is an Android RTTY transceiver. Start here; use
[docs/development.md](docs/development.md) for prerequisites and commands.
This is the shared guidance for humans and coding agents. `CLAUDE.md` points here.

## Repository map

- `rttyaf/`: active Android Gradle project; run Gradle from this directory.
- `rttyaf/app/src/main/kotlin/radio/ks3ckc/rttyaf/`: Compose UI, RTTY modem,
  settings and operating logic. The modem lives in `rtty/` and is pure Kotlin.
- `rttyaf/app/src/main/java/com/k1af/rttyaf/`: legacy Java integration,
  logging, radio control and audio. Namespace is `com.k1af.rttyaf`, but the
  installed application ID is **`radio.ks3ckc.rttyaf`**.
- `rttyaf/app/src/main/cpp/`: vendored libusb and JNI USB audio bridge, built
  from source into `librttyaf.so`. No FT8 DSP library or prebuilt JNI copy step.
- `rttyaf/app/src/test/{java,kotlin}/`: host JVM/JUnit4 and Robolectric tests.
- `ham-radio-core/`: unchanged portable Java library and its 44 tests;
  see [docs/shared-radio-core.md](docs/shared-radio-core.md) for update rules.
- `rttyaf/app/src/androidTest/`: instrumented tests requiring Android hardware
  or an emulator outside an orb.
- `desktop/`, `ios/`: legacy FT8 ports, not the Android RTTY app. Their native
  builds still reference the removed `ft8af/` tree. See development limitations;
  do not silently restore FT8 code or treat these as working RTTY targets.
- `.github/workflows/`: CI and publishing; `docs/release-pipeline.md` describes
  releases. Never use release workflows as a local test command.

## Fresh Amp orb

Amp runs executable `.agents/setup` automatically on a fresh uncached Linux x64
orb. It installs JDK 17 and the Android SDK/NDK/CMake, accepts SDK licenses, and
resolves Gradle plugins. It is safe to rerun; it needs network and passwordless
`sudo`. No signing keys, radio, account credentials or external services are
needed for debug builds and host tests. Setup does not run the test suite.

After setup, **new login shells** get `JAVA_HOME`, `ANDROID_HOME`,
`ANDROID_SDK_ROOT` and SDK tools on `PATH`. To validate from a current shell:

```sh
bash -lc 'cd rttyaf && ./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" :ham-radio-core:test testDebugUnitTest assembleDebug'
```

Always include `:ham-radio-core:test`: the Android test task does not run it.
Orbs cannot run accelerated Android emulators; do not install an AVD. Host tests
are not proof that the UI renders or that USB/audio works on a real radio.
Use a connected Android runner/device for UI, permission and hardware checks.
No web server or `.agents/resume` is needed for the host build/test workflow.

## Working rules

- Every work item goes on a feature branch and through a PR targeting `main`;
  never commit directly to `main`. Use an isolated worktree for separate work:
  `git worktree add ../rttyaf-my-task -b feat/my-task`. Build inside that worktree.
  Remove it after merge. Do not overwrite another contributor's changes.
- Read the owning code and existing tests before editing. Follow local patterns;
  avoid unrelated cleanup and changes to vendored libusb.
- Every added or changed code path needs a unit test in the same PR. Extend
  existing tests first. Use JUnit4 and Truth. Pure logic needs no Android runner;
  use `RobolectricTestRunner` for Android/Play Services types. Robolectric is
  pinned to SDK 35 in test resources although the app targets SDK 36.
- Keep Composables and DrawScope wrappers thin: extract decision/geometry logic
  into plain `internal` Kotlin functions/classes and test observable results.
- Run targeted tests while iterating, then the full JVM suite and debug build.
  Inspect rendered UI on a connected device for appearance changes; report the
  limitation if none is available. Build/install after code changes when a
  device is attached (target the intended serial, not every device).
- Keep machine paths and device serials in untracked `CLAUDE.local.md` or
  `rttyaf/local.properties`, not shared instructions. Never commit secrets,
  signing material, local SDK paths or build outputs.
- Pushes, PR creation, merges and releases require the user's authorization.
  Merging to `main` triggers publishing; do not trigger it merely to verify setup.
  Android publishes internally, but desktop publishes a production release on
  every `main` push. For the current shared-core rollout, stop at a build-only
  feature PR and test APK: no merge, release tag, production release or promotion
  until the user tests **all five apps** and explicitly approves production.

## Audio and device debugging

The native USB bridge sends **sample-rate-derived bytes per USB frame**, not
the endpoint's `wMaxPacketSize`. Preserve this: using the maximum packet size
changes playback speed/pitch. See `usb_audio_capture.cpp` before audio edits.

The app's structured event log is
`/sdcard/Android/data/radio.ks3ckc.rttyaf/files/debug.log`. Use `adb -s SERIAL`
for pull, logcat, install and input when more than one device is attached.
Screenshot capture can be black during splash/overlays: let the app settle and
retry before falling back to the accessibility tree. See development docs for
concrete commands.
