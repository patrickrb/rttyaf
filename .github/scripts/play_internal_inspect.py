#!/usr/bin/env python3
"""Inspect RTTYAF internal track using a disposable edit; never commit or publish."""

import json
import os
from datetime import datetime, timezone
from pathlib import Path

from internal_approval import require_source

PACKAGE = "radio.ks3ckc.rttyaf"
EXPECTED_VERSION_CODE = "1062"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"


def inspect_internal(session):
    receipt = {
        "packageName": PACKAGE,
        "track": "internal",
        "expectedVersionCode": EXPECTED_VERSION_CODE,
        "observedAt": datetime.now(timezone.utc).isoformat(),
        "editId": None,
        "editDeleted": False,
        "internalReleases": [],
        "matchingReleases": [],
        "errors": [],
        "testerAvailability": "Enrollment, propagation and device access not verified",
        "versionName": "Not exposed by tracks API; inspect existing APK separately",
    }
    try:
        opened = session.post(f"{API}/edits", timeout=30)
        opened.raise_for_status()
        receipt["editId"] = opened.json()["id"]
        response = session.get(f"{API}/edits/{receipt['editId']}/tracks/internal", timeout=30)
        response.raise_for_status()
        receipt["internalReleases"] = [
            {key: release[key] for key in ("name", "status", "versionCodes", "userFraction")
             if key in release}
            for release in response.json().get("releases", [])
        ]
        receipt["matchingReleases"] = [
            release for release in receipt["internalReleases"]
            if EXPECTED_VERSION_CODE in [str(code) for code in release.get("versionCodes", [])]
        ]
    except Exception as error:
        # Do not print response bodies or credentials. A POST transport failure
        # can leave an unknown edit; no automatic retry is safe.
        receipt["errors"].append(f"Inspection failed: {type(error).__name__}")
    finally:
        if receipt["editId"] is not None:
            try:
                deleted = session.delete(f"{API}/edits/{receipt['editId']}", timeout=30)
                deleted.raise_for_status()
                receipt["editDeleted"] = True
            except Exception as error:
                receipt["errors"].append(f"DELETE edit failed: {type(error).__name__}")
        else:
            receipt["errors"].append("No edit ID received; DELETE could not be attempted")
    return receipt


def main():
    require_source(os.environ.get("GITHUB_EVENT_NAME"), os.environ.get("GITHUB_REF", ""),
                   os.environ.get("GITHUB_SHA", ""), os.environ.get("REVIEWED_SHA"),
                   os.environ.get("GITHUB_REPOSITORY"))
    from google.auth.transport.requests import AuthorizedSession
    from google.oauth2 import service_account

    credentials = service_account.Credentials.from_service_account_info(
        json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]),
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    with AuthorizedSession(credentials) as session:
        receipt = inspect_internal(session)
    receipt["inspectionSourceSha"] = os.environ["GITHUB_SHA"]
    serialized = json.dumps(receipt, indent=2, sort_keys=True)
    Path(os.environ["INSPECTION_OUTPUT"]).write_text(serialized + "\n")
    print(serialized)
    if receipt["errors"]:
        raise SystemExit("Inspection or DELETE failed; inspect receipt before any retry")


if __name__ == "__main__":
    main()
