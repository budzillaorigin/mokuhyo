#!/usr/bin/env bash
# gate_build (BRIEF §11.1, Phase 1 onward): the installer for this OS builds, and the packaged app starts headless
# (--smoke: opens the DB, loads the CPU native library, lists languages, renders one Compose frame offscreen).
#   SKIP_MODELS=1  don't bundle model weights (faster CI smoke builds)
set -euo pipefail
cd "$(dirname "$0")/../.."
GRADLE="${GRADLE:-./gradlew}"
case "$(uname -s)" in
  Darwin) OS=macos ;;
  Linux) OS=linux ;;
  MINGW*|MSYS*|CYGWIN*) OS=windows ;;
  *) echo "unsupported OS"; exit 1 ;;
esac

echo "== gate_build: native library (cpu)"
if [ "$OS" = windows ]; then
  powershell -NoProfile -ExecutionPolicy Bypass -File native/build.ps1 -Variant cpu
else
  native/build.sh cpu
fi

echo "== gate_build: stage resources"
(cd tools && uv run --locked python release/stage_resources.py --require-native ${SKIP_MODELS:+--skip-models})

echo "== gate_build: package"
$GRADLE :desktopApp:createDistributable :desktopApp:packageDistributionForCurrentOS --console=plain

echo "== gate_build: packaged smoke"
APP=desktopApp/build/compose/binaries/main/app
case "$OS" in
  macos) BIN="$APP/Mokuhyo.app/Contents/MacOS/Mokuhyo" ;;
  windows) BIN="$APP/Mokuhyo/Mokuhyo.exe" ;;
  linux) BIN="$APP/mokuhyo/bin/mokuhyo" ;;
esac
"$BIN" --smoke

echo "== gate_build: license scan of the image"
(cd tools && uv run --locked python gates/check_licenses.py)

ls -la desktopApp/build/compose/binaries/main/*/ 2>/dev/null | grep -Ei '\.(dmg|msi|deb)$' || true
echo "gate_build: PASS"
