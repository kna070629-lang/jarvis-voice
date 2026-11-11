"""Authenticated loopback HTTP bridge for the native Android companion.

The bridge accepts a small structured chat protocol only. It never interprets
assistant output as a command or invokes a shell on behalf of the phone app.
"""

from __future__ import annotations

import hmac
import inspect
import json
import logging
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

from jarvis_agents import Speaker, speakable


HOST = "127.0.0.1"
PORT = 8765
MAX_BODY_BYTES = 16 * 1024
MAX_TEXT_CHARS = 4000
MAX_CACHE_ENTRIES = 256
REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
ACTION_ID_RE = re.compile(r"^[A-Za-z0-9_-]{16,64}$")
ACTION_NAME_RE = re.compile(r"^[a-z_]{3,40}$")
ACTION_STATUSES = {"succeeded", "failed", "cancelled", "permission_required", "needs_confirmation", "duplicate"}
MAX_ACTION_MESSAGE_CHARS = 2000
MAX_ACTIONS_TRACKED = 512
CHAT_KEYS = {"action", "request_id", "text"}
CHAT_OPTIONAL_KEYS = {"apps", "stream"}
ACTION_RESULT_KEYS = {"action", "request_id", "action_id", "status", "message"}
ORCHESTRATE_KEYS = {"action", "request_id", "text"}
SENSITIVE_ACTIONS = {"call", "prepare_sms", "prepare_whatsapp", "read_notifications"}
ALLOWED_ACTIONS = {
    "open_app",
    "open_package",
    "open_settings",
    "set_alarm",
    "open_dialer",
    "call",
    "prepare_sms",
    "prepare_whatsapp",
    "read_notifications",
    "accessibility",
    "call_contact",
    "open_chat",
    "click_text",
    "toggle_flashlight",
    "set_volume",
    "media_control",
    "set_timer",
    "set_calendar",
    "screenshot",
}
ACTION_ARGUMENTS = {
    "open_app": ({"name"}, set()),
    "open_package": ({"package"}, set()),
    "open_settings": (set(), set()),
    "set_alarm": ({"time"}, set()),
    "open_dialer": (set(), {"number"}),
    "call": ({"number"}, set()),
    "prepare_sms": ({"number", "text"}, set()),
    "prepare_whatsapp": ({"number", "text"}, set()),
    "read_notifications": (set(), set()),
    "accessibility": ({"navigation"}, set()),
    "call_contact": ({"name"}, set()),
    "open_chat": ({"name"}, {"text", "app"}),
    "click_text": ({"text"}, set()),
    "toggle_flashlight": (set(), {"state"}),
    "set_volume": ({"level"}, set()),
    "media_control": ({"action"}, set()),
    "set_timer": ({"duration"}, {"message"}),
    "set_calendar": ({"title"}, {"time"}),
    "screenshot": (set(), set()),
}
NAVIGATION_ACTIONS = {"back", "home", "recents"}
# A call is authorised by the spoken command itself. A message is not, because
# it puts words in someone else's inbox.
SENSITIVE_ACTIONS = {"prepare_sms", "prepare_whatsapp", "read_notifications", "open_chat"}
TERMINAL_ACTION_STATUSES = {"succeeded", "failed", "cancelled", "permission_required", "duplicate"}
TOKEN_PATH = Path.home() / ".config" / "jarvis" / "mobile.token"

log = logging.getLogger("jarvis.bridge")


def load_or_create_token(path: Path = TOKEN_PATH) -> str:
    """Load a private bearer token or create one with owner-only permissions."""
    override = os.environ.get("JARVIS_MOBILE_TOKEN", "").strip()
    if override:
        if len(override) < 32:
            raise ValueError("JARVIS_MOBILE_TOKEN must contain at least 32 characters")
        if not override.isascii():
            raise ValueError("JARVIS_MOBILE_TOKEN must be ASCII so it can be sent in an HTTP header")
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
        if len(token) < 32 or not token.isascii():
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

    def __init__(self, token: str, process_message: Callable[..., object], port: int = PORT,
                 status_provider: Callable[[], dict] | None = None,
                 action_planner: Callable[[str], dict | None] | None = None,
                 process_message_stream: Callable[..., object] | None = None,
                 self_check: Callable[[], dict] | None = None,
                 core: object | None = None):
        super().__init__((HOST, port), _BridgeHandler)
        self.token = token
        self.process_message = process_message
        self.process_message_stream = process_message_stream
        self.self_check = self_check
        self.core = core
        self._message_accepts_device = _accepts_device(process_message)
        self.status_provider = status_provider
        self.action_planner = action_planner
        self._request_lock = threading.Lock()
        # Only completed requests are eligible for LRU eviction.
        self._requests: OrderedDict[str, tuple[str, float, dict]] = OrderedDict()
        self._inflight: dict[str, str] = {}
        # Completed action IDs prevent a sensitive request from being replayed.
        self._actions: OrderedDict[str, float] = OrderedDict()
        # IDs issued to Android by this backend, plus concurrent results in flight.
        self._issued_actions: OrderedDict[str, float] = OrderedDict()
        self._inflight_actions: set[str] = set()


_SAFE_STATUS_TEXT = re.compile(r"^[A-Za-z0-9._:/+>\-]{1,80}$")


def _safe_runtime_status(status: dict) -> dict:
    """Expose only bounded, non-secret runtime metadata."""
    if not isinstance(status, dict):
        return {"mode": "unknown"}

    safe = {}

    value = status.get("mode")
    if isinstance(value, str) and value in {"online", "offline", "unknown"}:
        safe["mode"] = value

    for key in ("search", "memory", "ai", "ai_primary", "ai_fallback", "last_provider"):
        value = status.get(key)
        if isinstance(value, str) and _SAFE_STATUS_TEXT.fullmatch(value):
            safe[key] = value

    return safe


def _safe_app_list(value) -> list[dict[str, str]]:
    """Accept a bounded list of installed apps reported by the companion.

    The list is untrusted input that only feeds the model prompt, so every
    field is length checked and the package id is restricted to a safe shape.
    """
    if not isinstance(value, list) or len(value) > 200:
        return []
    apps: list[dict[str, str]] = []
    for entry in value:
        if not isinstance(entry, dict):
            continue
        label = entry.get("label")
        package = entry.get("package")
        if not isinstance(label, str) or not isinstance(package, str):
            continue
        label = label.strip()
        package = package.strip()
        if not label or not package:
            continue
        if len(label) > 60 or len(package) > 120:
            continue
        if not re.fullmatch(r"[A-Za-z0-9._]{1,120}", package):
            continue
        apps.append({"label": label, "package": package})
    return apps


def _accepts_device(handler) -> bool:
    """Report whether a message handler also accepts the device context."""
    try:
        params = inspect.signature(handler).parameters
    except (TypeError, ValueError):
        return False
    for param in params.values():
        if param.kind is inspect.Parameter.VAR_POSITIONAL:
            return True
        if param.kind is inspect.Parameter.VAR_KEYWORD:
            continue
    positional = [
        param
        for param in params.values()
        if param.kind in (inspect.Parameter.POSITIONAL_ONLY, inspect.Parameter.POSITIONAL_OR_KEYWORD)
    ]
    return len(positional) >= 2


def _normalize_name(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", str(value).lower()).strip()


def _app_name_known(name: str, apps: list[dict[str, str]]) -> bool:
    """Check a requested app name against the apps the device reported.

    Used to drop a naive "open the whole sentence" plan so the model can pick
    the right app instead.
    """
    if not apps:
        return True
    wanted = _normalize_name(name)
    if not wanted:
        return False
    for app in apps:
        label = _normalize_name(app.get("label", ""))
        package = _normalize_name(app.get("package", ""))
        tail = _normalize_name(app.get("package", "").split(".")[-1])
        if not label and not package:
            continue
        if wanted == label or wanted == package or wanted == tail:
            return True
        if label and (wanted in label or label in wanted):
            return True
        if tail and (wanted in tail or tail in wanted):
            return True
    return False


def _safe_action_request(value) -> dict | None:
    """Accept only a bounded, well-formed action request from a planner.

    The planner is untrusted input. Confirmation and sensitivity cannot be
    downgraded here, and only the exact action/argument schema is accepted.
    """
    if not isinstance(value, dict):
        return None
    required = {"action_id", "action", "args", "requires_confirmation", "sensitive"}
    allowed = required | {
        "request_id", "action_type", "arguments", "target", "permissions",
        "permission_status", "execution_status", "result", "error", "timestamp",
    }
    if not required.issubset(set(value)) or not set(value).issubset(allowed):
        return None
    action_id = value.get("action_id")
    action = value.get("action")
    args = value.get("args")
    if not isinstance(action_id, str) or not ACTION_ID_RE.fullmatch(action_id):
        return None
    if not isinstance(action, str) or not ACTION_NAME_RE.fullmatch(action) or action not in ALLOWED_ACTIONS:
        return None
    if not isinstance(args, dict):
        return None
    clean_args = {}
    for key, item in args.items():
        if not isinstance(key, str) or not re.fullmatch(r"[a-z_]{1,32}", key):
            return None
        if not isinstance(item, str) or len(item) > 240:
            return None
        clean_args[key] = item
    required, optional = ACTION_ARGUMENTS[action]
    if not required.issubset(clean_args) or not set(clean_args).issubset(required | optional):
        return None
    if type(value.get("requires_confirmation")) is not bool:
        return None
    if type(value.get("sensitive")) is not bool:
        return None
    confirmation = value["requires_confirmation"]
    sensitive = value["sensitive"]
    if action in SENSITIVE_ACTIONS and (not confirmation or not sensitive):
        return None
    if action in {"call", "prepare_sms", "prepare_whatsapp"}:
        number = clean_args.get("number", "")
        if not re.fullmatch(r"[0-9]{7,15}", number):
            return None
    if action in {"prepare_sms", "prepare_whatsapp"} and not clean_args.get("text", "").strip():
        return None
    if action == "open_app" and not clean_args.get("name", "").strip():
        return None
    if action == "open_package" and not clean_args.get("package", "").strip():
        return None
    if action == "set_alarm" and not clean_args.get("time", "").strip():
        return None
    if action == "open_dialer" and clean_args.get("number"):
        if not re.fullmatch(r"[0-9]{7,15}", clean_args["number"]):
            return None
    if action == "accessibility" and clean_args.get("navigation") not in NAVIGATION_ACTIONS:
        return None
    # The contract the Android companion reads. The companion is the only
    # thing that executes an action, so the state here says what has not
    # happened yet.
    request_id = str(value.get("request_id") or action_id)
    target = str(value.get("target") or "")
    if not target:
        target = clean_args.get("name") or clean_args.get("package") or clean_args.get("number") or ""
    permissions = value.get("permissions")
    if not isinstance(permissions, list):
        permissions = []
    permission_status = str(value.get("permission_status") or "unknown")
    execution_status = str(value.get("execution_status") or "pending")
    if execution_status not in ("pending", "executing"):
        execution_status = "pending"
    return {
        "action_id": action_id,
        "request_id": request_id,
        "action": action,
        "action_type": action,
        "args": clean_args,
        "arguments": clean_args,
        "target": target,
        "permissions": [str(item) for item in permissions][:16],
        "permission_status": permission_status,
        "requires_confirmation": confirmation,
        "execution_status": execution_status,
        "result": str(value.get("result") or ""),
        "error": str(value.get("error") or ""),
        "sensitive": sensitive,
        "timestamp": float(value.get("timestamp") or time.time()),
    }


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
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            self.close_connection = True

    def _begin_stream(self) -> bool:
        """Start a newline delimited JSON response."""
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            return True
        except (BrokenPipeError, ConnectionResetError, OSError):
            return False

    def _write_chunk(self, payload: dict) -> bool:
        """Write one NDJSON line. Returns False once the peer has gone."""
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n"
        try:
            self.wfile.write(body.encode("utf-8"))
            self.wfile.flush()
            return True
        except (BrokenPipeError, ConnectionResetError, OSError):
            return False

    def _note_core(self, tier: str, duration_ms: int, provider: str, error: str) -> None:
        """Tell the central core what happened, so the network view is honest."""
        core = self.server.core
        if core is None:
            return
        try:
            core.note_activity(tier, duration_ms, provider, error)
        except Exception:
            pass

    def _stream_chat(self, request_id: str, text: str, device: dict | None) -> None:
        """Answer a chat request as a stream of speakable sentences.

        The companion can start talking as soon as the first sentence arrives
        instead of waiting for the whole answer.
        """
        if not self._begin_stream():
            self.close_connection = True
            return
        try:
            speaker = Speaker()
            spoken: list[str] = []

            def on_delta(delta: str) -> None:
                for chunk in speaker.feed(delta):
                    for clean in speakable([chunk]):
                        spoken.append(clean)
                        self._write_chunk({"type": "speech", "request_id": request_id, "text": clean})

            handler = getattr(self.server, "process_message_stream", None)
            if not callable(handler):
                response = self.server.process_message(text, device)
                for clean in speakable([str(response)]):
                    spoken.append(clean)
                    self._write_chunk({"type": "speech", "request_id": request_id, "text": clean})
                self._write_chunk({"type": "final", "request_id": request_id, "text": str(response)})
                return

            result = handler(text, device, on_delta)
            for chunk in speaker.flush():
                for clean in speakable([chunk]):
                    spoken.append(clean)
                    self._write_chunk({"type": "speech", "request_id": request_id, "text": clean})

            if isinstance(result, dict):
                planned = _safe_action_request(result)
                if planned is not None:
                    with self.server._request_lock:
                        issued_id = planned["action_id"]
                        self.server._issued_actions[issued_id] = time.time()
                        self.server._issued_actions.move_to_end(issued_id)
                    self._write_chunk({
                        "type": "action",
                        "request_id": request_id,
                        "action_request": planned,
                    })
                    return
            final_text = str(result) if result is not None else ""
            self._write_chunk({
                "type": "final",
                "request_id": request_id,
                "text": final_text,
                "provider": _provider_name(self.server.status_provider),
            })
        except Exception:
            log.exception("The streamed chat request failed")
            self._write_chunk({"type": "error", "request_id": request_id, "text": "I could not finish that request."})
        finally:
            self.close_connection = True

    def do_GET(self) -> None:
        if self.path == "/health":
            auth = self.headers.get("Authorization", "")
            expected = "Bearer " + self.server.token
            if auth and not hmac.compare_digest(auth.encode("utf-8"), expected.encode("utf-8")):
                self._send(401, {"error": "unauthorized"})
                return
            payload = {
                "service": "jarvis-mobile",
                "version": 1,
                "authenticated": bool(auth),
            }
            if auth and self.server.status_provider is not None:
                try:
                    # Expose only approved, bounded metadata; never forward arbitrary fields.
                    status = self.server.status_provider()
                    payload["runtime"] = _safe_runtime_status(status)
                except Exception:
                    payload["runtime"] = {"mode": "unknown"}
            self._send(200, payload)
            return
        if self.path == "/v1/network":
            auth = self.headers.get("Authorization", "")
            expected = "Bearer " + self.server.token
            if not hmac.compare_digest(auth.encode("utf-8"), expected.encode("utf-8")):
                self._send(401, {"error": "unauthorized"})
                return
            core = self.server.core
            if core is None:
                self._send(404, {"error": "not_found"})
                return
            try:
                self._send(200, {"network": core.topology()})
            except Exception:
                log.exception("The network view could not be built")
                self._send(500, {"error": "network_unavailable"})
            return
        if self.path == "/v1/services":
            auth = self.headers.get("Authorization", "")
            expected = "Bearer " + self.server.token
            if not hmac.compare_digest(auth.encode("utf-8"), expected.encode("utf-8")):
                self._send(401, {"error": "unauthorized"})
                return
            core = self.server.core
            if core is None:
                self._send(404, {"error": "not_found"})
                return
            try:
                core.check_services()
                self._send(200, {
                    "services": [report.to_dict() for report in core.services.reports()],
                    "summary": core.services.summary(),
                })
            except Exception:
                log.exception("The service check failed")
                self._send(500, {"error": "service_check_failed"})
            return
        if self.path == "/v1/node":
            auth = self.headers.get("Authorization", "")
            expected = "Bearer " + self.server.token
            if not hmac.compare_digest(auth.encode("utf-8"), expected.encode("utf-8")):
                self._send(401, {"error": "unauthorized"})
                return
            core = self.server.core
            if core is None:
                self._send(404, {"error": "not_found"})
                return
            node_id = self.headers.get("X-Jarvis-Node", "")
            if not node_id or len(node_id) > 64:
                self._send(400, {"error": "invalid_node"})
                return
            try:
                self._send(200, {"node": core.node_detail(node_id)})
            except Exception:
                log.exception("The node detail could not be built")
                self._send(500, {"error": "node_unavailable"})
            return
        if self.path == "/v1/selfcheck":
            self._send(404, {"error": "not_found"})
            return
        if self.path == "/selfcheck":
            auth = self.headers.get("Authorization", "")
            expected = "Bearer " + self.server.token
            if not hmac.compare_digest(auth.encode("utf-8"), expected.encode("utf-8")):
                self._send(401, {"error": "unauthorized"})
                return
            handler = getattr(self.server, "self_check", None)
            if not callable(handler):
                self._send(404, {"error": "not_found"})
                return
            try:
                self._send(200, {"selfcheck": handler()})
            except Exception:
                self._send(500, {"error": "selfcheck_failed"})
            return
        self._send(404, {"error": "not_found"})

    def do_POST(self) -> None:
        if self.path != "/v1/command":
            self._send(404, {"error": "not_found"})
            return
        auth = self.headers.get("Authorization", "")
        expected = "Bearer " + self.server.token
        if not hmac.compare_digest(auth.encode("utf-8"), expected.encode("utf-8")):
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
        except (TimeoutError, OSError):
            self._send(408, {"error": "body_read_timeout"})
            return
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._send(400, {"error": "invalid_json"})
            return
        if not isinstance(request, dict):
            self._send(400, {"error": "invalid_schema"})
            return
        action = request.get("action")
        request_id = request.get("request_id")
        action_status = None
        if not isinstance(request_id, str) or not REQUEST_ID_RE.fullmatch(request_id):
            self._send(400, {"error": "invalid_request_id"})
            return

        if action == "orchestrate":
            if set(request) != ORCHESTRATE_KEYS:
                self._send(400, {"error": "invalid_schema"})
                return
            text = request.get("text")
            if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT_CHARS:
                self._send(400, {"error": "invalid_text"})
                return
            core = self.server.core
            if core is None:
                self._send(404, {"error": "not_found"})
                return
            try:
                outcome = core.execute(text)
            except Exception:
                log.exception("The orchestrator failed")
                self._send(500, {"error": "orchestration_failed"})
                return
            self._send(200, {
                "request_id": request_id,
                "text": str(outcome.get("answer", "")),
                "provider": outcome.get("provider", ""),
                "trace": outcome.get("trace", []),
                "action_request": outcome.get("action_request"),
                "pending_approval": outcome.get("pending_approval"),
                "duration_ms": outcome.get("duration_ms", 0),
                "duplicate": False,
            })
            return
        if action == "chat":
            if not CHAT_KEYS.issubset(set(request)) or not set(request).issubset(CHAT_KEYS | CHAT_OPTIONAL_KEYS):
                self._send(400, {"error": "invalid_schema"})
                return
            text = request.get("text")
            if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT_CHARS:
                self._send(400, {"error": "invalid_text"})
                return
            device = {"apps": _safe_app_list(request.get("apps"))} if "apps" in request else None
            fingerprint = "chat\n" + text
            action_id = ""
            if request.get("stream") is True:
                self._stream_chat(request_id, text, device)
                return
        elif action == "action_result":
            if set(request) != ACTION_RESULT_KEYS:
                self._send(400, {"error": "invalid_schema"})
                return
            action_id = request.get("action_id")
            action_status = request.get("status")
            message = request.get("message")
            if not isinstance(action_id, str) or not ACTION_ID_RE.fullmatch(action_id):
                self._send(400, {"error": "invalid_action_id"})
                return
            if not isinstance(action_status, str) or action_status not in ACTION_STATUSES:
                self._send(400, {"error": "invalid_action_status"})
                return
            if not isinstance(message, str) or len(message) > MAX_ACTION_MESSAGE_CHARS:
                self._send(400, {"error": "invalid_action_message"})
                return
            fingerprint = "action_result\n" + action_id + "\n" + action_status
        else:
            self._send(400, {"error": "unsupported_action"})
            return

        now = time.monotonic()
        cached_status = None
        cached_payload = None
        with self.server._request_lock:
            previous = self.server._requests.get(request_id)
            if previous is not None:
                old_fingerprint, _, old_payload = previous
                self.server._requests.move_to_end(request_id)
                if old_fingerprint != fingerprint:
                    cached_status = 409
                    cached_payload = {"error": "request_id_conflict"}
                else:
                    cached_status = 200
                    cached_payload = dict(old_payload)
                    cached_payload["duplicate"] = True
            else:
                inflight_fingerprint = self.server._inflight.get(request_id)
                if inflight_fingerprint is not None:
                    error = "request_id_conflict" if inflight_fingerprint != fingerprint else "request_in_progress"
                    cached_status = 409
                    cached_payload = {"error": error}
                else:
                    self.server._inflight[request_id] = fingerprint
                    if action_id:
                        if action_id in self.server._actions:
                            cached_status = 409
                            cached_payload = {"error": "action_replayed"}
                        elif action_id not in self.server._issued_actions:
                            cached_status = 409
                            cached_payload = {"error": "unknown_action"}
                        elif action_id in self.server._inflight_actions:
                            cached_status = 409
                            cached_payload = {"error": "action_in_progress"}
                        else:
                            self.server._inflight_actions.add(action_id)
                        if cached_status is not None:
                            self.server._inflight.pop(request_id, None)

        if cached_status is not None:
            self._send(cached_status, cached_payload)
            return

        try:
            if action == "chat":
                action_request = None
                planner = self.server.action_planner
                if planner is not None:
                    try:
                        action_request = _safe_action_request(planner(text))
                    except Exception:
                        action_request = None
                if action_request is not None and device and device.get("apps"):
                    # A naive plan can swallow the whole sentence as an app
                    # name. Drop it so the model can choose the real app.
                    if action_request["action"] == "open_app":
                        requested = action_request["args"].get("name", "")
                        if not _app_name_known(requested, device["apps"]):
                            action_request = None
                if action_request is not None:
                    with self.server._request_lock:
                        issued_id = action_request["action_id"]
                        self.server._issued_actions[issued_id] = now
                        self.server._issued_actions.move_to_end(issued_id)
                        while len(self.server._issued_actions) > MAX_ACTIONS_TRACKED:
                            self.server._issued_actions.popitem(last=False)
                    payload = {
                        "request_id": request_id,
                        "text": "",
                        "provider": "device",
                        "action_request": action_request,
                        "duplicate": False,
                    }
                else:
                    planned = None
                    try:
                        if self.server._message_accepts_device:
                            response = self.server.process_message(text, device)
                        else:
                            response = self.server.process_message(text)
                        if isinstance(response, dict):
                            # The model planned a device action. It still has to
                            # pass the same validation as a planned action.
                            planned = _safe_action_request(response)
                            if planned is None:
                                response = "I could not turn that into an action this device can run."
                            else:
                                with self.server._request_lock:
                                    issued_id = planned["action_id"]
                                    self.server._issued_actions[issued_id] = now
                                    self.server._issued_actions.move_to_end(issued_id)
                                    while len(self.server._issued_actions) > MAX_ACTIONS_TRACKED:
                                        self.server._issued_actions.popitem(last=False)
                                action_id = issued_id
                                response = ""
                        if not isinstance(response, str) or not response.strip():
                            response = "I could not get a response just now. Please try again."
                        response = response[:12000]
                    except Exception:
                        response = "I hit an error while processing that request. Please try again."
                    if planned is not None:
                        payload = {
                            "request_id": request_id,
                            "text": "",
                            "provider": "device",
                            "action_request": planned,
                            "duplicate": False,
                        }
                        self._note_core("device", 0, "device", "")
                    else:
                        payload = {
                            "request_id": request_id,
                            "text": response,
                            "provider": _provider_name(self.server.status_provider),
                            "duplicate": False,
                        }
                        self._note_core(
                            "model",
                            0,
                            payload["provider"],
                            "",
                        )
            else:
                if message.strip():
                    response = message.strip()[:MAX_ACTION_MESSAGE_CHARS]
                elif action_status == "succeeded":
                    response = "The Android action completed."
                elif action_status == "cancelled":
                    response = "The Android action was cancelled."
                else:
                    response = "The Android action did not complete."
                payload = {
                    "request_id": request_id,
                    "text": response,
                    "status": action_status,
                    "duplicate": False,
                }

            with self.server._request_lock:
                self.server._requests[request_id] = (fingerprint, now, payload)
                self.server._requests.move_to_end(request_id)
                while len(self.server._requests) > MAX_CACHE_ENTRIES:
                    self.server._requests.popitem(last=False)

            self._send(200, payload)
        finally:
            with self.server._request_lock:
                self.server._inflight.pop(request_id, None)
                if action_id:
                    self.server._inflight_actions.discard(action_id)
                    if action_status in TERMINAL_ACTION_STATUSES and action_id in self.server._issued_actions:
                        self.server._actions[action_id] = now
                        self.server._actions.move_to_end(action_id)
                        self.server._issued_actions.pop(action_id, None)
                        while len(self.server._actions) > MAX_ACTIONS_TRACKED:
                            self.server._actions.popitem(last=False)


def _provider_name(status_provider: Callable[[], dict] | None) -> str:
    if status_provider is None:
        return ""
    try:
        value = status_provider().get("last_provider", "")
    except Exception:
        return ""
    return value if isinstance(value, str) and _SAFE_STATUS_TEXT.fullmatch(value) else ""


def create_server(
    process_message: Callable[[str], str], token: str | None = None,
    port: int = PORT, status_provider: Callable[[], dict] | None = None,
    action_planner: Callable[[str], dict | None] | None = None,
    process_message_stream: Callable[..., object] | None = None,
    self_check: Callable[[], dict] | None = None,
    core: object | None = None,
) -> _BridgeServer:
    """Create the bridge without starting its serving thread (also useful in tests)."""
    return _BridgeServer(
        token or load_or_create_token(), process_message, port, status_provider, action_planner,
        process_message_stream, self_check, core,
    )


def start_mobile_bridge(
    process_message: Callable[[str], str],
    status_provider: Callable[[], dict] | None = None,
    action_planner: Callable[[str], dict | None] | None = None,
    process_message_stream: Callable[..., object] | None = None,
    core: object | None = None,
) -> tuple[_BridgeServer, threading.Thread]:
    server = create_server(
        process_message,
        status_provider=status_provider,
        action_planner=action_planner,
        process_message_stream=process_message_stream,
        core=core,
    )
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
