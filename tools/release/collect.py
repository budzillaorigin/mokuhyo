"""Collects a platform's installers into dist/ with release names (release.yml):
Mokuhyo-<version>-<platform>.{msi,dmg,deb} plus a portable archive of the app image (zip on Windows/macOS,
tar.gz on Linux). The macOS DMG carries the 1.y.z bundle version internally (D-008); its file name uses the real version.
Every platform's runtime CycloneDX SBOM (BRIEF_PHASE8 N-11) is required: Mokuhyo-<version>-<platform>-sbom.cdx.json.

    uv run python release/collect.py --name macos-arm64 --version 0.1.0 --out ../dist
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import tarfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BIN = REPO / "desktopApp" / "build" / "compose" / "binaries" / "main"


def patch_deb(deb: Path) -> None:
    """Make the .deb's desktop-menu step non-fatal (deb_menu.py; release dry run 39663569689)."""
    import deb_menu  # Linux only: needs dpkg-deb
    changed = deb_menu.patch(deb)
    print(f"collect: {deb.name}: {changed} desktop-menu command(s) made non-fatal")


def sbom_problem(path: Path) -> str | None:
    """Why [path] is not a usable CycloneDX JSON SBOM of the shipped runtime, or None."""
    if not path.is_file():
        return f"missing {path} (run :desktopApp:cyclonedxDirectBom; packaging depends on it)"
    try:
        bom = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        return f"{path.name} is not JSON: {e}"
    if not isinstance(bom, dict) or bom.get("bomFormat") != "CycloneDX" or not str(bom.get("specVersion", "")).startswith("1."):
        return f"{path.name} is not a CycloneDX 1.x BOM"
    components = bom.get("components")
    if not isinstance(components, list) or not components:
        return f"{path.name} lists no components"
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    out = Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    base = f"Mokuhyo-{a.version}-{a.name}"
    found = 0
    for ext in ("msi", "dmg", "deb"):
        for f in (BIN / ext).glob(f"*.{ext}") if (BIN / ext).is_dir() else []:
            shutil.copy2(f, out / f"{base}.{ext}")
            if ext == "deb":
                patch_deb(out / f"{base}.{ext}")
            found += 1
    app = BIN / "app"
    images = [p for p in app.iterdir()] if app.is_dir() else []
    if images:
        if a.name.startswith("linux"):
            with tarfile.open(out / f"{base}-portable.tar.gz", "w:gz") as t:
                t.add(images[0], arcname=images[0].name)
        else:
            shutil.make_archive(str(out / f"{base}-portable"), "zip", app)
        found += 1
    if not found:
        print("collect: FAIL no installers or app image found", file=sys.stderr)
        return 1
    # BRIEF_PHASE8 N-11 / QC Q6: a release without its SBOM is incomplete; refuse it instead of skipping.
    sbom = REPO / "desktopApp" / "build" / "reports" / "cyclonedx-direct" / "bom.json"
    problem = sbom_problem(sbom)
    if problem:
        print(f"collect: FAIL SBOM {problem}", file=sys.stderr)
        return 1
    shutil.copy2(sbom, out / f"{base}-sbom.cdx.json")
    for f in sorted(out.iterdir()):
        print(f"{f.name}  {f.stat().st_size / 1e6:.0f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
