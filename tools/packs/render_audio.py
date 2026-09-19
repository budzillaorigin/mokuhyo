"""Render pre-recorded audio packs with a local VOICEVOX engine (BRIEF_V2 §5.6, §6.7; DECISIONS D-090..D-099).

    uv run python packs/render_audio.py pitch minimal-pairs          # render these sets
    uv run python packs/render_audio.py all                          # every set (grammar: 2 examples per point)
    uv run python packs/render_audio.py grammar --grammar-all        # every grammar example
    uv run python packs/render_audio.py exam --dry-run               # count clips, render nothing
    uv run python packs/render_audio.py readers                      # graded readers, sentence by sentence
    uv run python packs/render_audio.py pitch --endpoint http://<lan-ip>:50021

Sets and clip keys (the app looks clips up by these; see shared `app.tsumugi.audio.AudioKeys`):

    exam           exam/<passage or item id>/<line index>      JLPT + DLPT listening scripts (exam.sqlite)
    dialogues      dialogue/<dialogue id>/<line ord>            practice.sqlite listening dialogues
    minimal-pairs  pair/<pair id>/a|b                           practice.sqlite minimal pairs
    pitch          pitch/<item id>                              pitch-accent test items (built here, items.json)
    grammar        grammar/<point id>/<example ord>             grammar.sqlite example sentences
    readers        reader/<story id>/<sentence index>            readers.sqlite read-along lines (one clip per sentence)

Each clip is synthesized with /audio_query (or /accent_phrases for single words) + /synthesis, encoded to
AAC-LC .m4a (24 kHz mono, 48 kbps; D-091) with ffmpeg, and cached in tools/.cache/audio by a hash of everything
that affects the sound (engine version, voice, text, accent, speed/pitch/intonation, encoder settings). Re-runs
only render what's new; an interrupted run resumes where it stopped. Output: content/packs/audio-<set>.zip
(stored, deterministic) + content/packs/audio-manifest.json.

Word items (pitch, minimal pairs) are spoken from their kana reading, so the engine can't misread the kanji, and
their accent is set explicitly: the accent phrase's `accent` is edited, pitches are recomputed with /mora_pitch,
and if the engine's contour still doesn't show the Tokyo high/low pattern the mora pitches are replaced by a
stylized contour that does (D-093). The accent is therefore guaranteed, not predicted.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import wave
import zipfile
from collections import defaultdict
from dataclasses import dataclass, field
from itertools import pairwise
from pathlib import Path

from common import CACHE, PACKS, log, nfc, to_hiragana

SETS = ("exam", "dialogues", "minimal-pairs", "pitch", "grammar", "readers")
FORMAT = 1  # index.json layout version; the app refuses packs with a newer major format
RENDER_STYLE = "1"  # bump to invalidate every cached clip (e.g. a change to the stylized contour)

# Codec (D-091): AAC-LC in MP4 plays natively with AVAudioPlayer/AVPlayer (iOS) and MediaPlayer/Media3 (Android).
SAMPLE_RATE = 24_000
BITRATE = "48k"
ENCODER_ARGS = ["-ac", "1", "-ar", str(SAMPLE_RATE), "-c:a", "aac", "-b:a", BITRATE,
                "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", "-movflags", "+faststart"]

# Approved characters (owner, 2026-09-18; 青山龍星 dropped 2026-09-19, D-170), ノーマル style. Ids are resolved by
# name at run time.
TSUMUGI, METAN, TAKEHIRO = "春日部つむぎ", "四国めたん", "玄野武宏"
CHARACTERS = (TSUMUGI, METAN, TAKEHIRO)
STYLE = "ノーマル"


@dataclass(frozen=True)
class Voice:
    """A character plus a delivery offset. 玄野武宏 is the only male character, so a second male speaker in a
    script is the same character slightly lower and slower (D-170); his other styles are emotional, not neutral."""

    character: str
    pitch: float = 0.0  # added to Clip.pitch (VOICEVOX pitchScale)
    speed: float = 0.0  # added to Clip.speed (VOICEVOX speedScale)


V_TSUMUGI, V_METAN, V_TAKEHIRO = Voice(TSUMUGI), Voice(METAN), Voice(TAKEHIRO)
V_TAKEHIRO_LOW = Voice(TAKEHIRO, pitch=-0.05, speed=-0.05)  # second or older male speaker
# Voice hint → voices in order of preference. The first speaker with a hint gets the first free voice.
POOLS = {
    "female": [V_TSUMUGI, V_METAN],
    "male": [V_TAKEHIRO, V_TAKEHIRO_LOW],
    "male-senior": [V_TAKEHIRO_LOW, V_TAKEHIRO],
    "narrator": [V_METAN, V_TSUMUGI, V_TAKEHIRO, V_TAKEHIRO_LOW],
}
# Graded readers (D-205): 春日部つむぎ narrates; quoted speech goes to a different voice, so a female speaker is
# めたん first, a male one 玄野武宏 (the lower variant for a second or older man, D-170).
READER_NARRATOR = V_TSUMUGI
READER_POOLS = {
    "female": [V_METAN, V_TSUMUGI],
    "male": [V_TAKEHIRO, V_TAKEHIRO_LOW],
    "male-senior": [V_TAKEHIRO_LOW, V_TAKEHIRO],
}
WORD_VOICE = TSUMUGI  # pitch and minimal-pair items: one voice, so only the accent differs
CARRIER = "が"  # particle after pitch-test words, so 平板 and 尾高 differ audibly

# Pitch test (§6.7): Tokyo patterns × mora length 2–4.
PITCH_TARGET = 300
PITCH_MORAE = (2, 3, 4)
PATTERNS = ("heiban", "atamadaka", "nakadaka", "odaka")
PATTERN_JA = {"heiban": "平板", "atamadaka": "頭高", "nakadaka": "中高", "odaka": "尾高"}

SMALL = set("ゃゅょぁぃぅぇぉゎ")
HIGH_LOW_MARGIN = 0.10  # log-F0 gap an engine contour must show at each high/low boundary (~1.7 semitones)
STYLIZED_STEP = 0.17  # log-F0 gap of the stylized contour (~3 semitones)


def morae(kana: str) -> list[str]:
    out: list[str] = []
    for c in kana:
        if c in SMALL and out:
            out[-1] += c
        else:
            out.append(c)
    return out


def to_katakana(s: str) -> str:
    return "".join(chr(ord(c) + 0x60) if "ぁ" <= c <= "ゖ" else c for c in s)


def pattern_of(downstep: int, n: int) -> str:
    if downstep == 0:
        return "heiban"
    if downstep == 1:
        return "atamadaka"
    return "odaka" if downstep >= n else "nakadaka"


def heights(downstep: int, n: int) -> list[bool]:
    """High/low per mora for a word of n morae plus one following particle (mirror of shared PitchAccent)."""
    out = []
    for pos in range(1, n + 2):
        if downstep == 0:
            out.append(pos != 1)
        elif downstep == 1:
            out.append(pos == 1)
        else:
            out.append(2 <= pos <= downstep)
    return out


# ---------------------------------------------------------------------------------------------- clips


@dataclass
class Clip:
    key: str
    voice: str  # character name
    text: str  # what is spoken (sentence text, or the word's kana + carrier)
    kana: str | None = None  # word items: kana reading spoken via /accent_phrases
    downstep: int | None = None  # word items: Kanjium downstep (0 = heiban); None = engine's own accent
    carrier: str = ""
    speed: float = 1.0
    pitch: float = 0.0
    intonation: float = 1.0
    meta: dict = field(default_factory=dict)


def load_overrides(path: Path | None) -> dict[str, dict]:
    """Optional per-key parameter overrides: {"exam/dl-0p-announcement-001/0": {"speed": 0.9}, ...}."""
    if not path or not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


class Allocator:
    """Maps a script's speakers to voices: consistent within a script, distinct where the pool allows."""

    def __init__(self) -> None:
        self.by_speaker: dict[str, Voice] = {}

    def voice(self, speaker: str, hint: str | None, age: str | None = None) -> Voice:
        if speaker in self.by_speaker:
            return self.by_speaker[speaker]
        hint = (hint or "narrator").lower()
        pool_name = "male-senior" if hint == "male" and age == "senior" else hint
        pool = POOLS.get(pool_name, POOLS["narrator"])
        used = set(self.by_speaker.values())
        choice = next((c for c in pool if c not in used), pool[0])
        self.by_speaker[speaker] = choice
        return choice


def speed_for(level: str) -> float:
    """Slower delivery for beginner levels (JLPT N5/N4, DLPT 0+/1, practice jlpt 5/4)."""
    return {"N6": 0.85, "N5": 0.9, "N4": 0.95, "5": 0.9, "4": 0.95, "0+": 0.9, "1": 0.95}.get(level, 1.0)


def exam_clips(packs: Path) -> list[Clip]:
    db = sqlite3.connect(packs / "exam.sqlite")
    clips: list[Clip] = []
    rows = list(db.execute("SELECT id, exam, level, script FROM exam_passage WHERE script NOT IN ('', '[]') ORDER BY id"))
    rows += list(db.execute("SELECT id, exam, level, script FROM exam_item WHERE script NOT IN ('', '[]') ORDER BY id"))
    for owner, exam, level, script in rows:
        alloc = Allocator()
        # JLPT levels are "N5".."N1", DLPT "0+".."3"; practice-style digits don't occur here.
        speed = speed_for(level) if exam == "JLPT" or level in ("0+", "1") else 1.0
        for i, line in enumerate(json.loads(script)):
            text = nfc(line.get("text", "")).strip()
            if not text:
                continue
            v = alloc.voice(line.get("speaker", ""), line.get("voice"))
            clips.append(Clip(f"exam/{owner}/{i}", v.character, text, speed=round(speed + v.speed, 3), pitch=v.pitch))
    db.close()
    return clips


def dialogue_clips(packs: Path) -> list[Clip]:
    db = sqlite3.connect(packs / "practice.sqlite")
    clips: list[Clip] = []
    speakers = {d: json.loads(s) for d, s in db.execute("SELECT id, speakers FROM dialogue")}
    jlpt = dict(db.execute("SELECT id, jlpt FROM dialogue"))
    allocs: dict[str, Allocator] = {}
    for did, ord_, speaker, ja in db.execute("SELECT dialogue_id, ord, speaker, ja FROM dialogue_line ORDER BY dialogue_id, ord"):
        alloc = allocs.get(did)
        if alloc is None:
            alloc = allocs[did] = Allocator()
            for sp in speakers.get(did, []):  # allocate in declared order so A/B are stable
                alloc.voice(sp["id"], sp.get("voice"), sp.get("age"))
        info = next((s for s in speakers.get(did, []) if s["id"] == speaker), {})
        v = alloc.voice(speaker, info.get("voice"), info.get("age"))
        speed = round(speed_for(str(jlpt.get(did, ""))) + v.speed, 3)
        clips.append(Clip(f"dialogue/{did}/{ord_}", v.character, nfc(ja), speed=speed, pitch=v.pitch))
    db.close()
    return clips


def minimal_pair_clips(packs: Path) -> list[Clip]:
    db = sqlite3.connect(packs / "practice.sqlite")
    clips: list[Clip] = []
    for row in db.execute(
        "SELECT id, category, text_a, reading_a, accent_a, text_b, reading_b, accent_b FROM minimal_pair ORDER BY id"
    ):
        pid, cat, ta, ra, aa, tb, rb, ab = row
        carrier = CARRIER if cat == "PITCH" else ""
        for side, text, reading, accent in (("a", ta, ra, aa), ("b", tb, rb, ab)):
            reading = to_hiragana(nfc(reading))
            clips.append(Clip(
                f"pair/{pid}/{side}", WORD_VOICE, reading + carrier, kana=reading, downstep=accent, carrier=carrier,
                speed=0.95, meta={"display": nfc(text)},
            ))
    db.close()
    return clips


def grammar_clips(packs: Path, per_point: int | None) -> list[Clip]:
    db = sqlite3.connect(packs / "grammar.sqlite")
    clips: list[Clip] = []
    sql = "SELECT point_id, ord, ja FROM grammar_example"
    params: tuple = ()
    if per_point is not None:
        sql += " WHERE ord < ?"
        params = (per_point,)
    for pid, ord_, ja in db.execute(sql + " ORDER BY point_id, ord", params):
        voice = TSUMUGI if ord_ % 2 == 0 else TAKEHIRO
        clips.append(Clip(f"grammar/{pid}/{ord_}", voice, nfc(ja)))
    db.close()
    return clips


def utf16_slice(text: str, start: int, end: int) -> str:
    """text[start:end] with UTF-16 offsets (what the pack stores, like Kotlin String indices)."""
    units = text.encode("utf-16-le")
    return units[start * 2:end * 2].decode("utf-16-le")


def reader_clips(packs: Path) -> list[Clip]:
    """One clip per read-along line of readers.sqlite (tools/packs/readers/build_readers.py): the narration voice for
    narration, the story's cast voices for quoted speech (a "Name：" or "Name" prefix before 「 isn't spoken)."""
    path = packs / "readers.sqlite"
    if not path.exists():
        log(f"readers: {path} is missing; build it with packs/readers/build_readers.py first")
        return []
    db = sqlite3.connect(path)
    clips: list[Clip] = []
    stories = {sid: (level, body, json.loads(cast)) for sid, level, body, cast in
               db.execute("SELECT id, level, body, cast_json FROM reader_story")}
    allocs: dict[str, dict[str, Voice]] = {}
    rows = db.execute("SELECT story_id, idx, start_offset, end_offset, speaker, voice FROM reader_sentence "
                      "ORDER BY story_id, idx")
    for sid, idx, start, end, speaker, hint in rows:
        level, body, cast = stories[sid]
        text = nfc(utf16_slice(body, start, end)).strip()
        if speaker and text.startswith(speaker):
            rest = text[len(speaker):].lstrip().removeprefix("：").removeprefix(":").lstrip()
            if rest.startswith(("「", "『")):
                text = rest
        if not any(c.isalnum() for c in text):
            continue
        if not speaker:
            v = READER_NARRATOR
        else:
            voices = allocs.setdefault(sid, {})
            if not voices:  # allocate in cast order so voices are stable across re-renders
                for member in cast:
                    pool = READER_POOLS.get(member.get("voice", "female"), READER_POOLS["female"])
                    taken = set(voices.values())
                    voices[member["name"]] = next((c for c in pool if c not in taken), pool[0])
            v = voices.get(speaker) or READER_POOLS.get(hint, READER_POOLS["female"])[0]
        speed = round(speed_for(level) + v.speed, 3)
        clips.append(Clip(f"reader/{sid}/{idx}", v.character, text, speed=speed, pitch=v.pitch))
    db.close()
    return clips


def pitch_items(packs: Path, target: int = PITCH_TARGET) -> list[dict]:
    """Same-kana words with different Kanjium accents, balanced over pattern × mora length (§6.7).

    Only words with a single recorded accent are used (a word listed as "0,2" has no one right answer). Ids are
    `p<JMdict id>`, stable across rebuilds.
    """
    d = sqlite3.connect(packs / "dictionary.sqlite")
    gloss: dict[int, str] = {}
    kana_usually: set[int] = set()
    for eid, glosses, misc in d.execute("SELECT entry_id, glosses, misc FROM sense WHERE ord = 0"):
        gl = json.loads(glosses) if glosses else []
        if gl:
            gloss[eid] = gl[0]
        if misc and "uk" in json.loads(misc):
            kana_usually.add(eid)
    kanji = dict(d.execute("SELECT entry_id, text FROM entry_kanji WHERE ord = 0"))
    reading_of = dict(d.execute("SELECT entry_id, text FROM entry_kana WHERE ord = 0"))
    accents: dict[tuple[str, str], list[int]] = {}
    for text, reading, acc in d.execute("SELECT text, reading, accents FROM pitch"):
        vals = [int(a) for a in acc.split(",") if a.strip().isdigit()]
        if vals:
            accents[(nfc(text), to_hiragana(nfc(reading)))] = vals
    words = []
    for eid, rank in d.execute("SELECT id, rank FROM entry WHERE is_common = 1 OR jlpt IS NOT NULL ORDER BY rank, id"):
        kana = reading_of.get(eid, "")
        reading = to_hiragana(nfc(kana))
        if not reading or any(not ("ぁ" <= c <= "ゖ" or c == "ー") for c in reading) or eid not in gloss:
            continue
        n = len(morae(reading))
        if n not in PITCH_MORAE:
            continue
        form = kanji.get(eid, kana)
        acc = accents.get((nfc(form), reading))
        if not acc or len(set(acc)) != 1:
            continue
        display = kana if eid in kana_usually or eid not in kanji else kanji[eid]
        words.append({"eid": eid, "rank": rank, "text": nfc(display), "reading": reading, "morae": n,
                      "downstep": acc[0], "pattern": pattern_of(acc[0], n), "gloss": gloss[eid]})
    d.close()

    # Groups: same reading, one word per distinct accent (most common first), at least two accents.
    by_reading: dict[str, list[dict]] = defaultdict(list)
    for w in words:
        group = by_reading[w["reading"]]
        if all(g["downstep"] != w["downstep"] for g in group) and all(g["text"] != w["text"] for g in group):
            group.append(w)
    groups = [g for g in by_reading.values() if len(g) >= 2]
    groups.sort(key=lambda g: (min(w["rank"] for w in g), g[0]["reading"]))

    cells = [(p, n) for n in PITCH_MORAE for p in PATTERNS if not (p == "nakadaka" and n < 3)]
    cap = -(-target // len(cells))
    count: dict[tuple[str, int], int] = defaultdict(int)
    chosen: list[dict] = []
    # Pass 1: whole groups whose every word lands in a cell with room (keeps pairs intact).
    for g in groups:
        if len(chosen) >= target:
            break
        if all(count[(w["pattern"], w["morae"])] < cap for w in g):
            for w in g:
                count[(w["pattern"], w["morae"])] += 1
            chosen.extend(dict(w, group=g[0]["reading"]) for w in g)
    # Pass 2: under-filled cells take single words from larger groups (paired with a word already chosen).
    chosen_ids = {w["eid"] for w in chosen}
    for g in groups:
        if len(chosen) >= target:
            break
        for w in g:
            cell = (w["pattern"], w["morae"])
            if w["eid"] not in chosen_ids and count[cell] < cap and len(chosen) < target:
                count[cell] += 1
                chosen_ids.add(w["eid"])
                chosen.append(dict(w, group=g[0]["reading"]))
    # Pass 3: cells no homophone group can fill (尾高 3–4 morae are rare among homophones) take single words;
    # their confusable partners are the other patterns of the same length.
    for w in words:
        if len(chosen) >= target:
            break
        cell = (w["pattern"], w["morae"])
        if w["eid"] not in chosen_ids and count[cell] < cap and w["reading"] not in by_reading_chosen(chosen):
            count[cell] += 1
            chosen_ids.add(w["eid"])
            chosen.append(dict(w, group=w["reading"]))
    items = []
    for w in sorted(chosen, key=lambda w: (w["group"], w["downstep"])):
        items.append({
            "id": f"p{w['eid']}",
            "entryId": w["eid"],
            "text": w["text"],
            "reading": w["reading"],
            "moraCount": w["morae"],
            "downstep": w["downstep"],
            "pattern": w["pattern"],
            "group": w["group"],
            "gloss": w["gloss"],
            "spoken": w["reading"] + CARRIER,
            "source": "kanjium",
        })
    # Confusable partners: other items in the same group.
    by_group: dict[str, list[str]] = defaultdict(list)
    for it in items:
        by_group[it["group"]].append(it["id"])
    for it in items:
        it["confusableWith"] = [i for i in by_group[it["group"]] if i != it["id"]]
    return items


def by_reading_chosen(chosen: list[dict]) -> set[str]:
    return {w["reading"] for w in chosen}


def pitch_clips(items: list[dict]) -> list[Clip]:
    return [
        Clip(f"pitch/{it['id']}", WORD_VOICE, it["spoken"], kana=it["reading"], downstep=it["downstep"],
             carrier=CARRIER, speed=0.95, meta={"display": it["text"]})
        for it in items
    ]


# ---------------------------------------------------------------------------------------------- engine


class Engine:
    def __init__(self, endpoint: str) -> None:
        self.endpoint = endpoint.rstrip("/")
        self.version = json.loads(self._get("/version"))
        self.speakers: dict[str, int] = {}
        for sp in json.loads(self._get("/speakers")):
            for st in sp["styles"]:
                if sp["name"] in CHARACTERS and st["name"] == STYLE:
                    self.speakers[sp["name"]] = st["id"]
        missing = [c for c in CHARACTERS if c not in self.speakers]
        if missing:
            raise SystemExit(f"VOICEVOX at {self.endpoint} lacks {missing} ({STYLE})")
        self.defaults = {k: v for k, v in json.loads(self._post("/audio_query", {"text": "あ", "speaker": 8})).items()
                         if k not in ("accent_phrases", "kana")}
        self.stylized = 0

    def _get(self, path: str) -> bytes:
        with urllib.request.urlopen(self.endpoint + path, timeout=30) as r:
            return r.read()

    def _post(self, path: str, params: dict, body=None, timeout: float = 300) -> bytes:
        url = f"{self.endpoint}{path}?{urllib.parse.urlencode(params)}"
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else b""
        req = urllib.request.Request(url, data=data, method="POST", headers={"Content-Type": "application/json"})
        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=timeout) as r:
                    return r.read()
            except urllib.error.HTTPError as e:
                raise RuntimeError(f"{path} {e.code}: {e.read().decode('utf-8', 'replace')[:300]}") from e
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                if attempt == 2:
                    raise
                time.sleep(2 + attempt * 3)
        raise AssertionError

    def synthesize(self, clip: Clip) -> bytes:
        spk = self.speakers[clip.voice]
        if clip.kana is not None:
            query = dict(self.defaults, accent_phrases=self._word_phrases(clip, spk))
        else:
            query = json.loads(self._post("/audio_query", {"text": clip.text, "speaker": spk}))
        query.update(speedScale=clip.speed, pitchScale=clip.pitch, intonationScale=clip.intonation,
                     outputSamplingRate=SAMPLE_RATE, outputStereo=False)
        return self._post("/synthesis", {"speaker": spk}, query)

    def _word_phrases(self, clip: Clip, spk: int) -> list:
        if clip.downstep is None:  # unknown accent: let the engine's own analysis decide
            return json.loads(self._post("/accent_phrases", {"text": clip.kana + clip.carrier, "speaker": spk}))
        kana = to_katakana(clip.kana + clip.carrier)
        # A trailing ' marks the accent on the last mora: one accent phrase, whatever the word. The real accent
        # is set below; the kana notation can't express 平板 on its own.
        phrases = json.loads(self._post("/accent_phrases", {"text": kana + "'", "speaker": spk, "is_kana": "true"}))
        if len(phrases) != 1:
            raise RuntimeError(f"{clip.key}: expected one accent phrase for {kana}, got {len(phrases)}")
        n_word = len(morae(clip.kana))
        n_all = len(phrases[0]["moras"])
        # VOICEVOX `accent` is the 1-based accented mora; accent == mora count means no fall inside the phrase.
        phrases[0]["accent"] = clip.downstep if clip.downstep > 0 else n_all
        phrases = json.loads(self._post("/mora_pitch", {"speaker": spk}, phrases))
        target = heights(clip.downstep, n_word)[:n_all] if clip.carrier else heights(clip.downstep, n_word)[:n_word]
        moras = phrases[0]["moras"]
        if len(target) == len(moras) and not contour_ok([m["pitch"] for m in moras], target):
            stylize(moras, target)
            self.stylized += 1
        return phrases


def contour_ok(pitches: list[float], high: list[bool]) -> bool:
    """Every voiced high/low boundary shows the right direction by at least HIGH_LOW_MARGIN."""
    voiced = [(p, h) for p, h in zip(pitches, high) if p > 0]
    for (p1, h1), (p2, h2) in pairwise(voiced):
        if h1 and not h2 and p1 - p2 < HIGH_LOW_MARGIN:
            return False
        if h2 and not h1 and p2 - p1 < HIGH_LOW_MARGIN:
            return False
    return True


def stylize(moras: list[dict], high: list[bool]) -> None:
    """Replace mora pitches with a clear two-level contour anchored at the engine's own register."""
    voiced = [m["pitch"] for m in moras if m["pitch"] > 0]
    if not voiced:
        return
    top = max(voiced)
    for i, (m, h) in enumerate(zip(moras, high)):
        if m["pitch"] > 0:  # devoiced morae stay devoiced
            m["pitch"] = (top if h else top - STYLIZED_STEP) - 0.01 * i  # slight declination


# ---------------------------------------------------------------------------------------------- cache


def find_ffmpeg(explicit: str | None, cache: Path) -> str:
    for cand in (explicit, os.environ.get("FFMPEG"), shutil.which("ffmpeg")):
        if cand and (Path(cand).exists() or shutil.which(cand)):
            return cand
    for p in sorted((cache / "ffmpeg").rglob("ffmpeg.exe" if os.name == "nt" else "ffmpeg")):
        return str(p)
    raise SystemExit("ffmpeg not found: install it, set FFMPEG, or unpack a static build into tools/.cache/ffmpeg")


def clip_hash(clip: Clip, engine_version: str, speaker_id: int) -> str:
    material = {
        "style": RENDER_STYLE, "engine": engine_version, "voice": clip.voice, "speaker": speaker_id,
        "text": clip.text, "kana": clip.kana, "downstep": clip.downstep, "carrier": clip.carrier,
        "speed": clip.speed, "pitch": clip.pitch, "intonation": clip.intonation, "encoder": ENCODER_ARGS,
    }
    if clip.kana is not None and clip.downstep is not None:  # word items: the contour rules shape the sound
        material["contour"] = [HIGH_LOW_MARGIN, STYLIZED_STEP]
    return hashlib.sha256(json.dumps(material, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def wav_ms(wav: bytes) -> int:
    with wave.open(io.BytesIO(wav)) as w:
        return round(w.getnframes() * 1000 / w.getframerate())


def encode(ffmpeg: str, wav: bytes, out: Path) -> None:
    tmp = out.with_name(out.name + ".part.m4a")
    subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "wav", "-i", "pipe:0",
                    *ENCODER_ARGS, str(tmp)], input=wav, check=True)
    os.replace(tmp, out)


# ---------------------------------------------------------------------------------------------- packaging


ZIP_TIME = (1980, 1, 1, 0, 0, 0)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_zip(path: Path, files: dict[str, bytes | Path]) -> None:
    """Deterministic, uncompressed zip (audio doesn't compress; stored entries are cheap to extract)."""
    tmp = path.with_name(path.name + ".part")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as z:
        for name in sorted(files):
            info = zipfile.ZipInfo(name, ZIP_TIME)
            info.external_attr = 0o644 << 16
            data = files[name]
            z.writestr(info, data if isinstance(data, bytes) else data.read_bytes())
    os.replace(tmp, path)


def update_manifest(out_dir: Path, entry: dict) -> None:
    path = out_dir / "audio-manifest.json"
    packs = json.loads(path.read_text(encoding="utf-8"))["packs"] if path.exists() else []
    packs = [p for p in packs if p["file"] != entry["file"]] + [entry]
    path.write_text(json.dumps({"format": FORMAT, "packs": sorted(packs, key=lambda p: p["file"])},
                               ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def render_set(name: str, clips: list[Clip], engine: Engine, ffmpeg: str, cache: Path, out_dir: Path,
               overrides: dict, extra_files: dict[str, bytes], meta: dict) -> dict:
    clip_dir = cache / "audio" / "clips"
    clip_dir.mkdir(parents=True, exist_ok=True)
    index_clips: dict[str, dict] = {}
    files: dict[str, bytes | Path] = {}
    rendered = cached = 0
    render_seconds = 0.0
    used_voices: set[str] = set()
    stylized_before = engine.stylized
    start = time.time()
    for n, clip in enumerate(clips, 1):
        for k, v in overrides.get(clip.key, {}).items():
            setattr(clip, k, v)
        spk = engine.speakers[clip.voice]
        h = clip_hash(clip, engine.version, spk)
        audio = clip_dir / h[:2] / f"{h}.m4a"
        side = audio.with_suffix(".json")
        if audio.exists() and side.exists():
            cached += 1
            ms = json.loads(side.read_text(encoding="utf-8"))["ms"]
        else:
            t = time.time()
            try:
                wav = engine.synthesize(clip)
            except RuntimeError as e:
                log(f"  skip {clip.key}: {e}")
                continue
            audio.parent.mkdir(parents=True, exist_ok=True)
            encode(ffmpeg, wav, audio)
            ms = wav_ms(wav)
            side.write_text(json.dumps({"ms": ms, "key": clip.key, "text": clip.text}, ensure_ascii=False),
                            encoding="utf-8")
            render_seconds += time.time() - t
            rendered += 1
        used_voices.add(clip.voice)
        file = f"clips/{h[:16]}.m4a"  # named by content: identical clips are stored once
        files[file] = audio
        entry = {"file": file, "bytes": audio.stat().st_size, "ms": ms, "voice": clip.voice, "text": clip.text}
        if clip.downstep is not None:
            entry["downstep"] = clip.downstep
        entry.update(clip.meta)
        index_clips[clip.key] = entry
        if n % 100 == 0 or n == len(clips):
            per = render_seconds / rendered if rendered else 0.0
            log(f"  {name}: {n}/{len(clips)} ({rendered} rendered, {cached} cached, {per:.2f} s/clip)")
    credits = [f"VOICEVOX:{v}" for v in CHARACTERS if v in used_voices]
    index = {
        "format": FORMAT, "set": name, "codec": "aac-lc", "container": "m4a", "sampleRate": SAMPLE_RATE,
        "channels": 1, "bitrate": BITRATE, "engine": f"VOICEVOX Engine {engine.version}", "credits": credits,
        "clips": dict(sorted(index_clips.items())), **meta,
    }
    files["index.json"] = json.dumps(index, ensure_ascii=False, indent=1).encode("utf-8")
    files.update(extra_files)
    out_dir.mkdir(parents=True, exist_ok=True)
    zip_path = out_dir / f"audio-{name}.zip"
    write_zip(zip_path, files)
    digest = sha256_file(zip_path)
    total_ms = sum(c["ms"] for c in index_clips.values())
    entry = {
        "file": zip_path.name, "set": name, "version": f"{FORMAT}-{digest[:12]}", "sha256": digest,
        "bytes": zip_path.stat().st_size, "clips": len(index_clips), "audioSeconds": round(total_ms / 1000),
        "credits": credits,
    }
    update_manifest(out_dir, entry)
    elapsed = time.time() - start
    per = render_seconds / rendered if rendered else 0.0
    log(f"{name}: {len(index_clips)} clips ({len(files) - 1 - len(extra_files)} files), "
        f"{entry['bytes'] / 1e6:.1f} MB, {total_ms / 60000:.1f} min audio; rendered {rendered} "
        f"({per:.2f} s/clip), cached {cached}; {elapsed:.0f} s")
    log_path = out_dir / "audio-build-log.jsonl"
    with log_path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(dict(entry, rendered=rendered, cached=cached, renderSecondsPerClip=round(per, 3),
                                elapsedSeconds=round(elapsed), engine=engine.version, stylized=engine.stylized - stylized_before,
                                at=time.strftime("%Y-%m-%dT%H:%M:%S")), ensure_ascii=False) + "\n")
    return entry


# ---------------------------------------------------------------------------------------------- main


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sets", nargs="+", choices=(*SETS, "all"))
    ap.add_argument("--endpoint", default="http://127.0.0.1:50021", help="VOICEVOX engine URL")
    ap.add_argument("--packs-dir", type=Path, default=PACKS,
                    help="where exam/practice/grammar/readers/dictionary packs are")
    ap.add_argument("--out-dir", type=Path, default=PACKS, help="where audio-<set>.zip is written")
    ap.add_argument("--cache-dir", type=Path, default=CACHE, help="clip cache root (tools/.cache)")
    ap.add_argument("--ffmpeg", help="ffmpeg executable (default: $FFMPEG, PATH, tools/.cache/ffmpeg)")
    ap.add_argument("--overrides", type=Path, default=Path(__file__).parent / "audio" / "overrides.json",
                    help="JSON of per-key speed/pitch/intonation overrides")
    ap.add_argument("--grammar-per-point", type=int, default=2, help="grammar examples per point (default 2)")
    ap.add_argument("--grammar-all", action="store_true", help="render every grammar example")
    ap.add_argument("--limit", type=int, help="render only the first N clips of each set (smoke tests)")
    ap.add_argument("--dry-run", action="store_true", help="count clips per set and exit")
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    sets = SETS if "all" in args.sets else tuple(dict.fromkeys(args.sets))

    overrides = load_overrides(args.overrides)
    plan: dict[str, tuple[list[Clip], dict[str, bytes], dict]] = {}
    for name in sets:
        extra: dict[str, bytes] = {}
        meta: dict = {}
        if name == "exam":
            clips = exam_clips(args.packs_dir)
        elif name == "dialogues":
            clips = dialogue_clips(args.packs_dir)
        elif name == "minimal-pairs":
            clips = minimal_pair_clips(args.packs_dir)
        elif name == "pitch":
            items = pitch_items(args.packs_dir)
            clips = pitch_clips(items)
            extra["items.json"] = json.dumps({"format": FORMAT, "carrier": CARRIER, "items": items},
                                             ensure_ascii=False, indent=1).encode("utf-8")
            cells: dict[str, int] = defaultdict(int)
            for it in items:
                cells[f"{PATTERN_JA[it['pattern']]}{it['moraCount']}"] += 1
            meta["cells"] = dict(sorted(cells.items()))
        elif name == "readers":
            clips = reader_clips(args.packs_dir)
            if not clips:
                continue
            meta["stories"] = len({c.key.split("/")[1] for c in clips})
        else:
            per = None if args.grammar_all else args.grammar_per_point
            clips = grammar_clips(args.packs_dir, per)
            meta["examplesPerPoint"] = per
        if args.limit:
            clips = clips[: args.limit]
        plan[name] = (clips, extra, meta)
        log(f"{name}: {len(clips)} clips" + (f" {json.dumps(meta, ensure_ascii=False)}" if meta else ""))
    if args.dry_run:
        return

    engine = Engine(args.endpoint)
    ffmpeg = find_ffmpeg(args.ffmpeg, args.cache_dir)
    log(f"VOICEVOX Engine {engine.version} at {engine.endpoint}: "
        + ", ".join(f"{k}={v}" for k, v in engine.speakers.items()) + f"; ffmpeg {ffmpeg}")
    for name, (clips, extra, meta) in plan.items():
        render_set(name, clips, engine, ffmpeg, args.cache_dir, args.out_dir, overrides, extra, meta)


if __name__ == "__main__":
    main()
