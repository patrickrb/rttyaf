# Release pipeline

Three platforms (Android, desktop, iOS) build and release **independently** —
each has its own workflow, its own path filter, its own tag namespace, and its
own required status-check gate. A change to one platform never rebuilds the
others (except a change to the shared native C core under
`rttyaf/app/src/main/cpp/**`, which all three depend on).

## Branch lifecycle

RTTYAF develops **straight to `main`** — there is no `dev`/`staging` promotion
chain (that was FT8AF's model; its source gates were removed in the fork).

```
feature/* ──PR──▶ main ──────────────▶ INTERNAL (prerelease) build
                                        → GitHub prerelease + Play internal
android-v<x.y> tag push ─────────────▶ PRODUCTION build
                                        → GitHub release + Play production
```

- **feature → main** — every work item. CI runs on the PR (tests, build
  check); merging it (a push to `main`) cuts an **internal-testing** build:
  a prerelease GitHub Release (`android-dev.<run#>`) published to the Play
  **internal** track. This is the equivalent of FT8AF's dev → staging merge.
- **android-v\* tag** — production is a deliberate act, never automatic.
  When an internal build has been validated, tag the commit and push:
  `git tag android-v1.2 && git push origin android-v1.2`. That cuts a full
  GitHub Release and publishes to the Play **production** track.

## Tag / release namespaces

Per-platform prefixes keep the Releases page unambiguous:

| Platform | Production tag        | Internal (prerelease) tag | Trigger of internal build |
|----------|-----------------------|---------------------------|---------------------------|
| Android  | `android-v<x.y>`      | `android-dev.<run#>`      | push to `main`            |
| Desktop  | `desktop-v<x.y.z>`    | `desktop-dev.<run#>`      | _(dormant — was `staging`)_ |
| iOS      | _(no release yet)_    | _(no release yet)_        | —                         |

- Android internal builds derive their versionName from the latest
  `android-v*` tag (e.g. `1.1.0-dev.42`); until the first such tag exists the
  workflow falls back to the static versionName in `rttyaf/app/build.gradle`.
- The desktop workflow still carries FT8AF's staging lane; since no `staging`
  branch exists it never fires — `main` pushes and `desktop-v*` tags are the
  only live desktop lanes.
- iOS is CI-only: it builds the test suite and an unsigned simulator build to
  prove it compiles. A distributable `.ipa` needs an Apple Developer cert +
  provisioning profile / TestFlight, which are not wired up yet.

## One-time setup (manual)

These cannot be done from a workflow file:

1. **Branch protection for `main`** → Require status checks → `android-gate`
   (plus `desktop-gate` / `ios-gate` as desired). The FT8AF source gates
   (`enforce-source-is-dev` / `enforce-source-is-staging`) are gone — remove
   them if they linger as required checks.
2. **Play Console** → the app `radio.ks3ckc.rttyaf` must exist with its
   **first AAB uploaded manually** (the Play API cannot create the very first
   release for a new app), and the `PLAY_SERVICE_ACCOUNT_JSON` service account
   must be granted release permission on it (internal + production tracks) —
   it was originally set up for FT8AF (`radio.ks3ckc.ft8af`).
3. **Signing** — `RELEASE_KEYSTORE_BASE64` / `RELEASE_STORE_PASSWORD` /
   `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` repo secrets must hold a
   keystore matching the signing key RTTYAF's Play listing expects.
