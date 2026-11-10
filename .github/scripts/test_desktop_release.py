"""Execute the desktop workflow's selectors without builds or remote writes."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


class DesktopReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = yaml.load(
            (ROOT / ".github/workflows/desktop.yml").read_text(), Loader=yaml.BaseLoader
        )

    def run_selector(self, job, step_id, event, ref, changed="false", tagged=False):
        script = next(step["run"] for step in self.workflow["jobs"][job]["steps"]
                      if step.get("id") == step_id)
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(["git", "init", "-q", directory], check=True)
            if tagged:
                subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                                "-c", "commit.gpgsign=false", "commit", "-qm", "Fixture", "--allow-empty"],
                               cwd=directory, check=True)
                for tag in ("desktop-v1.2.9", "desktop-v1.2.10", "android-v9.9.9"):
                    subprocess.run(["git", "tag", tag], cwd=directory, check=True)
            output = Path(directory) / "outputs"
            env = dict(os.environ, GITHUB_EVENT_NAME=event, GITHUB_REF=ref,
                       GITHUB_REF_NAME=ref.split("/")[-1], GITHUB_RUN_NUMBER="61",
                       CHANGED=changed, GITHUB_OUTPUT=str(output))
            subprocess.run(["bash", "-eu", "-o", "pipefail", "-c", script],
                           cwd=directory, env=env, check=True, capture_output=True)
            return dict(line.split("=", 1) for line in output.read_text().splitlines())

    def test_main_and_staging_pushes_select_only_prereleases(self):
        for branch in ("main", "staging"):
            for tagged in (False, True):
                with self.subTest(branch=branch, tagged=tagged):
                    selected = self.run_selector("version", "tag", "push", f"refs/heads/{branch}",
                                                 tagged=tagged)
                    self.assertEqual({"release_tag": "desktop-dev.61", "should_release": "true",
                                      "is_prerelease": "true",
                                      "version_name": f"{'1.2.10' if tagged else '0.1.0'}-dev.61"},
                                     selected)

    def test_explicit_desktop_production_tag_push_is_preserved(self):
        self.assertEqual({"release_tag": "desktop-v2.3.4", "version_name": "2.3.4",
                          "should_release": "true", "is_prerelease": "false"},
                         self.run_selector("version", "tag", "push", "refs/tags/desktop-v2.3.4"))

    def test_every_pr_ref_and_unsupported_event_is_compile_only(self):
        # Include branch/tag-shaped refs, not just GitHub's usual PR merge ref.
        for event in ("pull_request", "workflow_dispatch", "release"):
            for ref in ("refs/pull/24/merge", "refs/heads/main", "refs/heads/staging",
                        "refs/tags/desktop-v2.3.4"):
                with self.subTest(event=event, ref=ref):
                    self.assertEqual({"release_tag": "", "version_name": "",
                                      "should_release": "false", "is_prerelease": "false"},
                                     self.run_selector("version", "tag", event, ref))

    def test_dev_feature_similarly_named_branches_and_other_tags_do_not_publish(self):
        for ref in ("refs/heads/dev", "refs/heads/fix/desktop-native-release-guard",
                    "refs/heads/main-extra", "refs/heads/staging-extra", "refs/heads/desktop-v2.3.4",
                    "refs/tags/desktop-dev.61", "refs/tags/android-v2.3.4"):
            with self.subTest(ref=ref):
                self.assertEqual("false", self.run_selector("version", "tag", "push", ref)
                                 ["should_release"])

    def test_detection_builds_release_pushes_and_path_filters_prs_and_dev(self):
        for ref in ("refs/heads/main", "refs/heads/staging", "refs/tags/desktop-v2.3.4"):
            with self.subTest(ref=ref):
                self.assertEqual("true", self.run_selector("detect", "decide", "push", ref)["run"])
        for event, ref in (("pull_request", "refs/pull/24/merge"), ("push", "refs/heads/dev")):
            for changed in ("false", "true"):
                with self.subTest(event=event, ref=ref, changed=changed):
                    self.assertEqual(changed, self.run_selector("detect", "decide", event, ref,
                                                               changed=changed)["run"])

    def test_publisher_requires_push_and_uses_selected_prerelease_state(self):
        publisher = next(step for step in self.workflow["jobs"]["build"]["steps"]
                         if step.get("uses", "").startswith("tauri-apps/tauri-action@"))
        self.assertEqual("${{ github.event_name == 'push' && needs.version.outputs.should_release == 'true' }}",
                         publisher["if"])
        self.assertEqual("${{ needs.version.outputs.is_prerelease == 'true' }}",
                         publisher["with"]["prerelease"])
        self.assertNotIn("workflow_dispatch", self.workflow["on"])

    def test_policy_tests_run_before_detection_and_changes_trigger_builds(self):
        steps = self.workflow["jobs"]["detect"]["steps"]
        test_index = next(i for i, step in enumerate(steps)
                          if "test_desktop_release.py" in step.get("run", ""))
        filter_index = next(i for i, step in enumerate(steps) if step.get("id") == "filter")
        self.assertLess(test_index, filter_index)
        paths = yaml.load(steps[filter_index]["with"]["filters"], Loader=yaml.BaseLoader)["desktop"]
        self.assertIn(".github/scripts/test_desktop_release.py", paths)


if __name__ == "__main__":
    unittest.main()
