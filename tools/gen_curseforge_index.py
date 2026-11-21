#!/usr/bin/env python3
"""Regenerates app/src/main/assets/curseforge-index.json from the curated CurseForge seed list.

The app cannot search curseforge.com itself (Cloudflare challenges every HTML and API request without an API key),
and the public cfwidget mirror has no search endpoint. The shipped index is therefore the searchable catalogue; a
project that stops publishing a build for GAME_VERSION disappears from it on the next run.
"""

from __future__ import annotations

import datetime
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

GAME_VERSION = "1.60.1"
SLUGS = [
    "details", "bagnon", "grid2", "bartender4", "dominos", "clique",
    "vuhdo", "auctionator", "tomtom", "gathermate2", "handynotes",
    "pawn", "simulationcraft", "plater-nameplates", "masque", "quartz",
    "tellmewhen", "xperl", "skillet", "selljunk", "scrap",
    "leatrix-plus", "sexymap", "totemtimers",
]
OUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/curseforge-index.json"
USER_AGENT = "WoWForeverAndroid/2.4 (+https://github.com/hyylanlian/wow-forever-android)"
MIN_ADDONS = 10


def fetch(slug: str) -> dict | None:
    url = f"https://api.cfwidget.com/wow/addons/{slug}?version={GAME_VERSION}"
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        print(f"skip {slug}: HTTP {exc.code}")
    except Exception as exc:  # noqa: BLE001 - one bad project must not abort the run
        print(f"skip {slug}: {exc}")
    return None


def entry(slug: str) -> dict | None:
    data = fetch(slug)
    if data is None:
        return None
    download = data.get("download") or {}
    versions = download.get("versions") or []
    if GAME_VERSION not in versions:
        print(f"skip {slug}: no {GAME_VERSION} file")
        return None
    project_id = data.get("id")
    title = (data.get("title") or "").strip()
    if not isinstance(project_id, int) or project_id <= 0 or not title:
        print(f"skip {slug}: missing id or title")
        return None
    return {
        "id": project_id,
        "slug": slug,
        "title": title,
        "summary": (data.get("summary") or "").strip(),
    }


def main() -> int:
    addons = [item for slug in SLUGS if (item := entry(slug)) is not None]
    addons.sort(key=lambda item: item["title"].lower())
    if len(addons) < MIN_ADDONS:
        print(f"only {len(addons)} addons support {GAME_VERSION}; refusing to write the index")
        return 1
    payload = {
        "version": 1,
        "generatedAt": datetime.date.today().isoformat(),
        "gameVersion": GAME_VERSION,
        "addons": addons,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(addons)} addons")
    return 0


if __name__ == "__main__":
    sys.exit(main())
