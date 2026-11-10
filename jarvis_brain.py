"""jarvis_brain.py — Action-aware reasoning for the Android companion.

The brain turns one spoken request into either a spoken answer or a structured
device action. It is deliberately constrained to the action vocabulary the
companion can execute, and every action it produces is re-validated by the
bridge before it can reach the device.

The planner never invents data. A phone number is only used when the user
actually said one, and an app is only targeted when it exists in the list of
apps the device reported.
"""

from __future__ import annotations

import json
import logging
import re
import uuid


log = logging.getLogger("jarvis.brain")

# Actions the companion can execute, with their required and optional arguments.
ACTION_ARGUMENTS: dict[str, tuple[set[str], set[str]]] = {
    "open_app": ({"name"}, set()),
    "open_package": ({"package"}, set()),
    "open_settings": (set(), set()),
    "set_alarm": ({"time"}, set()),
    "open_dialer": (set(), {"number"}),
    "call": ({"number"}, set()),
    "prepare_sms": ({"number", "text"}, set()),
    "prepare_whatsapp": ({"number", "text"}, set()),
    "read_notifications": (set(), set()),
    "accessibility": ({"navigation"}, set()),
    "call_contact": ({"name"}, set()),
    "open_chat": ({"name"}, {"text", "app"}),
    "click_text": ({"text"}, set()),
    "toggle_flashlight": (set(), {"state"}),
    "set_volume": ({"level"}, set()),
    "media_control": ({"action"}, set()),
    "set_timer": ({"duration"}, {"message"}),
    "set_calendar": ({"title"}, {"time"}),
}

# Actions that the bridge treats as sensitive and always requires a spoken
# confirmation for.
SENSITIVE_ACTIONS = {
    "call",
    "prepare_sms",
    "prepare_whatsapp",
    "read_notifications",
    "call_contact",
    "open_chat",
}

NAVIGATION_ACTIONS = {"back", "home", "recents"}

MAX_APPS_IN_PROMPT = 120
MAX_APP_LABEL = 60
MAX_APP_PACKAGE = 120

_FENCE_RE = re.compile(r"```(?:json)?\s*(\{.*?\})\s*```", re.DOTALL)
_NUMBER_RE = re.compile(r"^[0-9]{7,15}$")
_TIME_RE = re.compile(r"^([01]?[0-9]|2[0-3]):[0-5][0-9]$")
_PACKAGE_RE = re.compile(r"^[A-Za-z0-9._]{1,120}$")

_ACTION_DOC = """open_app {"name": "<app name>"}
open_package {"package": "<package id>"}
open_settings {}
open_dialer {} or {"number": "<digits>"}
set_alarm {"time": "<HH:MM>"}
call {"number": "<digits>"}
call_contact {"name": "<person name>"}
prepare_sms {"number": "<digits>", "text": "<message>"}
prepare_whatsapp {"number": "<digits>", "text": "<message>"}
open_chat {"name": "<person name>"} with optional "text" and "app" ("whatsapp" or "sms")
read_notifications {}
accessibility {"navigation": "back|home|recents"}
click_text {"text": "<visible label on screen>"}
toggle_flashlight {} or {"state": "on|off"}
set_volume {"level": "up|down|mute|max|0-100"}
media_control {"action": "play|pause|next|previous|stop"}
set_timer {"duration": "<minutes>"} with optional "message"
set_calendar {"title": "<event title>"} with optional "time"
"""


def build_action_system(apps: list[dict[str, str]] | None) -> str:
    """Return the system prompt that teaches the model the device vocabulary."""
    system = (
        "You are JARVIS, the personal assistant running on this phone. You can act on the "
        "device as well as talk to the user.\n\n"
        "When the request needs something done on the phone, reply with exactly one JSON "
        "object inside a fenced block and no other text:\n"
        "```json\n"
        '{"action": "<action type>", "args": {...}}\n'
        "```\n\n"
        "Supported action types and their arguments:\n"
        f"{_ACTION_DOC}\n\n"
        "Rules for actions:\n"
        "- Only use a phone number that the user actually said. Never invent one. If no "
        "number was given, ask for it in plain text instead of returning an action.\n"
        "- Only target apps from the installed app list. If the app is not listed, say so.\n"
        "- Use the exact app name from the list so the device can find it.\n"
        "- One action per reply. Do not chain actions.\n"
        "- If no action is needed, answer in plain spoken text and do not include a JSON block.\n"
        "- Keep spoken answers to one or two short sentences. This is a voice assistant.\n"
        "- Never claim an action was completed. The device reports the real result."
    )
    listing = format_apps(apps)
    if listing:
        system += "\n\nInstalled apps on this device (use these exact names):\n" + listing
    return system


def format_apps(apps: list[dict[str, str]] | None) -> str:
    """Render the device app list compactly for the prompt."""
    if not apps:
        return ""
    rows: list[str] = []
    seen: set[str] = set()
    for entry in apps[:MAX_APPS_IN_PROMPT]:
        if not isinstance(entry, dict):
            continue
        label = str(entry.get("label", "")).strip()
        package = str(entry.get("package", "")).strip()
        if not label or not package:
            continue
        if len(label) > MAX_APP_LABEL or len(package) > MAX_APP_PACKAGE:
            continue
        if package in seen:
            continue
        seen.add(package)
        rows.append(f"- {label} ({package})")
    return "\n".join(rows)


def strip_action_blocks(reply: str) -> str:
    """Remove any action block from a reply so only spoken text remains."""
    if not reply:
        return ""
    return _FENCE_RE.sub("", reply).strip()


def extract_action(reply: str, apps: list[dict[str, str]] | None = None) -> dict | None:
    """Extract a validated device action from a model reply.

    Returns a dict shaped for the bridge validator, or None when the reply is
    ordinary spoken text.
    """
    if not reply:
        return None
    for match in _FENCE_RE.finditer(reply):
        candidate = match.group(1)
        try:
            parsed = json.loads(candidate)
        except (TypeError, ValueError):
            continue
        action = build_action(parsed, apps)
        if action is not None:
            return action
    return None


def build_action(parsed: object, apps: list[dict[str, str]] | None = None) -> dict | None:
    """Validate a model supplied action object against the device vocabulary."""
    if not isinstance(parsed, dict):
        return None
    action_type = parsed.get("action")
    if not isinstance(action_type, str):
        return None
    action_type = action_type.strip().lower()
    if action_type not in ACTION_ARGUMENTS:
        return None
    raw_args = parsed.get("args", {})
    if raw_args is None:
        raw_args = {}
    if not isinstance(raw_args, dict):
        return None
    required, optional = ACTION_ARGUMENTS[action_type]
    args: dict[str, str] = {}
    for key, value in raw_args.items():
        if not isinstance(key, str) or not re.fullmatch(r"[a-z_]{1,32}", key):
            return None
        if key not in required and key not in optional:
            return None
        if isinstance(value, bool) or not isinstance(value, (str, int, float)):
            return None
        text = str(value).strip()
        if not text or len(text) > 240:
            return None
        args[key] = text
    if not required.issubset(args):
        return None

    if action_type == "call" and not _NUMBER_RE.fullmatch(args.get("number", "")):
        return None
    if action_type in {"prepare_sms", "prepare_whatsapp"}:
        if not _NUMBER_RE.fullmatch(args.get("number", "")):
            return None
        if not args.get("text", "").strip():
            return None
    if action_type == "set_alarm" and not _TIME_RE.fullmatch(args.get("time", "")):
        return None
    if action_type == "accessibility" and args.get("navigation", "") not in NAVIGATION_ACTIONS:
        return None
    if action_type == "open_package" and not _PACKAGE_RE.fullmatch(args.get("package", "")):
        return None
    if action_type in {"call_contact", "open_chat"}:
        name = args.get("name", "").strip()
        if not name or len(name) > 60:
            return None
        args["name"] = name
    if action_type == "open_app":
        name = args.get("name", "")
        if not name.strip():
            return None
        if apps:
            known = {str(item.get("label", "")).strip().lower() for item in apps if isinstance(item, dict)}
            known |= {str(item.get("package", "")).strip().lower() for item in apps if isinstance(item, dict)}
            if name.strip().lower() not in known:
                # The model may name the app slightly differently. Accept it only
                # when it clearly matches one of the installed apps.
                if not any(name.strip().lower() in other or other in name.strip().lower() for other in known):
                    return None
        args["name"] = name

    sensitive = action_type in SENSITIVE_ACTIONS
    return {
        "action_id": uuid.uuid4().hex,
        "action": action_type,
        "args": args,
        "requires_confirmation": sensitive,
        "sensitive": sensitive,
    }


def plan_action(ai, text: str, apps: list[dict[str, str]] | None = None) -> dict | None:
    """Ask the model for a device action for one request.

    Returns a validated action dict or None when the model answered in text.
    """
    if ai is None or not isinstance(text, str) or not text.strip():
        return None
    ask = getattr(ai, "ask", None)
    if not callable(ask):
        return None
    system = build_action_system(apps)
    try:
        reply = ask(text, system)
    except Exception:
        log.exception("The action planner failed")
        return None
    text_reply = getattr(reply, "text", None)
    if not isinstance(text_reply, str):
        text_reply = reply if isinstance(reply, str) else ""
    return extract_action(text_reply, apps)
