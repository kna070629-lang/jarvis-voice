"""jarvis_core.engines.verification — The verification engine.

The engine checks what an agent produced before it becomes the answer. It
catches the two failures that matter most: a claim that something was done
when it was not, and an answer that is empty or malformed.
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass, field


# Phrases that claim an action happened. They are only allowed when the
# result carries evidence.
_ACTION_CLAIMS = re.compile(
    r"\b(?:i (?:have )?(?:sent|opened|started|called|set|turned|created|deleted|installed)"
    r"|done|completed|successfully|sent it|opened it|it is (?:now )?(?:on|off|running))\b",
    re.IGNORECASE,
)

# Phrases that claim a real world effect.
_EFFECT_CLAIMS = re.compile(
    r"\b(?:sent|opened|started|called|dialled|installed|deleted|purchased|paid)\b",
    re.IGNORECASE,
)


@dataclass
class VerificationResult:
    """The outcome of verifying a result."""

    ok: bool
    confidence: float
    issues: list[str] = field(default_factory=list)
    corrected_text: str = ""
    evidence_count: int = 0

    def to_dict(self) -> dict:
        return {
            "ok": self.ok,
            "confidence": round(self.confidence, 3),
            "issues": list(self.issues),
            "corrected_text": self.corrected_text,
            "evidence_count": self.evidence_count,
        }


class VerificationEngine:
    """Checks agent results for unsupported claims and empty answers."""

    def __init__(self, min_confidence: float = 0.35):
        self.min_confidence = min_confidence
        self._checks = 0
        self._passed = 0

    def verify(self, result: object, evidence: list | None = None, task_type: str = "") -> VerificationResult:
        """Verify one result."""
        self._checks += 1
        evidence = list(evidence or [])
        issues: list[str] = []

        if result is None:
            self._passed += 1
            return VerificationResult(
                ok=True,
                confidence=0.0,
                issues=["the task produced no result"],
                evidence_count=len(evidence),
            )

        text = result if isinstance(result, str) else str(result)
        if not text.strip():
            self._passed += 1
            return VerificationResult(
                ok=False,
                confidence=0.0,
                issues=["the answer was empty"],
                evidence_count=len(evidence),
            )

        confidence = 1.0
        corrected = text

        # An action claim without evidence is the failure that must never pass.
        if task_type in ("notify", "alarm", "timer", "call", "message", "launch",
                         "volume", "media", "flashlight", "automation", "device_action"):
            if _EFFECT_CLAIMS.search(text) and not evidence:
                issues.append("the result claims an action but carries no evidence")
                confidence *= 0.4
        elif _ACTION_CLAIMS.search(text) and not evidence and task_type == "answer":
            confidence *= 0.85

        # A refusal that claims success is worse than a plain refusal.
        if re.search(r"\bi (?:cannot|can't|am unable to)\b", text, re.IGNORECASE) and \
                re.search(r"\b(?:successfully|done|completed)\b", text, re.IGNORECASE):
            issues.append("the result reports a failure and a success at the same time")
            confidence *= 0.3

        ok = confidence >= self.min_confidence and not issues
        if ok:
            self._passed += 1
        return VerificationResult(
            ok=ok,
            confidence=confidence,
            issues=issues,
            corrected_text=corrected,
            evidence_count=len(evidence),
        )

    def stats(self) -> dict:
        return {
            "checks": self._checks,
            "passed": self._passed,
            "failed": self._checks - self._passed,
            "min_confidence": self.min_confidence,
        }
