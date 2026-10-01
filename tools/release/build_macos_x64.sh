#!/usr/bin/env bash
# Builds the Intel Mac installer (DMG + portable zip) on an Apple-silicon Mac, instead of GitHub Actions: native
# libraries and Piper cross-compiled for x86_64 (ARCH=x86_64), then Gradle and jpackage run on an x86_64 JDK under
# Rosetta 2 so the bundled runtime and launcher are Intel binaries. The packaged smoke runs under Rosetta.
#
#   tools/release/build_macos_x64.sh [VERSION]      # default 0.1.0; output in build/dist/
#
# Needs Rosetta 2 (softwareupdate --install-rosetta --agree-to-license). Sources come from the committed tree
# (git archive HEAD) in build/macos-x64/src, so the arm64 build dirs are never touched.
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO="$PWD"
VERSION="${1:-0.1.0}"
WORK="$REPO/build/macos-x64"
OUT="$REPO/build/dist"
JDK="$WORK/jdk"
mkdir -p "$WORK" "$OUT"
arch -x86_64 /bin/ls / >/dev/null 2>&1 || { echo "Rosetta 2 is not installed" >&2; exit 2; }

if [ ! -x "$JDK/Contents/Home/bin/java" ]; then
  echo "== x86_64 JDK 21"
  mkdir -p "$JDK"
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jdk/hotspot/normal/eclipse" | tar -xz -C "$JDK" --strip-components=1
fi
export JAVA_HOME="$JDK/Contents/Home"

echo "== sources (HEAD)"
rm -rf "$WORK/src" && mkdir -p "$WORK/src"
git archive --format=tar HEAD | tar -x -C "$WORK/src"
cd "$WORK/src"
mkdir -p tools/.cache content voices
ln -sfn "$REPO/tools/.cache/models" tools/.cache/models
ln -sfn "$REPO/tools/.cache/fonts" tools/.cache/fonts
ln -sfn "$REPO/voices/models" voices/models
rm -rf content/packs && cp -a "$REPO/content/packs" content/packs
# Native and Piper build trees persist between runs (incremental) outside the throwaway source tree.
mkdir -p "$WORK/native-build" "$WORK/voices-build"
ln -sfn "$WORK/native-build" native/build
ln -sfn "$WORK/voices-build" voices/build

echo "== native (x86_64: cpu, metal)"
ARCH=x86_64 native/build.sh cpu
ARCH=x86_64 native/build.sh metal || echo "WARNING: Metal variant failed; shipping CPU only"
echo "== piper (x86_64)"
ARCH=x86_64 voices/build.sh
echo "== stage"
(cd tools && uv run --locked python release/stage_fonts.py && MOKUHYO_ARCH=x86_64 uv run --locked python release/stage_resources.py --require-native --require-voices)
echo "== package (x86_64 JDK under Rosetta)"
arch -x86_64 env JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :desktopApp:packageDmg :desktopApp:createDistributable \
  -Pmokuhyo.version="$VERSION" -Dorg.gradle.java.home="$JAVA_HOME" --console=plain -q
file desktopApp/build/compose/binaries/main/app/Mokuhyo.app/Contents/MacOS/Mokuhyo | grep -q x86_64 || { echo "launcher is not x86_64" >&2; exit 1; }
echo "== packaged smoke (Rosetta)"
BIN=$(tools/gates/app_binary.sh); "$BIN" --smoke --require-packs
rm -f "$OUT"/*macos-x64*
(cd tools && uv run --locked python release/collect.py --name macos-x64 --version "$VERSION" --out "$OUT")
(cd "$OUT" && shasum -a 256 Mokuhyo-"$VERSION"-macos-x64* > SHA256SUMS.macos-x64)
echo "== fresh install"
cp "$OUT/Mokuhyo-$VERSION-macos-x64.dmg" "$WORK/" && cp "$OUT/SHA256SUMS.macos-x64" "$WORK/SHA256SUMS"
FROM_DIR="$WORK" "$REPO/tools/gates/fresh_install_smoke.sh" "v$VERSION" macos-x64
rm -f "$WORK/Mokuhyo-$VERSION-macos-x64.dmg"
ls -la "$OUT" | grep macos-x64
