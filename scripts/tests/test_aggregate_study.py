import csv
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("aggregate_study", Path(__file__).parents[1] / "aggregate-study.py")
aggregate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(aggregate)


class AggregateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def row(self, kind, id, exported=100, **values):
        return dict(schema_version="1", participant_id="p1", exported_at_utc_ms=str(exported),
                    record_type=kind, record_id=str(id), app="YouTube", is_demo="false",
                    opening_source="foreground_entry", status="completed", task_completed="true",
                    selected_minutes="15", tracked_elapsed_seconds="120", relaxation_before="2",
                    relaxation_after="4", motivation='A "video", then\nrelax', **values)

    def write(self, name, rows):
        path = self.root / name
        with path.open("w", encoding="utf-8-sig", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader(); writer.writerows(rows)
        return path

    def read_summary(self, output):
        with (output / "participant_app_summary.csv").open(encoding="utf-8-sig", newline="") as stream:
            return list(csv.DictReader(stream))

    def test_repeated_exports_are_deduplicated_and_latest_session_wins(self):
        old = self.row("session", 2); old.update(status="active", relaxation_after="", tracked_elapsed_seconds="60")
        newer = self.row("session", 2, exported=200)
        first = self.write("first.csv", [self.row("opening", 1), old])
        second = self.write("second.csv", [self.row("opening", 1, exported=200), newer])
        output = self.root / "results"
        self.assertEqual(2, aggregate.aggregate([second, first, first], output))
        summary = self.read_summary(output)[0]
        self.assertEqual("1", summary["opening_attempts"])
        self.assertEqual("1", summary["sessions_completed"])
        self.assertEqual("2.0", summary["mean_relaxation_change"])
        self.assertEqual("120.0", summary["total_completed_tracked_seconds"])
        with (output / "all_records.csv").open(encoding="utf-8-sig", newline="") as stream:
            rows = list(csv.DictReader(stream))
        self.assertEqual(newer["motivation"], rows[1]["motivation"])

    def test_demo_and_unfinished_sessions_do_not_pollute_outcomes(self):
        metadata = self.row("metadata", "study")
        demo = self.row("session", 2); demo["is_demo"] = "true"
        active = self.row("session", 3); active.update(status="active", relaxation_after="")
        file = self.write("data.csv", [metadata, demo, active])
        output = self.root / "results"
        aggregate.aggregate([file], output)
        youtube = next(row for row in self.read_summary(output) if row["app"] == "YouTube")
        self.assertEqual("1", youtube["sessions_started"])
        self.assertEqual("0", youtube["sessions_completed"])
        self.assertEqual("", youtube["mean_relaxation_change"])

    def test_same_record_ids_from_different_participants_are_preserved(self):
        first = self.row("opening", 1)
        second = dict(first, participant_id="p2")
        file = self.write("data.csv", [first, second])
        self.assertEqual(2, aggregate.aggregate([file], self.root / "results"))

    def test_timeout_time_is_included_without_treating_na_as_failure_or_rating(self):
        timed_out = self.row("session", 3)
        timed_out.update(status="inactivity_timeout", task_completed="N/A", relaxation_after="N/A", tracked_elapsed_seconds="45")
        file = self.write("data.csv", [self.row("session", 2), timed_out])
        output = self.root / "results"
        aggregate.aggregate([file], output)
        summary = self.read_summary(output)[0]
        self.assertEqual("1", summary["sessions_ended_without_response"])
        self.assertEqual("165.0", summary["total_ended_tracked_seconds"])
        self.assertEqual("1", summary["sessions_completed"])
        self.assertEqual("1", summary["paired_rating_count"])
        self.assertEqual("2.0", summary["mean_relaxation_change"])

    def test_existing_output_is_not_overwritten(self):
        file = self.write("data.csv", [self.row("opening", 1)])
        output = self.root / "results"; output.mkdir()
        with self.assertRaises(FileExistsError):
            aggregate.aggregate([file], output)

    def test_unrelated_csv_is_rejected(self):
        file = self.root / "wrong.csv"; file.write_text("name,age\nA,3\n")
        with self.assertRaises(ValueError):
            aggregate.aggregate([file], self.root / "results")


if __name__ == "__main__":
    unittest.main()
