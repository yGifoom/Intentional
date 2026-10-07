#!/usr/bin/env python3
"""Build and share the Android prototype; Python 3.9+ standard library only."""
import argparse
import hashlib
import html
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import queue
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlsplit
import zipfile

ROOT = Path(__file__).resolve().parent.parent
APK_NAME = "intentional.apk"


def validate_apk(apk):
    if not apk.is_file():
        raise ValueError(f"APK not found: {apk}")
    try:
        with zipfile.ZipFile(apk) as archive:
            if not {"AndroidManifest.xml", "classes.dex"} <= set(archive.namelist()):
                raise ValueError("Not an Android APK: manifest or DEX missing.")
            if archive.testzip() is not None:
                raise ValueError("APK archive is damaged. Build it again.")
    except zipfile.BadZipFile as error:
        raise ValueError("Not a valid APK archive.") from error


def download_page(size, digest):
    return f"""<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>Try Intentional</title>
<style>
*{{box-sizing:border-box}}body{{margin:0;background:#0d0f18;color:#f5f2fc;font:17px/1.6 system-ui,sans-serif}}
main{{max-width:540px;margin:5vh auto;padding:28px}}.spark{{color:#b99aff;font-size:46px}}
h1{{font-size:40px;line-height:1.15;letter-spacing:-1px}}h1 span{{color:#b99aff}}
p,li{{color:#bab7ca}}.button{{display:block;background:#b99aff;color:#151020;padding:18px;border-radius:20px;text-align:center;text-decoration:none;font-weight:700;margin:28px 0}}
section{{background:#1a1c2c;border:1px solid #383048;border-radius:24px;padding:22px;margin-top:24px}}
h2{{font-size:20px;margin-top:0}}li{{margin:12px 0}}ol{{padding-left:22px}}small{{font-size:13px;color:#aaa6bd}}a{{color:#b99aff}}code{{overflow-wrap:anywhere;font-size:11px}}
</style><main><div class="spark">✦</div><small>INTENTIONAL · ANDROID PROTOTYPE</small>
<h1>A little pause.<br><span>A clearer purpose.</span></h1>
<p>Set an intention before Instagram or YouTube. Choose your time. Check whether scrolling gave you what you needed.</p>
<a class="button" href="{APK_NAME}" download="{APK_NAME}">Download for Android · {size / 1048576:.1f} MB</a>
<small>Android 8.0 or later · Prototype for testing · Not an iPhone app</small>
<section><h2>From download to your first check-in</h2><ol>
<li>Open the downloaded APK. If Android asks, allow this browser to <strong>install unknown apps</strong>, then confirm installation.</li>
<li>Open <strong>Intentional</strong>. Choose <strong>Try the 75-second demo</strong> to explore without special access.</li>
<li>For Instagram and YouTube check-ins, choose <strong>Set up opening check-ins</strong> and enable <strong>Intentional</strong> in Accessibility settings.</li>
</ol><p><small>If Android blocks accessibility for a downloaded app, open Intentional’s Android App info, then its menu and <strong>Allow restricted settings</strong>, if available. Return to Accessibility settings afterward. Device wording varies.</small></p></section>
<section><h2>Your answers stay with you</h2><p>Intentions and ratings stay on your device unless you export them. The app does not read Instagram or YouTube screen content. Your phone’s speech provider may process voice input online.</p></section>
<p><small>Already installed? Install over the existing app to keep your journal. If Android reports a conflicting signature, ask the sender for a build signed with the original key; uninstalling removes local data.</small></p>
<section><h2>Finish your study check-in</h2><p>Tap <strong>Export study data (CSV)</strong> in Intentional. Choose your preferred sharing app and the study recipient to send your openings, motivations, session times, and ratings.</p></section>
<section><h2>Done trying the prototype?</h2><p>Tap <strong>Uninstall Intentional</strong> at the bottom of any app screen. You can export your journal first, then confirm removal with Android. You can also press and hold the app icon and choose <strong>App info → Uninstall</strong>.</p><small>Removing Intentional stops its check-ins and deletes its local journal. Instagram and YouTube stay installed.</small></section>
<details><summary><small>Download verification</small></summary><p><small>SHA-256</small><br><code>{html.escape(digest)}</code></p><a href="SHA256SUMS">Checksum file</a></details>
</main></html>""".encode()


def prepare(apk, destination):
    validate_apk(apk)
    destination.mkdir(parents=True, exist_ok=True)
    bundle = Path(tempfile.mkdtemp(prefix="intentional-", dir=destination))
    target = bundle / APK_NAME
    shutil.copyfile(apk, target)
    digest = hashlib.sha256()
    with target.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    checksum = digest.hexdigest()
    (bundle / "index.html").write_bytes(download_page(target.stat().st_size, checksum))
    (bundle / "SHA256SUMS").write_text(f"{checksum}  {APK_NAME}\n", encoding="utf-8")
    return bundle


def make_handler(bundle, token):
    prefix = f"/{token}/"
    routes = {
        prefix: ("index.html", "text/html; charset=utf-8"),
        prefix + APK_NAME: (APK_NAME, "application/vnd.android.package-archive"),
        prefix + "SHA256SUMS": ("SHA256SUMS", "text/plain; charset=utf-8"),
    }

    class DownloadHandler(BaseHTTPRequestHandler):
        # Exact routes only; no repository files or directory listing.
        def do_GET(self):
            self.respond(body=True)

        def do_HEAD(self):
            self.respond(body=False)

        def respond(self, body):
            route = routes.get(urlsplit(self.path).path)
            if route is None:
                self.send_error(404)
                return
            name, content_type = route
            with (bundle / name).open("rb") as stream:
                self.send_response(200)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(os.fstat(stream.fileno()).st_size))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Referrer-Policy", "no-referrer")
                self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
                if name == APK_NAME:
                    self.send_header("Content-Disposition", f'attachment; filename="{APK_NAME}"')
                self.end_headers()
                if body:
                    try:
                        shutil.copyfileobj(stream, self.wfile)
                    except (BrokenPipeError, ConnectionResetError):
                        pass

        def log_message(self, *_args):
            pass  # Do not log recipient IP addresses or the access token.

    return DownloadHandler


def lan_address():
    try:
        # Select the default interface without sending a UDP packet.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 80))
            return probe.getsockname()[0]
    except OSError:
        return None


def tunnel_url(process, log_path):
    lines = queue.Queue(maxsize=256)

    def read_logs():
        with log_path.open("w", encoding="utf-8") as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                try:
                    lines.put_nowait(line)
                except queue.Full:
                    pass  # Keep draining tunnel output after the startup URL is found.
        try:
            lines.put_nowait(None)
        except queue.Full:
            pass

    threading.Thread(target=read_logs, daemon=True).start()
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        try:
            line = lines.get(timeout=1)
        except queue.Empty:
            continue
        if line is None:
            break
        match = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", line)
        if match:
            return match.group(0)
    raise RuntimeError(f"Could not start the public tunnel. See {log_path}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="Share an existing signed APK instead of building and testing")
    parser.add_argument("--public", action="store_true", help="Create a temporary HTTPS link using installed cloudflared")
    parser.add_argument("--prepare", action="store_true", help="Only create the download bundle; do not serve it")
    parser.add_argument("--port", type=int, default=8765, help="Server port (default 8765; 0 selects an available port)")
    parser.add_argument("--host", help="LAN IP/hostname for the printed link, if automatic detection is wrong")
    args = parser.parse_args(argv)
    if args.public and args.prepare:
        parser.error("--public and --prepare cannot be combined")
    if not 0 <= args.port <= 65535:
        parser.error("--port must be between 0 and 65535")
    if args.public and not shutil.which("cloudflared"):
        raise ValueError("Public links need cloudflared. Install it from https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/ then rerun with --public.")
    apk = args.apk.expanduser().resolve() if args.apk else ROOT / "androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    if not args.apk:
        print("Testing session logic and building the signed debug APK…", flush=True)
        command = [str(ROOT / "gradlew.bat")] if os.name == "nt" else ["sh", str(ROOT / "gradlew")]
        # Build failures stop distribution; never silently serve a stale APK.
        subprocess.run(command + [":shared:jvmTest", ":androidApp:assembleDebug"], cwd=ROOT, check=True)
    bundle = prepare(apk, ROOT / "dist")
    print(f"Download bundle: {bundle}", flush=True)
    if args.prepare:
        print("Upload these three files together to a static host for a permanent download page.")
        return 0
    token = secrets.token_urlsafe(24)
    bind = "127.0.0.1" if args.public else "0.0.0.0"
    process = None
    with ThreadingHTTPServer((bind, args.port), make_handler(bundle, token)) as server:
        server.daemon_threads = True
        port = server.server_port
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            if args.public:
                print("Creating temporary public link…", flush=True)
                process = subprocess.Popen(
                    ["cloudflared", "tunnel", "--url", f"http://127.0.0.1:{port}"],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                )
                base = tunnel_url(process, bundle / "tunnel.log")
            else:
                host = args.host or lan_address()
                if not host:
                    raise RuntimeError("Could not determine the Wi-Fi address. Rerun with --host YOUR_COMPUTER_IP.")
                base = f"http://{host}:{port}"
            link = f"{base}/{token}/"
            print(f"\nShare this download page:\n{link}\n\nDirect APK link:\n{link}{APK_NAME}\n", flush=True)
            print("Anyone with the link can download. Keep this terminal open; Ctrl+C stops sharing.", flush=True)
            if not args.public:
                print("Phones must be on the same network. If unreachable, check firewall and Wi-Fi client isolation.", flush=True)
            while True:
                time.sleep(1)
                if process is not None and process.poll() is not None:
                    raise RuntimeError(f"The public tunnel stopped. See {bundle / 'tunnel.log'}")
        finally:
            if process is not None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            server.shutdown()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\nSharing stopped. Prepared files remain in dist/.")
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"\nCannot share the app: {error}", file=sys.stderr)
        sys.exit(1)
