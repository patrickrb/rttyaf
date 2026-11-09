#!/usr/bin/env python3
"""Require independent human approval of the exact internal-dispatch commit."""

import json
import os
import re
import subprocess
from urllib.parse import quote

REPOSITORY = "patrickrb/rttyaf"
REVIEWER = "Reid-n0rc"


def require_source(event, ref, sha, reviewed_sha, repository):
    if (event != "workflow_dispatch" or not ref.startswith("refs/heads/feat/")
            or repository != REPOSITORY or not re.fullmatch(r"[0-9a-f]{40}", sha)
            or sha != reviewed_sha):
        raise ValueError("Internal dispatch requires the exact reviewed feat/* SHA in RTTYAF")


def require_approval(pr, reviews, permission, sha):
    if (pr["state"] != "open" or pr["draft"] or pr["head"]["sha"] != sha
            or pr["mergeable_state"] != "clean"
            or pr["head"]["repo"]["full_name"] != REPOSITORY
            or pr["base"]["repo"]["full_name"] != REPOSITORY
            or pr["base"]["ref"] != "main"
            or pr["user"]["login"].lower() == REVIEWER.lower()):
        raise ValueError("Require an open, ready, clean same-repository PR for this exact source")
    if permission not in ("write", "maintain", "admin"):
        raise ValueError("Required independent reviewer must retain write access")
    decisions = [review for review in reviews
                 if review["user"]["login"].lower() == REVIEWER.lower()
                 and review["state"] in ("APPROVED", "CHANGES_REQUESTED", "DISMISSED")]
    # GitHub lists reviews chronologically. Comments do not replace a decision;
    # dismissal, a newer changes-request, or approval of an older SHA must fail.
    if (not decisions or decisions[-1]["state"] != "APPROVED"
            or decisions[-1]["commit_id"] != sha):
        raise ValueError("Reid-n0rc must approve this exact commit; agent review is insufficient")


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))


def main():
    sha = os.environ["GITHUB_SHA"]
    ref = os.environ["GITHUB_REF"]
    require_source(os.environ["GITHUB_EVENT_NAME"], ref, sha,
                   os.environ["REVIEWED_SHA"], os.environ["GITHUB_REPOSITORY"])
    head = quote("patrickrb:" + ref.removeprefix("refs/heads/"), safe="")
    prs = api(f"repos/{REPOSITORY}/pulls?state=open&head={head}&per_page=100")
    if len(prs) != 1:
        raise ValueError("Require exactly one open PR for the dispatched feature branch")
    # Fail closed for GitHub's pending/blocked/behind/unknown mergeability.
    # This checks eligibility only; the internal lane NEVER merges the PR.
    pr = api(f"repos/{REPOSITORY}/pulls/{prs[0]['number']}")
    pages = json.loads(subprocess.check_output([
        "gh", "api", "--paginate", "--slurp",
        f"repos/{REPOSITORY}/pulls/{pr['number']}/reviews?per_page=100"], text=True))
    permission = api(f"repos/{REPOSITORY}/collaborators/{REVIEWER}/permission")["permission"]
    require_approval(pr, [review for page in pages for review in page], permission, sha)
    print(f"Verified {REVIEWER}'s approval of {sha} on {pr['html_url']}; INTERNAL ONLY")


if __name__ == "__main__":
    main()
