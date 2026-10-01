"""Runs every tools/**/test_*.py script (each is a plain script that exits non-zero on failure)."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def main() -> int:
    failed = []
    tests = sorted(p for p in ROOT.rglob("test_*.py") if ".venv" not in p.parts)
    for t in tests:
        r = subprocess.run([sys.executable, t.name], cwd=t.parent, capture_output=True, text=True, check=False)
        status = "ok" if r.returncode == 0 else "FAIL"
        print(f"{status:4}  {t.relative_to(ROOT)}")
        if r.returncode:
            failed.append(t)
            print(r.stdout[-3000:], r.stderr[-3000:])
    print(f"{len(tests) - len(failed)}/{len(tests)} test scripts passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
