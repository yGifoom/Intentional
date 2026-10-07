#!/usr/bin/env python3
"""Combine Intentional study CSV exports, deduplicate, and summarize by participant/app."""
import argparse
import csv
from collections import defaultdict
from pathlib import Path
import statistics
import sys


def aggregate(inputs, output):
    records = {}
    columns = None
    for path in inputs:
        with path.open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream)
            required = {"schema_version", "participant_id", "exported_at_utc_ms", "record_type", "record_id", "app", "is_demo"}
            if not required <= set(reader.fieldnames or []):
                raise ValueError(f"Not an Intentional study export: {path}")
            if columns is not None and columns != reader.fieldnames:
                raise ValueError(f"CSV columns differ: {path}")
            columns = reader.fieldnames
            for row in reader:
                if None in row or any(value is None for value in row.values()):
                    raise ValueError(f"Malformed CSV row in {path}")
                if row["schema_version"] != "1" or not row["participant_id"]:
                    raise ValueError(f"Unsupported schema or missing participant ID in {path}")
                if row["record_type"] not in {"metadata", "opening", "session", "extension"}:
                    raise ValueError(f"Unknown record type in {path}")
                key = (row["participant_id"], row["record_type"], row["record_id"])
                previous = records.get(key)
                # Repeated exports update partial sessions rather than counting them twice.
                if previous is None or int(row["exported_at_utc_ms"]) > int(previous["exported_at_utc_ms"]):
                    records[key] = row
    if columns is None:
        raise ValueError("No CSV exports found")
    # Never overwrite an existing analysis or an input export.
    output.mkdir(parents=True, exist_ok=False)
    rows = sorted(records.values(), key=lambda row: (row["participant_id"], row["record_type"], row["record_id"]))
    with (output / "all_records.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader(); writer.writerows(rows)
    groups = defaultdict(list)
    for row in rows:
        if row["record_type"] == "metadata":
            for app in ("Instagram", "YouTube"):
                groups[(row["participant_id"], app)]
        elif row["is_demo"] == "false":
            groups[(row["participant_id"], row["app"])].append(row)

    def mean(values):
        return round(statistics.mean(values), 3) if values else ""

    summaries = []
    for (participant, app), group in sorted(groups.items()):
        openings = [r for r in group if r["record_type"] == "opening"]
        sessions = [r for r in group if r["record_type"] == "session"]
        completed = [r for r in sessions if r["status"] == "completed"]
        ended = [r for r in sessions if r["status"] in {"completed", "inactivity_timeout"}]
        paired = [r for r in completed if r["relaxation_before"] not in {"", "N/A"} and r["relaxation_after"] not in {"", "N/A"}]
        summaries.append(dict(
            participant_id=participant, app=app, opening_attempts=len(openings),
            foreground_entry_attempts=sum(r["opening_source"] == "foreground_entry" for r in openings),
            intentional_launcher_attempts=sum(r["opening_source"] == "intentional_launcher" for r in openings),
            sessions_started=len(sessions), sessions_completed=len(completed),
            sessions_ended_without_response=sum(r["status"] == "inactivity_timeout" for r in sessions),
            total_ended_tracked_seconds=round(sum(float(r["tracked_elapsed_seconds"]) for r in ended), 3),
            mean_ended_tracked_seconds=mean([float(r["tracked_elapsed_seconds"]) for r in ended]),
            intentions_fulfilled=sum(r["task_completed"] == "true" for r in completed),
            mean_selected_minutes=mean([float(r["selected_minutes"]) for r in sessions]),
            total_completed_tracked_seconds=round(sum(float(r["tracked_elapsed_seconds"]) for r in completed), 3),
            mean_completed_tracked_seconds=mean([float(r["tracked_elapsed_seconds"]) for r in completed]),
            paired_rating_count=len(paired),
            mean_relaxation_before=mean([float(r["relaxation_before"]) for r in paired]),
            mean_relaxation_after=mean([float(r["relaxation_after"]) for r in paired]),
            mean_relaxation_change=mean([float(r["relaxation_after"]) - float(r["relaxation_before"]) for r in paired]),
        ))
    if summaries:
        with (output / "participant_app_summary.csv").open("w", encoding="utf-8-sig", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(summaries[0]))
            writer.writeheader(); writer.writerows(summaries)
    return len(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("exports", type=Path, help="Folder containing participant CSV attachments (searched recursively)")
    parser.add_argument("--output", type=Path, default=Path("study-results"), help="New folder for results; must not already exist")
    args = parser.parse_args()
    inputs = sorted(args.exports.rglob("*.csv"))
    count = aggregate(inputs, args.output)
    print(f"Saved {count} unique records and participant/app summaries to {args.output}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, csv.Error) as error:
        print(f"Cannot aggregate study data: {error}", file=sys.stderr)
        sys.exit(1)
