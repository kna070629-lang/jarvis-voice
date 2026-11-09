"""Authenticated loopback HTTP bridge for the native Android companion.

The bridge accepts a small structured chat protocol only. It never interprets
assistant output as a command or invokes a shell on behalf of the phone app.
"""

from __future__ import annotations

import hmac
import json
import os
import re
import secrets
import stat
import threading
import time
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable


HOST = "127.0.0.1"
PORT = 8765
MAX_BODY_BYTES = 16 * 1024
MAX_TEXT_CHARS = 4000
MAX_CACHE_ENTRIES = 256
REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
TOKEN_PATH = Path.home() / ".config" / "jarvis" / "mobile.token"


def load_or_create_token(path: Path = TOKEN_PATH) -> str:
    """Load a private bearer token or create one with owner-only permissions."""
    override = os.environ.get("JARVIS_MOBILE_TOKEN", "").strip()
    if override:
        if len(override) < 32:
            raise ValueError("JARVIS_MOBILE_TOKEN must contain at least 32 characters")
        return override

    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        parent_stat = path.parent.stat()
        if parent_stat.st_uid != os.getuid() or not stat.S_ISDIR(parent_stat.st_mode):
            raise PermissionError("mobile token directory is not owned by the current user")
        os.chmod(path.parent, 0o700)
    except OSError as exc:
        raise PermissionError(f"cannot secure mobile token directory: {exc}") from exc

    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        token_stat = path.lstat()
        if not stat.S_ISREG(token_stat.st_mode) or token_stat.st_uid != os.getuid():
            raise PermissionError("mobile token must be a regular file owned by the current user")
        os.chmod(path, 0o600)
        token = path.read_text(encoding="utf-8").strip()
        if len(token) < 32:
            raise ValueError("existing mobile token is invalid; remove it manually and restart")
        return token

    token = secrets.token_urlsafe(32)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(token + "\n")
            stream.flush()
            os.fsync(stream.fileno())
    except Exception:
        path.unlink(missing_ok=True)
        raise
    return token


class _BridgeServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, token: str, process_message: Callable[[str], str], port: int = PORT):
        super().__init__((HOST, port), _BridgeHandler)
        self.token = token
        self.process_message = process_message
        self._request_lock = threading.Lock()
        self._requests: OrderedDict[str, tuple[str, float, str | None]] = OrderedDict()


class _BridgeHandler(BaseHTTPRequestHandler):
    server: _BridgeServer
    protocol_version = "HTTP/1.1"
    server_version = "JarvisMobileBridge/1"
    sys_version = ""

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(10)

    def log_message(self, _format: str, *args) -> None:
        # Do not log request bodies, prompts, bearer tokens, or notification text.
        return

    def _send(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self) -> None:
        if self.path != "/health":
            self._send(404, {"error": "not_found"})
            return
        auth = self.headers.get("Authorization", "")
        if auth and not hmac.compare_digest(auth, "Bearer " + self.server.token):
            self._send(401, {"error": "unauthorized"})
            return
        self._send(200, {
            "service": "jarvis-mobile",
            "version": 1,
            "authenticated": bool(auth),
        })

    def do_POST(self) -> None:
        if self.path != "/v1/command":
            self._send(404, {"error": "not_found"})
            return
        auth = self.headers.get("Authorization", "")
        expected = "Bearer " + self.server.token
        if not hmac.compare_digest(auth, expected):
            self._send(401, {"error": "unauthorized"})
            return

        try:
            length = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            length = -1
        if length < 1 or length > MAX_BODY_BYTES:
            self._send(413 if length > MAX_BODY_BYTES else 400, {"error": "invalid_body_size"})
            return
        try:
            raw = self.rfile.read(length)
            request = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._send(400, {"error": "invalid_json"})
            return
        if not isinstance(request, dict) or set(request) != {"action", "request_id", "text"}:
            self._send(400, {"error": "invalid_schema"})
            return
        action = request.get("action")
        request_id = request.get("request_id")
        text = request.get("text")
        if action != "chat":
            self._send(400, {"error": "unsupported_action"})
            return
        if not isinstance(request_id, str) or not REQUEST_ID_RE.fullmatch(request_id):
            self._send(400, {"error": "invalid_request_id"})
            return
        if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT_CHARS:
            self._send(400, {"error": "invalid_text"})
            return

        now = time.monotonic()
        with self.server._request_lock:
            previous = self.server._requests.get(request_id)
            if previous:
                old_text, _, old_response = previous
                if old_text != text:
                    self._send(409, {"error": "request_id_conflict"})
                elif old_response is None:
                    self._send(409, {"error": "request_in_progress"})
                else:
                    self._send(200, {"request_id": request_id, "text": old_response, "duplicate": True})
                return
            self.server._requests[request_id] = (text, now, None)

        try:
            response = self.server.process_message(text)
            if not isinstance(response, str) or not response.strip():
                response = "I could not get a response just now. Please try again."
            response = response[:12000]
        except Exception:
            response = "I hit an error while processing that request. Please try again."

        with self.server._request_lock:
            self.server._requests[request_id] = (text, now, response)
            while len(self.server._requests) > MAX_CACHE_ENTRIES:
                self.server._requests.popitem(last=False)
        self._send(200, {"request_id": request_id, "text": response, "duplicate": False})


def create_server(
    process_message: Callable[[str], str], token: str | None = None,
    port: int = PORT,
) -> _BridgeServer:
    """Create the bridge without starting its serving thread (also useful in tests)."""
    return _BridgeServer(token or load_or_create_token(), process_message, port)


def start_mobile_bridge(process_message: Callable[[str], str]) -> tuple[_BridgeServer, threading.Thread]:
    server = create_server(process_message)
    thread = threading.Thread(target=server.serve_forever, name="jarvis-mobile-api", daemon=True)
    thread.start()
    return server, thread


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Inspect the JARVIS companion pairing token")
    parser.add_argument("--show-token", action="store_true", help="print the private local pairing token")
    args = parser.parse_args()
    if not args.show_token:
        parser.error("the standalone bridge is started by tui.py; use --show-token to pair the app")
    print(load_or_create_token())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
