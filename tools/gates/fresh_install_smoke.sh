#!/usr/bin/env bash
# Fresh-machine install smoke (BRIEF §11.1 gate_release), macOS and Linux: downloads this OS's installer for a release
# tag, verifies it against the release's SHA256SUMS, installs it the way a learner would, and launches the installed
# app's headless smoke test. Run on a clean machine (CI runs it on fresh GitHub runners in release.yml).
#
#   tools/gates/fresh_install_smoke.sh v0.1.0 [macos-arm64|macos-x64|linux-x64]
#   FROM_DIR=dist tools/gates/fresh_install_smoke.sh main macos-arm64   # installers + SHA256SUMS from a folder (dry run)
set -euo pipefail
SUDO=$([ "$(id -u)" = 0 ] || echo sudo)
TAG="$1"
case "$TAG" in v[0-9]*) VERSION="${TAG#v}" ;; *) VERSION=0.1.0 ;; esac
FROM_DIR="${FROM_DIR:+$(cd "$FROM_DIR" && pwd)}"
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) DEFAULT=macos-arm64 ;; Darwin-x86_64) DEFAULT=macos-x64 ;; *) DEFAULT=linux-x64 ;;
esac
NAME="${2:-$DEFAULT}"
WORK=$(mktemp -d)
MNT="$WORK/mnt"
trap '[ -d "$MNT" ] && hdiutil detach "$MNT" -force >/dev/null 2>&1; rm -rf "$WORK"' EXIT
cd "$WORK"
EXT=$([ "${NAME%%-*}" = macos ] && echo dmg || echo deb)
ASSET="Mokuhyo-$VERSION-$NAME.$EXT"
echo "== fresh install: $ASSET"
if [ -n "$FROM_DIR" ]; then
  cp "$FROM_DIR/$ASSET" "$FROM_DIR/SHA256SUMS" .
else
  gh release download "$TAG" ${GH_REPO:+--repo "$GH_REPO"} --pattern "$ASSET" --pattern SHA256SUMS
fi
grep " $ASSET\$" SHA256SUMS > want.sha
if command -v sha256sum >/dev/null; then sha256sum -c want.sha; else shasum -a 256 -c want.sha; fi

if [ "$EXT" = dmg ]; then
  # The DMG shows the Apache-2.0 license agreement; accept it non-interactively (yes ends on SIGPIPE: ignore that).
  { yes || true; } | PAGER=cat hdiutil attach -nobrowse -readonly -mountpoint "$MNT" "$ASSET" >/dev/null
  DEST="$WORK/Applications"
  mkdir -p "$DEST"
  cp -R "$MNT"/*.app "$DEST/"
  hdiutil detach "$MNT" >/dev/null
  BIN=$(ls "$DEST"/*.app/Contents/MacOS/* | head -1)
else
  $SUDO apt-get install -y "./$ASSET" >/dev/null
  BIN=/opt/mokuhyo/bin/Mokuhyo
fi
echo "== fresh install: launching $BIN --smoke"
# An empty data directory: no settings, no learner, nothing cached (the owner's data is never touched).
"$BIN" --smoke --require-packs --data-dir "$WORK/data" | tee smoke.log
grep -q "smoke: OK" smoke.log
[ "$EXT" = deb ] && $SUDO apt-get remove -y mokuhyo >/dev/null || true
echo "fresh_install_smoke: PASS ($ASSET)"
