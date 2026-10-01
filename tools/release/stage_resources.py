"""Stages everything the installer bundles into desktopApp/resources/ (BRIEF §10), for the current OS:

- native/<variant>/  ← native/build/<os>-<arch>/<variant>/   (llama.cpp + whisper.cpp JNI library per variant)
- models/            ← bundled weights from content/models/manifest.json ("bundled": true), downloaded and
                       SHA-256 verified (cached under tools/.cache/models)
- voices/, packs/    ← added by Phase 2+ stagers when present (voices/bin, content/packs)

Compose's appResourcesRootDir layout: common/ for every OS, <os>-<arch>/ (macos-arm64, macos-x64, windows-x64,
linux-x64) for one platform.

    uv run python release/stage_resources.py [--skip-models]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import shutil
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RES = REPO / "desktopApp" / "resources"
CACHE = REPO / "tools" / ".cache" / "models"


def os_arch() -> tuple[str, str, str]:
    """(native os-arch dir, Compose resources dir, our os id)."""
    sysname = {"Darwin": "macos", "Windows": "windows", "Linux": "linux"}[platform.system()]
    machine = platform.machine().lower()
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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-models", action="store_true", help="don't bundle model weights (CI smoke builds)")
    ap.add_argument("--require-native", action="store_true")
    args = ap.parse_args()
    native_dir, compose_dir, _ = os_arch()
    n = stage_native(native_dir, compose_dir)
    if args.require_native and n == 0:
        print(f"no native library under native/build/{native_dir}; run native/build.sh cpu first", file=sys.stderr)
        return 1
    if not args.skip_models:
        stage_models()
    return 0


if __name__ == "__main__":
    sys.exit(main())
