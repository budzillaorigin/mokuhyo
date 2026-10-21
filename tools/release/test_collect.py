"""QC Q6 regression: release collection refuses an installer-only fixture (no SBOM) or a bad SBOM, and ships a valid
CycloneDX SBOM under the platform's release name. Synthetic files only."""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
from pathlib import Path

import collect

VALID = {"bomFormat": "CycloneDX", "specVersion": "1.6", "components": [{"type": "library", "name": "kotlin-stdlib", "version": "2.4.10"}]}


def run(root: Path, sbom: object | None) -> tuple[int, Path]:
    collect.REPO = root
    collect.BIN = root / "binaries"
    (collect.BIN / "deb").mkdir(parents=True, exist_ok=True)
    (collect.BIN / "deb" / "fixture.deb").write_bytes(b"synthetic fixture")
    bom = root / "desktopApp" / "build" / "reports" / "cyclonedx-direct" / "bom.json"
    bom.parent.mkdir(parents=True, exist_ok=True)
    bom.unlink(missing_ok=True)
    if sbom is not None:
        bom.write_text(sbom if isinstance(sbom, str) else json.dumps(sbom), encoding="utf-8")
    out = root / "dist"
    sys.argv = ["collect.py", "--name", "linux-x64", "--version", "0.4.0", "--out", str(out)]
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        return collect.main(), out


def main() -> int:
    for label, sbom in [("installer only", None), ("not JSON", "{"), ("not CycloneDX", {"bomFormat": "SPDX"}),
                        ("no components", {**VALID, "components": []})]:
        with tempfile.TemporaryDirectory() as t:
            code, out = run(Path(t), sbom)
            assert code != 0, f"{label}: collection must fail"
            assert not list(out.glob("*sbom*")), label
    with tempfile.TemporaryDirectory() as t:
        code, out = run(Path(t), VALID)
        assert code == 0, "valid SBOM: collection passes"
        shipped = out / "Mokuhyo-0.4.0-linux-x64-sbom.cdx.json"
        assert json.loads(shipped.read_text(encoding="utf-8")) == VALID
        assert (out / "Mokuhyo-0.4.0-linux-x64.deb").is_file()
    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
