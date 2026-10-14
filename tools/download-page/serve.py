#!/usr/bin/env python3
"""Local preview of the download page: serves the page and the APKs of release/.

    python3 tools/download-page/serve.py --port 8080

Only the page, the APKs listed in release/SHA256SUMS.txt, the checksum file and the two
fonts are served — never the rest of the repository. The QR code on the page points at the
address the page was opened with, so a phone can scan it from a computer screen.
"""
from __future__ import annotations

import argparse
import http.server
import shutil
import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_page  # noqa: E402

ROOT = build_page.ROOT
FONTS = {build_page.FONT_REGULAR, build_page.FONT_BOLD}
APK_TYPE = "application/vnd.android.package-archive"


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "YadinoDownload/1"

    def do_GET(self) -> None:  # noqa: N802 (http.server naming)
        self.respond(head=False)

    def do_HEAD(self) -> None:  # noqa: N802
        self.respond(head=True)

    def respond(self, head: bool) -> None:
        path = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path).lstrip("/")
        if path in ("", "index.html"):
            host = self.headers.get("X-Forwarded-Host") or self.headers.get("Host") or "localhost"
            proto = self.headers.get("X-Forwarded-Proto") or ("https" if host.endswith(".e2b.app") else "http")
            body = build_page.render(f"{proto}://{host}/").encode("utf-8")
            self.send_bytes(body, "text/html; charset=utf-8", head)
            return
        names = {f.name for f in build_page.release_info().files}
        if path.startswith("release/") and path[len("release/"):] in names:
            name = path[len("release/"):]
            self.send_file(ROOT / "release" / name, APK_TYPE, head, attachment=name)
        elif path == "release/SHA256SUMS.txt":
            self.send_file(ROOT / path, "text/plain; charset=utf-8", head)
        elif path in FONTS:
            self.send_file(ROOT / path, "font/ttf", head, cache="public, max-age=86400")
        else:
            self.send_bytes("یافت نشد".encode("utf-8"), "text/plain; charset=utf-8", head, status=404)

    def send_bytes(self, body: bytes, content_type: str, head: bool, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if not head:
            self.wfile.write(body)

    def send_file(self, file: Path, content_type: str, head: bool, attachment: str | None = None,
                  cache: str = "no-cache") -> None:
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(file.stat().st_size))
        self.send_header("Cache-Control", cache)
        if attachment:
            self.send_header("Content-Disposition", f'attachment; filename="{attachment}"')
        self.end_headers()
        if not head:
            with file.open("rb") as f:
                shutil.copyfileobj(f, self.wfile, 64 * 1024)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    server = http.server.ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"download page on http://{args.host}:{args.port}/", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
