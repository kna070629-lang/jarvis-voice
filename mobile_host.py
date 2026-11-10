#!/usr/bin/env python3
"""Run the Python-first engine behind the existing authenticated Android bridge."""

from __future__ import annotations

import logging

from jarvis_engine import JarvisEngine
from mobile_bridge import create_server


log = logging.getLogger("jarvis.mobile")
ENGINE: JarvisEngine | None = None


def _engine() -> JarvisEngine:
    global ENGINE
    if ENGINE is None:
        ENGINE = JarvisEngine(session_key="jarvis-mobile")
    return ENGINE


def process_message(text: str) -> str:
    """Process one authenticated chat request without shell execution."""
    try:
        return _engine().process(text)
    except Exception:
        log.exception("JARVIS engine failed while processing a request")
        return "JARVIS encountered a local processing error. No device action was taken. Please try again."


def provider_status() -> dict[str, object]:
    """Return non-secret runtime diagnostics for the authenticated health check."""
    try:
        return _engine().status()
    except Exception:
        log.exception("Could not read JARVIS runtime status")
        return {"mode": "unknown", "openrouter": "unavailable", "memory": "unavailable"}


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        _engine()
    except Exception:
        log.exception("JARVIS local startup failed")
        return 1
    server = create_server(process_message, status_provider=provider_status)
    print("JARVIS Python backend listening on 127.0.0.1:8765; press Ctrl-C to stop.", flush=True)
    print("Provider status: " + str(provider_status()), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
