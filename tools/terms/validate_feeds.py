#!/usr/bin/env python3
"""validate_feeds.py — the current-events link list (BRIEF_PHASE8 C-09): schema check plus a link check at build.

Every row needs lang (one of the 11), title, url (http/https), publisher; every language needs at least one link. The
link check sends one GET per URL (connect 5 s, read 30 s, browser-like User-Agent) and records the result in
feeds.status.json. A link fails on HTTP 404/410/5xx or no connection — unless the row says reachability=unreliable
(sites that geo-block), which is reported but does not fail. 401/403/405/429 count as reachable (the site exists but
refuses scripts). --offline checks the schema only.
"""
import argparse
import datetime as dt
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
FEEDS = HERE / "feeds.json"
STATUS = HERE / "feeds.status.json"
LANGS = ["ja", "ko", "de", "fr", "es", "pt-BR", "ru", "ar", "fa", "id", "zh-Hans"]
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"


def check(url: str) -> tuple[bool, str]:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "text/html"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return True, f"HTTP {r.status}"
    except urllib.error.HTTPError as e:
        return e.code in (401, 403, 405, 429), f"HTTP {e.code}"
    except Exception as e:  # noqa: BLE001 - any connection problem is a result to report
        return False, type(e).__name__ + ": " + str(e)[:120]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true")
    a = ap.parse_args()
    feeds = json.loads(FEEDS.read_text(encoding="utf-8"))["feeds"]
    errors = []
    for i, f in enumerate(feeds):
        for k in ("lang", "title", "url", "publisher"):
            if not str(f.get(k, "")).strip():
                errors.append(f"row {i}: {k} missing")
        if f.get("lang") not in LANGS:
            errors.append(f"row {i}: unknown lang {f.get('lang')}")
        if not str(f.get("url", "")).startswith(("http://", "https://")):
            errors.append(f"row {i}: url must be http(s)")
    for lang in LANGS:
        if not any(f.get("lang") == lang for f in feeds):
            errors.append(f"{lang}: no link")
    status = {}
    if not a.offline:
        for f in feeds:
            ok, why = check(f["url"])
            status[f["url"]] = {"ok": ok, "result": why, "checked": dt.datetime.now(dt.UTC).strftime("%Y-%m-%d")}
            flag = "ok  " if ok else ("warn" if f.get("reachability") == "unreliable" else "FAIL")
            print(f"  {flag} {f['lang']:8} {f['url']} — {why}")
            if not ok and f.get("reachability") != "unreliable":
                errors.append(f"{f['url']}: {why}")
        STATUS.write_text(json.dumps(status, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    for e in errors:
        print("ERROR " + e)
    print(f"validate_feeds: {len(feeds)} links, {len(errors)} errors")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
