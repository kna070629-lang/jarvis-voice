"""
opencode_client.py — Client for the local OpenCode HTTP server

Talks to `opencode serve` over localhost using only the standard library.
Supports one-shot prompts and incremental (SSE) streaming for the voice TUI.
"""

import http.client
import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable


class OpenCodeError(RuntimeError):
    """Raised when the OpenCode server returns an error."""

    def __init__(self, message: str, status: int | None = None, body: str = ""):
        super().__init__(message)
        self.status = status
        self.body = body


DEFAULT_SYSTEM_PROMPT = (
    "You are a voice assistant speaking through text-to-speech. "
    "Output ONLY the final answer intended for the user's ears. "
    "Never explain your reasoning, never restate the request, never add "
    "preamble or sign-off. If asked to reply with an exact string, output "
    "only that string."
)


@dataclass
class OpenCodeConfig:
    host: str = "127.0.0.1"
    port: int = 4096
    provider: str = "opencode"
    model: str = "nemotron-3.5-lightning-free"
    agent: str = "build"
    session_title: str = "JARVIS"
    variant: str = ""
    request_timeout: float = 30.0
    stream_timeout: float = 120.0
    autostart: bool = True
    start_timeout: float = 30.0
    config_content: dict | None = field(
        default_factory=lambda: {
            "mcp": {
                "vercel": {
                    "type": "remote",
                    "url": "https://mcp.vercel.com",
                    "enabled": False,
                }
            }
        }
    )
    system_prompt: str = DEFAULT_SYSTEM_PROMPT
    log_file: Path | None = None
    # Where the server runs. The home directory holds the whole OpenCode
    # runtime, and the server builds a project context from the working
    # directory. A small dedicated directory keeps that context tiny.
    work_dir: Path | None = None

    @classmethod
    def from_env(cls) -> "OpenCodeConfig":
        def _int(name, default):
            try:
                return int(os.environ[name])
            except (KeyError, ValueError):
                return default

        def _float(name, default):
            try:
                return float(os.environ[name])
            except (KeyError, ValueError):
                return default

        cfg = cls(
            host=os.environ.get("JARVIS_OPENCODE_HOST", cls.host),
            port=_int("JARVIS_OPENCODE_PORT", cls.port),
            provider=os.environ.get("JARVIS_OPENCODE_PROVIDER", cls.provider),
            model=os.environ.get("JARVIS_OPENCODE_MODEL", cls.model),
            agent=os.environ.get("JARVIS_OPENCODE_AGENT", cls.agent),
            session_title=os.environ.get("JARVIS_OPENCODE_TITLE", cls.session_title),
            variant=os.environ.get("JARVIS_OPENCODE_VARIANT", cls.variant),
            request_timeout=_float("JARVIS_OPENCODE_REQUEST_TIMEOUT", cls.request_timeout),
            stream_timeout=_float("JARVIS_OPENCODE_STREAM_TIMEOUT", cls.stream_timeout),
            autostart=os.environ.get("JARVIS_OPENCODE_AUTOSTART", "1") not in ("0", "false", "no"),
            start_timeout=_float("JARVIS_OPENCODE_START_TIMEOUT", cls.start_timeout),
            system_prompt=os.environ.get("JARVIS_OPENCODE_SYSTEM_PROMPT", cls.system_prompt),
        )
        if os.environ.get("JARVIS_OPENCODE_KEEP_GLOBAL_MCP", "").lower() in ("1", "true", "yes"):
            cfg.config_content = None
        return cfg

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"


class _SSEReader(threading.Thread):
    """Reads the /event stream and pushes decoded JSON objects to a queue."""

    def __init__(self, host: str, port: int, out_queue, ready: threading.Event, timeout: float):
        super().__init__(daemon=True)
        self.host = host
        self.port = port
        self.out_queue = out_queue
        self.ready = ready
        self.timeout = timeout
        self.stop_event = threading.Event()
        self.error: Exception | None = None
        self._conn: http.client.HTTPConnection | None = None

    def run(self):
        try:
            self._conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
            self._conn.request(
                "GET",
                "/event",
                headers={"Accept": "text/event-stream", "Cache-Control": "no-cache"},
            )
            resp = self._conn.getresponse()
            if resp.status != 200:
                raise OpenCodeError(
                    f"event stream rejected ({resp.status})", status=resp.status
                )
            self.ready.set()
            while not self.stop_event.is_set():
                line = resp.readline()
                if not line:
                    break
                line = line.decode(errors="replace").strip("\r\n")
                if not line or line.startswith(":"):
                    continue
                if line.startswith("data:"):
                    payload = line[5:].strip()
                    try:
                        obj = json.loads(payload)
                    except ValueError:
                        continue
                    self.out_queue.put(obj)
        except Exception as exc:
            self.error = exc
        finally:
            self.ready.set()

    def stop(self):
        self.stop_event.set()
        conn = self._conn
        if conn is not None:
            try:
                if conn.sock is not None:
                    conn.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                conn.close()
            except OSError:
                pass


class OpenCodeClient:
    """Thin, dependency-free client for a local OpenCode server."""

    def __init__(self, config: OpenCodeConfig | None = None):
        self.config = config or OpenCodeConfig.from_env()
        self._lock = threading.Lock()
        self._proc: subprocess.Popen | None = None
        self._session_id: str | None = None
        self._last_info: dict | None = None
        self._log_path = self.config.log_file or (
            Path(tempfile.gettempdir()) / "jarvis-opencode-server.log"
        )

    @property
    def base_url(self) -> str:
        return self.config.base_url

    @property
    def last_message_info(self) -> dict | None:
        return self._last_info

    @property
    def session_id(self) -> str | None:
        return self._session_id

    def _url(self, path: str) -> str:
        return f"{self.base_url}{path}"

    def _request_json(self, method: str, path: str, body=None, timeout: float | None = None):
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self._url(path), data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.config.request_timeout) as resp:
                raw = resp.read().decode(errors="replace")
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode(errors="replace")
            raise OpenCodeError(
                f"{method} {path} failed ({exc.code})", status=exc.code, body=raw
            ) from exc
        except urllib.error.URLError as exc:
            raise OpenCodeError(f"cannot reach OpenCode at {self.base_url}: {exc.reason}") from exc

    def is_running(self) -> bool:
        try:
            with urllib.request.urlopen(self._url("/api/health"), timeout=2.0) as resp:
                payload = json.loads(resp.read().decode(errors="replace") or "{}")
                return resp.status == 200 and payload.get("healthy", True)
        except Exception:
            return False

    def ensure_server(self) -> None:
        """Start `opencode serve` if nothing is listening on the configured port."""
        if self.is_running():
            return
        if not self.config.autostart:
            raise OpenCodeError(
                f"OpenCode server not reachable at {self.base_url} and autostart is disabled"
            )
        with self._lock:
            if self.is_running():
                return
            env = os.environ.copy()
            if self.config.config_content is not None and "OPENCODE_CONFIG_CONTENT" not in env:
                env["OPENCODE_CONFIG_CONTENT"] = json.dumps(self.config.config_content)
            self._log_path.parent.mkdir(parents=True, exist_ok=True)
            work_dir = Path(self.config.work_dir) if self.config.work_dir else Path.home()
            work_dir.mkdir(parents=True, exist_ok=True)
            log = open(self._log_path, "ab")
            try:
                self._proc = subprocess.Popen(
                    [
                        "opencode",
                        "serve",
                        "--port",
                        str(self.config.port),
                        "--hostname",
                        self.config.host,
                    ],
                    cwd=str(work_dir),
                    env=env,
                    stdin=subprocess.DEVNULL,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                )
            finally:
                log.close()
            deadline = time.time() + self.config.start_timeout
            while time.time() < deadline:
                if self._proc.poll() is not None:
                    raise OpenCodeError(
                        f"opencode serve exited (code {self._proc.returncode}); see {self._log_path}"
                    )
                if self.is_running():
                    return
                time.sleep(0.25)
            raise OpenCodeError(
                f"opencode serve did not become healthy within {self.config.start_timeout}s"
            )

    def stop_server(self) -> None:
        """Stop the server only if this client spawned it."""
        with self._lock:
            proc = self._proc
            self._proc = None
            if proc is None or proc.poll() is not None:
                return
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
            except (ProcessLookupError, PermissionError, OSError):
                try:
                    proc.terminate()
                except OSError:
                    pass
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
                except (ProcessLookupError, PermissionError, OSError):
                    try:
                        proc.kill()
                    except OSError:
                        pass

    def list_sessions(self) -> list[dict]:
        sessions = self._request_json("GET", "/session") or []
        return sessions if isinstance(sessions, list) else []

    def get_or_create_session(self, title: str | None = None) -> str:
        if self._session_id:
            return self._session_id
        wanted = title or self.config.session_title
        matches = [s for s in self.list_sessions() if (s.get("title") or "") == wanted]

        def _updated(session: dict) -> float:
            return float(session.get("time", {}).get("updated", 0) or 0)

        if matches:
            self._session_id = max(matches, key=_updated)["id"]
            return self._session_id
        body = {"title": wanted}
        if self.config.agent:
            body["agent"] = self.config.agent
        if self.config.provider and self.config.model:
            body["model"] = {"id": self.config.model, "providerID": self.config.provider}
        session = self._request_json("POST", "/session", body)
        self._session_id = session["id"]
        return self._session_id

    def reset_session(self) -> None:
        self._session_id = None

    def abort(self, session_id: str | None = None) -> None:
        sid = session_id or self._session_id
        if not sid:
            return
        try:
            self._request_json("POST", f"/api/session/{urllib.parse.quote(sid)}/interrupt", {})
        except OpenCodeError:
            pass

    def _model_payload(self) -> dict:
        return {"providerID": self.config.provider, "modelID": self.config.model}

    def _prompt_body(self, prompt: str) -> dict:
        body = {
            "parts": [{"type": "text", "text": prompt}],
            "model": self._model_payload(),
            "agent": self.config.agent,
        }
        if self.config.variant:
            # The variant controls how much the model thinks before it answers.
            # A voice assistant wants the low effort variant so the reply
            # starts arriving in a couple of seconds.
            body["variant"] = self.config.variant
        if self.config.system_prompt:
            body["system"] = self.config.system_prompt
        return body

    def ask(self, prompt: str, session_id: str | None = None) -> str:
        """Send a prompt and wait for the complete reply."""
        self.ensure_server()
        sid = session_id or self.get_or_create_session()
        body = self._prompt_body(prompt)
        try:
            response = self._request_json(
                "POST",
                f"/session/{urllib.parse.quote(sid)}/message",
                body,
                timeout=self.config.stream_timeout,
            )
        except OpenCodeError as exc:
            if exc.status != 404:
                raise
            self.reset_session()
            sid = self.get_or_create_session()
            response = self._request_json(
                "POST",
                f"/session/{urllib.parse.quote(sid)}/message",
                body,
                timeout=self.config.stream_timeout,
            )
        self._last_info = (response or {}).get("info")
        texts = [
            part.get("text", "")
            for part in (response or {}).get("parts", [])
            if part.get("type") == "text" and part.get("text")
        ]
        return "".join(texts)

    def ask_stream(
        self,
        prompt: str,
        session_id: str | None = None,
        on_delta: Callable[[str], None] | None = None,
        on_text: Callable[[str], None] | None = None,
        timeout: float | None = None,
    ) -> str:
        """Send a prompt and stream assistant text as it is produced."""
        self.ensure_server()
        sid = session_id or self.get_or_create_session()
        deadline = time.time() + (timeout or self.config.stream_timeout)

        events: "queue.Queue[dict]" = __import__("queue").Queue()
        ready = threading.Event()
        reader = _SSEReader(
            self.config.host,
            self.config.port,
            events,
            ready,
            timeout=max(self.config.stream_timeout + 30.0, 30.0),
        )
        reader.start()
        if not ready.wait(10.0):
            reader.stop()
            raise OpenCodeError("timed out connecting to the OpenCode event stream")
        if reader.error is not None:
            raise OpenCodeError(f"event stream failed: {reader.error}")

        order: list[str] = []
        parts: dict[str, dict] = {}
        part_kinds: dict[str, str] = {}
        assistant_msgs: set[str] = set()
        finished = threading.Event()

        body = self._prompt_body(prompt)
        try:
            try:
                self._request_json(
                    "POST",
                    f"/session/{urllib.parse.quote(sid)}/prompt_async",
                    body,
                    timeout=self.config.request_timeout,
                )
            except OpenCodeError as exc:
                if exc.status != 404:
                    raise
                self.reset_session()
                sid = self.get_or_create_session()
                self._request_json(
                    "POST",
                    f"/session/{urllib.parse.quote(sid)}/prompt_async",
                    body,
                    timeout=self.config.request_timeout,
                )

            while not finished.is_set() and time.time() < deadline:
                try:
                    event = events.get(timeout=0.5)
                except __import__("queue").Empty:
                    continue
                etype = event.get("type")
                props = event.get("properties") or {}
                if props.get("sessionID") != sid:
                    continue
                if etype == "message.updated":
                    info = props.get("info") or {}
                    if info.get("role") == "assistant" and info.get("id"):
                        assistant_msgs.add(info["id"])
                        self._last_info = info
                elif etype == "message.part.delta":
                    if props.get("field") != "text":
                        continue
                    pid = props.get("partID")
                    delta = props.get("delta") or ""
                    if not pid or not delta:
                        continue
                    # The delta event carries no part type, so the kind recorded
                    # from the part update decides. Reasoning must never be
                    # spoken out loud.
                    if part_kinds.get(pid) == "reasoning":
                        continue
                    if pid not in parts:
                        parts[pid] = {"text": ""}
                        order.append(pid)
                    parts[pid]["text"] += delta
                    if on_delta:
                        on_delta(delta)
                    if on_text:
                        on_text(self._joined(order, parts))
                elif etype == "message.part.updated":
                    part = props.get("part") or {}
                    pid = part.get("id")
                    if pid:
                        part_kinds[pid] = str(part.get("type") or "")
                    if part.get("type") != "text":
                        continue
                    text = part.get("text")
                    if not pid or text is None:
                        continue
                    if pid in parts:
                        previous = parts[pid]["text"]
                        parts[pid]["text"] = text
                        # Some server builds send the whole text instead of
                        # deltas, so the growth is reported as a delta here.
                        if len(text) > len(previous) and text.startswith(previous):
                            if on_delta:
                                on_delta(text[len(previous):])
                    elif part.get("messageID") in assistant_msgs and text:
                        parts[pid] = {"text": text}
                        order.append(pid)
                        if on_delta:
                            on_delta(text)
                        if on_text:
                            on_text(self._joined(order, parts))
                elif etype == "session.idle":
                    finished.set()
                elif etype == "session.error":
                    raise OpenCodeError(f"session error: {json.dumps(props)[:300]}")
                elif etype == "session.status":
                    if (props.get("status") or {}).get("type") == "idle":
                        finished.set()
            if not finished.is_set():
                raise TimeoutError(f"no reply within {timeout or self.config.stream_timeout}s")
        finally:
            reader.stop()

        return self._joined(order, parts)

    @staticmethod
    def _joined(order: list[str], parts: dict[str, dict]) -> str:
        return "".join(parts[pid]["text"] for pid in order)


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: python opencode_client.py <prompt>")
        return 2
    client = OpenCodeClient(OpenCodeConfig.from_env())
    prompt = " ".join(argv)

    def emit(delta: str) -> None:
        sys.stdout.write(delta)
        sys.stdout.flush()

    try:
        text = client.ask_stream(prompt, on_delta=emit)
    except Exception as exc:
        print(f"\nerror: {exc}", file=sys.stderr)
        return 1
    if not text.endswith("\n"):
        print()
    info = client.last_message_info or {}
    tokens = info.get("tokens") or {}
    print(f"[session {client.session_id} | tokens in={tokens.get('input')} out={tokens.get('output')}]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
