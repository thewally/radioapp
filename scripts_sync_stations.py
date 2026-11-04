#!/usr/bin/env python3
"""Regenerate the app's station list from stations.json.

stations.json is the single source of truth. This script rewrites
app/src/main/java/com/arjenvdw/radioapp/Stations.kt to match it and prunes any
ic_logo_<key> drawables whose station is no longer listed. It does NOT create
logo images — use scripts_set_logo.py for that.

Usage: python3 scripts_sync_stations.py
"""
import json
from pathlib import Path

REPO = Path(__file__).resolve().parent
STATIONS_JSON = REPO / "stations.json"
STATIONS_KT = REPO / "app/src/main/java/com/arjenvdw/radioapp/Stations.kt"
DRAWABLE_DIR = REPO / "app/src/main/res/drawable-nodpi"


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def main():
    stations = json.loads(STATIONS_JSON.read_text())["stations"]

    lines = [
        "package com.arjenvdw.radioapp",
        "",
        "/**",
        " * Static list of all radio stations this app can play. Generated from",
        " * stations.json by scripts_sync_stations.py; each entry has a matching",
        " * ic_logo_<key> drawable.",
        " */",
        "object Stations {",
        "    val ALL: List<Station> = listOf(",
    ]
    for s in stations:
        lines += [
            "        Station(",
            f'            key = "{esc(s["key"])}",',
            f'            name = "{esc(s["display_name"])}",',
            f'            country = "{esc(s.get("country", "nl"))}",',
            f'            streamUrl = "{esc(s["stream_url"])}",',
            f'            mediaId = "{esc(s["media_id"])}",',
            f'            logoResName = "ic_logo_{esc(s["key"])}",',
            "        ),",
        ]
    lines += [
        "    )",
        "",
        "    fun byMediaId(mediaId: String?): Station? =",
        "        ALL.firstOrNull { it.mediaId == mediaId }",
        "",
        "    /** Best-effort match of a free-text voice query to a station. */",
        "    fun byQuery(query: String?): Station? {",
        "        val q = query?.trim()?.lowercase() ?: return null",
        "        if (q.isEmpty()) return null",
        "        return ALL.firstOrNull { it.name.lowercase() == q }",
        "            ?: ALL.firstOrNull { it.name.lowercase() in q || q in it.name.lowercase() }",
        "            ?: ALL.firstOrNull { it.key in q }",
        "    }",
        "}",
        "",
    ]
    STATIONS_KT.write_text("\n".join(lines))
    print(f"Wrote {STATIONS_KT.relative_to(REPO)} ({len(stations)} stations)")

    # Prune orphan logos and warn about missing ones.
    keys = {s["key"] for s in stations}
    for png in DRAWABLE_DIR.glob("ic_logo_*.png"):
        key = png.stem[len("ic_logo_"):]
        if key not in keys:
            png.unlink()
            print(f"Removed orphan logo {png.name}")
    for s in stations:
        if not (DRAWABLE_DIR / f"ic_logo_{s['key']}.png").exists():
            print(f"WARNING: no logo for '{s['key']}' — run scripts_set_logo.py {s['key']} <image>")


if __name__ == "__main__":
    main()
