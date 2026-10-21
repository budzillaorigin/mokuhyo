"""QC Q5 regression: when the Linux .deb install fails, fresh_install_smoke.sh must keep apt's detailed stdout (where
dpkg often writes the real cause), its stderr and its exit status. Stubbed apt/dpkg only: nothing is installed."""
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "tools" / "gates" / "fresh_install_smoke.sh"
STUBS = {
    "id": "#!/bin/sh\necho 0\n",
    "apt-get": "#!/bin/sh\necho 'dpkg: dependency problems prevent configuration of mokuhyo: needs libfixture1'\n"
               "echo 'E: Sub-process /usr/bin/dpkg returned an error code (1)' >&2\nexit 100\n",
    "dpkg-deb": "#!/bin/sh\necho ' Package: mokuhyo (stub info)'\n",
    "dpkg": "#!/bin/sh\necho \"stub dpkg $*\"\n",
}


def run(tmp: Path, log_dir: Path) -> subprocess.CompletedProcess[str]:
    bins = tmp / "bin"
    bins.mkdir()
    for name, body in STUBS.items():
        (bins / name).write_text(body)
        (bins / name).chmod(0o755)
    asset = tmp / "Mokuhyo-0.4.0-linux-x64.deb"
    asset.write_bytes(b"synthetic fixture, not an installer")
    (tmp / "SHA256SUMS").write_text(f"{hashlib.sha256(asset.read_bytes()).hexdigest()}  {asset.name}\n")
    env = dict(os.environ, FROM_DIR=str(tmp), LOG_DIR=str(log_dir), PATH=f"{bins}{os.pathsep}{os.environ['PATH']}")
    return subprocess.run(["bash", str(SCRIPT), "v0.4.0", "linux-x64"], env=env, text=True, capture_output=True, timeout=60, check=False)


def test_failed_install_keeps_both_streams_and_status() -> None:
    with tempfile.TemporaryDirectory(prefix="mokuhyo-q5-") as t:
        tmp = Path(t)
        logs = tmp / "logs"
        p = run(tmp, logs)
        out = p.stdout + p.stderr
        assert p.returncode == 100, (p.returncode, out)
        assert "needs libfixture1" in p.stdout, out
        assert "returned an error code (1)" in p.stderr, out
        assert "apt-get install exited 100" in p.stderr, out
        assert "stub info" in out and "stub dpkg --audit" in out, out
        assert "needs libfixture1" in (logs / "apt-install.out").read_text()
        assert "error code (1)" in (logs / "apt-install.err").read_text()
        assert (logs / "dpkg-diagnostics.log").is_file()
        assert "launching" not in out, "no launch after a failed install"


if __name__ == "__main__":
    if sys.platform == "win32" or shutil.which("bash") is None or shutil.which("sha256sum") is None:
        print("skip: needs bash and sha256sum (Linux)")
        sys.exit(0)
    test_failed_install_keeps_both_streams_and_status()
    print("ok")
