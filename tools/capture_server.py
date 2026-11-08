"""Local HTTP endpoint for testing notifiers: logs every request as one JSON line.

Usage: python tools/capture_server.py <out.jsonl> [port]   (listens on 127.0.0.1, default port 8765)
Point a webhook/ntfy/Discord notifier at http://127.0.0.1:<port>/... (local http is allowed).
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

OUT = sys.argv[1] if len(sys.argv) > 1 else "capture.jsonl"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8765


class Handler(BaseHTTPRequestHandler):
    def _handle(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8", "replace") if length else ""
        entry = {"t": time.time(), "method": self.command, "path": self.path,
                 "headers": {k: v for k, v in self.headers.items()}, "body": body}
        with open(OUT, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"{}")

    do_POST = do_PUT = do_PATCH = do_GET = _handle

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
