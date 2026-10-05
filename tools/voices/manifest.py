"""Writes, checks and fetches voices/manifest.json: the Piper voices the installer bundles (BRIEF §3.3, §4).

The curated list (VOICES below) is the source of truth for which voices ship, their speaker gender and provenance;
this script adds each file's SHA-256 and size from the Hugging Face API, pinned to one commit of
rhasspy/piper-voices, and refuses any voice whose training-data license is not redistributable (CLAUDE.md rule 6).

    uv run python voices/manifest.py write            # query HF, (re)write voices/manifest.json
    uv run python voices/manifest.py check            # offline: validate voices/manifest.json (gate)
    uv run python voices/manifest.py --fetch [DIR]    # download + verify into voices/models/<id>/ (or DIR/<id>/)

`write` also downloads each voice's MODEL_CARD and .onnx.json (a few KB) to confirm the dataset license on the card
matches the one recorded here and that the voice uses eSpeak phonemes (the bundled C++ Piper 2023.11.14-2 cannot
run "pinyin"/g2pW voices). Exit status is non-zero on any problem. Standard library only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import urllib.parse
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MANIFEST = REPO / "voices" / "manifest.json"
MODELS = REPO / "voices" / "models"
HF_REPO = "rhasspy/piper-voices"
HF = "https://huggingface.co"
TIMEOUT = 30
DOWNLOAD_TIMEOUT = 120

# Training-data licenses that allow redistribution inside a free app (rule 6). SPDX ids.
ALLOWED = {"CC0-1.0", "CC-BY-3.0", "CC-BY-4.0", "CC-BY-SA-4.0", "Apache-2.0", "MIT", "Unlicense"}

# How each dataset license appears on a MODEL_CARD "License:" line -> SPDX id.
CARD_LICENSES = [
    (re.compile(r"^cc0$", re.IGNORECASE), "CC0-1.0"),
    (re.compile(r"creativecommons\.org/licenses/by/3\.0", re.IGNORECASE), "CC-BY-3.0"),
    (re.compile(r"^cc-?by 4\.0$", re.IGNORECASE), "CC-BY-4.0"),
    (re.compile(r"creativecommons\.org/licenses/by-sa/4\.0", re.IGNORECASE), "CC-BY-SA-4.0"),
    (re.compile(r"^apache-?2\.0$", re.IGNORECASE), "Apache-2.0"),
    (re.compile(r"^mit$", re.IGNORECASE), "MIT"),
    (re.compile(r"unlicense\.org", re.IGNORECASE), "Unlicense"),
    (re.compile(r"Attribution-ShareAlike 4\.0", re.IGNORECASE), "CC-BY-SA-4.0"),
]

# Speaker variety (BRIEF_PHASE8 N-07): up to four voices per language — ideally two female and two male, regional variants
# where licensed. A multi-speaker model can serve several entries ("model" names the shared files). Gender is measured:
# "measuredF0Hz" is the median pitch tools/voices/measure.py found (male < 150 Hz, female > 190 Hz); between those the
# label stays as documented or inferred and genderSource says so.
MAX_PER_LANGUAGE = 4

PUBLISHER = "Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices"
PUBLISHER_COUNTRY = "United States / Switzerland"
LESSAC = "en_US-lessac-medium (Blizzard 2013 Lessac data, research license)"

# The curated list: at most two voices per language, one female + one male where a bundleable voice exists.
# gender comes from the dataset documentation or the model's speaker_id_map; "genderSource" says which, and says
# so plainly when it is only inferred from the speaker's name.
VOICES = [
    {
        "id": "es_ES-davefx-medium", "measuredF0Hz": 126, "language": "es", "gender": "male", "quality": "medium",
        "path": "es/es_ES/davefx/medium", "datasetLicense": "CC0-1.0",
        "dataset": "davefx (OHF-Voice voice-datasets)", "datasetUrl": "https://github.com/OHF-Voice/voice-datasets",
        "baseModel": LESSAC,
        "genderSource": "measured: median F0 126 Hz (the dataset entry does not state gender)",
    },
    {
        "id": "es_ES-sharvard-medium", "measuredF0Hz": 204, "language": "es", "gender": "female", "speaker": 1, "quality": "medium",
        "path": "es/es_ES/sharvard/medium", "datasetLicense": "CC-BY-3.0",
        "dataset": "Sharvard corpus (Aubanel, García Lecumberri, Cooke)",
        "datasetUrl": "https://datashare.ed.ac.uk/handle/10283/574",
        "attribution": "Sharvard corpus © V. Aubanel, M. L. García Lecumberri, M. Cooke, CC BY 3.0",
        "baseModel": LESSAC,
        "genderSource": "model config speaker_id_map {M: 0, F: 1}; measured median F0 204 Hz",
    },
    {
        "id": "fr_FR-siwis-medium", "measuredF0Hz": 202, "language": "fr", "gender": "female", "quality": "medium",
        "path": "fr/fr_FR/siwis/medium", "datasetLicense": "CC-BY-4.0",
        "dataset": "SIWIS French Speech Synthesis Database (Honnet, Lazaridis, Garner, Yamagishi)",
        "datasetUrl": "https://datashare.is.ed.ac.uk/handle/10283/2353",
        "attribution": "SIWIS French Speech Synthesis Database © Idiap/University of Edinburgh et al., CC BY 4.0",
        "baseModel": LESSAC,
        "genderSource": "dataset documentation: a single female speaker",
    },
    {
        "id": "fr_FR-upmc-medium", "measuredF0Hz": 138, "language": "fr", "gender": "male", "speaker": 1, "quality": "medium",
        "path": "fr/fr_FR/upmc/medium", "datasetLicense": "CC-BY-SA-4.0",
        "dataset": "UPMC Pierre (MaryTTS upmc-pierre-data)", "datasetUrl": "https://github.com/marytts/upmc-pierre-data",
        "attribution": "upmc-pierre-data © Université Pierre et Marie Curie / MaryTTS, CC BY-SA 4.0",
        "baseModel": LESSAC,
        "genderSource": "model config speaker_id_map {jessica: 0, pierre: 1}; dataset is the male speaker Pierre; measured 138 Hz",
    },
    {
        "id": "de_DE-thorsten-medium", "measuredF0Hz": 127, "language": "de", "gender": "male", "quality": "medium",
        "path": "de/de_DE/thorsten/medium", "datasetLicense": "CC0-1.0",
        "dataset": "Thorsten-Voice (Thorsten Müller)", "datasetUrl": "https://github.com/thorstenMueller/Thorsten-Voice",
        "baseModel": LESSAC,
        "genderSource": "dataset documentation: Thorsten Müller's own (male) voice",
    },
    {
        "id": "pt_BR-faber-medium", "measuredF0Hz": 179, "language": "pt-BR", "gender": "male", "quality": "medium",
        "path": "pt/pt_BR/faber/medium", "datasetLicense": "CC0-1.0",
        "dataset": "faber (OHF-Voice voice-datasets)", "datasetUrl": "https://github.com/OHF-Voice/voice-datasets",
        "baseModel": LESSAC,
        "genderSource": "unverified: inferred from the speaker's name; median F0 179 Hz is in the ambiguous band",
    },
    {
        "id": "pt_BR-cadu-medium", "measuredF0Hz": 145, "language": "pt-BR", "gender": "male", "quality": "medium",
        "path": "pt/pt_BR/cadu/medium", "datasetLicense": "CC0-1.0",
        "dataset": "cadu (OHF-Voice voice-datasets)", "datasetUrl": "https://github.com/OHF-Voice/voice-datasets",
        "baseModel": LESSAC,
        "genderSource": "measured: median F0 145 Hz; no verified female pt_BR voice is available",
    },
    {
        "id": "ru_RU-dmitri-medium", "measuredF0Hz": 185, "language": "ru", "gender": "male", "quality": "medium",
        "path": "ru/ru_RU/dmitri/medium", "datasetLicense": "CC0-1.0",
        "dataset": "dmitri (OHF-Voice voice-datasets)", "datasetUrl": "https://github.com/OHF-Voice/voice-datasets",
        "baseModel": LESSAC,
        "genderSource": "unverified: inferred from the speaker's name (Dmitri); median F0 185 Hz is in the ambiguous band",
    },
    {
        "id": "ru_RU-denis-medium", "measuredF0Hz": 144, "language": "ru", "gender": "male", "quality": "medium",
        "path": "ru/ru_RU/denis/medium", "datasetLicense": "CC0-1.0",
        "dataset": "denis (OHF-Voice voice-datasets)", "datasetUrl": "https://github.com/OHF-Voice/voice-datasets",
        "baseModel": LESSAC,
        "genderSource": "measured: median F0 144 Hz; ru_RU-irina (female) is excluded",
    },
    {
        "id": "fa_IR-amir-medium", "measuredF0Hz": 135, "language": "fa", "gender": "male", "quality": "medium",
        "path": "fa/fa_IR/amir/medium", "datasetLicense": "CC0-1.0",
        "dataset": "Amir (Datacula Persian TTS databases)", "datasetUrl": "https://datacula.com/tts-databases",
        "baseModel": LESSAC,
        "genderSource": "measured: median F0 135 Hz",
    },
    {
        "id": "fa_IR-ganji-medium", "measuredF0Hz": 98, "language": "fa", "gender": "male", "quality": "medium",
        "path": "fa/fa_IR/ganji/medium", "datasetLicense": "CC0-1.0",
        "dataset": "Ganji (Datacula Persian TTS databases)", "datasetUrl": "https://tts.datacula.com/",
        "baseModel": "fa_IR-amir-medium",
        "genderSource": "measured: median F0 98 Hz (neither the model card nor the dataset page states the speaker's gender)",
    },
    {
        "id": "es_ES-sharvard-medium-m", "model": "es_ES-sharvard-medium", "measuredF0Hz": 125, "language": "es", "region": "es-ES",
        "gender": "male", "speaker": 0, "quality": "medium",
        "path": "es/es_ES/sharvard/medium", "datasetLicense": "CC-BY-3.0",
        "dataset": "Sharvard corpus (Aubanel, García Lecumberri, Cooke)",
        "datasetUrl": "https://datashare.ed.ac.uk/handle/10283/574",
        "attribution": "Sharvard corpus © V. Aubanel, M. L. García Lecumberri, M. Cooke, CC BY 3.0",
        "baseModel": LESSAC,
        "genderSource": "model config speaker_id_map {M: 0, F: 1}; measured median F0 125 Hz",
    },
    {
        "id": "es_MX-claude-high", "measuredF0Hz": 192, "language": "es", "region": "es-MX", "gender": "female", "quality": "high",
        "path": "es/es_MX/claude/high", "datasetLicense": "Apache-2.0",
        "dataset": "claude (Mexican Spanish single-speaker dataset, per the model card)",
        "datasetUrl": "https://huggingface.co/rhasspy/piper-voices/tree/main/es/es_MX/claude/high",
        "baseModel": "trained by the Piper community; model card states dataset license Apache-2.0",
        "genderSource": "measured: median F0 192 Hz",
    },
    {
        "id": "fr_FR-upmc-medium-f", "model": "fr_FR-upmc-medium", "measuredF0Hz": 242, "language": "fr", "region": "fr-FR",
        "gender": "female", "speaker": 0, "quality": "medium",
        "path": "fr/fr_FR/upmc/medium", "datasetLicense": "CC-BY-SA-4.0",
        "dataset": "UPMC Jessica (MaryTTS upmc-jessica-data)", "datasetUrl": "https://github.com/marytts/upmc-pierre-data",
        "attribution": "upmc-pierre-data © Université Pierre et Marie Curie / MaryTTS, CC BY-SA 4.0",
        "baseModel": LESSAC,
        "genderSource": "model config speaker_id_map {jessica: 0, pierre: 1}; measured median F0 242 Hz",
    },
    {
        "id": "fr_FR-mls-medium-m16", "model": "fr_FR-mls-medium", "measuredF0Hz": 109, "language": "fr", "region": "fr-FR",
        "gender": "male", "speaker": 16, "quality": "medium",
        "path": "fr/fr_FR/mls/medium", "datasetLicense": "CC-BY-4.0",
        "dataset": "Multilingual LibriSpeech, French (Pratap et al., Meta AI; LibriVox public-domain recordings)",
        "datasetUrl": "https://www.openslr.org/94/",
        "attribution": "Multilingual LibriSpeech © Meta Platforms, Inc., CC BY 4.0; recordings from LibriVox (public domain)",
        "baseModel": "trained from scratch (model card)",
        "genderSource": "measured: speaker 16 (MLS 5764) median F0 109 Hz",
    },
    {
        "id": "de_DE-mls-medium-f12", "model": "de_DE-mls-medium", "measuredF0Hz": 235, "language": "de", "region": "de-DE",
        "gender": "female", "speaker": 12, "quality": "medium",
        "path": "de/de_DE/mls/medium", "datasetLicense": "CC-BY-4.0",
        "dataset": "Multilingual LibriSpeech, German (Pratap et al., Meta AI; LibriVox public-domain recordings)",
        "datasetUrl": "https://www.openslr.org/94/",
        "attribution": "Multilingual LibriSpeech © Meta Platforms, Inc., CC BY 4.0; recordings from LibriVox (public domain)",
        "baseModel": "trained from scratch (model card)",
        "genderSource": "measured: speaker 12 (MLS 5424) median F0 235 Hz",
    },
    {
        "id": "de_DE-mls-medium-f15", "model": "de_DE-mls-medium", "measuredF0Hz": 248, "language": "de", "region": "de-DE",
        "gender": "female", "speaker": 15, "quality": "medium",
        "path": "de/de_DE/mls/medium", "datasetLicense": "CC-BY-4.0",
        "dataset": "Multilingual LibriSpeech, German (Pratap et al., Meta AI; LibriVox public-domain recordings)",
        "datasetUrl": "https://www.openslr.org/94/",
        "attribution": "Multilingual LibriSpeech © Meta Platforms, Inc., CC BY 4.0; recordings from LibriVox (public domain)",
        "baseModel": "trained from scratch (model card)",
        "genderSource": "measured: speaker 15 (MLS 3885) median F0 248 Hz",
    },
    {
        "id": "de_DE-mls-medium-m6", "model": "de_DE-mls-medium", "measuredF0Hz": 97, "language": "de", "region": "de-DE",
        "gender": "male", "speaker": 6, "quality": "medium",
        "path": "de/de_DE/mls/medium", "datasetLicense": "CC-BY-4.0",
        "dataset": "Multilingual LibriSpeech, German (Pratap et al., Meta AI; LibriVox public-domain recordings)",
        "datasetUrl": "https://www.openslr.org/94/",
        "attribution": "Multilingual LibriSpeech © Meta Platforms, Inc., CC BY 4.0; recordings from LibriVox (public domain)",
        "baseModel": "trained from scratch (model card)",
        "genderSource": "measured: speaker 6 (MLS 5055) median F0 97 Hz",
    },
    {
        "id": "pt_PT-tugao-medium", "remoteId": "pt_PT-tugão-medium", "measuredF0Hz": 179, "language": "pt-BR", "region": "pt-PT",
        "gender": "unknown", "quality": "medium",
        "path": "pt/pt_PT/tugão/medium", "datasetLicense": "CC0-1.0",
        "dataset": "tugão (OHF-Voice voice-datasets)", "datasetUrl": "https://github.com/OHF-Voice/voice-datasets",
        "baseModel": LESSAC,
        "genderSource": "unknown: median F0 179 Hz is in the ambiguous band and the dataset entry does not state gender; European "
                        "Portuguese, offered as the regional variant in the Portuguese pack",
    },
]

EXCLUDED = [
    {"id": "de_DE-kerstin-low", "reason": "fine-tuned from en_US-ryan-low, whose RyanSpeech data is CC BY-NC-SA 4.0 (non-commercial base model, D-013)"},
    {"id": "ko_KR-kss-medium", "reason": "dataset KSS is CC BY-NC-SA 4.0 (non-commercial)"},
    {"id": "ja_JP-hi_fi_captain-medium", "reason": "dataset Hi-Fi-CAPTAIN is CC BY-NC-SA 4.0 (non-commercial)"},
    {"id": "zh_CN-xiao_ya-medium", "reason": "dataset BZNSYP (Data Baker) is non-commercial use only"},
    {"id": "zh_CN-huayan-medium", "reason": "dataset license unknown"},
    {"id": "zh_CN-chaowen-medium",
     "reason": "phoneme_type 'pinyin' (g2pW) needs Piper >= 1.3 (Python); the bundled C++ Piper 2023.11.14-2 "
               "only runs eSpeak-phoneme voices. Also fine-tuned from zh_CN-xiao_ya (non-commercial dataset)"},
    {"id": "ru_RU-irina-medium", "reason": "dataset license unknown"},
    {"id": "fr_FR-tom-medium", "reason": "dataset is AGPL (not a content license; copyleft terms unclear for weights)"},
    {"id": "ar_JO-kareem-medium", "reason": "dataset repository (github.com/AliMokhammad/arabicttstrain) has no license file or "
                                            "terms; re-checked 2026-10-04 (N-00e), still excluded"},
    {"id": "id_ID-news_tts-medium", "reason": "model card's dataset link is a Kaggle notebook about a Malayalam corpus and gives no "
                                              "license; re-checked 2026-10-04 (N-00e), still excluded"},
    {"id": "es_MX-ald-medium", "reason": "allowed (Unlicense) but not bundled: median F0 160 Hz leaves its gender unverifiable and es already has two male voices"},
    {"id": "pt_BR-jeff-medium", "reason": "allowed (CC0) but not bundled: median F0 154 Hz (ambiguous) and pt-BR already has two such voices"},
    {"id": "ru_RU-ruslan-medium", "reason": "dataset is CC BY-NC-SA 4.0 (non-commercial)"},
    {"id": "de_DE-pavoque-low", "reason": "dataset is CC BY-NC-SA 4.0 (non-commercial)"},
    {"id": "fa_IR-reza_ibrahim-medium", "reason": "Quran-recitation dataset: a chanted reading style unsuited to everyday listening passages"},
]

# Fallback order per language (BRIEF_PHASE8 N-00): what speaks a line, first available wins.
FALLBACK_ORDER = {
    "ja": ["clip:voicevox (exam listening)", "clip:chatterbox-multilingual", "os", "text"],
    **{lang: ["clip:chatterbox-multilingual", "os", "text"] for lang in ("ko", "ar", "zh-Hans")},
    **{lang: ["clip:chatterbox-multilingual", "piper", "os", "text"] for lang in ("es", "fr", "de", "pt-BR", "ru")},
    "fa": ["clip:piper", "piper", "os", "text"],
    "id": ["os", "text"],
}
FALLBACK_NOTES = (
    "Live synthesis uses Piper or the OS voice. Chatterbox runs at build time only (D-041). Kokoro-82M (ja; Apache-2.0, "
    "hexgrad, StyleTTS 2 lineage) was evaluated for a Tier A live Japanese voice and is not bundled yet — logged in PROGRESS."
)

LANG_FALLBACK = {
    "ja": "OS voice (macOS Kyoko; Windows Japanese speech pack)",
    "ko": "OS voice (macOS Yuna; Windows Korean speech pack) — BRIEF open decision 4",
    "zh-Hans": "OS voice (macOS Tingting; Windows Chinese speech pack)",
    "ar": "OS voice (macOS Majed; Windows Arabic speech pack)",
    "id": "OS voice (macOS Damayanti; Windows Indonesian speech pack)",
}


def http_json(url: str) -> object:
    req = urllib.request.Request(url, headers={"User-Agent": "mokuhyo-tools"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return json.load(r)


def http_bytes(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "mokuhyo-tools"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return r.read()


def card_license(card: str) -> str | None:
    m = re.search(r"^\*\s*License:\s*(.+)$", card, re.MULTILINE)
    if not m:
        return None
    value = m.group(1).strip()
    for pattern, spdx in CARD_LICENSES:
        if pattern.search(value):
            return spdx
    return value


def write() -> int:
    problems: list[str] = []
    revision = http_json(f"{HF}/api/models/{HF_REPO}")["sha"]
    print(f"{HF_REPO} @ {revision}")
    voices = []
    for v in VOICES:
        if v["datasetLicense"] not in ALLOWED:
            problems.append(f"{v['id']}: dataset license {v['datasetLicense']} is not redistributable")
        if re.search(r"\bNC\b|non-commercial", v.get("baseModel", ""), re.IGNORECASE):
            problems.append(f"{v['id']}: fine-tuned from a non-commercial base model ({v['baseModel']})")
            continue
        tree = http_json(f"{HF}/api/models/{HF_REPO}/tree/{revision}/{urllib.parse.quote(v['path'])}")
        by_name = {Path(e["path"]).name: e for e in tree if e["type"] == "file"}
        model = v.get("model", v["id"])
        remote = v.get("remoteId", model)
        names = [f"{remote}.onnx", f"{remote}.onnx.json", "MODEL_CARD"]
        files = []
        for name in names:
            entry = by_name.get(name)
            local_name = name.replace(remote, model, 1)
            if entry is None:
                problems.append(f"{v['id']}: {name} missing from {v['path']}")
                continue
            url = f"{HF}/{HF_REPO}/resolve/{revision}/{urllib.parse.quote(v['path'] + '/' + name)}"
            if "lfs" in entry:
                sha, size = entry["lfs"]["oid"], entry["lfs"]["size"]
            else:  # small git file: the API gives a git oid, not SHA-256, so hash it ourselves
                data = http_bytes(url)
                sha, size = hashlib.sha256(data).hexdigest(), len(data)
                if name == "MODEL_CARD":
                    found = card_license(data.decode("utf-8"))
                    if found != v["datasetLicense"]:
                        problems.append(f"{v['id']}: MODEL_CARD says license '{found}', VOICES says "
                                        f"'{v['datasetLicense']}'")
                if name.endswith(".onnx.json"):
                    cfg = json.loads(data)
                    ptype = cfg.get("phoneme_type", "espeak")
                    if ptype not in ("espeak", "text"):
                        problems.append(f"{v['id']}: phoneme_type '{ptype}' is not supported by Piper 2023.11.14-2")
                    n = cfg.get("num_speakers", 1)
                    if "speaker" in v and not (0 <= v["speaker"] < n):
                        problems.append(f"{v['id']}: speaker {v['speaker']} out of range (num_speakers {n})")
                    if "speaker" not in v and n > 1:
                        problems.append(f"{v['id']}: multi-speaker model needs a 'speaker'")
            files.append({"name": local_name, "url": url, "sha256": sha, "bytes": size})
        entry = {
            "id": v["id"],
            "language": v["language"],
            "gender": v["gender"],
            "genderSource": v["genderSource"],
            "engine": "piper",
            **({"model": v["model"]} if "model" in v else {}),
            "region": v.get("region", v["id"][:5].replace("_", "-")),
            "measuredF0Hz": v["measuredF0Hz"],
            **({"speaker": v["speaker"]} if "speaker" in v else {}),
            "quality": v["quality"],
            "files": files,
            "license": v["datasetLicense"],
            "provenance": {
                "developer": PUBLISHER,
                "country": PUBLISHER_COUNTRY,
                "license": "MIT (rhasspy/piper-voices repository); training data " + v["datasetLicense"],
                "source": f"{HF}/{HF_REPO}/tree/{revision}/{v['path']}",
                "dataset": v["dataset"],
                "datasetUrl": v["datasetUrl"],
                "datasetLicense": v["datasetLicense"],
                "baseModel": v["baseModel"],
                **({"attribution": v["attribution"]} if "attribution" in v else {}),
            },
        }
        voices.append(entry)
        print(f"  {v['id']}: {sum(f['bytes'] for f in files) / 1e6:.1f} MB")
    for p in problems:
        print("VOICE:", p, file=sys.stderr)
    if problems:
        print(f"manifest: {len(problems)} problem(s); voices/manifest.json not written", file=sys.stderr)
        return 1
    manifest = {
        "comment": "Generated by tools/voices/manifest.py from its curated VOICES list; edit that, not this file.",
        "source": {"repo": HF_REPO, "revision": revision},
        "allowedLicenses": sorted(ALLOWED),
        "voices": voices,
        "excluded": EXCLUDED,
        "osVoiceLanguages": LANG_FALLBACK,
        "fallbackOrder": FALLBACK_ORDER,
        "fallbackNotes": FALLBACK_NOTES,
    }
    MANIFEST.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    total = sum(f["bytes"] for v in voices for f in v["files"])
    print(f"wrote {MANIFEST.relative_to(REPO)}: {len(voices)} voices, {total / 1e6:.0f} MB")
    return check()


def check() -> int:
    problems: list[str] = []
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    ids = set()
    per_lang: dict[str, int] = {}
    for v in data["voices"]:
        vid = v.get("id", "?")
        if vid in ids:
            problems.append(f"{vid}: duplicate id")
        ids.add(vid)
        if v.get("license") not in ALLOWED:
            problems.append(f"{vid}: license {v.get('license')!r} is not in the allowed set {sorted(ALLOWED)}")
        prov = v.get("provenance") or {}
        if prov.get("datasetLicense") not in ALLOWED:
            problems.append(f"{vid}: dataset license {prov.get('datasetLicense')!r} is not redistributable")
        for field in ("developer", "country", "license", "source", "dataset", "datasetLicense"):
            if not prov.get(field):
                problems.append(f"{vid}: provenance.{field} missing")
        if v.get("engine") != "piper":
            problems.append(f"{vid}: engine must be 'piper'")
        if v.get("gender") not in ("female", "male", "unknown"):
            problems.append(f"{vid}: gender must be female|male|unknown")
        model = v.get("model", vid)
        names = {f["name"] for f in v.get("files", [])}
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", vid + model):
            problems.append(f"{vid}: ids and model names must be ASCII (installer paths)")
        f0 = v.get("measuredF0Hz")
        if not isinstance(f0, int | float):
            problems.append(f"{vid}: measuredF0Hz missing (tools/voices/measure.py)")
        elif v.get("gender") == "male" and f0 >= 150 and not v.get("genderSource", "").startswith("unverified"):
            problems.append(f"{vid}: labelled male but measured {f0} Hz without saying it is unverified")
        elif v.get("gender") == "female" and f0 <= 190 and not v.get("genderSource", "").startswith("unverified"):
            problems.append(f"{vid}: labelled female but measured {f0} Hz without saying it is unverified")
        for need in (f"{model}.onnx", f"{model}.onnx.json"):
            if need not in names:
                problems.append(f"{vid}: files lack {need}")
        for f in v.get("files", []):
            if not re.fullmatch(r"[0-9a-f]{64}", f.get("sha256", "")) or f.get("bytes", 0) <= 0:
                problems.append(f"{vid}: {f.get('name')} lacks sha256/bytes")
        per_lang[v["language"]] = per_lang.get(v["language"], 0) + 1
    for lang, n in per_lang.items():
        if n > MAX_PER_LANGUAGE:
            problems.append(f"{lang}: {n} voices (at most {MAX_PER_LANGUAGE} per language)")
    for p in problems:
        print("VOICE:", p, file=sys.stderr)
    print(f"voices/manifest.json: {len(data['voices'])} voices, {len(problems)} problem(s)")
    return 1 if problems else 0


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch_file(f: dict, dest: Path) -> bool:
    """Download f into dest unless an identical copy is there. Returns True if it downloaded."""
    if dest.exists() and dest.stat().st_size == f["bytes"] and sha256(dest) == f["sha256"]:
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    req = urllib.request.Request(f["url"], headers={"User-Agent": "mokuhyo-tools"})
    with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT) as r, part.open("wb") as out:
        shutil.copyfileobj(r, out, 1 << 20)
    got = sha256(part)
    if got != f["sha256"] or part.stat().st_size != f["bytes"]:
        part.unlink()
        raise SystemExit(f"checksum mismatch for {f['url']}: {got} != {f['sha256']}")
    part.replace(dest)
    return True


def fetch(target: Path, only: list[str] | None) -> int:
    if check() != 0:
        return 1
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for v in data["voices"]:
        if only and v["id"] not in only and v["language"] not in only:
            continue
        for f in v["files"]:
            did = fetch_file(f, target / v.get("model", v["id"]) / f["name"])
            print(f"{'fetched' if did else 'ok     '} {v['id']}/{f['name']} ({f['bytes'] / 1e6:.1f} MB)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", nargs="?", choices=["write", "check"], default="check")
    ap.add_argument("--fetch", nargs="?", const=str(MODELS), metavar="DIR",
                    help="download and verify the voice files into DIR/<id>/ (default voices/models)")
    ap.add_argument("--only", nargs="*", help="with --fetch: voice ids or language codes to fetch")
    args = ap.parse_args()
    if args.fetch:
        return fetch(Path(args.fetch), args.only)
    return write() if args.command == "write" else check()


if __name__ == "__main__":
    sys.exit(main())
