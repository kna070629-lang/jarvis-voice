#!/usr/bin/env python3
"""Run the existing OpenClaw backend for the Android companion without TUI mic/VAD."""

from __future__ import annotations

import logging

import tui
from mobile_bridge import create_server


def process_message(text: str) -> str:
    # Keep using the existing authenticated OpenClaw CLI/gateway and session.
    return tui.send_message(tui.SESSION_KEY, text) or tui.get_response(text)


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    server = create_server(process_message)
    print("JARVIS mobile backend listening on 127.0.0.1:8765; press Ctrl-C to stop.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
