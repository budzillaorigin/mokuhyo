"""Stages everything the installer bundles into desktopApp/resources/ (BRIEF §10), for the current OS:

- native/<variant>/  ← native/build/<os>-<arch>/<variant>/   (llama.cpp + whisper.cpp JNI library per variant)
- models/            ← bundled weights from content/models/manifest.json ("bundled": true), downloaded and
                       SHA-256 verified (cached under tools/.cache/models)
- voices/piper/      ← voices/build/<os>-<arch>/piper/ (Piper voice service, a separate GPL program; voices/build.sh)
  common/voices/     ← voices/manifest.json + each voice's files under <id>/, SHA-256 verified (voices/models/ if
                       fetched there, else downloaded into tools/.cache/voices)
- packs/             ← added by later stagers when present (content/packs)

Compose's appResourcesRootDir layout: common/ for every OS, <os>-<arch>/ (macos-arm64, macos-x64, windows-x64,
linux-x64) for one platform.

    uv run python release/stage_resources.py [--skip-models]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RES = REPO / "desktopApp" / "resources"
CACHE = REPO / "tools" / ".cache" / "models"
VOICE_CACHE = REPO / "tools" / ".cache" / "voices"


def os_arch() -> tuple[str, str, str]:
    """(native os-arch dir, Compose resources dir, our os id)."""
    sysname = {"Darwin": "macos", "Windows": "windows", "Linux": "linux"}[platform.system()]
    # MOKUHYO_ARCH=x86_64 stages an Intel Mac build from an Apple-silicon machine (tools/release/build_macos_x64.sh).
    machine = os.environ.get("MOKUHYO_ARCH", platform.machine()).lower()
    arch = "arm64" if machine in ("arm64", "aarch64") else "x86_64"
    compose = f"{sysname}-{'arm64' if arch == 'arm64' else 'x64'}"
    return f"{sysname}-{arch}", compose, sysname


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(url: str, dest: Path, expect_sha: str, size: int) -> None:
    if dest.exists() and dest.stat().st_size == size and sha256(dest) == expect_sha:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(dest.suffix + ".part")
    print(f"downloading {url} ({size / 1e6:.0f} MB)")
    req = urllib.request.Request(url, headers={"User-Agent": "mokuhyo-release"})
    with urllib.request.urlopen(req, timeout=120) as r, part.open("wb") as out:
        shutil.copyfileobj(r, out, 1 << 20)
    got = sha256(part)
    if got != expect_sha:
        part.unlink()
        raise SystemExit(f"checksum mismatch for {url}: {got} != {expect_sha}")
    part.replace(dest)


def stage_native(native_dir: str, compose_dir: str) -> int:
    src = REPO / "native" / "build" / native_dir
    dest = RES / compose_dir / "native"
    if dest.exists():
        shutil.rmtree(dest)
    count = 0
    for variant in sorted(p for p in src.glob("*") if p.is_dir() and p.name in ("cpu", "metal", "vulkan")):
        libs = [f for f in variant.iterdir() if f.suffix in (".dylib", ".so", ".dll")]
        if not libs:
            continue
        (dest / variant.name).mkdir(parents=True, exist_ok=True)
        for f in libs:
            shutil.copy2(f, dest / variant.name / f.name)
            count += 1
    print(f"native: staged {count} librar{'y' if count == 1 else 'ies'} into {dest.relative_to(REPO)}")
    return count


def stage_models() -> None:
    manifest = json.loads((REPO / "content/models/manifest.json").read_text(encoding="utf-8"))
    dest = RES / "common" / "models"
    dest.mkdir(parents=True, exist_ok=True)
    for m in manifest["models"]:
        if not m.get("bundled"):
            continue
        for f in m["files"]:
            cached = CACHE / f["name"]
            fetch(f["url"], cached, f["sha256"], f["bytes"])
            target = dest / f["name"]
            if not (target.exists() and target.stat().st_size == f["bytes"]):
                shutil.copy2(cached, target)
            print(f"models: {f['name']} ({f['bytes'] / 1e6:.0f} MB)")


def stage_voices(native_dir: str, compose_dir: str, with_voice_files: bool = True) -> bool:
    """Piper (per OS) + voice files and manifest (common). Returns False when the Piper build is missing."""
    src = REPO / "voices" / "build" / native_dir / "piper"
    dest = RES / compose_dir / "voices" / "piper"
    if dest.exists():
        shutil.rmtree(dest)
    exe = src / ("piper.exe" if native_dir.startswith("windows") else "piper")
    have_piper = exe.is_file()
    if have_piper:
        shutil.copytree(src, dest, symlinks=False)  # copy2 keeps the executable bit
        size = sum(f.stat().st_size for f in dest.rglob("*") if f.is_file())
        print(f"voices: staged Piper into {dest.relative_to(REPO)} ({size / 1e6:.0f} MB)")
    else:
        print(f"voices: no Piper build at {src.relative_to(REPO)}; run voices/build.sh (OS voices only)")

    common = RES / "common" / "voices"
    manifest_path = REPO / "voices" / "manifest.json"
    if common.exists():
        shutil.rmtree(common)
    if not with_voice_files or not manifest_path.exists():
        return have_piper
    common.mkdir(parents=True)
    shutil.copy2(manifest_path, common / "manifest.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    total = 0
    for v in manifest["voices"]:
        for f in v["files"]:
            local = REPO / "voices" / "models" / v["id"] / f["name"]
            if not (local.exists() and local.stat().st_size == f["bytes"] and sha256(local) == f["sha256"]):
                local = VOICE_CACHE / v["id"] / f["name"]
                fetch(f["url"], local, f["sha256"], f["bytes"])
            target = common / v["id"] / f["name"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(local, target)
            total += f["bytes"]
        print(f"voices: {v['id']}")
    print(f"voices: {len(manifest['voices'])} voices ({total / 1e6:.0f} MB) into {common.relative_to(REPO)}")
    return have_piper


PACK_FILES = ("exam.json", "opi.json", "dictionary.sqlite", "tokenizer.sqlite", "culture.json", "pragmatics.json", "personas.json", "feeds.json", "exemplars.json")


def stage_packs() -> int:
    """content/packs/<lang>/ → resources/common/packs/<lang>/ (exam, interview, dictionary, tokenizer, audio clips)."""
    src = REPO / "content" / "packs"
    dest = RES / "common" / "packs"
    if dest.exists():
        shutil.rmtree(dest)
    n = 0
    for lang_dir in sorted(p for p in src.glob("*") if p.is_dir()) if src.is_dir() else []:
        names = [f for f in PACK_FILES if (lang_dir / f).is_file()] + sorted(p.name for p in lang_dir.glob("track-*.json"))
        for f in names:  # topic tracks (BRIEF_PHASE8 C-03) ship next to the exam and interview packs
            (dest / lang_dir.name).mkdir(parents=True, exist_ok=True)
            shutil.copy2(lang_dir / f, dest / lang_dir.name / f)
            n += 1
        if (lang_dir / "audio").is_dir():
            shutil.copytree(lang_dir / "audio", dest / lang_dir.name / "audio")
    print(f"packs: staged {n} files into {dest.relative_to(REPO)}")
    return n


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-models", action="store_true", help="don't bundle model weights (CI smoke builds)")
    ap.add_argument("--skip-voices", action="store_true", help="don't bundle voice files (CI smoke builds)")
    ap.add_argument("--require-native", action="store_true")
    ap.add_argument("--skip-packs", action="store_true", help="don't bundle content packs (CI smoke builds)")
    ap.add_argument("--require-voices", action="store_true", help="fail when the Piper build is missing")
    args = ap.parse_args()
    native_dir, compose_dir, _ = os_arch()
    n = stage_native(native_dir, compose_dir)
    if args.require_native and n == 0:
        print(f"no native library under native/build/{native_dir}; run native/build.sh cpu first", file=sys.stderr)
        return 1
    if not args.skip_models:
        stage_models()
    if not getattr(args, "skip_packs", False):
        stage_packs()
    if not stage_voices(native_dir, compose_dir, with_voice_files=not args.skip_voices) and args.require_voices:
        print(f"no Piper build under voices/build/{native_dir}; run voices/build.sh first", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
