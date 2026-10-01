"""Builds a language's exam pack: content/packs/<lang>/exam.json (BRIEF §5.1, §5.3) from the item banks in
items/bank/<lang>/, the blueprint (items/blueprints/default.json + optional <lang>.json override) and the rendered
audio index (content/packs/<lang>/audio/). Strict validation runs first; any error aborts without writing.

    uv run --group content python packs/build_packs.py --language es
    uv run --group content python packs/build_packs.py --language all
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(TOOLS / "items"))

import gen_dlpt  # noqa: E402
import langtext  # noqa: E402

PACKS = REPO / "content" / "packs"
BLUEPRINTS = TOOLS / "items" / "blueprints"
AUDIO_EXT = (".ogg", ".opus", ".wav")


def deep_merge(base: dict, override: dict) -> dict:
    out = dict(base)
    for k, v in override.items():
        out[k] = deep_merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) else v
    return out


def blueprint(lang: str) -> dict:
    bp = json.loads((BLUEPRINTS / "default.json").read_text(encoding="utf-8"))
    over = BLUEPRINTS / f"{lang}.json"
    if over.exists():
        bp = deep_merge(bp, json.loads(over.read_text(encoding="utf-8")))
    bp["language"] = lang
    return bp


def audio_index(lang: str) -> dict[str, str]:
    d = PACKS / lang / "audio"
    if not d.is_dir():
        return {}
    return {f.stem: f"audio/{f.name}" for f in sorted(d.iterdir()) if f.suffix in AUDIO_EXT}


def build(lang: str, allow_empty: bool) -> dict:
    bands = gen_dlpt.load_bands()
    banks = {}
    for skill in gen_dlpt.SKILLS:
        bank = gen_dlpt.load_bank(lang, skill)
        report = gen_dlpt.Report()
        gen_dlpt.validate_bank(bank, lang, skill, report, bands, True, f"{lang}/{skill}")
        if report.errors:
            for e in report.errors[:20]:
                print("ERROR", e)
            raise SystemExit(f"{lang} {skill}: {len(report.errors)} validation errors; pack not written")
        if not bank["passages"] and not allow_empty:
            raise SystemExit(f"{lang} {skill}: bank is empty; draft content first (items/gen_dlpt.py fill)")
        banks[skill] = bank
    pack = {
        "language": lang, "version": 1, "built": dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "blueprint": blueprint(lang), "reading": banks["reading"], "listening": banks["listening"], "audio": audio_index(lang),
    }
    out = PACKS / lang / "exam.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    data = json.dumps(pack, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    fd, tmp = tempfile.mkstemp(dir=out.parent, suffix=".tmp")
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    os.replace(tmp, out)
    stats = {s: gen_dlpt.counts(lang, s) for s in gen_dlpt.SKILLS}
    listening_ids = [p["id"] for p in banks["listening"]["passages"]]
    with_audio = sum(1 for p in listening_ids if p in pack["audio"])
    manifest = {"language": lang, "exam": {"file": "exam.json", "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                "passages": stats, "items": {s: len(banks[s]["items"]) for s in banks},
                "listeningWithAudio": with_audio, "listeningPassages": len(listening_ids),
                "license": gen_dlpt.LICENSE, "attribution": banks["reading"].get("attribution", "")}}
    (PACKS / lang / "exam.manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"{lang}: exam.json {len(data) / 1e6:.1f} MB · reading {stats['reading']} · listening {stats['listening']} · audio {with_audio}/{len(listening_ids)}")
    return manifest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--language", required=True)
    ap.add_argument("--allow-empty", action="store_true", help="write a pack even when a bank is empty (tests only)")
    args = ap.parse_args()
    langs = langtext.LANGS if args.language == "all" else args.language.split(",")
    for lang in langs:
        build(lang, args.allow_empty)
    return 0


if __name__ == "__main__":
    sys.exit(main())
