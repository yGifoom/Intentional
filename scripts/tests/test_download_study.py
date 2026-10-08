import base64
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("download_study", Path(__file__).parents[1] / "download-study.py")
download = importlib.util.module_from_spec(spec)
spec.loader.exec_module(download)


class DownloadTests(unittest.TestCase):
    participant = "12345678-1234-1234-1234-123456789abc"
    submission = "12345678-1234-1234-1234-123456789001"

    def document(self, data=None):
        if data is None:
            data = ("\ufeffparticipant_id,schema_version,record_type,record_id,exported_at_utc_ms,motivation\r\n"
                    f'{self.participant},1,metadata,study,100,"Répondre 🌿"\r\n').encode()
        return {"name": f"projects/demo-study/databases/(default)/documents/intentionalStudyParticipants/u/intentionalStudyExports/{self.submission}",
                "fields": {"participantId": {"stringValue": self.participant},
                           "csv": {"bytesValue": base64.b64encode(data).decode()},
                           "sha256": {"stringValue": hashlib.sha256(data).hexdigest()},
                           "schemaVersion": {"integerValue": "1"}, "contentType": {"stringValue": "text/csv"}}}

    def test_unicode_round_trip(self):
        name, data = download.decode_export(self.document())
        self.assertEqual(f"{self.participant}-{self.submission}.csv", name)
        self.assertIn("Répondre 🌿", data.decode("utf-8-sig"))

    def test_latest_csv_uses_a_stable_per_participant_filename(self):
        doc = self.document()
        doc["name"] = doc["name"].rsplit("/", 1)[0] + "/latest"
        name, _ = download.decode_export(doc)
        self.assertEqual(f"{self.participant}-latest.csv", name)

    def test_bad_checksum_and_oversize_are_rejected(self):
        doc = self.document(); doc["fields"]["sha256"]["stringValue"] = "a" * 64
        with self.assertRaises(ValueError): download.decode_export(doc)
        with self.assertRaises(ValueError): download.decode_export(self.document(b"a" * 524289))

    def test_wrong_participant_and_malformed_csv_are_rejected(self):
        for data in [b"anything\n", b"participant_id,schema_version,record_type,record_id,exported_at_utc_ms\nwrong,1,metadata,study,100\n"]:
            with self.assertRaises(ValueError): download.decode_export(self.document(data))

    def test_unsafe_file_identity_is_rejected(self):
        doc = self.document(); doc["fields"]["participantId"]["stringValue"] = "../../outside"
        with self.assertRaises(ValueError): download.decode_export(doc)

    def test_pagination_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "exports"
            cursors = []
            def fetch(after):
                cursors.append(after)
                return [] if after else [self.document()]
            self.assertEqual(1, download.download("demo-study", output, fetch))
            self.assertEqual([None, self.document()["name"]], cursors)
            with self.assertRaises(FileExistsError): download.download("demo-study", output, fetch)

    def test_invalid_project_cannot_be_used_in_request_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError): download.download("../other", Path(directory) / "exports", lambda after: [])


if __name__ == "__main__":
    unittest.main()
