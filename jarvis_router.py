"""Conservative, deterministic request classification for JARVIS."""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class Route:
    kind: str
    query: str = ""


_TIME = re.compile(r"^(?:(?:what(?:'s| is)?\s+)?(?:the\s+)?time(?:\s+is\s+it)?|time)[?.! ]*$", re.I)
_DATE = re.compile(r"^(?:(?:what(?:'s| is)\s+)?(?:the\s+)?date(?:\s+is\s+it)?|today(?:'s)? date)[?.! ]*$", re.I)
_WEATHER = re.compile(r"\bweather\b(?:\s+(?:in|for|at)\s+(.+?))?[?.! ]*$", re.I)
_SEARCH_CUES = re.compile(r"\b(search|look up|find online|web search|latest|current|today's news|breaking news|recent news|stock price|share price)\b", re.I)
_DEVICE_ACTIONS = re.compile(
    r"^(?:open|launch|start)\s+.+$|"
    r"\b(?:open|launch|start)\s+(?:the\s+)?(?:app|application|spotify|youtube|chrome|whatsapp|camera|settings|phone|messages|clock)\b|"
    r"\b(?:call|dial|place a call|set an alarm|send (?:a )?(?:text|sms|message)|send whatsapp)\b",
    re.I,
)
_DANGEROUS = re.compile(
    r"\b(?:delete|erase|remove)\s+(?!my\s+(?:saved\s+)?mem(?:ory|ories)\b)|"
    r"\b(?:buy|purchase|pay|transfer money|make a payment|change security settings|disable security)\b",
    re.I,
)


def route_request(text: str) -> Route:
    value = " ".join(text.strip().split())
    if _TIME.fullmatch(value):
        return Route("local_time")
    if _DATE.fullmatch(value):
        return Route("local_date")
    if _WEATHER.search(value):
        match = _WEATHER.search(value)
        return Route("weather", (match.group(1) or "").strip(" .!?"))
    if _DANGEROUS.search(value):
        return Route("blocked_action")
    if _DEVICE_ACTIONS.search(value):
        return Route("android_action")
    if _SEARCH_CUES.search(value):
        return Route("web_search", value)
    return Route("conversation", value)
