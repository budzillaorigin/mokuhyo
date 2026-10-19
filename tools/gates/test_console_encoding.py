"""CI run #15 regression: tools scripts that print non-ASCII text (→ · — …) crashed with UnicodeEncodeError on the
cp1252 Windows console. Every such entry point reconfigures stdout/stderr to UTF-8."""
from __future__ import annotations

import re
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
PRINTS_NON_ASCII = re.compile(r"(print|\.write)\(.*[^\x00-\x7f]")
RECONFIGURED = 'sys.stdout.reconfigure(encoding="utf-8")'


def scripts() -> list[Path]:
    return sorted(p for p in TOOLS.rglob("*.py") if ".venv" not in p.parts and not p.name.startswith("test_"))


def test_non_ascii_printers_reconfigure() -> None:
    missing = []
    for p in scripts():
        src = p.read_text(encoding="utf-8")
        if '__name__ == "__main__"' in src and PRINTS_NON_ASCII.search(src) and RECONFIGURED not in src:
            missing.append(str(p.relative_to(TOOLS)))
    assert not missing, f"print non-ASCII without a UTF-8 stdout: {missing}"


def test_scan_finds_refresh() -> None:
    assert PRINTS_NON_ASCII.search((TOOLS / "terms" / "refresh.py").read_text(encoding="utf-8"))


if __name__ == "__main__":
    for t in (test_non_ascii_printers_reconfigure, test_scan_finds_refresh):
        t()
        print("ok", t.__name__)
