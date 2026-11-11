"""jarvis_core.engines.security — The security engine.

Nothing reaches the device without passing through here. The engine checks
the permissions an action needs, scores how risky it is and decides whether
the user has to confirm it first.
"""

from __future__ import annotations

import logging
import re
import threading
import time
from dataclasses import dataclass, field

from ..messages import Message, Status


log = logging.getLogger("jarvis.core.security")


@dataclass
class SecurityDecision:
    """The outcome of a security check."""

    allowed: bool
    requires_confirmation: bool = False
    risk: str = "low"
    reason: str = ""
    missing_permissions: list[str] = field(default_factory=list)
    action: str = ""

    def to_dict(self) -> dict:
        return {
            "allowed": self.allowed,
            "requires_confirmation": self.requires_confirmation,
            "risk": self.risk,
            "reason": self.reason,
            "missing_permissions": list(self.missing_permissions),
            "action": self.action,
        }


class SecurityEngine:
    """Authorises an action and decides whether it needs confirmation."""

    #: Actions that always need the user to confirm.
    SENSITIVE_ACTIONS = {
        "prepare_sms",
        "prepare_whatsapp",
        "open_chat",
        "read_notifications",
        "send_message",
        "purchase",
        "payment",
    }

    #: Actions that are never allowed, whatever a model asks for.
    FORBIDDEN_ACTIONS = {
        "purchase",
        "payment",
        "transfer_money",
        "disable_security",
        "install_package",
        "uninstall_package",
        "shell",
        "exec",
        "run_shell",
    }

    #: Patterns that suggest a shell command slipped into a request.
    SHELL_PATTERN = re.compile(
        r"(?:^|[;&|`$])\s*(?:sudo|rm\s+-rf|chmod|chown|mkfs|dd\s+if|:\(\)\{|"
        r"curl\s+[^|]*\|\s*(?:ba)?sh|wget\s+[^|]*\|\s*(?:ba)?sh|eval\s|exec\s*\()",
        re.IGNORECASE,
    )

    def __init__(self, granted_permissions: set[str] | None = None):
        self._granted = set(granted_permissions or set())
        self._lock = threading.RLock()
        self._log: list[dict] = []

    def set_granted_permissions(self, permissions) -> None:
        with self._lock:
            self._granted = set(permissions or set())

    def granted_permissions(self) -> list[str]:
        with self._lock:
            return sorted(self._granted)

    def check(
        self,
        action: str,
        required_permissions: list[str] | None = None,
        payload: dict | None = None,
    ) -> SecurityDecision:
        """Check one action."""
        name = (action or "").strip().lower()
        payload = payload or {}

        if name in self.FORBIDDEN_ACTIONS:
            return self._decide(SecurityDecision(
                allowed=False,
                risk="critical",
                reason="That action is never allowed from a spoken or generated request.",
                action=name,
            ))

        text = " ".join(str(value) for value in payload.values())
        if self.SHELL_PATTERN.search(text):
            return self._decide(SecurityDecision(
                allowed=False,
                risk="critical",
                reason="That request looks like a shell command and was refused.",
                action=name,
            ))

        missing = [
            permission
            for permission in (required_permissions or [])
            if permission not in self._granted
        ]
        if missing:
            return self._decide(SecurityDecision(
                allowed=False,
                risk="medium",
                reason="Permission is needed for that action.",
                missing_permissions=missing,
                action=name,
            ))

        risk = self._risk(name, payload)
        needs_confirmation = name in self.SENSITIVE_ACTIONS or risk in ("high", "critical")
        return self._decide(SecurityDecision(
            allowed=True,
            requires_confirmation=needs_confirmation,
            risk=risk,
            reason="The user must confirm this action." if needs_confirmation else "",
            action=name,
        ))

    def _risk(self, action: str, payload: dict) -> str:
        """Score how risky an action is."""
        if action in ("call", "call_contact"):
            return "high"
        if action in ("prepare_sms", "prepare_whatsapp", "open_chat"):
            return "medium"
        if action == "read_notifications":
            return "medium"
        if action in ("click_text", "set_alarm", "set_timer", "set_calendar"):
            return "medium"
        if action in ("open_app", "open_package", "open_settings", "open_dialer"):
            return "low"
        if action in ("open_app", "open_package", "open_settings", "open_dialer"):
            return "low"
        # An action with a phone number is treated as sensitive even when the
        # name is unfamiliar.
        for value in payload.values():
            digits = "".join(ch for ch in str(value) if ch.isdigit())
            if len(digits) >= 7:
                return "medium"
        return "low"

    def _decide(self, decision: SecurityDecision) -> SecurityDecision:
        with self._lock:
            self._log.insert(0, {
                "action": decision.action,
                "allowed": decision.allowed,
                "risk": decision.risk,
                "requires_confirmation": decision.requires_confirmation,
                "reason": decision.reason,
                "at": time.time(),
            })
            del self._log[:-100]
        return decision

    def recent_decisions(self, limit: int = 30) -> list[dict]:
        with self._lock:
            return list(self._log)[:limit]

    def stats(self) -> dict:
        with self._lock:
            log_entries = list(self._log)
        allowed = [entry for entry in log_entries if entry["allowed"]]
        return {
            "checks": len(log_entries),
            "allowed": len(allowed),
            "refused": len(log_entries) - len(allowed),
            "confirmations": len([entry for entry in log_entries if entry["requires_confirmation"]]),
        }
