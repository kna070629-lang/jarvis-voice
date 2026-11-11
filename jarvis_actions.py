"""Structured, non-executing device action interpretation for the bridge.

The Python backend may *understand* a phone command and return an action
request, but it never executes one.  The Android companion validates and runs
the request, then posts the result back.  This module therefore only produces
validated JSON-serialisable descriptions.
"""

from __future__ import annotations

import re
import secrets
import time


MAX_ARG_CHARS = 240
_WAKE_PREFIX = re.compile(
    r"^\s*(?:hey\s+|ok(?:ay)?\s+|hello\s+|arey\s+|arre\s+)?jarvis\s*(?:,\s*)?",
    re.IGNORECASE,
)
_OPEN = re.compile(r"^(?:open|launch|start|activate|run)\s+(.+?)\s*[.!?]*$", re.IGNORECASE)
_ALARM = re.compile(
    r"^(?:set|create)\s+(?:an?\s+)?alarm(?:\s+for)?\s+(.+?)\s*[.!?]*$",
    re.IGNORECASE,
)
_CALL = re.compile(r"^(?:call|phone)\s+([+0-9][+0-9 ().-]{4,24})\s*[.!?]*$", re.IGNORECASE)
# Calling or messaging a person by name. The number comes from the contacts
# on the device, so the user never has to say a number.
_CALL_NAME = re.compile(
    r"^(?:call|phone|ring)\s+(?!the\s+dialer)(?:my\s+|please\s+)?([A-Za-z][A-Za-z .'-]{0,58}?)\s*[.!?]*$",
    re.IGNORECASE,
)
_CHAT_NAME = re.compile(
    r"^(?:open|start|resume|continue)\s+(?:a\s+|the\s+)?(?:chat|conversation|messages?)\s+with\s+"
    r"([A-Za-z][A-Za-z .'-]{0,58}?)\s*[.!?]*$",
    re.IGNORECASE,
)
# A phrase that is really a question must not become a device action.
_QUESTION_CUE = re.compile(
    r"\b(?:about|regarding|concerning|why|how|what|when|where|which|who|"
    r"explain|tell me|difference|question|information|info|details?)\b",
    re.IGNORECASE,
)
# Device controls that do not need the model to work out anything.
_FLASHLIGHT = re.compile(
    r"^(?:turn\s+)?(?:the\s+)?(flashlight|torch|flash\s+light)\s+(on|off)\s*[.!?]*$",
    re.IGNORECASE,
)
_FLASHLIGHT_REVERSE = re.compile(
    r"^turn\s+(on|off)\s+(?:the\s+)?(?:flashlight|torch|flash\s+light)\s*[.!?]*$",
    re.IGNORECASE,
)
_FLASHLIGHT_TOGGLE = re.compile(
    r"^(?:turn\s+)?(?:the\s+)?(flashlight|torch|flash\s+light)\s*[.!?]*$",
    re.IGNORECASE,
)
_VOLUME = re.compile(
    r"^(?:set\s+)?(?:the\s+)?volume\s+(up|down|mute|unmute|max|min|full|[0-9]{1,3}\s*%?)\s*[.!?]*$",
    re.IGNORECASE,
)
_MEDIA = re.compile(
    r"^(play|pause|resume|stop|skip|next|previous|prev)\s+(?:the\s+)?(?:music|song|media|track|video|playback)\s*[.!?]*$",
    re.IGNORECASE,
)
_TIMER = re.compile(
    r"^(?:set|start)\s+(?:a\s+)?timer\s+(?:for\s+)?([0-9]{1,3})\s*(?:minutes?|mins?|m)\s*[.!?]*$",
    re.IGNORECASE,
)
_DIAL = re.compile(r"^(?:dial|open\s+(?:the\s+)?dialer)\s*([+0-9][+0-9 ().-]{4,24})?\s*[.!?]*$", re.IGNORECASE)
_SMS = re.compile(
    r"^(?:prepare|draft|send)\s+(?:an?\s+)?(?:sms|text(?:\s+message)?)\s+to\s+"
    r"([+0-9][+0-9 ().-]{4,24})\s+(?:saying|that\s+says)\s+(.+?)\s*[.!?]*$",
    re.IGNORECASE,
)
_WHATSAPP = re.compile(
    r"^(?:prepare|draft|send)\s+(?:a\s+)?whatsapp\s+message\s+to\s+"
    r"([+0-9][+0-9 ().-]{4,24})\s+(?:saying|that\s+says)\s+(.+?)\s*[.!?]*$",
    re.IGNORECASE,
)
_NOTIFICATIONS = re.compile(r"^(?:read|show|check|summari[sz]e)\s+(?:my\s+|the\s+)?notifications?[.!?]*$", re.IGNORECASE)
_NAVIGATION = re.compile(
    r"^(?:(?:go|navigate|take\s+me|show|open)(?:\s+to)?\s+)?(back|home|recents?|recent\s+apps?)[.!?]*$",
    re.IGNORECASE,
)

_SAFE_TIME = re.compile(r"^[0-9]{1,2}(?::[0-9]{2})?\s*(?:am|pm)?$", re.IGNORECASE)

# Reject sentence-like text after open/start/run so ordinary chat is not
# misclassified as an app launch. Real multi-word app names stay allowed.
_APP_STOPWORDS = frozenset({
    "the", "a", "an", "my", "your", "our", "their", "his", "her", "this",
    "that", "these", "those", "is", "are", "was", "were", "be", "been",
    "being", "am", "do", "does", "did", "can", "could", "should", "would",
    "will", "shall", "may", "might", "must", "have", "has", "had", "to",
    "of", "in", "on", "at", "by", "for", "with", "from", "about", "into",
    "over", "under", "and", "or", "but", "if", "then", "than", "so",
    "because", "while", "when", "where", "what", "which", "who", "whom",
    "whose", "why", "how", "quick", "quickly", "meeting", "notes",
    "calculation", "source", "important", "file", "files", "folder",
    "document", "documents", "website", "site", "link", "terminal", "shell",
    "script", "code", "project", "app", "apps",
})


def _plausible_app_name(name: str) -> bool:
    words = name.casefold().split()
    if not 1 <= len(words) <= 3:
        return False
    if len(name) > 60:
        return False
    return not any(word in _APP_STOPWORDS for word in words)


_APP_PREFIX = frozenset({"the", "a", "an", "my", "our", "up"})
_APP_SUFFIX = frozenset({"please", "now", "app", "apps", "application", "for", "me"})
_WHATSAPP_ALIASES = frozenset({"whatsapp", "whatsappbusiness", "whatsappmessenger"})
_APP_ALIASES = {
    "whatsapp": "whatsapp",
    "whatsappbusiness": "whatsapp business",
    "whatsappmessenger": "whatsapp",
    "instagram": "instagram",
    "insta": "instagram",
    "youtube": "youtube",
    "playstore": "play store",
}


def _clean_app_name(value: str) -> str:
    """Normalize spoken app names: filler words, possessives, common ASR splits."""
    name = _clean(value)
    name = re.sub(r"['\u2019]s\b", "s", name, flags=re.IGNORECASE)
    name = name.replace("'", "").replace("\u2019", "")
    words = name.split()
    while words and words[0].casefold() in _APP_PREFIX:
        words.pop(0)
    compact = "".join(words).casefold()
    if compact in _WHATSAPP_ALIASES:
        return "whatsapp"
    if compact in _APP_ALIASES:
        return _APP_ALIASES[compact]
    while words and words[-1].casefold() in _APP_SUFFIX:
        words.pop()
    cleaned = " ".join(words)
    compact = cleaned.replace(" ", "").casefold()
    if compact in _WHATSAPP_ALIASES:
        return "whatsapp"
    return _APP_ALIASES.get(compact, cleaned)


def _request(action: str, args: dict[str, str], *, confirmation: bool = False, sensitive: bool = False) -> dict:
    """Build a device action in the shared contract.

    The Android companion is the only thing that executes an action, so the
    contract states the target, the arguments, whether a confirmation is
    needed and what has not happened yet.
    """
    action_id = secrets.token_hex(16)
    target = args.get("name") or args.get("package") or args.get("number") or ""
    return {
        "action_id": action_id,
        "request_id": action_id,
        "action": action,
        "action_type": action,
        "args": args,
        "arguments": args,
        "target": target,
        "permissions": [],
        "permission_status": "unknown",
        "requires_confirmation": bool(confirmation),
        "execution_status": "pending",
        "result": "",
        "error": "",
        "sensitive": bool(sensitive),
        "timestamp": time.time(),
    }


def _clean(value: str) -> str:
    return " ".join(str(value).strip().split())[:MAX_ARG_CHARS]


def _digits(value: str) -> str:
    return "".join(char for char in value if char.isdigit())


def _looks_like_number(value: str) -> bool:
    """True when the text is a spoken number rather than a person's name."""
    digits = _digits(value)
    letters = "".join(char for char in value if char.isalpha())
    if digits and len(letters) <= 2:
        return True
    return bool(re.fullmatch(r"[\s+\-().0-9]+", value))


def _strip_wake(text: str) -> str:
    value = " ".join(str(text or "").strip().split())
    stripped = _WAKE_PREFIX.sub("", value, count=1).strip()
    return stripped or value


def interpret_device_action(text: str) -> dict | None:
    """Return a structured action request, or None for ordinary chat text."""
    value = _strip_wake(text)
    if not value or len(value) > 500:
        return None
    lowered = value.casefold()

    if _NOTIFICATIONS.fullmatch(value):
        return _request("read_notifications", {}, confirmation=True, sensitive=True)

    navigation = _NAVIGATION.fullmatch(value)
    if navigation:
        target = navigation.group(1).casefold()
        action = "recents" if target.startswith("recent") else target
        return _request("accessibility", {"navigation": action})

    whatsapp = _WHATSAPP.fullmatch(value)
    if whatsapp:
        number = _digits(whatsapp.group(1))
        body = _clean(whatsapp.group(2))
        if len(number) in range(7, 16) and body:
            # Confirmation stays on. The bridge refuses to downgrade it, and the
            # app asks for a spoken yes instead of sending the user to the app.
            return _request("prepare_whatsapp", {"number": number, "text": body}, confirmation=True, sensitive=True)
        return None

    sms = _SMS.fullmatch(value)
    if sms:
        number = _digits(sms.group(1))
        body = _clean(sms.group(2))
        if len(number) in range(7, 16) and body:
            return _request("prepare_sms", {"number": number, "text": body}, confirmation=True, sensitive=True)
        return None

    call = _CALL.fullmatch(value)
    if call:
        number = _digits(call.group(1))
        if len(number) in range(7, 16):
            # The spoken command is the authorisation for a call. Asking again
            # is the repetitive confirmation the user complained about.
            return _request("call", {"number": number}, confirmation=False, sensitive=False)
        return None

    call_name = _CALL_NAME.fullmatch(value)
    if call_name and not _QUESTION_CUE.search(value):
        name = _clean(call_name.group(1))
        if name and not _looks_like_number(name):
            # The spoken command is the authorisation for a call. Asking again
            # is the repetitive confirmation the user complained about.
            return _request("call_contact", {"name": name}, confirmation=False, sensitive=False)
        return None

    chat_name = _CHAT_NAME.fullmatch(value)
    if chat_name and not _QUESTION_CUE.search(value):
        name = _clean(chat_name.group(1))
        if name:
            return _request("open_chat", {"name": name}, confirmation=True, sensitive=True)
        return None

    flashlight = _FLASHLIGHT.fullmatch(value)
    if flashlight:
        return _request("toggle_flashlight", {"state": flashlight.group(2).lower()})

    flashlight_reverse = _FLASHLIGHT_REVERSE.fullmatch(value)
    if flashlight_reverse:
        return _request("toggle_flashlight", {"state": flashlight_reverse.group(1).lower()})

    if _FLASHLIGHT_TOGGLE.fullmatch(value):
        return _request("toggle_flashlight", {})

    volume = _VOLUME.fullmatch(value)
    if volume:
        level = volume.group(1).lower().replace(" ", "").replace("%", "")
        return _request("set_volume", {"level": level})

    media = _MEDIA.fullmatch(value)
    if media:
        action = media.group(1).lower()
        if action == "resume":
            action = "play"
        if action in {"skip"}:
            action = "next"
        return _request("media_control", {"action": action})

    timer = _TIMER.fullmatch(value)
    if timer:
        minutes = int(timer.group(1))
        if 1 <= minutes <= 600:
            return _request("set_timer", {"duration": str(minutes)})
        return None

    dial = _DIAL.fullmatch(value)
    if dial:
        number = _digits(dial.group(1) or "")
        args = {"number": number} if len(number) in range(7, 16) else {}
        return _request("open_dialer", args)

    alarm = _ALARM.fullmatch(value)
    if alarm:
        spoken = _clean(alarm.group(1))
        if spoken and len(spoken) <= 40 and _SAFE_TIME.fullmatch(spoken):
            return _request("set_alarm", {"time": spoken})
        return None

    if lowered in {"settings", "android settings", "system settings"}:
        return _request("open_settings", {})

    opened = _OPEN.fullmatch(value)
    if opened:
        name = _clean_app_name(opened.group(1))
        if not name or len(name) > 80 or not _plausible_app_name(name):
            return None
        if name.casefold() in {"settings", "android settings", "system settings"}:
            return _request("open_settings", {})
        if name.casefold() in {"dialer", "phone dialer"}:
            return _request("open_dialer", {})
        return _request("open_app", {"name": name})

    return None
