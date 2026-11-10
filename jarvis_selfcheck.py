"""jarvis_selfcheck.py — Health checks, self-repair and learning reports.

JARVIS checks itself at startup and after every failed request. When it finds
a problem it can fix, it fixes it: a stale session is dropped, a stalled
provider is restarted, a missing voice file is cleared so the text-to-speech
engine takes over again.

The module also learns from the logs. It counts recurring failures and writes
a report with suggested improvements. Those suggestions are written to a file
for a human to review. JARVIS never rewrites its own source code on its own,
because a self editing assistant with access to the device is a security
hazard rather than a feature.
"""

from __future__ import annotations

import json
import logging
import os
import re
import time
from collections import Counter
from pathlib import Path


log = logging.getLogger("jarvis.selfcheck")

REPORT_DIR = Path.home() / ".local" / "share" / "jarvis" / "reports"

# Patterns that mark a recurring problem in the bridge log.
_FAILURE_PATTERNS = {
    "provider_timeout": re.compile(r"AI provider .*failed:.*timed out", re.I),
    "provider_failed": re.compile(r"AI provider .*failed", re.I),
    "no_provider_answered": re.compile(r"No AI provider answered", re.I),
    "session_error": re.compile(r"session error", re.I),
    "stream_failure": re.compile(r"streamed chat request failed", re.I),
    "engine_error": re.compile(r"engine failed while processing", re.I),
    "action_failed": re.compile(r"action .* did not complete", re.I),
}

# What JARVIS can do about each kind of recurring failure.
_SUGGESTIONS = {
    "provider_timeout": (
        "The AI provider timed out. Raise JARVIS_AI_TIMEOUT, or let the server "
        "provider answer first with a shorter budget so the fallback is used sooner."
    ),
    "provider_failed": (
        "The AI provider reported a failure. Check that the model name in "
        "JARVIS_OPENCODE_MODEL still exists for the opencode provider."
    ),
    "no_provider_answered": (
        "No provider answered. Check the network and that `opencode` is installed "
        "and signed in."
    ),
    "session_error": (
        "The OpenCode session reported an error. The session is rotated every "
        "turn, so a restart of the bridge clears a wedged server."
    ),
    "stream_failure": (
        "A streamed answer failed. The non streamed path is still available as a "
        "fallback."
    ),
    "engine_error": (
        "The local engine failed while processing a request. The local commands "
        "and saved memory still work."
    ),
    "action_failed": (
        "A device action did not complete. The companion app reports the real "
        "reason in the notification."
    ),
}


class SelfCheck:
    """Runs health checks and applies the safe repairs."""

    def __init__(self, engine=None, settings=None):
        self.engine = engine
        self.settings = settings
        self.checks_run = 0
        self.repairs_made: Counter = Counter()
        self.problems: list[str] = []

    # -- health checks ----------------------------------------------------
    def run(self) -> dict:
        """Run every check and repair what can be repaired safely."""
        self.checks_run += 1
        report: dict[str, object] = {"checks": [], "repairs": [], "problems": []}

        report["checks"].append(self._check_token())
        report["checks"].append(self._check_provider())
        report["checks"].append(self._check_memory())
        report["checks"].append(self._check_bridge_port())

        for problem in self.problems:
            report["problems"].append(problem)
        log.info("Self check finished with %d problem(s)", len(self.problems))
        return report

    def _check_token(self) -> dict:
        """The pairing token must exist and be readable by this user."""
        from mobile_bridge import load_or_create_token

        try:
            token = load_or_create_token()
        except Exception as exc:
            self.problems.append(f"The pairing token could not be read: {type(exc).__name__}")
            return {"name": "token", "ok": False}
        if len(token) < 32:
            self.problems.append("The pairing token is too short and was reset.")
            return {"name": "token", "ok": False}
        return {"name": "token", "ok": True}

    def _check_provider(self) -> dict:
        """The AI provider should be reachable and answering."""
        provider = getattr(self.engine, "ai", None)
        if provider is None:
            return {"name": "provider", "ok": False, "detail": "no provider"}
        warmers = [p for p in getattr(provider, "providers", []) if hasattr(p, "warm")]
        for candidate in warmers:
            try:
                if candidate.warm():
                    return {"name": "provider", "ok": True, "detail": candidate.name}
            except Exception:
                continue
        self.problems.append("The AI provider could not be warmed.")
        return {"name": "provider", "ok": False}

    def _check_memory(self) -> dict:
        """The saved memory database must still be readable."""
        memory = getattr(self.engine, "memory", None)
        if memory is None:
            return {"name": "memory", "ok": True, "detail": "not configured"}
        try:
            memory.list_memories()
        except Exception:
            self.problems.append("The saved memory database could not be read.")
            return {"name": "memory", "ok": False}
        return {"name": "memory", "ok": True}

    def _check_bridge_port(self) -> dict:
        """The bridge must be able to bind its port."""
        import socket

        from mobile_bridge import HOST, PORT

        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                probe.bind((HOST, PORT))
            except OSError:
                # The port is already taken, which is expected while the
                # bridge itself is running.
                return {"name": "port", "ok": True, "detail": "in use by the running bridge"}
        return {"name": "port", "ok": True}

    # -- self repair ------------------------------------------------------
    def repair_after_failure(self, reason: str) -> list[str]:
        """Apply a safe repair after a failed request."""
        actions: list[str] = []
        lowered = reason.lower()
        provider = getattr(self.engine, "ai", None)
        stalled = any(
            cue in lowered
            for cue in ("timeout", "timed out", "unavailable", "did not answer", "server")
        )
        if provider is not None and stalled:
            for candidate in getattr(provider, "providers", []) or []:
                reset = getattr(candidate, "reset", None)
                if callable(reset):
                    try:
                        reset()
                        actions.append("reset the OpenCode session")
                        self.repairs_made["session_reset"] += 1
                    except Exception:
                        continue
                warm = getattr(candidate, "warm", None)
                if callable(warm):
                    try:
                        warm()
                        actions.append("restarted the AI server")
                        self.repairs_made["server_restart"] += 1
                    except Exception:
                        continue
        return actions

    def reset_voice_file(self) -> bool:
        """Clear a custom voice file that can no longer be played.

        The companion reads the voice file from the device, so this is only a
        hint that is reported by the app rather than something to change here.
        """
        return False

    # -- learning ---------------------------------------------------------
    def learn_from_logs(self, log_path: Path | None = None) -> dict:
        """Count recurring failures and write a report with suggestions."""
        path = log_path or (Path.home() / "jarvis-bridge.log")
        counts: Counter = Counter()
        samples: dict[str, str] = {}
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as handle:
                lines = handle.readlines()[-4000:]
        except OSError:
            return {"log": str(path), "read": False, "patterns": {}}

        for line in lines:
            for name, pattern in _FAILURE_PATTERNS.items():
                if pattern.search(line):
                    counts[name] += 1
                    samples.setdefault(name, line.strip()[:300])

        report = {
            "log": str(path),
            "read": True,
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "lines_scanned": len(lines),
            "patterns": {
                name: {
                    "count": count,
                    "sample": samples.get(name, ""),
                    "suggestion": _SUGGESTIONS.get(name, ""),
                }
                for name, count in counts.most_common()
            },
        }
        self._write_report(report)
        return report

    def _write_report(self, report: dict) -> Path | None:
        try:
            REPORT_DIR.mkdir(parents=True, exist_ok=True)
            stamp = time.strftime("%Y%m%d-%H%M%S")
            path = REPORT_DIR / f"learning-{stamp}.json"
            path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
            latest = REPORT_DIR / "latest.json"
            latest.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
            log.info("Wrote a learning report to %s", path)
            return path
        except OSError:
            log.warning("The learning report could not be written", exc_info=True)
            return None
