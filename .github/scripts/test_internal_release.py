"""Release-lane and Play receipt contracts; no credentials or remote writes."""

import copy
import fnmatch
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

from internal_approval import REPOSITORY, require_approval, require_source
from play_internal_receipt import PACKAGE, build_receipt, fetch_tracks

ROOT = Path(__file__).resolve().parents[2]


class WorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflows = {
            path.name: yaml.load(path.read_text(), Loader=yaml.BaseLoader)
            for path in (ROOT / ".github/workflows").glob("*.yml")
        }
        cls.android = cls.workflows["android.yml"]

    def run_selector(self, job, step_id, event, ref, changed="false"):
        script = next(step["run"] for step in self.android["jobs"][job]["steps"]
                      if step.get("id") == step_id)
        with tempfile.NamedTemporaryFile() as output:
            env = dict(os.environ, GITHUB_EVENT_NAME=event, GITHUB_REF=ref,
                       GITHUB_REF_NAME=ref.split("/")[-1], GITHUB_RUN_NUMBER="61",
                       CHANGED=changed, GITHUB_OUTPUT=output.name)
            subprocess.run(["bash", "-eu", "-o", "pipefail", "-c", script],
                           cwd=ROOT, env=env, check=True, capture_output=True)
            return dict(line.split("=", 1) for line in Path(output.name).read_text().splitlines())

    def test_feature_dispatch_runs_tests_and_selects_only_internal_prerelease(self):
        self.assertEqual("true", self.run_selector("detect", "decide", "workflow_dispatch",
                                                   "refs/heads/feat/internal")["run"])
        selected = self.run_selector("build", "tag", "workflow_dispatch", "refs/heads/feat/internal")
        self.assertEqual(("true", "true", "internal", "android-dev.61"),
                         tuple(selected[k] for k in ("should_release", "is_prerelease",
                                                      "play_track", "release_tag")))
        for job in ("test", "instrumented"):
            self.assertIn("github.event_name == 'workflow_dispatch'", self.android["jobs"][job]["if"])

    def test_dispatch_main_production_tag_or_other_branch_fails_closed(self):
        for ref in ("refs/heads/main", "refs/tags/android-v1.2.1", "refs/heads/internal"):
            with self.subTest(ref=ref), self.assertRaises(subprocess.CalledProcessError):
                self.run_selector("build", "tag", "workflow_dispatch", ref)

    def test_existing_main_and_tag_selection_is_preserved_not_executed(self):
        for ref, track, prerelease in [("refs/heads/main", "internal", "true"),
                                       ("refs/tags/android-v1.2.1", "production", "false")]:
            with self.subTest(ref=ref):
                selected = self.run_selector("build", "tag", "push", ref)
                self.assertEqual(track, selected["play_track"])
                self.assertEqual(prerelease, selected["is_prerelease"])

    def test_prs_and_similarly_named_branches_never_select_publishing(self):
        for event, ref in [("pull_request", "refs/heads/internal"),
                           ("pull_request", "refs/pull/23/merge"),
                           ("push", "refs/heads/internal-production"),
                           ("push", "refs/heads/feat/internal")]:
            with self.subTest(event=event, ref=ref):
                selected = self.run_selector("build", "tag", event, ref)
                self.assertEqual("false", selected["should_release"])
                self.assertEqual("", selected["play_track"])

    def test_feature_push_and_generated_tag_do_not_trigger_other_workflows(self):
        for name, workflow in self.workflows.items():
            push = workflow["on"].get("push", {})
            self.assertFalse(any(fnmatch.fnmatch("feat/internal", p)
                                 for p in push.get("branches", [])), name)
            self.assertFalse(any(fnmatch.fnmatch("android-dev.61", p)
                                 for p in push.get("tags", [])), name)
        self.assertIn("ham-radio-core/**", self.workflows["agent-readiness.yml"]
                      ["on"]["pull_request"]["paths"])

    def test_dispatch_cannot_reach_build_without_human_approval(self):
        jobs = self.android["jobs"]
        self.assertEqual(["reviewed_sha"], list(self.android["on"]["workflow_dispatch"]["inputs"]))
        self.assertEqual("authorize-internal", jobs["detect"]["needs"])
        self.assertIn("needs.authorize-internal.result == 'success'", jobs["detect"]["if"])
        self.assertIn("authorize-internal", jobs["android-gate"]["needs"])
        self.assertIn("detect", jobs["build"]["needs"])
        self.assertIn("needs.detect.outputs.run == 'true'", jobs["build"]["if"])
        approval = jobs["authorize-internal"]["steps"][-1]
        self.assertEqual("github.event_name == 'workflow_dispatch'", approval["if"])
        self.assertEqual("python3 .github/scripts/internal_approval.py", approval["run"])

    def test_human_approval_is_rechecked_before_signing_and_publishing(self):
        steps = self.android["jobs"]["build"]["steps"]
        names = [step.get("name") for step in steps]
        for guard, action in [("Recheck exact human approval before signing", "Decode keystore (release builds only)"),
                              ("Recheck exact human approval before Play publishing", "Publish AAB to Play")]:
            self.assertLess(names.index(guard), names.index(action))
            check = steps[names.index(guard)]
            self.assertEqual("github.event_name == 'workflow_dispatch'", check["if"])
            self.assertEqual("python3 .github/scripts/internal_approval.py", check["run"])
            self.assertNotIn("continue-on-error", check)

    def test_internal_publish_and_readback_are_blocking_and_serialized(self):
        steps = self.android["jobs"]["build"]["steps"]
        publish = next(s for s in steps if s.get("id") == "play_publish")
        self.assertEqual("${{ github.event_name != 'workflow_dispatch' }}", publish["continue-on-error"])
        self.assertEqual("completed", publish["with"]["status"])
        self.assertEqual(PACKAGE, publish["with"]["packageName"])
        receipt = next(s for s in steps if s.get("name") == "Verify committed Play internal package/version/status")
        self.assertIn("github.event_name == 'workflow_dispatch'", receipt["if"])
        self.assertIn("!cancelled()", receipt["if"])
        self.assertIn("steps.play_publish.outcome == 'failure'", receipt["if"])
        self.assertNotIn("continue-on-error", receipt)
        self.assertIn("output-metadata.json", receipt["run"])
        self.assertIn("github.event_name == 'workflow_dispatch'", self.android["concurrency"]["group"])
        self.assertEqual("false", self.android["concurrency"]["cancel-in-progress"])


class ApprovalTest(unittest.TestCase):
    def setUp(self):
        self.sha = "a" * 40
        self.pr = {"state": "open", "draft": False, "mergeable_state": "clean",
                   "user": {"login": "patrickrb"},
                   "head": {"sha": self.sha, "repo": {"full_name": REPOSITORY}},
                   "base": {"ref": "main", "repo": {"full_name": REPOSITORY}}}
        self.approval = {"user": {"login": "Reid-n0rc"}, "state": "APPROVED", "commit_id": self.sha}

    def test_current_independent_approval_and_comments_are_accepted(self):
        require_source("workflow_dispatch", "refs/heads/feat/internal", self.sha, self.sha, REPOSITORY)
        require_approval(self.pr, [self.approval, dict(self.approval, state="COMMENTED")], "write", self.sha)

    def test_wrong_event_ref_sha_or_repository_is_rejected(self):
        valid = ["workflow_dispatch", "refs/heads/feat/internal", self.sha, self.sha, REPOSITORY]
        for index, value in [(0, "push"), (1, "refs/heads/main"), (1, "refs/tags/android-v9"),
                             (2, "bad-sha"), (3, "b" * 40), (4, "someone/fork")]:
            case = valid.copy()
            case[index] = value
            with self.subTest(index=index, value=value), self.assertRaises(ValueError):
                require_source(*case)

    def test_absent_agent_old_dismissed_or_changes_requested_review_fails(self):
        for reviews in ([], [dict(self.approval, user={"login": "agent"})],
                        [dict(self.approval, commit_id="b" * 40)],
                        [self.approval, dict(self.approval, state="DISMISSED")],
                        [self.approval, dict(self.approval, state="CHANGES_REQUESTED")]):
            with self.subTest(reviews=reviews), self.assertRaises(ValueError):
                require_approval(self.pr, reviews, "write", self.sha)

    def test_approval_revoked_after_initial_check_blocks_release_recheck(self):
        require_approval(self.pr, [self.approval], "write", self.sha)
        with self.assertRaises(ValueError):
            require_approval(self.pr, [dict(self.approval, state="DISMISSED")], "write", self.sha)
        self.pr["head"]["sha"] = "b" * 40
        with self.assertRaises(ValueError):
            require_approval(self.pr, [self.approval], "write", self.sha)

    def test_draft_closed_fork_changed_source_self_approval_and_read_only_fail(self):
        cases = [dict(self.pr, draft=True), dict(self.pr, state="closed"),
                 *[dict(self.pr, mergeable_state=state)
                   for state in ("unknown", "blocked", "behind", "dirty", "unstable")],
                 dict(self.pr, head={"sha": "b" * 40, "repo": {"full_name": REPOSITORY}}),
                 dict(self.pr, head={"sha": self.sha, "repo": {"full_name": "someone/fork"}}),
                 dict(self.pr, base={"ref": "staging", "repo": {"full_name": REPOSITORY}}),
                 dict(self.pr, user={"login": "Reid-n0rc"})]
        for pr in cases:
            with self.subTest(pr=pr), self.assertRaises(ValueError):
                require_approval(pr, [self.approval], "write", self.sha)
        with self.assertRaises(ValueError):
            require_approval(self.pr, [self.approval], "read", self.sha)


class ReceiptTest(unittest.TestCase):
    def setUp(self):
        self.before = {"packageName": PACKAGE, "tracks": {"internal": [], "production": [
            {"releaseName": "stable", "track": "production",
             "releaseLifecycleState": "RELEASE_LIFECYCLE_STATE_PUBLISHED",
             "activeArtifacts": [{"versionCode": 1009}]}]}}
        self.after = copy.deepcopy(self.before)
        self.after["tracks"]["internal"] = [
            {"releaseName": "android-dev.61", "track": "internal",
             "releaseLifecycleState": "RELEASE_LIFECYCLE_STATE_PUBLISHED",
             "activeArtifacts": [{"versionCode": 1061}]}]
        self.metadata = {"applicationId": PACKAGE, "variantName": "release",
                         "elements": [{"versionCode": 1061, "versionName": "1.2.1-dev.61"}]}

    def receipt(self):
        return build_receipt(self.before, self.after, self.metadata, "android-dev.61", "abc123")

    def test_exact_published_summary_reports_identity_and_explicit_rollout_limits(self):
        receipt = self.receipt()
        self.assertTrue(receipt["verified"])
        self.assertEqual("1.2.1-dev.61", receipt["versionName"])
        self.assertEqual((PACKAGE, "internal", "1061", ["RELEASE_LIFECYCLE_STATE_PUBLISHED"], "abc123"),
                         tuple(receipt[k] for k in ("packageName", "track", "versionCode",
                                                    "observedLifecycleStates", "sourceSha")))
        self.assertIn("not proof of completed", receipt["rolloutCompletion"])
        self.assertIn("partial/halted", receipt["testerRollout"])
        self.assertIn("no notes or rollout fraction", receipt["productionComparisonScope"])
        self.assertEqual("not verified", receipt["deviceAvailability"])
        self.assertIn("enrollment/propagation unverified", receipt["testerRollout"])
        self.assertEqual("not tested", receipt["physicalUsbCatPttRf"])

    def test_draft_review_pending_or_approved_unpublished_is_not_published(self):
        for status in ("RELEASE_LIFECYCLE_STATE_DRAFT", "RELEASE_LIFECYCLE_STATE_IN_REVIEW",
                       "RELEASE_LIFECYCLE_STATE_APPROVED_NOT_PUBLISHED", "", "PUBLISHED"):
            with self.subTest(status=status):
                self.after["tracks"]["internal"][0]["releaseLifecycleState"] = status
                result = self.receipt()
                self.assertFalse(result["verified"])
                self.assertEqual([status], result["observedLifecycleStates"])
                self.assertIn("not confirmed", result["testerRollout"])

    def test_wrong_version_or_name_and_missing_release_are_not_success(self):
        for field, value in [("activeArtifacts", [{"versionCode": 1060}]),
                             ("activeArtifacts", [{"versionCode": "1061"}]),
                             ("releaseName", "android-dev.60"), ("track", "production")]:
            with self.subTest(field=field):
                changed = copy.deepcopy(self.after)
                changed["tracks"]["internal"][0][field] = value
                self.assertFalse(build_receipt(self.before, changed, self.metadata,
                                               "android-dev.61", "abc")["verified"])
        for releases in (self.after["tracks"]["internal"] * 2, []):
            self.after["tracks"]["internal"] = releases
            self.assertFalse(self.receipt()["verified"])

    def test_observed_production_summary_changes_fail_verification(self):
        for field, value in [("activeArtifacts", [{"versionCode": 1061}]),
                             ("releaseLifecycleState", "RELEASE_LIFECYCLE_STATE_DRAFT"),
                             ("releaseName", "changed")]:
            with self.subTest(field=field):
                changed = copy.deepcopy(self.after)
                changed["tracks"]["production"][0][field] = value
                receipt = build_receipt(self.before, changed, self.metadata, "android-dev.61", "abc")
                self.assertFalse(receipt["verified"])
                self.assertFalse(receipt["productionSummariesUnchanged"])

    def test_wrong_package_debug_variant_or_multiple_outputs_are_rejected(self):
        for field, value in [("applicationId", "radio.ks3ckc.ft8af"),
                             ("variantName", "debug"), ("elements", [])]:
            with self.subTest(field=field):
                metadata = dict(self.metadata, **{field: value})
                with self.assertRaises(ValueError):
                    build_receipt(self.before, self.after, metadata, "android-dev.61", "abc")
        self.before["packageName"] = "radio.ks3ckc.jttyaf"
        with self.assertRaises(ValueError):
            self.receipt()

    def test_readback_cli_refuses_non_internal_dispatch_before_authentication(self):
        for event, ref in [("push", "refs/heads/main"), ("pull_request", "refs/heads/internal")]:
            with self.subTest(event=event, ref=ref), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "receipt.json"
                result = subprocess.run(["python3", str(ROOT / ".github/scripts/play_internal_receipt.py"),
                                         "before", str(output)], capture_output=True, text=True,
                                        env=dict(os.environ, GITHUB_EVENT_NAME=event, GITHUB_REF=ref))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("requires the exact reviewed feat/* SHA", result.stderr)
                self.assertFalse(output.exists())

    def test_get_only_readback_handles_empty_track_without_edit_methods(self):
        class Response:
            def __init__(self, data, status=200):
                self.data, self.status_code = data, status

            def raise_for_status(self):
                if self.status_code >= 400:
                    raise RuntimeError("request failed")

            def json(self):
                return self.data

        class Session:
            def get(self, url, timeout):
                if url == f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}/tracks/internal/releases":
                    return Response({})
                if url == f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}/tracks/production/releases":
                    return Response({"releases": [{"track": "production", "releaseName": "stable",
                        "activeArtifacts": [{"versionCode": 1009}],
                        "releaseLifecycleState": "RELEASE_LIFECYCLE_STATE_PUBLISHED"}]})
                raise AssertionError(f"Unexpected endpoint: {url}")

        result = fetch_tracks(Session())
        self.assertEqual(PACKAGE, result["packageName"])
        self.assertEqual([], result["tracks"]["internal"])
        self.assertEqual([{"track": "production", "releaseName": "stable",
                           "activeArtifacts": [{"versionCode": 1009}],
                           "releaseLifecycleState": "RELEASE_LIFECYCLE_STATE_PUBLISHED"}],
                         result["tracks"]["production"])

    def test_wrong_track_or_api_failure_is_not_an_empty_success(self):
        class Session:
            def __init__(self, fail):
                self.fail = fail

            def get(self, url, timeout):
                return self

            def raise_for_status(self):
                if self.fail:
                    raise RuntimeError("HTTP failure")

            def json(self):
                return {"releases": [{"track": "production"}]}

        with self.assertRaises(ValueError):
            fetch_tracks(Session(False))
        with self.assertRaises(RuntimeError):
            fetch_tracks(Session(True))


if __name__ == "__main__":
    unittest.main()
