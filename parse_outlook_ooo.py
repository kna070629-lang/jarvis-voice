#!/usr/bin/env python3
"""Parse the Outlook ICS for OOO/Leave/Vacation events in a given date range.

Usage:
  parse_outlook_ooo.py <ics_path> <start_date> <end_date>
  parse_outlook_ooo.py outlook.ics 2026-08-11 2026-08-15
"""
import sys, re, datetime
from zoneinfo import ZoneInfo

OOO_PATTERNS = [
    r"\booo\b",
    r"out of office",
    r"out-of-office",
    r"\bholiday\b",
    r"\bvacation\b",
    r"\bleave\b",
    r"paternity",
    r"maternity",
    r"annual leave",
    r"\btime off\b",
    r"\bpto\b",
    r"\babsence\b",
    r"\baway\b",
    r"\bal\b",        # matches "AL" as a word (Annual Leave shorthand)
    r"\ba/l\b",       # matches "A/L"
]


WIN_TZ_MAP = {
    "GMT Standard Time": "Europe/London",
    "India Standard Time": "Asia/Kolkata",
    "Central European Standard Time": "Europe/Berlin",
    "GTB Standard Time": "Europe/Bucharest",
    "Egypt Standard Time": "Africa/Cairo",
    "Turkey Standard Time": "Europe/Istanbul",
    "UTC": "UTC",
}


def _resolve_tz(tzid):
    if not tzid:
        return datetime.timezone.utc
    if tzid in WIN_TZ_MAP:
        return ZoneInfo(WIN_TZ_MAP[tzid])
    try:
        return ZoneInfo(tzid)
    except Exception:
        # Unknown tz — fall back to UTC for safe ordering
        return datetime.timezone.utc


def parse_dt(value, tzid=None):
    """Parse an iCal date or datetime. Returns tz-aware UTC datetime."""
    value = value.strip()
    # Date-only: YYYYMMDD
    if re.fullmatch(r"\d{8}", value):
        d = datetime.datetime.strptime(value, "%Y%m%d")
        d = d.replace(tzinfo=_resolve_tz(tzid))
        return d.astimezone(datetime.timezone.utc)
    # Datetime with Z (UTC): YYYYMMDDTHHMMSSZ
    m = re.fullmatch(r"(\d{8})T(\d{6})Z", value)
    if m:
        d = datetime.datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S")
        return d.replace(tzinfo=datetime.timezone.utc)
    # Datetime naive: YYYYMMDDTHHMMSS (interpret as local-tzid)
    m = re.fullmatch(r"(\d{8})T(\d{6})", value)
    if m:
        d = datetime.datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S")
        d = d.replace(tzinfo=_resolve_tz(tzid))
        return d.astimezone(datetime.timezone.utc)
    raise ValueError(f"can't parse dt: {value!r}")


def is_ooo(summary, organizer=None):
    if not summary:
        return False
    s = summary.lower()
    return any(re.search(p, s) for p in OOO_PATTERNS)


def main():
    ics_path = sys.argv[1]
    start_date = datetime.date.fromisoformat(sys.argv[2])  # inclusive
    end_date = datetime.date.fromisoformat(sys.argv[3])    # inclusive

    with open(ics_path, "r", encoding="utf-8", errors="replace") as f:
        text = f.read()

    # Split into VEVENT blocks
    events = []
    cur = None
    for raw in text.splitlines():
        line = raw.strip()
        if line == "BEGIN:VEVENT":
            cur = {}
        elif line == "END:VEVENT":
            if cur:
                events.append(cur)
            cur = None
        elif cur is not None and ":" in line:
            # iCal escapes: \\, \,, \;, \n
            key, val = line.split(":", 1)
            key = key.split(";", 1)[0]
            cur.setdefault(key, val)
        # DTSTART has parameters: DTSTART;TZID=GMT Standard Time:20250811T090000
        # Also handles DTSTART;VALUE=DATE:20260803 (all-day events, common for OOO)
        if cur is not None:
            m = re.match(r"^DTSTART(?:;[^:]*)*:(.*)$", raw)
            if m and "DTSTART_VAL" not in cur:
                cur["DTSTART_TZID"] = None  # tzid is irrelevant for VALUE=DATE all-day
                cur["DTSTART_VAL"] = m.group(1)

    matches = []
    for ev in events:
        if "DTSTART_VAL" not in ev:
            continue
        try:
            dtstart = parse_dt(ev["DTSTART_VAL"], ev.get("DTSTART_TZID"))
        except ValueError:
            continue
        # Also parse DTEND for multi-day events (all-day DATE values)
        dtend = None
        if "DTEND" in ev:
            try:
                dtend = parse_dt(ev["DTEND"], ev.get("DTEND_TZID"))
            except ValueError:
                dtend = None
        # Compute event's date range (in UTC dates). iCal DTEND for all-day events
        # is exclusive — the event ENDS the day before DTEND. So real last day = DTEND - 1.
        start_iso = dtstart.astimezone(datetime.timezone.utc).date()
        if dtend:
            end_iso = dtend.astimezone(datetime.timezone.utc).date() - datetime.timedelta(days=1)
        else:
            end_iso = start_iso
        # Overlap check: event overlaps [start_date, end_date] iff
        # event_start <= end_date AND event_end >= start_date
        if start_iso <= end_date and end_iso >= start_date:
            matches.append((dtstart, ev, start_iso, end_iso))

    matches.sort(key=lambda x: x[0])

    print(f"Events overlapping {start_date}..{end_date}: {len(matches)}")
    print(f"OoO/Leave matches (handles multi-day all-day events):")
    ooo_count = 0
    for dt, ev, ds_iso, de_iso in matches:
        summary = ev.get("SUMMARY", "(no summary)")
        organizer = ev.get("ORGANIZER", "")
        flag = "🟡" if is_ooo(summary, organizer) else "  "
        if is_ooo(summary, organizer):
            ooo_count += 1
        dt_str = dt.strftime("%a %Y-%m-%d %H:%M %Z")
        # Show date range if multi-day
        if ds_iso != de_iso:
            range_str = f"{ds_iso} → {de_iso}"
        else:
            range_str = str(ds_iso)
        print(f"{flag}  {range_str}  {summary[:80]}  (organizer={organizer[:50] if organizer else ''})")

    print(f"\nTotal OOO-flagged: {ooo_count}")


if __name__ == "__main__":
    main()
