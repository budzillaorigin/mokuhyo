"""CI run #15 regression: uv picked Python 3.15 on macOS, where mecab-python3 (via wordfreq[cjk]) has no wheel and
failed to build. tools/.python-version pins the tools environment; gate_core.sh and CI must use it."""
from __future__ import annotations

import sys
import tomllib
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
REPO = TOOLS.parent


def pinned() -> tuple[int, int]:
    major, minor = (TOOLS / ".python-version").read_text(encoding="utf-8").strip().split(".")[:2]
    return int(major), int(minor)


def test_pin_is_312_and_allowed() -> None:
    assert pinned() == (3, 12), pinned()
    req = tomllib.loads((TOOLS / "pyproject.toml").read_text(encoding="utf-8"))["project"]["requires-python"]
    assert req == ">=3.12", f"pin {pinned()} must satisfy requires-python {req}"


def test_running_interpreter_matches_pin() -> None:
    assert sys.version_info[:2] == pinned(), f"tools ran on {sys.version.split()[0]}, pinned {pinned()}"


def test_gate_and_ci_use_pin() -> None:
    gate = (TOOLS / "gates" / "gate_core.sh").read_text(encoding="utf-8")
    assert "tools/.python-version" in gate, "gate_core.sh must export UV_PYTHON from tools/.python-version"
    for wf in ("ci.yml", "release.yml"):
        text = (REPO / ".github" / "workflows" / wf).read_text(encoding="utf-8")
        assert text.count("astral-sh/setup-uv") == text.count("uv python install"), \
            f"{wf}: every setup-uv needs a 'uv python install' from tools/.python-version"


if __name__ == "__main__":
    for t in (test_pin_is_312_and_allowed, test_running_interpreter_matches_pin, test_gate_and_ci_use_pin):
        t()
        print("ok", t.__name__)
