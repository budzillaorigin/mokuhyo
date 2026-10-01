#!/usr/bin/env bash
# gate_release (BRIEF §11.1, Phase 7): for a tag, the Release workflow built installers for Windows, macOS (arm64 and
# x64) and Linux, published them as a GitHub *pre-release* with SHA256SUMS, and the fresh-install job installed and
# launched each one on a clean runner; docs/INSTALL.md is present. Never publishes anything itself.
#
#   tools/gates/gate_release.sh v0.1.0
set -euo pipefail
cd "$(dirname "$0")/../.."
TAG="${1:?usage: gate_release.sh vX.Y.Z}"
VERSION="${TAG#v}"
fail() { echo "gate_release: FAIL $*"; exit 1; }

[ -s docs/INSTALL.md ] || fail "docs/INSTALL.md missing"
[ -s docs/RELEASE_NOTES.md ] || fail "docs/RELEASE_NOTES.md missing"

echo "== gate_release: release $TAG"
info=$(gh release view "$TAG" --json isPrerelease,isDraft,assets) || fail "no release $TAG"
[ "$(jq -r .isPrerelease <<<"$info")" = true ] || fail "$TAG is not a pre-release (v0.x must never be a full release)"
[ "$(jq -r .isDraft <<<"$info")" = false ] || fail "$TAG is still a draft"
assets=$(jq -r '.assets[].name' <<<"$info")
for want in "Mokuhyo-$VERSION-windows-x64.msi" "Mokuhyo-$VERSION-macos-arm64.dmg" "Mokuhyo-$VERSION-macos-x64.dmg" \
            "Mokuhyo-$VERSION-linux-x64.deb" SHA256SUMS; do
  grep -qx "$want" <<<"$assets" || fail "asset $want missing"
  echo "  $want"
done

echo "== gate_release: checksums"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
gh release download "$TAG" --pattern SHA256SUMS --dir "$WORK"
listed=$(awk '{print $2}' "$WORK/SHA256SUMS" | sed 's/^\*//')
for a in $assets; do
  [ "$a" = SHA256SUMS ] && continue
  grep -qx "$a" <<<"$listed" || fail "$a not in SHA256SUMS"
done
# Every published asset's digest as GitHub computed it must match SHA256SUMS.
jq -r '.assets[] | select(.name != "SHA256SUMS") | "\(.name) \(.digest // "")"' <<<"$info" | while read -r name digest; do
  [ -z "$digest" ] && continue
  want=$(awk -v n="$name" '{f=$2; sub(/^\*/, "", f); if (f == n) print $1}' "$WORK/SHA256SUMS")
  [ "sha256:$want" = "$digest" ] || fail "$name: GitHub digest $digest != SHA256SUMS $want"
done

echo "== gate_release: fresh-install jobs"
run=$(gh run list --workflow release.yml --json databaseId,headBranch,event,status,conclusion --limit 20 \
  | jq -r --arg t "$TAG" '[.[] | select(.headBranch == $t or .event == "workflow_dispatch")][0].databaseId')
[ -n "$run" ] && [ "$run" != null ] || fail "no Release workflow run for $TAG"
jobs=$(gh run view "$run" --json jobs -q '.jobs[] | "\(.name)\t\(.conclusion)"')
echo "$jobs" | sed 's/^/  /'
n=$(grep -c '^fresh install' <<<"$jobs" || true)
[ "$n" -ge 4 ] || fail "expected 4 fresh-install jobs, found $n"
grep '^fresh install' <<<"$jobs" | grep -qv $'\tsuccess$' && fail "a fresh-install job did not succeed"
echo "gate_release: PASS ($TAG)"
