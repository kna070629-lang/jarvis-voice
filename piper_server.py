"""piper_server.py — The persistent JARVIS voice server.

It runs beside the backend, loads the community JARVIS voice once and keeps
it warm, so a request only pays for the synthesis and not for loading a
hundred megabytes of model.

The server listens on a unix socket, because the container that runs it has
its own network namespace and cannot be reached over the loopback of the
phone, but it shares the file system.

The protocol is one line of text in, one wave file out.
"""

from __future__ import annotations

import os
import socket
import socketserver
import sys
import threading

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from jarvis_voice import JarvisVoice, VoiceUnavailable  # noqa: E402


SOCKET_PATH = os.environ.get(
    "JARVIS_PIPER_SOCKET",
    "/data/data/com.termux/files/home/.local/share/jarvis/piper.sock",
)

VOICE = JarvisVoice()
VOICE_LOCK = threading.Lock()


class Handler(socketserver.BaseRequestHandler):
    """One request: a line of text in, a wave file out."""

    def handle(self):
        try:
            raw = self.request.recv(65536).decode("utf-8", "replace")
        except OSError:
            return
        text = raw.strip()
        if not text:
            return
        try:
            with VOICE_LOCK:
                data = VOICE.synthesize(text)
        except VoiceUnavailable as exc:
            message = f"voice unavailable: {exc}".encode("utf-8")
            self.request.sendall(b"ERR " + message)
            return
        except Exception as exc:  # pragma: no cover - defensive
            message = f"voice failed: {type(exc).__name__}".encode("utf-8")
            self.request.sendall(b"ERR " + message)
            return
        try:
            self.request.sendall(b"OK " + str(len(data)).encode("ascii") + b"\n")
            self.request.sendall(data)
        except OSError:
            pass


class Server(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> int:
    directory = os.path.dirname(SOCKET_PATH)
    if directory:
        os.makedirs(directory, exist_ok=True)
    if os.path.exists(SOCKET_PATH):
        os.unlink(SOCKET_PATH)
    if not VOICE.warm():
        print("the voice model could not be loaded", flush=True)
        # The socket still comes up, so the backend gets a clear error rather
        # than a connection that never answers.
    server = Server(SOCKET_PATH, Handler)
    os.chmod(SOCKET_PATH, 0o600)
    print(f"the JARVIS voice is listening on {SOCKET_PATH}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        try:
            os.unlink(SOCKET_PATH)
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
