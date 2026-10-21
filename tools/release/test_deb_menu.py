"""Regression for release dry run 39663569689: the .deb's postinst ran `xdg-desktop-menu install` under `set -e`; on a
system without a writable applications folder it exits 3 and dpkg aborts the install. After deb_menu.patch the
maintainer scripts warn and continue, the app files are unchanged, the package is still valid, and a second run is a
no-op. Builds a small synthetic package with jpackage's script shape; needs dpkg-deb (Linux)."""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import deb_menu

# The shape of jpackage's Linux templates (postinst.template / prerm.template) with the shortcut enabled.
POSTINST = """#!/bin/sh
# postinst script for mokuhyo
set -e

case "$1" in
    configure)
xdg-desktop-menu install /opt/mokuhyo/lib/mokuhyo-Mokuhyo.desktop
    ;;

    abort-upgrade|abort-remove|abort-deconfigure)
    ;;

    *)
        echo "postinst called with unknown argument \\`$1'" >&2
        exit 1
    ;;
esac

exit 0
"""
PRERM = """#!/bin/sh
set -e

case "$1" in
    remove|upgrade|deconfigure)
xdg-desktop-menu uninstall /opt/mokuhyo/lib/mokuhyo-Mokuhyo.desktop
    ;;
esac

exit 0
"""
# The failure the Ubuntu 22.04 runner printed.
FAILING_XDG = "#!/bin/sh\necho 'xdg-desktop-menu: No writable system menu directory found.' >&2\nexit 3\n"
WORKING_XDG = "#!/bin/sh\nexit 0\n"


def build(root: Path) -> Path:
    pkg = root / "pkg"
    (pkg / "DEBIAN").mkdir(parents=True)
    (pkg / "opt" / "mokuhyo" / "bin").mkdir(parents=True)
    (pkg / "opt" / "mokuhyo" / "bin" / "Mokuhyo").write_bytes(b"synthetic launcher\x00" * 4097)  # odd size: ar padding
    (pkg / "DEBIAN" / "control").write_text(
        "Package: mokuhyo\nVersion: 0.4.1\nArchitecture: amd64\nMaintainer: Mokuhyo contributors\nDescription: fixture\n")
    for name, body in (("postinst", POSTINST), ("prerm", PRERM)):
        (pkg / "DEBIAN" / name).write_text(body)
        (pkg / "DEBIAN" / name).chmod(0o755)
    deb = root / "mokuhyo.deb"
    subprocess.run(["dpkg-deb", "--root-owner-group", "--build", str(pkg), str(deb)], check=True, capture_output=True)
    return deb


def control_file(deb: Path, name: str, into: Path) -> Path:
    into.mkdir(parents=True, exist_ok=True)
    subprocess.run(["dpkg-deb", "--control", str(deb), str(into)], check=True, capture_output=True)
    return into / name


def run_script(script: Path, xdg: str, root: Path, arg: str) -> subprocess.CompletedProcess[str]:
    bins = root / f"bin-{len(xdg)}"
    bins.mkdir(exist_ok=True)
    (bins / "xdg-desktop-menu").write_text(xdg)
    (bins / "xdg-desktop-menu").chmod(0o755)
    return subprocess.run(["sh", str(script), arg], env={"PATH": f"{bins}:/usr/bin:/bin"}, text=True, capture_output=True, check=False)


def fsys(deb: Path) -> bytes:
    return subprocess.run(["dpkg-deb", "--fsys-tarfile", str(deb)], check=True, capture_output=True).stdout


def test_menu_failure_no_longer_aborts_the_install() -> None:
    with tempfile.TemporaryDirectory(prefix="mokuhyo-deb-") as t:
        root = Path(t)
        deb = build(root)
        data_before = fsys(deb)

        # The bug, as shipped: postinst fails with status 3, so dpkg would abort.
        before = run_script(control_file(deb, "postinst", root / "c0"), FAILING_XDG, root, "configure")
        assert before.returncode == 3, before

        assert deb_menu.patch(deb) == 2  # postinst install + prerm uninstall
        subprocess.run(["dpkg-deb", "--info", str(deb)], check=True, capture_output=True)  # still a valid package
        assert fsys(deb) == data_before, "app files must be copied unchanged"

        post = control_file(deb, "postinst", root / "c1")
        assert post.stat().st_mode & 0o111, "maintainer scripts stay executable"
        failed = run_script(post, FAILING_XDG, root, "configure")
        assert failed.returncode == 0, failed
        assert "continuing without the menu entry" in failed.stderr, failed.stderr
        assert "No writable system menu directory" in failed.stderr, "the real cause is still printed"
        ok = run_script(post, WORKING_XDG, root, "configure")
        assert ok.returncode == 0 and "continuing without" not in ok.stderr, ok
        removed = run_script(control_file(deb, "prerm", root / "c1"), FAILING_XDG, root, "remove")
        assert removed.returncode == 0, removed
        # Everything else in the script still works: an unknown argument still fails.
        assert run_script(post, WORKING_XDG, root, "bogus").returncode == 1

        # Idempotent: nothing left to change, and the package is byte-for-byte the same.
        once = deb.read_bytes()
        assert deb_menu.patch(deb) == 0
        assert deb.read_bytes() == once


def test_rejects_a_file_that_is_not_a_package() -> None:
    with tempfile.TemporaryDirectory(prefix="mokuhyo-deb-") as t:
        bogus = Path(t) / "x.deb"
        bogus.write_bytes(b"synthetic fixture")
        try:
            deb_menu.patch(bogus)
        except ValueError:
            return
        raise AssertionError("a non-package must be refused")


if __name__ == "__main__":
    if sys.platform == "win32" or shutil.which("dpkg-deb") is None:
        print("skip: needs dpkg-deb (Linux)")
        sys.exit(0)
    test_menu_failure_no_longer_aborts_the_install()
    test_rejects_a_file_that_is_not_a_package()
    print("ok")
