"""
cot_helper.py — Cursor on Target (CoT) XML utilities

Provides functions for creating, parsing, and validating CoT event XML
messages compatible with ATAK, WinTAK, iTAK, and TAK Server.
"""

import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta


# ── CoT Event Builder ──────────────────────────────────────────────────────

def build_sa_event(uid, callsign, lat, lon, hae=0.0, ce=9999999.0,
                   le=9999999.0, cot_type="a-f-G-U-C", how="h-e",
                   stale_seconds=120, team="Cyan", role="Team Member",
                   speed=0.0, course=0.0, remarks=""):
    """
    Build a Situational Awareness (SA / PLI) CoT event XML string.

    Parameters
    ----------
    uid : str          Unique identifier for the entity.
    callsign : str     Human-readable callsign.
    lat, lon : float   WGS-84 coordinates.
    hae : float        Height above ellipsoid (metres).
    ce, le : float     Circular / linear error (metres).
    cot_type : str     CoT type string (MIL-STD-2525).
    how : str          How the position was determined.
    stale_seconds : int Seconds until the event is stale.
    team : str         Team colour (Cyan, White, Red, etc.).
    role : str         Team role.
    speed, course : float  Movement parameters.
    remarks : str      Free-text remarks.

    Returns
    -------
    str : Complete CoT XML event.
    """
    now = datetime.now(timezone.utc)
    stale = now + timedelta(seconds=stale_seconds)

    fmt = "%Y-%m-%dT%H:%M:%S.%fZ"
    time_str  = now.strftime(fmt)
    start_str = time_str
    stale_str = stale.strftime(fmt)

    event = ET.Element("event", {
        "version": "2.0",
        "uid":     uid,
        "type":    cot_type,
        "time":    time_str,
        "start":   start_str,
        "stale":   stale_str,
        "how":     how,
    })

    ET.SubElement(event, "point", {
        "lat":  f"{lat:.7f}",
        "lon":  f"{lon:.7f}",
        "hae":  f"{hae:.1f}",
        "ce":   f"{ce:.1f}",
        "le":   f"{le:.1f}",
    })

    detail = ET.SubElement(event, "detail")

    ET.SubElement(detail, "contact", {"callsign": callsign})

    ET.SubElement(detail, "__group", {
        "name": team,
        "role": role,
    })

    ET.SubElement(detail, "track", {
        "speed":  f"{speed:.1f}",
        "course": f"{course:.1f}",
    })

    ET.SubElement(detail, "precisionlocation", {
        "altsrc":   "GPS",
        "geopointsrc": "GPS",
    })

    if remarks:
        rem_el = ET.SubElement(detail, "remarks")
        rem_el.text = remarks

    ET.SubElement(detail, "uid", {"Droid": callsign})

    return ET.tostring(event, encoding="unicode", xml_declaration=True)


def build_ping():
    """Build a TAK Server ping (t-x-c-t) CoT event."""
    now = datetime.now(timezone.utc)
    stale = now + timedelta(seconds=20)
    fmt = "%Y-%m-%dT%H:%M:%S.%fZ"

    uid = f"ping-{uuid.uuid4()}"

    event = ET.Element("event", {
        "version": "2.0",
        "uid":     uid,
        "type":    "t-x-c-t",
        "time":    now.strftime(fmt),
        "start":   now.strftime(fmt),
        "stale":   stale.strftime(fmt),
        "how":     "h-g-i-g-o",
    })

    ET.SubElement(event, "point", {
        "lat": "0.0", "lon": "0.0", "hae": "0.0",
        "ce": "9999999.0", "le": "9999999.0",
    })

    ET.SubElement(event, "detail")

    return ET.tostring(event, encoding="unicode", xml_declaration=True)


# ── CoT Parser ─────────────────────────────────────────────────────────────

def parse_cot(xml_str):
    """
    Parse a CoT XML event string into a dict.

    Returns
    -------
    dict with keys: uid, type, time, start, stale, how, lat, lon, hae,
                    ce, le, callsign, team, role, speed, course, remarks, raw
    """
    try:
        root = ET.fromstring(xml_str)
    except ET.ParseError:
        return None

    if root.tag != "event":
        return None

    result = {
        "uid":   root.get("uid", ""),
        "type":  root.get("type", ""),
        "time":  root.get("time", ""),
        "start": root.get("start", ""),
        "stale": root.get("stale", ""),
        "how":   root.get("how", ""),
        "raw":   xml_str,
    }

    point = root.find("point")
    if point is not None:
        result["lat"] = float(point.get("lat", "0"))
        result["lon"] = float(point.get("lon", "0"))
        result["hae"] = float(point.get("hae", "0"))
        result["ce"]  = float(point.get("ce", "9999999"))
        result["le"]  = float(point.get("le", "9999999"))

    detail = root.find("detail")
    if detail is not None:
        contact = detail.find("contact")
        if contact is not None:
            result["callsign"] = contact.get("callsign", "")

        group = detail.find("__group")
        if group is not None:
            result["team"] = group.get("name", "")
            result["role"] = group.get("role", "")

        track = detail.find("track")
        if track is not None:
            result["speed"]  = float(track.get("speed", "0"))
            result["course"] = float(track.get("course", "0"))

        remarks = detail.find("remarks")
        if remarks is not None and remarks.text:
            result["remarks"] = remarks.text

    return result


def is_valid_cot(xml_str):
    """Return True if xml_str is a parseable CoT event."""
    return parse_cot(xml_str) is not None


def get_cot_type_description(cot_type):
    """Return a human-readable description of common CoT type codes."""
    prefixes = {
        "a-f": "Friendly",
        "a-h": "Hostile",
        "a-n": "Neutral",
        "a-u": "Unknown",
        "a-p": "Pending",
        "a-s": "Suspect",
        "a-j": "Joker",
        "a-k": "Faker",
    }
    dimensions = {
        "A": "Air",
        "G": "Ground",
        "S": "Sea Surface",
        "U": "Subsurface",
        "P": "Space",
        "F": "SOF",
    }

    parts = cot_type.split("-")
    desc_parts = []

    if len(parts) >= 2:
        prefix = f"{parts[0]}-{parts[1]}"
        desc_parts.append(prefixes.get(prefix, prefix))

    if len(parts) >= 3:
        desc_parts.append(dimensions.get(parts[2], parts[2]))

    return " ".join(desc_parts) if desc_parts else cot_type
