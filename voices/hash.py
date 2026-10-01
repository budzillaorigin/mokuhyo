"""Helpers for voices/build.sh and voices/build.ps1 (stdlib only).

    python3 voices/hash.py sources <os>-<arch>     print LICENSES/SOURCE.txt (exact source tarballs + hashes)
    python3 voices/hash.py artifacts <os>-<arch>   record voices/build/<os>-<arch>/piper/ in voices/lock.json

"artifacts" entries keep platforms not built on this machine, so each CI runner adds its own.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
LOCK = HERE / "lock.json"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sources(platform: str) -> str:
    lock = json.loads(LOCK.read_text(encoding="utf-8"))["sources"]
    lines = [
        "Corresponding source for the programs in this folder",
        "====================================================",
        "",
        "This folder holds Piper, a text-to-speech program that Mokuhyo starts as a separate process. It is not",
        "linked into Mokuhyo. Because piper loads espeak-ng (GPL-3.0-or-later), piper together with the libraries",
        "here is distributed under the GPL-3.0-or-later; the license texts are in this folder.",
        "",
        "The exact sources these binaries were built from (each archive is verified against its SHA-256 at build",
        "time). The build scripts are voices/build.sh, voices/build.ps1, voices/CMakeLists.txt and",
        "voices/patch_piper.cmake in the Mokuhyo source repository (Apache-2.0), next to voices/lock.json.",
        "",
    ]
    order = ["espeak-ng", "piper", "piper-phonemize", "fmt", "spdlog", "onnxruntime"]
    for name in order:
        s = lock[name]
        if name == "onnxruntime":
            p = s["platforms"][platform]
            url, sha = p["url"], p["sha256"]
            extra = "prebuilt binary release from Microsoft (source: https://github.com/microsoft/onnxruntime/tree/v1.14.1)"
        else:
            url, sha = s["url"], s["sha256"]
            extra = s.get("patch", "")
        lines += [f"{name} {s['version']} ({s['license']})", f"  {url}", f"  sha256 {sha}"]
        if extra:
            lines.append(f"  {extra}")
        lines.append("")
    return "\n".join(lines)


def artifacts(platform: str) -> None:
    root = HERE / "build" / platform / "piper"
    if not root.is_dir():
        raise SystemExit(f"no build at {root}")
    files = sorted(p for p in root.rglob("*") if p.is_file())
    total = sum(p.stat().st_size for p in files)
    tree = hashlib.sha256()
    binaries = {}
    for p in files:
        rel = p.relative_to(root).as_posix()
        digest = sha256(p)
        tree.update(f"{rel}\0{digest}\n".encode())
        if "/" not in rel and not rel.endswith(".txt"):
            binaries[rel] = {"sha256": digest, "bytes": p.stat().st_size}
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    lock.setdefault("artifacts", {})[platform] = {
        "treeSha256": tree.hexdigest(),
        "files": len(files),
        "bytes": total,
        "binaries": binaries,
    }
    lock["artifacts"] = dict(sorted(lock["artifacts"].items()))
    LOCK.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"lock.json: {platform} {len(files)} files, {total / 1e6:.1f} MB, tree {tree.hexdigest()[:16]}")


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in ("sources", "artifacts"):
        print(__doc__, file=sys.stderr)
        return 2
    if sys.argv[1] == "sources":
        sys.stdout.write(sources(sys.argv[2]))
    else:
        artifacts(sys.argv[2])
    return 0


if __name__ == "__main__":
    sys.exit(main())
