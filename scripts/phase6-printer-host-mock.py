#!/usr/bin/env python3
"""Small, deterministic OctoPrint/PrusaLink upload mock for Phase 6 E2E tests."""

import argparse
import base64
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


class State:
    def __init__(self, mode):
        self.mode = mode
        self.requests = []
        self.lock = threading.Lock()

    def add(self, request):
        with self.lock:
            self.requests.append(request)

    def clear(self):
        with self.lock:
            self.requests.clear()

    def snapshot(self):
        with self.lock:
            return list(self.requests)


def response_for(mode, method, path):
    if method == "GET" and path == "/api/version":
        if mode == "octoprint":
            return 200, {"api": "1.1", "server": "1.10.0", "text": "OctoPrint 1.10.0"}
        return 200, {"api": "1.0", "server": "PrusaLink 0.7.0", "text": "PrusaLink 0.7.0"}
    if mode == "octoprint" and method == "GET" and path == "/api/job":
        return 200, {"state": "Operational", "progress": {"completion": 0, "printTime": 0}, "job": {"file": {"name": ""}}}
    if mode == "prusalink" and method == "GET" and path == "/api/v1/status":
        return 200, {"printer": {"state": "IDLE", "temp_nozzle": 0, "target_nozzle": 0, "temp_bed": 0, "target_bed": 0}, "job": {"progress": 0, "time_printing": 0, "file": {"name": "", "display_name": ""}}}
    if mode == "octoprint" and method == "POST" and path == "/api/files/local":
        return 201, {"done": True, "files": []}
    if mode == "prusalink" and method == "PUT" and path.startswith("/api/v1/files/usb/"):
        return 201, {"done": True}
    if mode == "octoprint" and method == "POST" and path.startswith("/api/files/local/"):
        return 204, {}
    if mode == "prusalink" and method == "POST" and path.startswith("/api/v1/files/usb/"):
        return 204, {}
    return 404, {"error": "not found"}


class Handler(BaseHTTPRequestHandler):
    server_version = "Phase6PrinterHostMock/1.0"

    def _json(self, status, value):
        payload = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if payload:
            self.wfile.write(payload)

    def _body(self):
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length) if length else b""

    def _handle(self):
        path = urlsplit(self.path).path
        body = self._body() if self.command not in ("GET", "HEAD") else b""
        if path == "/__health" and self.command == "GET":
            self._json(200, {"ok": True, "mode": self.server.state.mode})
            return
        if path == "/__requests" and self.command == "GET":
            self._json(200, {"mode": self.server.state.mode, "requests": self.server.state.snapshot()})
            return
        if path == "/__requests" and self.command == "DELETE":
            self.server.state.clear()
            self._json(200, {"cleared": True})
            return

        # Requests to the inspection endpoints are intentionally not recorded.
        record = {
            "method": self.command,
            "path": self.path,
            "headers": {key: value for key, value in self.headers.items()},
            "body": body.decode("utf-8", errors="replace"),
            "body_base64": base64.b64encode(body).decode("ascii"),
        }
        self.server.state.add(record)
        status, payload = response_for(self.server.state.mode, self.command, path)
        self._json(status, payload)

    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()

    def do_PUT(self):
        self._handle()

    def do_DELETE(self):
        self._handle()

    def log_message(self, fmt, *args):
        return


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("octoprint", "prusalink"), default=os.environ.get("MODE", "octoprint"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8080")))
    args = parser.parse_args()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.state = State(args.mode)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
