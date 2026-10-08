#!/usr/bin/env python3
"""Download private Firebase study CSVs with a researcher's Google Cloud identity.

Requires gcloud CLI + roles/datastore.viewer (or a narrower equivalent).
Never put this credential or a service-account key into the Android app.
"""
import argparse
import base64
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import urllib.request

MAX_CSV_BYTES = 524288
UUID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z")


def decode_export(document):
    fields = document["fields"]
    participant = fields["participantId"]["stringValue"]
    submission = document["name"].rsplit("/", 1)[-1]
    if not UUID.fullmatch(participant) or (submission != "latest" and not UUID.fullmatch(submission)):
        raise ValueError("Invalid submission identity")
    data = base64.b64decode(fields["csv"]["bytesValue"], validate=True)
    if not 0 < len(data) <= MAX_CSV_BYTES:
        raise ValueError("CSV exceeds the upload limit")
    if hashlib.sha256(data).hexdigest() != fields["sha256"]["stringValue"]:
        raise ValueError("CSV checksum does not match")
    if fields["contentType"]["stringValue"] != "text/csv" or fields["schemaVersion"]["integerValue"] != "1":
        raise ValueError("Unsupported submission type")
    reader = csv.DictReader(io.StringIO(data.decode("utf-8-sig")))
    required = {"participant_id", "schema_version", "record_type", "record_id", "exported_at_utc_ms"}
    if not required <= set(reader.fieldnames or []):
        raise ValueError("Invalid study CSV header")
    count = 0
    for row in reader:
        if None in row or any(value is None for value in row.values()):
            raise ValueError("Malformed study CSV")
        if row["participant_id"] != participant or row["schema_version"] != "1":
            raise ValueError("CSV identity/schema mismatch")
        if row["record_type"] not in {"metadata", "opening", "session", "extension"}:
            raise ValueError("Unknown study record type")
        count += 1
    if count == 0:
        raise ValueError("Study CSV has no records")
    return f"{participant}-{submission}.csv", data


def fetch_page(project, token, after=None):
    query = {"from": [{"collectionId": "intentionalStudyExports", "allDescendants": True}],
             "orderBy": [{"field": {"fieldPath": "__name__"}, "direction": "ASCENDING"}], "limit": 25}
    if after:
        query["startAt"] = {"values": [{"referenceValue": after}], "before": False}
    request = urllib.request.Request(
        f"https://firestore.googleapis.com/v1/projects/{project}/databases/(default)/documents:runQuery",
        data=json.dumps({"structuredQuery": query}).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        result = json.load(response)
    return [entry["document"] for entry in result if "document" in entry]


def download(project, output, fetch):
    if not re.fullmatch(r"[a-z][a-z0-9-]{4,61}[a-z0-9]", project):
        raise ValueError("Invalid Firebase project ID")
    output.mkdir(parents=True, exist_ok=False)
    after = None
    saved = 0
    while True:
        documents = fetch(after)
        if not documents:
            return saved
        for document in documents:
            name, data = decode_export(document)
            with (output / name).open("xb") as stream:
                stream.write(data)
            saved += 1
        cursor = documents[-1]["name"]
        if after == cursor:
            raise ValueError("Server returned a repeated page")
        after = cursor


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="Firebase/Google Cloud project ID")
    parser.add_argument("--output", type=Path, required=True, help="New private output folder; never overwritten")
    args = parser.parse_args()
    # Token stays in memory; it is neither printed nor written into the repository.
    token = subprocess.run(["gcloud", "auth", "print-access-token"], check=True, capture_output=True, text=True).stdout.strip()
    count = download(args.project, args.output, lambda after: fetch_page(args.project, token, after))
    print(f"Downloaded {count} CSV exports into {args.output}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, csv.Error, subprocess.CalledProcessError):
        print("Download failed. Check Google Cloud access, project ID, and CSV integrity. Partial downloads, if any, were kept; rerun into a new folder.", file=sys.stderr)
        sys.exit(1)
