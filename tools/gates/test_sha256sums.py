"""Regression for release dry run 39663569689: the Windows fresh-install gate couldn't read a SHA256SUMS written by
Git for Windows' sha256sum, which marks binary mode with "*" ("<hash> *<name>"), and failed with "You cannot call a
method on a null-valued expression" before installing anything. Get-ExpectedSha256 (sha256sums.ps1) must read both
coreutils formats, CRLF lines, and pick the exact file among similar names. Synthetic hashes only; needs pwsh."""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
MSI = "Mokuhyo-0.1.0-windows-x64.msi"
H_MSI, H_ZIP, H_SBOM = "a" * 63 + "1", "B" * 63 + "2", "c" * 63 + "3"


def lookup(sums: str, asset: str) -> subprocess.CompletedProcess[str]:
    with tempfile.TemporaryDirectory(prefix="mokuhyo-sums-") as t:
        path = Path(t) / "SHA256SUMS"
        path.write_bytes(sums.encode())
        script = f". '{HERE / 'sha256sums.ps1'}'; Get-ExpectedSha256 -SumsPath '{path}' -Asset '{asset}'"
        return subprocess.run(["pwsh", "-NoProfile", "-NonInteractive", "-Command", script],
                              text=True, capture_output=True, timeout=60, check=False)


def expect(sums: str, asset: str, want: str) -> None:
    r = lookup(sums, asset)
    assert r.returncode == 0 and r.stdout.strip() == want, (sums, asset, r.stdout, r.stderr)


def main() -> None:
    listing = [(H_ZIP, "Mokuhyo-0.1.0-windows-x64-portable.zip"), (H_SBOM, "Mokuhyo-0.1.0-windows-x64-sbom.cdx.json"), (H_MSI, MSI)]
    # The dry run on Windows: binary-mode "*" markers (the case that failed).
    expect("".join(f"{h} *{n}\n" for h, n in listing), MSI, H_MSI)
    # A real release, written on Linux: text mode, two spaces.
    expect("".join(f"{h}  {n}\n" for h, n in listing), MSI, H_MSI)
    # CRLF line ends, and an upper-case hash comes back lower-case to compare with Get-FileHash.
    expect("".join(f"{h} *{n}\r\n" for h, n in listing), "Mokuhyo-0.1.0-windows-x64-portable.zip", H_ZIP.lower())
    # A file that isn't listed is an error, not a silent null.
    missing = lookup(f"{H_ZIP} *Mokuhyo-0.1.0-windows-x64-portable.zip\n", MSI)
    assert missing.returncode != 0 and "no entry for" in (missing.stdout + missing.stderr), missing


if __name__ == "__main__":
    if shutil.which("pwsh") is None:
        print("skip: needs pwsh (PowerShell 7)")
        sys.exit(0)
    main()
    print("ok")
