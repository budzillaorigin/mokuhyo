#!/usr/bin/env bash
# Builds the Apple-silicon Mac installer (DMG + portable zip) natively, with a fresh-install smoke (D-025).
#
#   tools/release/build_macos_arm64.sh [VERSION]      # default 0.1.0; output in build/dist/
#
# Needs native/build.sh cpu + metal and voices/build.sh run first, and content/packs built.
# The Compose output is wiped first: Gradle has reused a stale app image after the staged packs changed, which shipped
# a DMG with old content while every smoke still passed.
set -euo pipefail
cd "$(dirname "$0")/../.."
VERSION="${1:-0.1.0}"
OUT=build/dist
mkdir -p "$OUT"
(cd tools && uv run --locked python release/stage_fonts.py && uv run --locked python release/stage_resources.py --require-native --require-voices)
rm -rf desktopApp/build/compose
./gradlew :desktopApp:packageDmg :desktopApp:createDistributable :desktopApp:cyclonedxDirectBom -Pmokuhyo.version="$VERSION" --console=plain -q
rm -f "$OUT"/*macos-arm64*
(cd tools && uv run --locked python release/collect.py --name macos-arm64 --version "$VERSION" --out "../$OUT")
(cd "$OUT" && shasum -a 256 Mokuhyo-"$VERSION"-macos-arm64* > SHA256SUMS.macos-arm64)
D=$(mktemp -d)
cp "$OUT/Mokuhyo-$VERSION-macos-arm64.dmg" "$D/" && cp "$OUT/SHA256SUMS.macos-arm64" "$D/SHA256SUMS"
FROM_DIR="$D" tools/gates/fresh_install_smoke.sh "v$VERSION" macos-arm64
rm -rf "$D"
