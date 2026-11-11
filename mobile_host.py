#!/usr/bin/env python3
"""Run the Python-first engine behind the existing authenticated Android bridge."""

from __future__ import annotations

import logging
import os
import threading
import time

from pathlib import Path

from jarvis_actions import interpret_device_action
from jarvis_config import load_settings
from jarvis_search import WikipediaSearchProvider
from jarvis_core import JarvisCore
from jarvis_engine import JarvisEngine
from jarvis_selfcheck import SelfCheck
from mobile_bridge import create_server


log = logging.getLogger("jarvis.mobile")
ENGINE: JarvisEngine | None = None
_ENGINE_LOCK = threading.Lock()
_SELFCHECK: SelfCheck | None = None
_CORE: JarvisCore | None = None


def core() -> JarvisCore:
    """The central orchestrator. It is built once and shared."""
    global _CORE
    if _CORE is None:
        with _ENGINE_LOCK:
            if _CORE is None:
                _CORE = JarvisCore(
                    settings=load_settings(),
                    providers=_core_providers(),
                    search=WikipediaSearchProvider(),
                    repository_root=str(Path(__file__).parent),
                )
    return _CORE


def _core_providers() -> list:
    """The providers the core may use. The same set the engine uses."""
    from ai_provider import build_default_provider

    settings = load_settings()
    router = build_default_provider(settings)
    return list(getattr(router, "providers", []) or [])


def _engine() -> JarvisEngine:
    global ENGINE
    if ENGINE is None:
        with _ENGINE_LOCK:
            if ENGINE is None:
                ENGINE = JarvisEngine(session_key="jarvis-mobile")
    return ENGINE


def process_message(text: str, device: dict | None = None) -> object:
    """Process one authenticated chat request without shell execution.

    Returns plain spoken text, or a validated device action dict when the
    model planned an action for the paired Android companion.
    """
    try:
        return _engine().process(text, device)
    except Exception:
        log.exception("JARVIS engine failed while processing a request")
        return "JARVIS encountered a local processing error. No device action was taken. Please try again."


def process_message_stream(text: str, device: dict | None = None, on_delta=None) -> object:
    """Process a chat request while streaming the answer as it is written.

    The companion speaks each sentence as soon as it is ready, so the reply
    starts being heard long before the whole answer has been generated.
    """
    try:
        return _engine().process(text, device, on_delta=on_delta)
    except Exception:
        log.exception("JARVIS engine failed while streaming a request")
        return "JARVIS encountered a local processing error. No device action was taken. Please try again."


def provider_status() -> dict[str, object]:
    """Return non-secret runtime diagnostics for the authenticated health check."""
    try:
        return _engine().status()
    except Exception:
        log.exception("Could not read JARVIS runtime status")
        return {"mode": "unknown", "openrouter": "unavailable", "memory": "unavailable"}


def warm_up() -> None:
    """Start the AI server in the background so the first reply is fast."""
    def run() -> None:
        try:
            engine = _engine()
        except Exception:
            log.exception("Could not build the engine for the warm-up")
            return
        provider = getattr(engine, "ai", None)
        warmer = None
        for candidate in getattr(provider, "providers", []) or []:
            if hasattr(candidate, "warm"):
                warmer = candidate
                break
        if warmer is None:
            return
        try:
            if warmer.warm():
                log.info("The AI provider is warm and ready")
        except Exception:
            log.exception("The warm-up failed")

    threading.Thread(target=run, name="jarvis-warmup", daemon=True).start()


def self_check() -> dict:
    """Run the health checks and apply the safe repairs."""
    global _SELFCHECK
    try:
        engine = _engine()
    except Exception:
        log.exception("Could not build the engine for the self check")
        return {"ok": False}
    if _SELFCHECK is None:
        _SELFCHECK = SelfCheck(engine=engine)
    return _SELFCHECK.run()


def learning_report() -> dict:
    """Read the bridge log and write a report of recurring problems."""
    global _SELFCHECK
    try:
        engine = _engine()
    except Exception:
        engine = None
    if _SELFCHECK is None:
        _SELFCHECK = SelfCheck(engine=engine)
    return _SELFCHECK.learn_from_logs()


def learning_loop() -> None:
    """Writes a learning report on a schedule so the assistant keeps learning."""
    interval = max(900, int(os.environ.get("JARVIS_LEARN_INTERVAL", "3600")))
    while True:
        time.sleep(interval)
        try:
            report = learning_report()
            patterns = report.get("patterns") or {}
            if patterns:
                log.info("Learning report found %d recurring pattern(s)", len(patterns))
        except Exception:
            log.exception("The learning report failed")


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        _engine()
    except Exception:
        log.exception("JARVIS local startup failed")
        return 1
    server = create_server(
        process_message,
        status_provider=provider_status,
        action_planner=interpret_device_action,
        process_message_stream=process_message_stream,
        self_check=self_check,
        core=core(),
    )
    print("JARVIS Python backend listening on 127.0.0.1:8765; press Ctrl-C to stop.", flush=True)
    print("Provider status: " + str(provider_status()), flush=True)
    warm_up()
    threading.Thread(target=learning_loop, name="jarvis-learning", daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
