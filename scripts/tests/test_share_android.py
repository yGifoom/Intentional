import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("share_android", Path(__file__).parents[1] / "share_android.py")
share = importlib.util.module_from_spec(spec)
spec.loader.exec_module(share)


class FakeConnection:
    def __init__(self, request):
        self.request = io.BytesIO(request)
        self.response = bytearray()

    def makefile(self, *_args):
        return self.request

    def sendall(self, data):
        self.response.extend(data)


class DistributionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "fixture.apk"
        # Structural fixture only, deliberately not an installable APK.
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"test manifest")
            archive.writestr("classes.dex", b"test dex")
        self.bundle = share.prepare(self.apk, self.root / "dist")

    def request(self, path, method="GET"):
        connection = FakeConnection(f"{method} {path} HTTP/1.0\r\nHost: localhost\r\n\r\n".encode())
        share.make_handler(self.bundle, "test-token")(connection, ("127.0.0.1", 1), object())
        return bytes(connection.response).split(b"\r\n\r\n", 1)

    def test_bundle_has_exact_files_and_matching_checksum(self):
        self.assertEqual({"index.html", "intentional.apk", "SHA256SUMS"}, {p.name for p in self.bundle.iterdir()})
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.assertEqual(f"{digest}  intentional.apk\n", (self.bundle / "SHA256SUMS").read_text())
        self.assertIn(digest, (self.bundle / "index.html").read_text())

    def test_existing_bundle_is_preserved(self):
        other = share.prepare(self.apk, self.root / "dist")
        self.assertNotEqual(other, self.bundle)
        self.assertTrue((self.bundle / "intentional.apk").exists())

    def test_rejects_non_apk(self):
        bad = self.root / "invalid.apk"
        bad.write_bytes(b"not an apk")
        with self.assertRaises(ValueError):
            share.prepare(bad, self.root / "bad-output")
        self.assertFalse((self.root / "bad-output").exists())

    def test_rejects_zip_without_dex(self):
        bad = self.root / "invalid.zip"
        with zipfile.ZipFile(bad, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"test")
        with self.assertRaises(ValueError):
            share.validate_apk(bad)

    def test_download_returns_exact_apk_with_android_mime(self):
        headers, body = self.request("/test-token/intentional.apk")
        self.assertIn(b"200 OK", headers)
        self.assertIn(b"application/vnd.android.package-archive", headers)
        self.assertIn(b'attachment; filename="intentional.apk"', headers)
        self.assertEqual(self.apk.read_bytes(), body)

    def test_page_contains_relative_download_and_installation_instructions(self):
        headers, body = self.request("/test-token/")
        self.assertIn(b"200 OK", headers)
        self.assertIn(b'href="intentional.apk"', body)
        self.assertIn(b"Accessibility", body)
        self.assertIn(b"install unknown apps", body)

    def test_head_returns_length_without_body(self):
        headers, body = self.request("/test-token/intentional.apk", "HEAD")
        self.assertIn(f"Content-Length: {self.apk.stat().st_size}".encode(), headers)
        self.assertEqual(b"", body)

    def test_repository_and_traversal_paths_are_never_served(self):
        for path in ["/", "/intentional.apk", "/wrong/intentional.apk", "/test-token/../README.md", "/test-token/%2e%2e/README.md", "/test-token/tunnel.log"]:
            with self.subTest(path=path):
                headers, _ = self.request(path)
                self.assertIn(b"404", headers)

    def test_prepare_mode_needs_neither_build_nor_socket(self):
        with patch.object(share, "ROOT", self.root), patch.object(share.subprocess, "run") as build, patch.object(share, "ThreadingHTTPServer") as server, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, share.main(["--apk", str(self.apk), "--prepare"]))
            build.assert_not_called()
            server.assert_not_called()

    def test_failed_build_does_not_share_a_stale_apk(self):
        with patch.object(share.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "gradle")), patch.object(share, "prepare") as prepare, contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(subprocess.CalledProcessError):
                share.main([])
            prepare.assert_not_called()

    def test_extracts_public_link_from_tunnel_output(self):
        process = SimpleNamespace(stdout=io.StringIO("Starting tunnel\nVisit https://demo-example.trycloudflare.com now\n"))
        self.assertEqual("https://demo-example.trycloudflare.com", share.tunnel_url(process, self.root / "tunnel.log"))

    def test_tunnel_failure_reports_log_location(self):
        process = SimpleNamespace(stdout=io.StringIO("Failed to reach provider\n"))
        with self.assertRaisesRegex(RuntimeError, "tunnel.log"):
            share.tunnel_url(process, self.root / "tunnel.log")


if __name__ == "__main__":
    unittest.main()
