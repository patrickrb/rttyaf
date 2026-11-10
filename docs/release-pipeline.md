# Release pipeline

> **Shared-core rollout: internal testing only.** Do not merge to `main` as an
> unreviewed delivery shortcut. Main pushes publish Android to Play internal and
> desktop as `desktop-dev.*` prereleases, even for docs-only merges. Preserve
> required review and obtain owner authorization before merging, or use the
> reviewed feature-ref INTERNAL dispatch below; while blocked, provide a test APK.
> No production release, promotion or rollout until Patrick tests
> **all five apps** (FT8AF, RTTYAF, SSTVAF, ROTA, JTTYAF) and explicitly approves.
> Do not change protections/environments or bypass human review to deliver it.

## Reviewed feature-ref internal delivery

The Android workflow has an **INTERNAL-only** manual entry point. It does not
need a main merge, a new internal/staging branch, production tags, new signing
material or new services. Existing main/tag routing below is historical behavior,
not authorization to run those lanes during the all-five-app hold.

**Trust boundary:** this is a release-policy check for trusted repository writers
and the operator selecting the reviewed ref, not tamper-proof credential isolation
from those writers. Both workflow and guard script come from the feature commit;
a writer can edit them. Independently inspect the exact approved SHA before
dispatch. If enforcement against repository writers is required, stop for an
owner-approved external protection/credential boundary; do not invent one here.

1. Open a ready PR to main from a same-repository `feat/*` branch containing
   the combined source and lane changes. Keep it **DO NOT MERGE**, with no
   auto-merge. Request **Reid-n0rc** as the independent writer/non-author reviewer.
   He must submit **Approve** for the **exact current head commit**, not a comment
   or approval of an earlier commit. Agent review does not substitute for this.
2. Wait for normal required CI/review gates. Dispatch requires GitHub's current
   `mergeable_state` to be `clean` (eligibility only, **never actually merge**).
   Unknown, blocked, behind or failed states fail closed. Do not infer permission
   from empty rules, an inaccessible protection endpoint, or a secret-list 403.
   Preserve all existing human/environment gates; do not add an unprotected lane
   or change settings to make the check pass. Review must be renewed after a push.
   The workflow rechecks live approval before signing, before the Play snapshot /
   GitHub prerelease, and immediately before Play publishing. Approval is evaluated
   at those checks, not continuously; revocation after the last check cannot cancel
   an API operation already in flight. Dispatch-time pending checks may itself
   affect GitHub mergeability: if it fails closed, report the observed state, do
   not relax the check just to obtain delivery.
3. With owner authorization for internal delivery, dispatch the existing registered
   Android workflow **on that feature ref**, setting `reviewed_sha` to its exact
   approved 40-character commit. For example, after substituting the actual SHA:

   ```sh
   gh workflow run android.yml --repo patrickrb/rttyaf \
     --ref feat/internal-readiness-shared-core -f reviewed_sha=<approved-head-sha>
   ```

   The source guard rejects main, every tag, wrong repos and SHA mismatches before
   credentials are used. It verifies the named human still has write access and
   has not dismissed approval or requested changes. No track/status inputs exist:
   dispatch always selects **internal**, **completed**, and a GitHub **prerelease**
   `android-dev.<run#>` at the built SHA. That tag and feature pushes do not match
   desktop publishing triggers. If GitHub refuses feature-ref dispatch because
   default-branch registration is insufficient, report the exact response and
   stop; do not merge main or bootstrap a bypass. Existing Android workflow
   registration is visible in `gh workflow list` but is not proof of successful
   feature-ref dispatch.
4. Existing hosted signing and `PLAY_SERVICE_ACCOUNT_JSON` secrets are reused
   only inside the authorized job. Do not download/expose them or create new keys.
   Dispatch runs `:ham-radio-core:test` separately from app tests. Play failures
   are blocking in this lane; the older main/tag warn-only behavior is unchanged.
5. Retain the `play-internal-readback` artifact: authenticated **GET-only**
   `applications/{package}/tracks/{track}/releases` reads RTTYAF internal and
   production release summaries before/after publishing. It creates/commits/deletes
   **no edits** and updates no tracks. Verification requires the built release
   APK's exact integer versionCode and releaseName on internal with raw lifecycle
   **RELEASE_LIFECYCLE_STATE_PUBLISHED**, and unchanged exposed production summaries.
   Draft/review-pending/approved-but-unpublished or mismatches fail verification.
   The workflow requests `completed`, but **PUBLISHED is not proof of completed**:
   [Google's schema](https://developers.google.com/android-publisher/api-ref/rest/v3/applications.tracks.releases)
   includes partial/halted releases. The API returns at most 20 non-obsolete
   summaries, no notes/versionName/rollout fraction. The receipt labels these
   limits; production-summary equality is not full production-state proof.
   VersionName is local build metadata, not an observed Play field. If a
   failure happens after upload, read-back still runs on publisher success/failure
   (unless cancelled); inspect actual Play state before retrying.
   Published does not prove tester enrollment/propagation or device availability.
   The inherited scheduled Play watcher uses a separate concurrency group and
   can still invalidate the publisher's upload edit using the same service account;
   GET-only read-back cannot. Android release runs serialize with each other,
   not with that watcher. Report such a failure;
   never disable the watcher, change default-branch automation or retry blindly.
6. Provide source SHA, APK download, SHA256, package/version and per-suite results
   plus CI/read-back links. Debug fallback APKs cannot upgrade a differently signed
   installed app; export data before uninstalling (uninstall deletes it). A signed
   GitHub APK uses the existing release/upload key, which may differ from the Play
   App Signing key; do not promise it can replace a Play-installed app. No local
   device means no physical USB/CAT/PTT/RF or on-air validation.

Credential-free safeguard validation:

```sh
uv run --with PyYAML python -m unittest discover -s .github/scripts -p 'test_*.py' -v
```

Android and desktop build and release **independently**, with separate workflows,
path filters, tag namespaces and status-check gates. PRs use those path filters;
main pushes always build/release both internal snapshots. iOS is not a supported
target; its legacy FT8 app, kit, demo and CI workflow have been retired.

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
| Desktop  | `desktop-v<x.y.z>`    | `desktop-dev.<run#>`      | push to `main` or `staging` |

- Desktop main/staging pushes create **prereleases**, never `desktop-v*` tags.
  Only an explicit `desktop-v*` tag push selects desktop production; it remains
  unauthorized during the all-five-app hold.
- Android internal builds derive their versionName from the latest
  `android-v*` tag (e.g. `1.2.1-dev.42`); until the first such tag exists the
  workflow falls back to the static versionName in `rttyaf/app/build.gradle`.
- The desktop workflow still accepts staging pushes; no `staging` branch
  currently exists. Do not create branches or tags to trigger release lanes.

## Release-notes API audit

RTTYAF currently has **no Anthropic API calls** in its release workflows or
scripts, including the `release/1.2.0-notes` and `release/1.2.1-notes` branches
audited on November 10, 2026. Android's `generate_release_notes: true` is GitHub's
release-note generation, not a paid model call. Desktop uses a deterministic
release body, and Play uses checked-in `distribution/whatsnew` text. There is
no paid first-internal call to remove or repeated paid call to deduplicate.
No release automation was changed for this audit.

## One-time setup (manual)

These cannot be done from a workflow file:

1. **Branch protection for `main`** → Require status checks → `android-gate`
   (plus `desktop-gate` as desired). Before merging the iOS-removal PR, Patrick
   must inspect effective classic protections and rulesets for `main` and retire
   the `ios-gate` requirement if present. The removed workflow also targeted
   `dev`/`staging`; neither branch currently exists, but matching wildcard
   rulesets/protections must not require `ios-gate` if those branches return.
   Classic protection GET returned 403; visible rules/rulesets were empty, so
   this audit cannot certify the check is optional. No protection changes or
   fake green replacement gate are part of the PR. The FT8AF source gates
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
