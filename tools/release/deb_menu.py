"""Makes the Linux .deb's desktop-menu integration non-fatal (release dry run 39663569689).

jpackage's maintainer scripts run under `set -e` and call `xdg-desktop-menu install` (postinst) and `... uninstall`
(prerm/postrm). On a system without a writable applications folder (minimal and server installs, the Ubuntu 22.04 CI
runner) that command exits 3 with "No writable system menu directory found", and dpkg leaves the whole package
unconfigured. The Compose plugin offers no way to replace jpackage's script templates, so this rewrites the finished
package instead: every `xdg-*` line gets `|| echo "<warning>" >&2`, so the app installs without its menu entry rather
than failing. Only the control member (the maintainer scripts) is rebuilt; the app files (data member) are copied
byte for byte. Running it again changes nothing.

    python release/deb_menu.py path/to/Mokuhyo-0.4.1-linux-x64.deb
"""
from __future__ import annotations

import gzip
import io
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

SCRIPTS = ("postinst", "prerm", "postrm")
WARNING = ('|| echo "mokuhyo: desktop menu integration failed; continuing without the menu entry'
           ' (the app is in /opt/mokuhyo/bin)" >&2')
MAGIC = b"!<arch>\n"


def guard(script: str) -> tuple[str, int]:
    """[script] with every unguarded `xdg-*` command made non-fatal, and how many lines changed."""
    out, changed = [], 0
    for line in script.split("\n"):
        if line.strip().startswith("xdg-") and "||" not in line:
            line = f"{line.rstrip()} {WARNING}"
            changed += 1
        out.append(line)
    return "\n".join(out), changed


def members(f) -> list[tuple[bytes, int, int, bytes]]:
    """The ar members of an open .deb: (raw 60-byte header, data offset, size, name)."""
    f.seek(0)
    if f.read(8) != MAGIC:
        raise ValueError("not a Debian package (no ar header)")
    found = []
    while True:
        header = f.read(60)
        if not header:
            return found
        if len(header) != 60 or header[58:60] != b"`\n":
            raise ValueError("corrupt ar member header")
        name, size = header[:16].rstrip(b" ").rstrip(b"/"), int(header[48:58])
        found.append((header, f.tell(), size, name))
        f.seek(size + (size & 1), os.SEEK_CUR)


def ar_header(name: bytes, template: bytes, size: int) -> bytes:
    """A member header like [template] (same mtime/uid/gid/mode) for a new [name] and [size]."""
    return name.ljust(16) + template[16:48] + str(size).encode().ljust(10) + b"`\n"


def patch(deb: Path) -> int:
    """Rewrites [deb] in place if any maintainer script has an unguarded xdg command; returns lines changed."""
    with open(deb, "rb") as f:
        parts = members(f)
    names = [p[3] for p in parts]
    if len(parts) < 3 or names[0] != b"debian-binary" or not names[1].startswith(b"control.tar"):
        raise ValueError(f"unexpected .deb layout: {names}")
    # dpkg-deb decompresses whatever the builder chose (gz, xz or zst) into a plain tar.
    control = subprocess.run(["dpkg-deb", "--ctrl-tarfile", str(deb)], capture_output=True, check=True).stdout
    total = 0
    rebuilt = io.BytesIO()
    with tarfile.open(fileobj=io.BytesIO(control)) as src, tarfile.open(fileobj=rebuilt, mode="w", format=tarfile.GNU_FORMAT) as dst:
        for info in src.getmembers():
            data = src.extractfile(info).read() if info.isfile() else None
            if data is not None and Path(info.name).name in SCRIPTS:
                text, changed = guard(data.decode("utf-8"))
                total += changed
                data = text.encode("utf-8")
                info.size = len(data)
            dst.addfile(info, io.BytesIO(data) if data is not None else None)
    if total == 0:
        return 0
    member = gzip.compress(rebuilt.getvalue(), mtime=0)
    tmp = Path(tempfile.mkstemp(prefix=deb.name, suffix=".tmp", dir=deb.parent)[1])
    try:
        with open(deb, "rb") as f, open(tmp, "wb") as out:
            out.write(MAGIC)
            for i, (header, offset, size, name) in enumerate(parts):
                if i == 1:
                    out.write(ar_header(b"control.tar.gz", header, len(member)) + member + (b"\n" if len(member) & 1 else b""))
                    continue
                out.write(header)
                f.seek(offset)
                shutil.copyfileobj(io.BufferedReader(_Limited(f, size)), out, 1 << 20)
                if size & 1:
                    out.write(b"\n")
        os.replace(tmp, deb)
    finally:
        tmp.unlink(missing_ok=True)
    return total


class _Limited(io.RawIOBase):
    """Reads at most [left] bytes from [f] (copies a large ar member without loading it)."""

    def __init__(self, f, left: int):
        self.f, self.left = f, left

    def readable(self) -> bool:
        return True

    def readinto(self, b) -> int:
        n = self.f.readinto(memoryview(b)[: min(len(b), self.left)]) if self.left else 0
        self.left -= n
        return n


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    deb = Path(sys.argv[1])
    changed = patch(deb)
    print(f"deb_menu: {deb.name}: {changed} desktop-menu command(s) made non-fatal" if changed else
          f"deb_menu: {deb.name}: no unguarded desktop-menu commands")
    return 0


if __name__ == "__main__":
    sys.exit(main())
