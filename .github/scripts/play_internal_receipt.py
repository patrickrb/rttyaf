#!/usr/bin/env python3
"""Read committed Play state for RTTYAF's internal lane; never modify tracks."""

import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path

from internal_approval import require_source

PACKAGE = "radio.ks3ckc.rttyaf"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"


def fetch_tracks(session):
    # An uncommitted edit reads currently committed state. Delete it, never commit
    # it or send a tracks.update request. Do not reuse the publisher's edit.
    response = session.post(f"{API}/edits", timeout=30)
    response.raise_for_status()
    edit_id = response.json()["id"]
    try:
        tracks = {}
        for track in ("internal", "production"):
            response = session.get(f"{API}/edits/{edit_id}/tracks/{track}", timeout=30)
            if response.status_code == 404:
                tracks[track] = []
                continue
            response.raise_for_status()
            data = response.json()
            if data.get("track") != track:
                raise ValueError("Play returned an unexpected track")
            # Preserve all release fields so a change in production's status,
            # fraction, notes, etc. also fails the before/after comparison.
            tracks[track] = sorted(data.get("releases", []),
                                   key=lambda release: json.dumps(release, sort_keys=True))
        return {"packageName": PACKAGE, "tracks": tracks}
    finally:
        response = session.delete(f"{API}/edits/{edit_id}", timeout=30)
        response.raise_for_status()


def build_receipt(before, after, metadata, release_name, source_sha):
    if (before["packageName"] != PACKAGE or after["packageName"] != PACKAGE
            or metadata["applicationId"] != PACKAGE):
        raise ValueError("Package identity does not match RTTYAF")
    if metadata["variantName"] != "release" or len(metadata["elements"]) != 1:
        raise ValueError("Expected one release APK version")
    version = str(metadata["elements"][0]["versionCode"])
    matches = [release for release in after["tracks"]["internal"]
               if version in [str(code) for code in release.get("versionCodes", [])]
               and release.get("name") == release_name]
    production_unchanged = before["tracks"]["production"] == after["tracks"]["production"]
    statuses = [release.get("status") for release in matches]
    verified = len(matches) == 1 and statuses == ["completed"] and production_unchanged
    return {
        "packageName": PACKAGE,
        "sourceSha": source_sha,
        "track": "internal",
        "versionCode": version,
        "versionName": metadata["elements"][0]["versionName"],
        "releaseName": release_name,
        "observedStatuses": statuses,
        "verified": verified,
        "productionUnchanged": production_unchanged,
        "productionBefore": before["tracks"]["production"],
        "productionAfter": after["tracks"]["production"],
        "internalReleases": after["tracks"]["internal"],
        "testerRollout": ("completed internal track release; enrollment/propagation unverified"
                          if statuses == ["completed"] else "not confirmed; draft is not rollout"),
        "deviceAvailability": "not verified",
        "physicalUsbCatPttRf": "not tested",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    before_parser = sub.add_parser("before")
    before_parser.add_argument("output", type=Path)
    after_parser = sub.add_parser("after")
    after_parser.add_argument("before", type=Path)
    after_parser.add_argument("metadata", type=Path)
    after_parser.add_argument("release_name")
    after_parser.add_argument("output", type=Path)
    args = parser.parse_args()
    require_source(os.environ.get("GITHUB_EVENT_NAME"), os.environ.get("GITHUB_REF", ""),
                   os.environ.get("GITHUB_SHA", ""), os.environ.get("REVIEWED_SHA"),
                   os.environ.get("GITHUB_REPOSITORY"))

    # Same existing service-account secret as the publisher. Never print it,
    # materialize it on disk, or export its access token to workflow outputs.
    from google.auth.transport.requests import AuthorizedSession
    from google.oauth2 import service_account

    credentials = service_account.Credentials.from_service_account_info(
        json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]),
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    with AuthorizedSession(credentials) as session:
        current = fetch_tracks(session)
    if args.command == "after":
        result = build_receipt(json.loads(args.before.read_text()), current,
                               json.loads(args.metadata.read_text()), args.release_name,
                               os.environ["GITHUB_SHA"])
    else:
        result = current
    result["observedAt"] = datetime.now(timezone.utc).isoformat()
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(json.dumps(result, sort_keys=True))
    if args.command == "after" and not result["verified"]:
        raise SystemExit("Internal delivery unverified or production state changed; see receipt")


if __name__ == "__main__":
    main()
