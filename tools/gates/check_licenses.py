"""License gate (CLAUDE.md rule 6, BRIEF §11.1 gate_core).

- Every artifact in the Gradle lockfiles (the runtime classpaths that ship) matches a pattern in the
  "App classpath" table of docs/LICENSES.md, and none of those rows is GPL/AGPL/LGPL.
- Every id in content/models/manifest.json, voices/manifest.json and native/lock.json has a row in docs/LICENSES.md.
- If a packaged app image exists (desktopApp/build/compose/binaries/**/app), no jar on its classpath declares a GPL
  license and no GPL executable (piper, espeak-ng) sits in the app/JNI lib dirs; they belong in the voices/ folder.

Exit status 0 = pass. Prints every problem.
"""
from __future__ import annotations

import fnmatch
import json
import re
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
GPL = re.compile(r"\b(A|L)?GPL", re.IGNORECASE)


def table_rows(md: str, heading: str) -> list[list[str]]:
    """Cells of every data row in the first table under the '## heading' section."""
    m = re.search(rf"^## {re.escape(heading)}.*?$(.*?)(?=^## |\Z)", md, re.MULTILINE | re.DOTALL)
    if not m:
        return []
    rows = []
    for line in m.group(1).splitlines():
        line = line.strip()
        if not line.startswith("|") or set(line) <= set("|-: "):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        rows.append(cells)
    return rows[1:] if rows else []  # drop the header


def first_col_keys(cell: str) -> list[str]:
    return re.findall(r"`([^`]+)`", cell)


def lockfile_artifacts() -> set[str]:
    out = set()
    for lock in REPO.glob("*/gradle.lockfile"):
        for line in lock.read_text().splitlines():
            if not line or line.startswith("#"):
                continue
            coord = line.split("=")[0]
            parts = coord.split(":")
            out.add(":".join(parts[:2]) if len(parts) >= 2 else coord)
    return out


def manifest_ids(path: Path, key: str) -> list[str]:
    if not path.exists():
        return []
    data = json.loads(path.read_text(encoding="utf-8"))
    items = data.get(key, data if isinstance(data, list) else [])
    return [i["id"] for i in items]


def scan_image(problems: list[str]) -> None:
    images = list((REPO / "desktopApp/build/compose/binaries").glob("**/app"))
    for app in images:
        for jar in app.rglob("*.jar"):
            try:
                with zipfile.ZipFile(jar) as z:
                    for name in z.namelist():
                        if name.endswith("pom.xml") or name == "META-INF/MANIFEST.MF":
                            text = z.read(name).decode("utf-8", "replace")
                            lic = re.findall(r"<license>\s*<name>([^<]+)</name>", text) + re.findall(
                                r"Bundle-License: (.+)", text)
                            for value in lic:
                                if GPL.search(value) and "classpath" not in value.lower():
                                    problems.append(f"{jar.relative_to(REPO)}: declares {value.strip()}")
            except zipfile.BadZipFile:
                problems.append(f"{jar}: unreadable jar")
        for f in app.rglob("*"):
            if f.is_file() and re.search(r"(^|[/_-])(piper|espeak)", f.name, re.IGNORECASE) and "voices" not in f.parts:
                problems.append(f"{f.relative_to(REPO)}: GPL executable inside the app classpath/JNI dir")


def main() -> int:
    md = (REPO / "docs/LICENSES.md").read_text(encoding="utf-8")
    problems: list[str] = []

    classpath = table_rows(md, "App classpath")
    patterns = [(p, row) for row in classpath for p in first_col_keys(row[0])]
    for p, row in patterns:
        if any(GPL.search(c) for c in row[2:3]):
            problems.append(f"LICENSES.md App classpath row '{p}' is GPL-family ({row[2]}): not allowed on the classpath")
    for art in sorted(lockfile_artifacts()):
        if not any(fnmatch.fnmatchcase(art, p) for p, _ in patterns):
            problems.append(f"lockfile artifact {art} has no row in docs/LICENSES.md 'App classpath'")

    for heading, path, key in [
        ("Models", REPO / "content/models/manifest.json", "models"),
        ("Voices", REPO / "voices/manifest.json", "voices"),
        ("Native libraries", REPO / "native/lock.json", "libraries"),
    ]:
        keys = {k for row in table_rows(md, heading) for k in first_col_keys(row[0])}
        for mid in manifest_ids(path, key):
            if mid not in keys:
                problems.append(f"{path.relative_to(REPO)} id '{mid}' has no row in docs/LICENSES.md '{heading}'")

    scan_image(problems)
    for p in problems:
        print("LICENSE:", p)
    print(f"check_licenses: {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
