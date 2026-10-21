"""Review-only defect reproductions. No downloads, real installers, or system writes.

Run: python docs/qc/2026-10-21-features/release-probes.py
The assertions describe the reviewed defects; they are not acceptance tests.
"""
import contextlib
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile

repo = Path(__file__).resolve().parents[3]
with tempfile.TemporaryDirectory(prefix="mokuhyo-release-qc-") as tmp:
    root = Path(tmp)
    bins = root / "bin"
    bins.mkdir()
    for name, body in {
        "id": "#!/bin/sh\necho 0\n",
        "apt-get": "#!/bin/sh\necho 'QC detailed dpkg cause on stdout'\necho 'QC generic dpkg failure on stderr' >&2\nexit 100\n",
    }.items():
        p = bins / name
        p.write_text(body)
        p.chmod(0o755)
    asset = root / "Mokuhyo-0.4.0-linux-x64.deb"
    asset.write_bytes(b"synthetic fixture, not an installer")
    (root / "SHA256SUMS").write_text(f"{hashlib.sha256(asset.read_bytes()).hexdigest()}  {asset.name}\n")
    env = dict(os.environ, FROM_DIR=str(root), PATH=f"{bins}:{os.environ['PATH']}")
    p = subprocess.run(["bash", str(repo / "tools/gates/fresh_install_smoke.sh"), "v0.4.0", "linux-x64"], env=env, text=True, capture_output=True)
    output = p.stdout + p.stderr
    assert p.returncode == 100, output
    assert "QC generic dpkg failure" in output
    assert "QC detailed dpkg cause" not in output
    print("Linux logging probe: exit=100, generic stderr retained, detailed stdout lost. This does NOT reproduce the package installation failure.")
    print(output.strip())

    spec = importlib.util.spec_from_file_location("qc_collect", repo / "tools/release/collect.py")
    collect = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(collect)
    collect.REPO = root
    collect.BIN = root / "binaries"
    (collect.BIN / "deb").mkdir(parents=True)
    (collect.BIN / "deb/fixture.deb").write_bytes(b"synthetic fixture")
    sys.argv = ["collect.py", "--name", "linux-x64", "--version", "0.4.0", "--out", str(root / "dist")]
    with contextlib.redirect_stdout(io.StringIO()):
        result = collect.main()
    assert result == 0
    assert not list((root / "dist").glob("*sbom*"))
    print("SBOM probe: collection exit=0 with installer present and no SBOM; missing mandatory artifact is accepted.")
