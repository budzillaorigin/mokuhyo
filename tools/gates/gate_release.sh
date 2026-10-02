#!/usr/bin/env bash
# gate_release (BRIEF §11.1, Phase 7; D-025: local builds, no GitHub Actions, no Linux installer). For a tag:
#  - the GitHub release is a *pre-release* (never a full release without the owner), not a draft;
#  - it carries the Windows MSI, both macOS DMGs and SHA256SUMS, and every asset's GitHub digest matches SHA256SUMS;
#  - docs/INSTALL.md and docs/RELEASE_NOTES.md exist;
#  - the macOS fresh-install smokes passed (tools/logs/build-arm64.log, tools/logs/build-macos-x64.log); the Windows one
#    is run by the owner (tools/release/build_windows.ps1) and recorded in docs/PROGRESS.md.
# Never publishes anything itself.
#
#   tools/gates/gate_release.sh v0.1.0
set -euo pipefail
cd "$(dirname "$0")/../.."
TAG="${1:?usage: gate_release.sh vX.Y.Z}"
VERSION="${TAG#v}"
fail() { echo "gate_release: FAIL $*"; exit 1; }

[ -s docs/INSTALL.md ] || fail "docs/INSTALL.md missing"
[ -s docs/RELEASE_NOTES.md ] || fail "docs/RELEASE_NOTES.md missing"
for log in tools/logs/build-arm64.log tools/logs/build-macos-x64.log; do
  grep -q "fresh_install_smoke: PASS" "$log" 2>/dev/null || fail "no passing fresh-install smoke in $log"
done

echo "== gate_release: release $TAG"
info=$(gh release view "$TAG" --json isPrerelease,isDraft,assets) || fail "no release $TAG"
[ "$(jq -r .isPrerelease <<<"$info")" = true ] || fail "$TAG is not a pre-release"
[ "$(jq -r .isDraft <<<"$info")" = false ] || fail "$TAG is still a draft"
assets=$(jq -r '.assets[].name' <<<"$info")
for want in "Mokuhyo-$VERSION-windows-x64.msi" "Mokuhyo-$VERSION-macos-arm64.dmg" "Mokuhyo-$VERSION-macos-x64.dmg" SHA256SUMS; do
  grep -qx "$want" <<<"$assets" || fail "asset $want missing"
  echo "  $want"
done

echo "== gate_release: checksums"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
gh release download "$TAG" --pattern SHA256SUMS --dir "$WORK"
jq -r '.assets[] | select(.name != "SHA256SUMS") | "\(.name) \(.digest // "")"' <<<"$info" | while read -r name digest; do
  want=$(awk -v n="$name" '{f=$2; sub(/^\*/, "", f); if (f == n) print $1}' "$WORK/SHA256SUMS")
  [ -n "$want" ] || fail "$name not in SHA256SUMS"
  [ -z "$digest" ] || [ "sha256:$want" = "$digest" ] || fail "$name: GitHub digest $digest != SHA256SUMS $want"
done
echo "gate_release: PASS ($TAG)"
